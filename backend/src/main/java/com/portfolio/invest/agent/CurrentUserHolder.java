package com.portfolio.invest.agent;

/** 请求级 userId 的 ThreadLocal 载体：resolver 写，工厂读后 remove（池化线程复用需防跨请求串值）。 */
public final class CurrentUserHolder {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private CurrentUserHolder() {}
    public static void set(Long userId) { CURRENT.set(userId); }
    public static Long get() { return CURRENT.get(); }
    public static void remove() { CURRENT.remove(); }
}
