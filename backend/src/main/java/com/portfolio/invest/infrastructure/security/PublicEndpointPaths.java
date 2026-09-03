package com.portfolio.invest.infrastructure.security;

/**
 * 公开端点唯一清单：SecurityConfig 的 permitAll 与 ActiveUserFilter 的
 * shouldNotFilter 共用本类，避免两处名单漂移（停用用户被 401 挡住公开端点）。
 */
public final class PublicEndpointPaths {

    private PublicEndpointPaths() {}

    /** 精确公开路径（无子路径语义）。 */
    public static final String[] EXACT = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/agent/health",
            "/api/agent/status",
    };

    /** 前缀公开路径（含子路径，映射为 /** 通配）。 */
    public static final String[] PREFIXES = {
            "/api/market/",
            "/api/valuation/",
            "/api/screening/",
            "/actuator/",
    };

    /** Ant 通配形式（供 SecurityConfig requestMatchers 使用）。 */
    public static String[] antPatterns() {
        String[] patterns = new String[PREFIXES.length];
        for (int i = 0; i < PREFIXES.length; i++) {
            patterns[i] = PREFIXES[i] + "**";
        }
        return patterns;
    }

    /** 供过滤器按 servletPath 判断是否公开端点。 */
    public static boolean isPublicPath(String path) {
        if (path == null) {
            return false;
        }
        for (String exact : EXACT) {
            if (exact.equals(path)) {
                return true;
            }
        }
        for (String prefix : PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
