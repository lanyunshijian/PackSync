package com.dsh.packsync.mixin;

/**
 * 【已废弃，勿再使用】登录处理器私有字段的 Mixin {@code @Accessor}。
 *
 * <p>保留这个空壳只是为了记录一次真实事故：{@code @Accessor} <b>不支持
 * {@code require = 0}</b>。一旦 {@code connection} 字段的名与预期不符，
 * Mixin 不会降级，而是抛 {@code InvalidAccessorException}，再被包装成
 * {@code MixinTransformerError}。因为注入发生在<b>登录期</b>，
 * 后果是客户端一连接就崩 —— 实测日志恰好停在
 * {@code Mixing LoginAccessors$ClientLogin ... into ClientHandshakePacketListenerImpl}
 * 那一行，之后再无任何输出。
 *
 * <p>现已改为反射实现：{@link com.dsh.packsync.network.LoginConnectionAccess}。
 * 它按<b>字段类型</b>（{@code Connection}）扫描而不是按名字查找，
 * 取不到只返回 {@code null}，绝不把游戏搞崩。
 *
 * <p>本类<b>未</b>出现在 {@code packsync.mixins.json} 里，因此不会被 Mixin 处理。
 */
public final class LoginAccessors {

    private LoginAccessors() {
    }
}
