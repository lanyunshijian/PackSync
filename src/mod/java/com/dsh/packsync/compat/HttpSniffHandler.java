package com.dsh.packsync.compat;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;

/**
 * 同端口分流：让 PackSync 的分发服务与 Minecraft **共用同一个端口**。
 *
 * <p>插在 MC 每个连接 pipeline 的最前面，靠**首字节**区分两类流量：
 * <ul>
 *   <li>以 HTTP 方法开头、路径是 {@code /packsync/…} → 我们的流量，
 *       把这条连接代理到本机真正监听 HTTP 的内部端口；</li>
 *   <li>其它（MC 自己的握手包） → 把已缓冲字节原样交还给 MC 的下一个 handler，
 *       并把自己摘掉，MC 完全感知不到我们存在过。</li>
 * </ul>
 *
 * <p><b>为什么不用 {@code ByteToMessageDecoder}</b>：它的 {@code decode} 约定是
 * "消费多少、剩下多少自动传递"，而在里面同时做「移除自己 + 建立第二条连接 +
 * 转让已读字节」会与它的内部累积缓冲互相打架（实测表现为连接建了但一个字节都没回来）。
 * 这里改成手动累积，字节的归属完全由本类掌控，引用计数也一目了然。
 *
 * <p>另一条可选路线是自定义二进制协议：在 TLS 之前先发明文魔数再原地升级 TLS。
 * 这里复用标准 HTTP，于是"魔数"就是 HTTP 方法 + 路径前缀。
 * 取舍是刻意的 —— <b>少一层自定义协议，多一分可调试性</b>
 * （标准客户端、curl、浏览器都能直接用）。
 */
public class HttpSniffHandler extends ChannelInboundHandlerAdapter {

    private static final byte[] PATH_PREFIX = "/packsync/".getBytes(StandardCharsets.US_ASCII);

    private static final byte[][] METHODS = {
            "GET ".getBytes(StandardCharsets.US_ASCII),
            "POST ".getBytes(StandardCharsets.US_ASCII),
            "PUT ".getBytes(StandardCharsets.US_ASCII),
            "HEAD ".getBytes(StandardCharsets.US_ASCII),
            "OPTIONS ".getBytes(StandardCharsets.US_ASCII),
    };

    /**
     * 判定结果。
     *
     * <p>刻意做成"渐进式前缀匹配"而不是"等满 N 字节再判"：
     * 后者会让 MC 客户端发完握手包后**与我们互相等待**
     * （它在等响应，我们在等够 N 字节）—— 实测表现为 Server List Ping 直接超时。
     * 现在的规则是：能确定就立刻确定，只有"确实是某个模式的前缀"时才继续等。
     */
    private enum Decision { MATCH, NO_MATCH, NEED_MORE }

    private final int targetPort;

    /** 判定之前的字节累积区。 */
    private ByteBuf probe;

    /** 判定完成后的状态。 */
    private boolean decided;
    private boolean proxying;

    /** 代理目标连接。 */
    private Channel upstream;

    public HttpSniffHandler(int targetPort) {
        this.targetPort = targetPort;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // 已进入代理模式：一律转发给内部服务。
        if (proxying) {
            if (upstream != null && upstream.isActive()) {
                upstream.writeAndFlush(buf);
            } else {
                buf.release();
                ctx.close();
            }
            return;
        }

        // 累积字节直到够判断。
        if (probe == null) {
            probe = ctx.alloc().buffer();
        }
        probe.writeBytes(buf);
        buf.release();

        if (decided) {
            // 已经在代理但还有字节进来（极少见）：补发。
            if (upstream != null && upstream.isActive()) {
                upstream.writeAndFlush(probe);
                probe = null;
            }
            return;
        }

        Decision decision = decide(probe);
        if (decision == Decision.NEED_MORE) {
            return; // 确实还是个前缀，继续等 —— 这种情况只可能是真的 HTTP 请求
        }
        decided = true;
        if (decision == Decision.MATCH) {
            startProxy(ctx);
        } else {
            handBackToMinecraft(ctx);
        }
    }

    /**
     * 选出与服务端连接**同一类 event loop** 的客户端 channel。
     *
     * <p>必须匹配，否则 Netty 直接抛
     * {@code IllegalStateException: incompatible event loop type}。
     * Linux 上 MC 用的是 Epoll（这也是我们最初踩的坑：
     * 写死 {@code NioSocketChannel} 会让代理连接一建立就失败），
     * macOS 上是 KQueue，其余情况是 NIO。
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends SocketChannel> clientChannelClass(Channel serverChannel) {
        String name = serverChannel.getClass().getName();
        if (name.contains("Epoll")) {
            return (Class<? extends SocketChannel>) (Class<?>) EpollSocketChannel.class;
        }
        // KQueue（macOS）不额外引依赖：它不存在时上面的分支也走不到，
        // 而 MC 在 macOS 上同样用 KQueue，这里退回 NIO 会由 Netty 明确报错，
        // 不会静默出错。需要支持时再补 KQueueSocketChannel。
        return NioSocketChannel.class;
    }

    /**
     * 渐进式判定：把已累积的字节与每个 "方法 + /packsync/" 模式逐字节比对。
     *
     * <ul>
     *   <li>完全命中某个模式 → {@link Decision#MATCH}</li>
     *   <li>与所有模式都已不可能匹配 → {@link Decision#NO_MATCH}（立即交还 MC）</li>
     *   <li>仍然是某个模式的前缀 → {@link Decision#NEED_MORE}（继续等）</li>
     * </ul>
     *
     * <p>这样 MC 的握手包在**第一个字节**就能被排除（它的首字节是包长度 varint，
     * 不是 'G'/'P'/'H'/'O'），不必等满固定字节数。
     */
    private static Decision decide(ByteBuf in) {
        int start = in.readerIndex();
        int readable = in.readableBytes();
        if (readable == 0) {
            return Decision.NEED_MORE;
        }

        boolean anyPrefixAlive = false;
        for (byte[] method : METHODS) {
            int total = method.length + PATH_PREFIX.length;
            int cmp = Math.min(readable, total);

            boolean stillPrefix = true;
            for (int i = 0; i < cmp; i++) {
                byte expected = (i < method.length) ? method[i] : PATH_PREFIX[i - method.length];
                if (in.getByte(start + i) != expected) {
                    stillPrefix = false;
                    break;
                }
            }
            if (!stillPrefix) {
                continue; // 这个模式已不可能
            }
            if (readable >= total) {
                return Decision.MATCH; // 完整命中
            }
            anyPrefixAlive = true; // 还差几个字节，可能命中
        }
        return anyPrefixAlive ? Decision.NEED_MORE : Decision.NO_MATCH;
    }

    /**
     * 不是我们的流量：把累积的字节**原样**交给 MC 的下一个 handler，并把自己摘掉。
     *
     * <p>摘除顺序很关键：先 {@code remove} 再 {@code fireChannelRead}，
     * 否则字节会绕回自己造成递归。
     */
    private void handBackToMinecraft(ChannelHandlerContext ctx) {
        ByteBuf pending = probe;
        probe = null;
        ctx.pipeline().remove(this);
        if (pending != null && pending.isReadable()) {
            ctx.fireChannelRead(pending);
        } else if (pending != null) {
            pending.release();
        }
    }

    /** 是我们的流量：连上本机 HTTP 端口，双向转发。 */
    private void startProxy(ChannelHandlerContext ctx) {
        proxying = true;
        final ByteBuf pending = probe;
        probe = null;

        // 把自己换成纯转发 handler：后续字节直接进 upstream。
        ctx.pipeline().remove(this);

        Bootstrap bootstrap = new Bootstrap()
                .group(ctx.channel().eventLoop())
                .channel(clientChannelClass(ctx.channel()))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 内部服务 → 客户端
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext c, Object msg) {
                                if (ctx.channel().isActive()) {
                                    ctx.writeAndFlush(msg);
                                } else {
                                    ReferenceCountUtil.release(msg);
                                }
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext c) {
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext c, Throwable t) {
                                ctx.close();
                            }
                        });
                    }
                });

        bootstrap.connect("127.0.0.1", targetPort).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                System.err.println("[PackSync][sniff] 无法连接内部 HTTP 端口 "
                        + targetPort + " -> " + future.cause());
                if (pending != null) {
                    pending.release();
                }
                ctx.close();
                return;
            }
            upstream = future.channel();

            // 客户端 → 内部服务
            ctx.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext c, Object msg) {
                    if (upstream.isActive()) {
                        upstream.writeAndFlush(msg);
                    } else {
                        ReferenceCountUtil.release(msg);
                    }
                }

                @Override
                public void channelInactive(ChannelHandlerContext c) {
                    upstream.close();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext c, Throwable t) {
                    upstream.close();
                }
            });

            // 嗅探阶段读走的字节补发给内部服务 —— 少了这一步，服务端会一直等请求头。
            if (pending != null && pending.isReadable()) {
                upstream.writeAndFlush(pending);
            } else if (pending != null) {
                pending.release();
            }
        });
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        // 被动移除（例如连接提前关闭）时释放探测缓冲，避免内存泄漏。
        if (probe != null) {
            probe.release();
            probe = null;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (upstream != null) {
            upstream.close();
        }
        ctx.fireChannelInactive();
    }
}
