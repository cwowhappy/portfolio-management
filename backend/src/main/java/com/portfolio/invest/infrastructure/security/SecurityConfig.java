package com.portfolio.invest.infrastructure.security;

import com.portfolio.invest.config.InvestProperties;
import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/** Spring Security 装配：会话认证 + remember-me；CSRF 关闭（同源 JSON API + SameSite=Lax，ADR-0007）。 */
@Configuration
public class SecurityConfig {

    public static final String REMEMBER_ME_COOKIE = "invest-remember-me";
    private static final int REMEMBER_ME_SECONDS = 30 * 24 * 3600;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, ActiveUserStatusCache activeUserStatusCache,
            RememberMeServices rememberMeServices, SessionRegistry sessionRegistry) throws Exception {

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
            .sessionManagement(sm -> sm
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    // B14 重置密码吊销全部会话：maximumSessions(-1)=不限并发，仅为启用过期会话拦截
                    //（ConcurrentSessionFilter 查 SessionRegistry，过期会话不再放行）。登录是控制器手动
                    // 认证、不走过滤器内 SessionAuthenticationStrategy，登记由 AuthController 显式调用
                    // UserSessionRegistry.register 完成。默认过期策略写 200 纯文本，JSON API 统一改 401。
                    .sessionConcurrency(conc -> conc
                            .maximumSessions(-1)
                            .sessionRegistry(sessionRegistry)
                            .expiredSessionStrategy(event -> {
                                HttpServletResponse response = event.getResponse();
                                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.getWriter()
                                        .write("{\"code\":\"SESSION_EXPIRED\",\"message\":\"会话已失效，请重新登录\"}");
                            })))
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
            // 判定走短 TTL 缓存（60s 复用 + 状态变更事件逐出），不每请求查库（B9）。
            .addFilterBefore(new ActiveUserFilter(activeUserStatusCache),
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
        // 登录是 JSON API，请求里没有 remember-me 表单参数；loginSuccess 内部会再校验该参数，
        // 不开 alwaysRemember 会静默不下发 cookie。是否签发已由 AuthController 按 body 的 rememberMe 字段把关。
        svc.setAlwaysRemember(true);
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

    /** 会话登记表（B14）：记录 sessionId→username 归属，重置密码按用户名吊销全部会话。 */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * 会话销毁事件桥（B14）：session invalidate/超时 → HttpSessionDestroyedEvent →
     * SessionRegistryImpl 自动逐出条目，避免登记表随时间泄漏已销毁会话。
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
