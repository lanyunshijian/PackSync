package com.dsh.packsync.mixin;

import com.dsh.packsync.network.LoginConnectionAccess;
import com.dsh.packsync.network.LoginQueryID;
import com.dsh.packsync.network.ServerLoginAddon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端登录期握手的注入点。
 *
 * <p>三处注入，结构与既有实现的
 * {@code ServerLoginNetworkHandlerMixin} 一一对应：
 * <ol>
 *   <li>{@code <init>} 返回时挂上 addon；</li>
 *   <li>{@code handleCustomQueryPacket} 的 HEAD：接住客户端应答；</li>
 *   <li>{@code tick} 的 HEAD：推进握手，<b>没完成就取消原版 tick</b> ——
 *       这是"抢在 Forge 的 mod 列表校验之前完成信息交换"的关键手法。</li>
 * </ol>
 *
 * <p><b>方法名一律写 MCP 名</b>（{@code tick} / {@code handleCustomQueryPacket}），
 * 由 refmap 在构建期映射成 SRG 名。绝不要手写 {@code m_9933_} 这类 SRG 名：曾经因为
 * 手写 SRG 名，把同名字段 {@code f_10020_}（一个 int 计数器）当成了 {@code tick()} 方法，
 * 而当时 {@code require = 0} 让这个错误<b>完全静默</b> —— 登录握手永不发起，客户端被
 * Forge 以 {@code mismatched mod channel list} 断开，排查了很久才定位。
 * 现在 {@code defaultRequire = 1}，名字不对会在构建/加载时立刻报出来。
 *
 * <p>需要核对真实名字时：
 * <pre>javap -p -cp joined-1.20.1-*-srg.jar net.minecraft.server.network.ServerLoginPacketListenerImpl</pre>
 */
@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 300)
public abstract class ServerLoginMixin {

    @Unique
    private ServerLoginAddon packsync$addon;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void packsync$initAddon(CallbackInfo ci) {
        try {
            this.packsync$addon = new ServerLoginAddon((ServerLoginPacketListenerImpl) (Object) this);
        } catch (Throwable t) {
            // 绝不能让构造失败影响登录。
            this.packsync$addon = null;
        }
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void packsync$onResponse(ServerboundCustomQueryPacket packet, CallbackInfo ci) {
        ServerLoginAddon addon = this.packsync$addon;
        if (addon == null) {
            return;
        }
        try {
            int id = packet.getTransactionId();
            // 不是我们的通道就交给原版处理
            if (LoginQueryID.byId(id) == null) {
                return;
            }
            // data == null 表示客户端没装本 mod（MC 对未知查询会回空数据）
            FriendlyByteBuf data = packet.getData();
            addon.handleResponse(id, data);
            ci.cancel(); // 我们的包自己处理完了，不劳原版
        } catch (Throwable t) {
            // 同样放行 —— 本 mod 绝不能挡住玩家进服。
        }
    }

    /**
     * 推进握手。**未完成时取消原版 tick**，把登录流程钉在这里。
     *
     * <p>只在 {@code NEGOTIATING} / {@code READY_TO_ACCEPT} 阶段插手 ——
     * 这两个阶段正是 Forge 做 mod 列表校验的前后，也是我们唯一该发包的时机；
     * 其余阶段取消 tick 只会帮倒忙。这个判断照抄参考实现。
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void packsync$tickHandshake(CallbackInfo ci) {
        ServerLoginAddon addon = this.packsync$addon;
        if (addon == null) {
            return;
        }
        try {
            if (!packsync$isOurPhase((Object) this)) {
                return;
            }
            if (addon.tick()) {
                // 握手已结束，之后不再干预登录流程。
                this.packsync$addon = null;
            } else {
                ci.cancel(); // 钉住登录，等客户端应答
            }
        } catch (Throwable t) {
            // 出错就放行，绝不卡住玩家。
        }
    }

    /**
     * 当前是否处于"该我们插手"的登录阶段。
     *
     * <p><b>为什么用反射而不是直接写常量</b>：{@code ServerLoginPacketListenerImpl.State}
     * 是<b>包私有</b>枚举（{@code final class ...$State}），从我们的包里根本引用不了，
     * 写 {@code State.NEGOTIATING} 会直接编译失败。所以这里按<b>类型名</b>反射取到
     * 当前状态，再按枚举名比较 —— 语义相同，且不依赖访问权限。
     *
     * <p>取不到状态时返回 {@code true}（不加限制）：宁可多做一次握手推进，
     * 也不要因为取不到状态而漏掉这次同步。
     */
    @Unique
    private static boolean packsync$isOurPhase(Object handler) {
        Object state = LoginConnectionAccess.fieldValueByTypeName(
                handler, "net.minecraft.server.network.ServerLoginPacketListenerImpl$State");
        if (state == null) {
            return true;
        }
        String name = String.valueOf(state);
        return "NEGOTIATING".equals(name) || "READY_TO_ACCEPT".equals(name);
    }
}
