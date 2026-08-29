package com.portfolio.invest.domain.user;

/** remember-me 持久令牌端口：重置密码等安全操作后吊销该用户的全部令牌。实现在 infrastructure。 */
public interface RememberMeTokenStore {

    void removeUserTokens(String username);
}
