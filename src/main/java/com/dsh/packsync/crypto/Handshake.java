package com.dsh.packsync.crypto;

import java.util.Arrays;

/**
 * 三次消息的密钥协商握手，把「PSK」与「TOFU」两种信任模型统一在同一套 transcript 上。
 *
 * <pre>
 *   客户端                                                  服务端
 *     |-- ClientHello: 协议版本, clientNonce, 临时X25519公钥, 客户端名 -->|
 *     |<-- ServerHello: serverNonce, 临时X25519公钥, 身份公钥,           --|
 *     |                 mode, proof(签名 [+PSK-HMAC])                    |
 *     |-- ClientAuth: 客户端身份公钥, proof(签名 [+PSK-HMAC]) ---------->|
 * </pre>
 *
 * <p><b>为什么要「临时 X25519 + 长期 Ed25519」两套密钥</b>：
 * X25519 每次连接都换（前向保密，事后泄露身份密钥也解不开旧流量）；
 * Ed25519 长期不变（让客户端能认出「还是那台服务器」，抵抗中间人）。
 * 两者职责分离，缺一不可。
 *
 * <p><b>两种模式只在「是否额外要求 PSK-MAC」上有区别</b>：
 * <ul>
 *   <li>{@link #MODE_PSK}：服务端配置了预共享密钥。proof 里既有 Ed25519 签名，
 *       也有 HMAC(psk, transcript)。**中间人没有 PSK 就伪造不出 MAC**，
 *       所以这是最强的模式（连首次连接都不怕）。</li>
 *   <li>{@link #MODE_TOFU}：服务端没配 PSK。只有签名，客户端首次连接时
 *       「信任并记住」服务端指纹，之后指纹一变就报警。零配置，
 *       代价是**首次**连接理论上可被中间人劫持。</li>
 * </ul>
 *
 * <p>所有参与签名的数据都用一个**独立的 transcript**在两端各自重算，
 * 而不是把消息原文回传比对 —— 这样即使未来加了字段，也不会出现
 * 「两端理解不一致但都能通过」的降级攻击。
 */
public final class Handshake {

    public static final int PROTOCOL_VERSION = 1;

    public static final int MODE_PSK = 1;
    public static final int MODE_TOFU = 2;

    public static final int MSG_CLIENT_HELLO = 1;
    public static final int MSG_SERVER_HELLO = 2;
    public static final int MSG_CLIENT_AUTH = 3;

    public static final int MAC_LEN = 32;
    public static final int MAX_NAME_LEN = 64;

    private static final byte[] MAGIC = Bytes.utf8("PSYN");

    private static final byte[] L_T1 = Bytes.utf8("packsync/v1/transcript-1");
    private static final byte[] L_T2 = Bytes.utf8("packsync/v1/transcript-2");
    private static final byte[] L_SERVER = Bytes.utf8("packsync/v1/server-proof");
    private static final byte[] L_CLIENT = Bytes.utf8("packsync/v1/client-proof");
    private static final byte[] L_KEY_C2S = Bytes.utf8("packsync/v1/key/c2s");
    private static final byte[] L_KEY_S2C = Bytes.utf8("packsync/v1/key/s2c");
    /**
     * 无状态请求（HTTP 清单 / 文件下载）用的密钥标签。
     *
     * <p>刻意与流式通道的密钥**分开派生**：流式通道用「严格连续的计数器」
     * 防重放，而 HTTP 请求天生可能乱序、可能重发（浏览器、代理、重试），
     * 硬套计数器只会把正常请求判成攻击。无状态通道改为「每条消息随机 nonce」，
     * 重放无害（拿到的还是同一份公开清单），完整性仍由 GCM 保证。
     */
    private static final byte[] L_KEY_STATELESS = Bytes.utf8("packsync/v1/key/stateless");

    private Handshake() {
    }

    // =====================================================================
    // 服务端
    // =====================================================================

    /** 服务端一侧的握手状态机。 */
    public static final class ServerSide {

        private final Ed25519Identity identity;
        private final byte[] psk;
        private final int mode;

        private final int protocolVersion;
        private final byte[] clientNonce;
        private final byte[] clientEphPub;
        private final String clientName;

        private final X25519.Keys ephemeral;
        private final byte[] serverNonce;
        private byte[] serverProof;
        private byte[] t1;
        private byte[] transcriptHash;
        private SecureSession session;
        private SessionKeys keys;
        private byte[] clientIdentityPub;

        /**
         * @param identity 服务端长期身份（TOFU 用；PSK 模式也提供，让客户端能校验服务端身份）
         * @param psk      预共享密钥，可为 null / 空（则退化为 TOFU 模式）
         * @param clientHelloPacket 收到的 ClientHello 原始字节
         */
        public ServerSide(Ed25519Identity identity, byte[] psk, byte[] clientHelloPacket)
                throws HandshakeException {
            if (identity == null) {
                throw new IllegalArgumentException("服务端身份不能为空");
            }
            this.identity = identity;
            this.psk = (psk == null || psk.length == 0) ? null : psk.clone();
            this.mode = this.psk == null ? MODE_TOFU : MODE_PSK;

            try {
                Buf in = Buf.reader(clientHelloPacket);
                requireMagic(in);
                int type = in.readU8();
                if (type != MSG_CLIENT_HELLO) {
                    throw new HandshakeException("期望 ClientHello，收到消息类型 " + type);
                }
                this.protocolVersion = in.readU32();
                if (protocolVersion != PROTOCOL_VERSION) {
                    throw new HandshakeException("协议版本不兼容：对端 " + protocolVersion
                            + "，本端 " + PROTOCOL_VERSION);
                }
                this.clientNonce = in.readBytes(32);
                this.clientEphPub = in.readBytes(X25519.KEY_LEN);
                this.clientName = in.readStr(MAX_NAME_LEN);
                in.requireFullyRead();
            } catch (Buf.ProtocolFormatException e) {
                throw new HandshakeException("ClientHello 格式非法：" + e.getMessage(), e);
            }

            X25519.validatePeerPublicKey(clientEphPub);
            this.ephemeral = X25519.generate();
            this.serverNonce = Bytes.random(32);
        }

        public int mode() {
            return mode;
        }

        public String clientName() {
            return clientName;
        }

        /** 生成 ServerHello。可重复调用，返回同一份字节。 */
        public byte[] buildServerHello() {
            byte[] t1 = computeT1();
            this.t1 = t1;

            byte[] signature = identity.sign(Bytes.concat(L_SERVER, Bytes.lengthPrefixed(t1)));
            byte[] hmac = (mode == MODE_PSK) ? hmac(psk, L_SERVER, t1) : new byte[0];
            this.serverProof = Bytes.concat(signature, hmac);

            return Buf.writer()
                    .bytes(MAGIC)
                    .u8(MSG_SERVER_HELLO)
                    .u32(PROTOCOL_VERSION)
                    .bytes(serverNonce)
                    .bytes(ephemeral.publicKey())
                    .bytes(identity.publicKey())
                    .u8(mode)
                    .lengthPrefixed(serverProof)
                    .toBytes();
        }

        /**
         * 校验 ClientAuth 并生成会话密钥。
         *
         * @return 会话结果（含加密通道、客户端身份公钥）
         */
        public ServerResult finish(byte[] clientAuthPacket) throws HandshakeException {
            if (serverProof == null) {
                throw new IllegalStateException("必须先调用 buildServerHello()");
            }
            try {
                Buf in = Buf.reader(clientAuthPacket);
                requireMagic(in);
                int type = in.readU8();
                if (type != MSG_CLIENT_AUTH) {
                    throw new HandshakeException("期望 ClientAuth，收到消息类型 " + type);
                }
                this.clientIdentityPub = in.readBytes(Ed25519Identity.PUBLIC_KEY_LEN);
                byte[] clientProof = in.readLengthPrefixed(Ed25519Identity.SIGNATURE_LEN + MAC_LEN);
                in.requireFullyRead();

                int expectedLen = Ed25519Identity.SIGNATURE_LEN + (mode == MODE_PSK ? MAC_LEN : 0);
                if (clientProof.length != expectedLen) {
                    throw new HandshakeException("ClientAuth proof 长度应为 " + expectedLen
                            + "，实际 " + clientProof.length);
                }

                byte[] signature = Arrays.copyOfRange(clientProof, 0, Ed25519Identity.SIGNATURE_LEN);
                byte[] t2 = computeT2();

                // 1) 客户端必须证明自己持有身份私钥
                if (!Ed25519Identity.verify(clientIdentityPub,
                        Bytes.concat(L_CLIENT, Bytes.lengthPrefixed(t2)), signature)) {
                    throw new HandshakeException("客户端身份签名校验失败");
                }
                // 2) PSK 模式下再要求对方知道预共享密钥
                if (mode == MODE_PSK) {
                    byte[] mac = Arrays.copyOfRange(clientProof, Ed25519Identity.SIGNATURE_LEN,
                            clientProof.length);
                    byte[] expected = hmac(psk, L_CLIENT, t2);
                    if (!Bytes.constantTimeEquals(mac, expected)) {
                        throw new HandshakeException("预共享密钥校验失败（密钥不对，或对端在冒充）");
                    }
                }

                this.transcriptHash = Bytes.sha256(Bytes.lengthPrefixed(t1),
                        Bytes.lengthPrefixed(serverProof), clientIdentityPub,
                        Bytes.lengthPrefixed(clientProof));
                this.keys = deriveKeys(psk, mode, transcriptHash,
                        clientNonce, serverNonce, ephemeral, clientEphPub);
                this.session = keys.openSession(true);
                return new ServerResult(session, keys, clientIdentityPub, clientName, mode,
                        transcriptHash);
            } catch (Buf.ProtocolFormatException e) {
                throw new HandshakeException("ClientAuth 格式非法：" + e.getMessage(), e);
            }
        }

        private byte[] computeT1() {
            return Bytes.concat(L_T1, Buf.writer()
                    .u32(protocolVersion)
                    .bytes(clientNonce)
                    .bytes(clientEphPub)
                    .bytes(serverNonce)
                    .bytes(ephemeral.publicKey())
                    .bytes(identity.publicKey())
                    .u8(mode)
                    .str(clientName)
                    .toBytes());
        }

        /**
         * T2 是「客户端 proof 所覆盖的转写」。它**不含** clientProof 自身
         * （签名无法覆盖自己的签名），但 proof 随后会被算进 transcriptHash，
         * 从而绑定进会话密钥 —— 篡改 proof 会让两端密钥不一致，连接立刻失败。
         */
        private byte[] computeT2() {
            return Bytes.concat(L_T2, Buf.writer()
                    .lengthPrefixed(t1)
                    .lengthPrefixed(serverProof)
                    .bytes(clientIdentityPub)
                    .toBytes());
        }
    }

    /** 服务端握手成功的结果。 */
    public static final class ServerResult {
        private final SecureSession session;
        private final SessionKeys keys;
        private final byte[] clientIdentityPub;
        private final String clientName;
        private final int mode;
        private final byte[] transcriptHash;

        ServerResult(SecureSession session, SessionKeys keys, byte[] clientIdentityPub,
                     String clientName, int mode, byte[] transcriptHash) {
            this.session = session;
            this.keys = keys;
            this.clientIdentityPub = clientIdentityPub;
            this.clientName = clientName;
            this.mode = mode;
            this.transcriptHash = transcriptHash;
        }

        public SecureSession session() {
            return session;
        }

        /** 无状态通道（HTTP 清单 / 文件下载）用的密钥材料。 */
        public SessionKeys keys() {
            return keys;
        }

        public byte[] clientIdentityPub() {
            return clientIdentityPub.clone();
        }

        public String clientName() {
            return clientName;
        }

        public int mode() {
            return mode;
        }

        public byte[] transcriptHash() {
            return transcriptHash.clone();
        }

        public String clientFingerprint() {
            return Ed25519Identity.fingerprintOf(clientIdentityPub);
        }
    }

    // =====================================================================
    // 客户端
    // =====================================================================

    /** 客户端一侧的握手状态机。 */
    public static final class ClientSide {

        private final byte[] psk;
        private final Ed25519Identity clientIdentity;

        /** 已知的服务端指纹；null 表示首次接触（TOFU 记录）。 */
        private String expectedServerFingerprint;

        private X25519.Keys ephemeral;
        private byte[] clientNonce;
        private String clientName;

        private int mode;
        private int protocolVersion;
        private byte[] serverNonce;
        private byte[] serverEphPub;
        private byte[] serverIdentityPub;
        private byte[] serverProof;
        private byte[] t1;
        private byte[] clientProof;
        private byte[] transcriptHash;
        private SecureSession session;
        private SessionKeys keys;
        private boolean firstContact;

        /**
         * @param psk                       预共享密钥，可为 null（未配置）
         * @param clientIdentity            客户端长期身份（用于让服务端识别本机）
         * @param expectedServerFingerprint 已记住的服务端指纹，null 表示首次
         */
        public ClientSide(byte[] psk, Ed25519Identity clientIdentity,
                          String expectedServerFingerprint) {
            this.psk = (psk == null || psk.length == 0) ? null : psk.clone();
            if (clientIdentity == null) {
                throw new IllegalArgumentException("客户端身份不能为空");
            }
            this.clientIdentity = clientIdentity;
            this.expectedServerFingerprint = expectedServerFingerprint;
        }

        /** 生成 ClientHello。 */
        public byte[] buildClientHello(String name) {
            this.clientName = name == null ? "" : name;
            if (clientName.length() > MAX_NAME_LEN) {
                this.clientName = clientName.substring(0, MAX_NAME_LEN);
            }
            this.ephemeral = X25519.generate();
            this.clientNonce = Bytes.random(32);
            this.protocolVersion = PROTOCOL_VERSION;
            return Buf.writer()
                    .bytes(MAGIC)
                    .u8(MSG_CLIENT_HELLO)
                    .u32(PROTOCOL_VERSION)
                    .bytes(clientNonce)
                    .bytes(ephemeral.publicKey())
                    .str(this.clientName)
                    .toBytes();
        }

        /**
         * 处理 ServerHello：校验服务端身份，算出会话密钥。
         *
         * @throws PskRequiredException 服务端要求 PSK 而客户端没有配置时抛出，
         *                              上层据此弹出「请输入服务器密钥」界面
         */
        public ServerInfo handleServerHello(byte[] packet)
                throws HandshakeException, PskRequiredException {
            if (ephemeral == null) {
                throw new IllegalStateException("必须先调用 buildClientHello()");
            }
            try {
                Buf in = Buf.reader(packet);
                requireMagic(in);
                int type = in.readU8();
                if (type != MSG_SERVER_HELLO) {
                    throw new HandshakeException("期望 ServerHello，收到消息类型 " + type);
                }
                this.protocolVersion = in.readU32();
                if (protocolVersion != PROTOCOL_VERSION) {
                    throw new HandshakeException("协议版本不兼容：服务端 " + protocolVersion
                            + "，客户端 " + PROTOCOL_VERSION);
                }
                this.serverNonce = in.readBytes(32);
                this.serverEphPub = in.readBytes(X25519.KEY_LEN);
                this.serverIdentityPub = in.readBytes(Ed25519Identity.PUBLIC_KEY_LEN);
                this.mode = in.readU8();
                this.serverProof = in.readLengthPrefixed(Ed25519Identity.SIGNATURE_LEN + MAC_LEN);
                in.requireFullyRead();
            } catch (Buf.ProtocolFormatException e) {
                throw new HandshakeException("ServerHello 格式非法：" + e.getMessage(), e);
            }

            if (mode != MODE_PSK && mode != MODE_TOFU) {
                throw new HandshakeException("服务端声明了未知的认证模式：" + mode);
            }
            X25519.validatePeerPublicKey(serverEphPub);

            int expectedLen = Ed25519Identity.SIGNATURE_LEN + (mode == MODE_PSK ? MAC_LEN : 0);
            if (serverProof.length != expectedLen) {
                throw new HandshakeException("ServerHello proof 长度应为 " + expectedLen
                        + "，实际 " + serverProof.length);
            }
            if (mode == MODE_PSK && psk == null) {
                throw new PskRequiredException("服务端要求预共享密钥，但本机尚未配置");
            }

            this.t1 = computeT1();

            // 1) 签名必须对（无论哪种模式都不跳过 —— 否则中间人可以随便编一个身份公钥）
            byte[] signature = Arrays.copyOfRange(serverProof, 0, Ed25519Identity.SIGNATURE_LEN);
            if (!Ed25519Identity.verify(serverIdentityPub,
                    Bytes.concat(L_SERVER, Bytes.lengthPrefixed(t1)), signature)) {
                throw new HandshakeException("服务端身份签名校验失败（可能是中间人）");
            }
            // 2) PSK 模式下再校验 MAC
            if (mode == MODE_PSK) {
                byte[] mac = Arrays.copyOfRange(serverProof, Ed25519Identity.SIGNATURE_LEN,
                        serverProof.length);
                if (!Bytes.constantTimeEquals(mac, hmac(psk, L_SERVER, t1))) {
                    throw new HandshakeException("预共享密钥校验失败：密钥不对");
                }
            }
            // 3) TOFU：比对记住的指纹
            String fingerprint = Ed25519Identity.fingerprintOf(serverIdentityPub);
            this.firstContact = (expectedServerFingerprint == null);
            if (!firstContact && !fingerprint.equals(expectedServerFingerprint)) {
                throw new ServerIdentityChangedException(
                        "服务端身份指纹变了！\n  之前：" + expectedServerFingerprint
                                + "\n  现在：" + fingerprint
                                + "\n可能是服务端换了密钥，也可能是有人在中间人攻击。",
                        fingerprint);
            }
            this.expectedServerFingerprint = fingerprint;
            return new ServerInfo(fingerprint, firstContact, mode, psk != null && mode == MODE_TOFU);
        }

        /** 生成 ClientAuth（同时内部算出会话密钥）。 */
        public byte[] buildClientAuth() {
            if (t1 == null) {
                throw new IllegalStateException("必须先调用 handleServerHello()");
            }
            byte[] clientIdentityPub = clientIdentity.publicKey();
            byte[] t2 = Bytes.concat(L_T2, Buf.writer()
                    .lengthPrefixed(t1)
                    .lengthPrefixed(serverProof)
                    .bytes(clientIdentityPub)
                    .toBytes());
            // 注意：T2 在服务端那侧还要拼上 clientProof 才能算 transcriptHash，
            // 所以这里 proof 的签名/MAC 覆盖的是「不含 proof 自身」的 T2，
            // 而最终的 transcriptHash 覆盖含 proof 的完整转写。
            byte[] signature = clientIdentity.sign(Bytes.concat(L_CLIENT, Bytes.lengthPrefixed(t2)));
            byte[] mac = (mode == MODE_PSK) ? hmac(psk, L_CLIENT, t2) : new byte[0];
            this.clientProof = Bytes.concat(signature, mac);

            this.transcriptHash = Bytes.sha256(Bytes.lengthPrefixed(t1),
                    Bytes.lengthPrefixed(serverProof), clientIdentityPub,
                    Bytes.lengthPrefixed(clientProof));
            this.keys = deriveKeys(psk, mode, transcriptHash,
                    clientNonce, serverNonce, ephemeral, serverEphPub);
            this.session = keys.openSession(false);

            return Buf.writer()
                    .bytes(MAGIC)
                    .u8(MSG_CLIENT_AUTH)
                    .bytes(clientIdentityPub)
                    .lengthPrefixed(clientProof)
                    .toBytes();
        }

        public SecureSession session() {
            if (session == null) {
                throw new IllegalStateException("握手尚未完成，没有会话密钥");
            }
            return session;
        }

        /** 无状态通道（HTTP 清单 / 文件下载）用的密钥材料。 */
        public SessionKeys keys() {
            if (keys == null) {
                throw new IllegalStateException("握手尚未完成，没有会话密钥");
            }
            return keys;
        }

        public byte[] transcriptHash() {
            return transcriptHash.clone();
        }

        private byte[] computeT1() {
            return Bytes.concat(L_T1, Buf.writer()
                    .u32(protocolVersion)
                    .bytes(clientNonce)
                    .bytes(ephemeral.publicKey())
                    .bytes(serverNonce)
                    .bytes(serverEphPub)
                    .bytes(serverIdentityPub)
                    .u8(mode)
                    .str(clientName)
                    .toBytes());
        }
    }

    /** 客户端处理 ServerHello 的结果，供 UI 展示。 */
    public static final class ServerInfo {
        private final String fingerprint;
        private final boolean firstContact;
        private final int mode;
        private final boolean pskConfiguredButServerDoesNotUseIt;

        ServerInfo(String fingerprint, boolean firstContact, int mode,
                   boolean pskConfiguredButServerDoesNotUseIt) {
            this.fingerprint = fingerprint;
            this.firstContact = firstContact;
            this.mode = mode;
            this.pskConfiguredButServerDoesNotUseIt = pskConfiguredButServerDoesNotUseIt;
        }

        public String fingerprint() {
            return fingerprint;
        }

        /** true 表示这是第一次见到这台服务器，上层应把指纹展示给玩家确认。 */
        public boolean firstContact() {
            return firstContact;
        }

        public int mode() {
            return mode;
        }

        public String modeName() {
            return mode == MODE_PSK ? "PSK" : "TOFU";
        }

        /** 本机配了 PSK，但服务端没启用 —— 提示玩家（不致命）。 */
        public boolean pskIgnored() {
            return pskConfiguredButServerDoesNotUseIt;
        }
    }

    // =====================================================================
    // 共用
    // =====================================================================

    /**
     * 由 ECDH 共享秘密派生双向会话密钥。
     *
     * <p>PSK 模式下把 PSK 也拼进 IKM：这样**没有 PSK 就推不出会话密钥**，
     * 即使 ECDH 的某一端被攻破也无法解密。
     */
    private static SessionKeys deriveKeys(byte[] psk, int mode, byte[] transcriptHash,
                                          byte[] clientNonce, byte[] serverNonce,
                                          X25519.Keys ownEphemeral, byte[] peerEphemeralPub) {
        byte[] shared = X25519.sharedSecret(ownEphemeral.privateKey(), peerEphemeralPub);
        if (X25519.isAllZero(shared)) {
            throw new IllegalStateException("ECDH 共享秘密为全零（对端使用了低阶点），拒绝建立会话");
        }
        byte[] ikm = (mode == MODE_PSK) ? Bytes.concat(shared, psk) : shared;
        byte[] salt = Bytes.concat(clientNonce, serverNonce);

        byte[] c2s = Hkdf.derive(salt, ikm, Bytes.concat(L_KEY_C2S, transcriptHash), 32);
        byte[] s2c = Hkdf.derive(salt, ikm, Bytes.concat(L_KEY_S2C, transcriptHash), 32);
        byte[] stateless = Hkdf.derive(salt, ikm, Bytes.concat(L_KEY_STATELESS, transcriptHash), 32);

        return new SessionKeys(c2s, s2c, stateless);
    }

    /**
     * 一次握手派生出的全部密钥材料。
     *
     * <p>为什么不只给一把密钥：不同传输层对「防重放」的要求不一样，
     * 用同一把密钥加不同 info 派生，可以保证**一条通道被攻破不影响另一条**
     * （密码学上的域隔离）。
     */
    public static final class SessionKeys {

        private final byte[] clientToServer;
        private final byte[] serverToClient;
        private final byte[] stateless;

        SessionKeys(byte[] clientToServer, byte[] serverToClient, byte[] stateless) {
            this.clientToServer = clientToServer.clone();
            this.serverToClient = serverToClient.clone();
            this.stateless = stateless.clone();
        }

        /** 开一条流式加密通道（MC 自定义包用）。 */
        public SecureSession openSession(boolean serverSide) {
            return serverSide
                    ? SecureSession.forServer(clientToServer, serverToClient)
                    : SecureSession.forClient(clientToServer, serverToClient);
        }

        /** 无状态请求（HTTP 清单 / 下载）用的密钥。 */
        public byte[] statelessKey() {
            return stateless.clone();
        }
    }

    private static byte[] hmac(byte[] psk, byte[] label, byte[] transcript) {
        return Hkdf.hmac(psk, label, Bytes.lengthPrefixed(transcript));
    }

    private static void requireMagic(Buf in) throws HandshakeException {
        byte[] magic = in.readBytes(MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new HandshakeException("这不是 PackSync 握手包（魔数不匹配）—— "
                    + "可能是别的 mod 占用了同一通道，或对端版本完全不同");
        }
    }

    /** 握手失败。 */
    public static class HandshakeException extends Exception {
        private static final long serialVersionUID = 1L;

        public HandshakeException(String message) {
            super(message);
        }

        public HandshakeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 服务端要求 PSK，而客户端没配 —— 这**不是**攻击，走「让玩家输入密钥」的流程。 */
    public static final class PskRequiredException extends HandshakeException {
        private static final long serialVersionUID = 1L;

        public PskRequiredException(String message) {
            super(message);
        }
    }

    /** 服务端指纹与记住的不一致 —— 这**是**安全事件，必须让玩家明确知情。 */
    public static final class ServerIdentityChangedException extends HandshakeException {
        private static final long serialVersionUID = 1L;

        /**
         * 服务端本次<b>实际报出</b>的指纹。
         *
         * <p>必须作为字段带出来，不能只写在消息文本里 —— 上层要拿它让玩家重新核对。
         * 曾经只传消息，结果客户端界面拿不到"服务器现在是什么指纹"，
         * 只能报「服务器未提供指纹，无法核对」，玩家被卡在核对界面上出不去。
         */
        public final String actual;

        public ServerIdentityChangedException(String message) {
            this(message, "");
        }

        public ServerIdentityChangedException(String message, String actual) {
            super(message);
            this.actual = actual == null ? "" : actual;
        }
    }
}
