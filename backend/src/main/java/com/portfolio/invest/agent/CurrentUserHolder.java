package com.portfolio.invest.agent;

/** 请求级 userId 的 ThreadLocal 载体：resolver 写，工厂读后 remove（池化线程复用需防跨请求串值）。 */
final class CurrentUserHolder {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private CurrentUserHolder() {}
    static void set(Long userId) { CURRENT.set(userId); }
    static Long get() { return CURRENT.get(); }
    static void remove() { CURRENT.remove(); }
}
