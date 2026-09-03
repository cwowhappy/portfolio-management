package com.portfolio.invest.infrastructure.security;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.user.UserRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

/** Spring Security 装配：会话认证 + remember-me；CSRF 关闭（同源 JSON API + SameSite=Lax，ADR-0007）。 */
@Configuration
public class SecurityConfig {

    public static final String REMEMBER_ME_COOKIE = "invest-remember-me";
    private static final int REMEMBER_ME_SECONDS = 30 * 24 * 3600;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, UserRepository userRepository,
            RememberMeServices rememberMeServices) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout
                    .logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler((req, res, auth) -> {
                        res.setStatus(HttpStatus.OK.value());
                        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        res.getWriter().write("{\"message\":\"已退出登录\"}");
                    })
                    .deleteCookies("JSESSIONID", REMEMBER_ME_COOKIE))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                    // 公开端点单一清单：PublicEndpointPaths（与 ActiveUserFilter 对齐）
                    .requestMatchers(PublicEndpointPaths.EXACT).permitAll()
                    .requestMatchers(PublicEndpointPaths.antPatterns()).permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated())
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
            // 停用即时生效：在所有认证过滤器（含 remember-me）之后、授权之前校验用户状态；
            // 若置于 SecurityContextHolderFilter 之前，上下文尚未加载，检查永远是空操作。
            .addFilterBefore(new ActiveUserFilter(userRepository),
                    org.springframework.security.web.access.intercept.AuthorizationFilter.class);

        return http.build();
    }

    /** 去兜底：remember-me 签名 key 缺失/空白时启动报错，禁止用公开/可预测 key 静默运行（B-22）。 */
    static String requireRememberMeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("REMEMBER_ME_KEY 未配置：remember-me 签名 key 必须显式提供");
        }
        return key;
    }

    @Bean
    public RememberMeServices rememberMeServices(UserDetailsService userDetailsService,
                                                 PersistentTokenRepository tokenRepository,
                                                 InvestProperties props) {
        PersistentTokenBasedRememberMeServices svc = new PersistentTokenBasedRememberMeServices(
                requireRememberMeKey(props.getSecurity().getRememberMeKey()), userDetailsService, tokenRepository);
        svc.setCookieName(REMEMBER_ME_COOKIE);
        svc.setTokenValiditySeconds(REMEMBER_ME_SECONDS);
        // Spring Security 7 已移除 setCookiePath，改用 Cookie 自定义器
        svc.setCookieCustomizer(cookie -> cookie.setPath("/"));
        return svc;
    }

    @Bean
    public PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repo = new JdbcTokenRepositoryImpl();
        repo.setDataSource(dataSource);
        return repo;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
