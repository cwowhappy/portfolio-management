package com.portfolio.invest.domain.user;

/**
 * HTTP 会话登记端口：登录时登记会话归属，重置密码等安全操作后吊销该用户全部会话（B14）。
 * 实现在 infrastructure（包 Spring SessionRegistry，过期由 ConcurrentSessionFilter 拦截为 401）。
 */
public interface UserSessionRegistry {

    /** 登录成功后登记会话（须在 changeSessionId 之后，以轮换后的 sessionId 登记）。 */
    void register(String sessionId, String username);

    /** 吊销该用户当前全部会话（已过期/登出的条目由容器事件自动逐出，无需处理）。 */
    void expireAll(String username);
}
