package com.dsh.packsync.network;

import net.minecraft.network.Connection;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射拿登录处理器里的 {@code connection} 字段。
 *
 * <p><b>为什么不用 Mixin 的 {@code @Accessor}</b>：{@code @Accessor} <b>不支持
 * {@code require = 0}</b> —— 字段名一旦对不上（比如 SRG 名与预期不符），
 * Mixin 会直接抛 {@code InvalidAccessorException} 把游戏<b>崩掉</b>。
 * 这在客户端尤其危险：那个字段名无法在服务端环境验证，
 * 而一次崩溃就足以让玩家连服务器都进不去。
 *
 * <p>反射则可以捕获失败：拿不到就退化成"不记录 / 不断开"，
 * 顶多功能不生效，绝不会把游戏搞崩。
 *
 * <p>查找策略<b>不依赖字段名</b>：直接扫描声明字段里类型为 {@link Connection} 的那个。
 * 这样无论映射怎么变（official / SRG），只要字段还在就能找到。
 */
public final class LoginConnectionAccess {

    /** 按类缓存，避免每次登录都反射扫描。 */
    private static final Map<Class<?>, Field> CACHE = new ConcurrentHashMap<>();

    /** 按「类 + 目标字段类型名」缓存，供 {@link #fieldValueByTypeName} 使用。 */
    private static final Map<String, Field> BY_TYPE_CACHE = new ConcurrentHashMap<>();

    private LoginConnectionAccess() {
    }

    /** 取连接；拿不到返回 null（调用方必须处理 null）。 */
    public static Connection connectionOf(Object handler) {
        if (handler == null) {
            return null;
        }
        try {
            Field field = CACHE.computeIfAbsent(handler.getClass(), LoginConnectionAccess::findConnectionField);
            if (field == null) {
                return null;
            }
            return (Connection) field.get(handler);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按<b>字段类型的全名</b>取字段值，不依赖字段名 —— 找不到或读不到都返回 null。
     *
     * <p>登录处理器里有好几个私有字段，字段名混淆后毫无语义（{@code f_243717_}），
     * 但<b>类型</b>是稳定且唯一的。例如要拿客户端握手实例里的
     * {@code net.minecraft.client.multiplayer.ServerData}（玩家正在连的那台服务器，
     * 登录期唯一可靠的地址来源），传类型全名即可。
     *
     * <p>这里刻意用<b>字符串</b>比对类型名，而不是引用类本身：这个类会被两端共用，
     * 而 {@code ServerData} 是客户端专有类，直接引用会让服务端加载时报
     * {@code NoClassDefFoundError}。
     */
    public static Object fieldValueByTypeName(Object handler, String typeName) {
        if (handler == null || typeName == null || typeName.isBlank()) {
            return null;
        }
        try {
            String key = handler.getClass().getName() + "|" + typeName;
            Field field = BY_TYPE_CACHE.computeIfAbsent(key, k -> findFieldByTypeName(handler.getClass(), typeName));
            return field == null ? null : field.get(handler);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在类及其父类里找类型全名匹配的字段。 */
    private static Field findFieldByTypeName(Class<?> type, String typeName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals(typeName)) {
                    try {
                        f.setAccessible(true);
                        return f;
                    } catch (Throwable ignored) {
                        // 试下一个
                    }
                }
            }
        }
        return null;
    }

    /** 在类及其父类里找类型为 Connection 的字段。 */
    private static Field findConnectionField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Connection.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return f;
                    } catch (Throwable ignored) {
                        // 试下一个
                    }
                }
            }
        }
        return null;
    }
}
