package com.portfolio.invest.infrastructure.security;

import com.portfolio.invest.domain.user.RememberMeTokenStore;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Component;

/** remember-me 持久令牌吊销：委托 Spring Security 的 JDBC 令牌仓库（persistent_logins 表）。 */
@Component
public class JdbcRememberMeTokenStore implements RememberMeTokenStore {

    private final PersistentTokenRepository delegate;

    public JdbcRememberMeTokenStore(PersistentTokenRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public void removeUserTokens(String username) {
        delegate.removeUserTokens(username);
    }
}
