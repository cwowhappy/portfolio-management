package com.portfolio.invest.web;

import com.portfolio.invest.application.auth.AuthApplicationService;
import com.portfolio.invest.application.auth.EmailCodeService;
import com.portfolio.invest.application.auth.RegisterCommand;
import com.portfolio.invest.application.auth.UserView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserSessionRegistry;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.infrastructure.security.LoginRateLimiter;
import com.portfolio.invest.web.dto.LoginRequest;
import com.portfolio.invest.web.dto.PasswordResetRequest;
import com.portfolio.invest.web.dto.RegisterCodeRequest;
import com.portfolio.invest.web.dto.ResetCodeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接入层：注册 / 找回密码 / 登录（JSON）/ me。登出由 Security 过滤器处理。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthApplicationService auth;
    private final EmailCodeService emailCodeService;
    private final AuthenticationManager authenticationManager;
    private final RememberMeServices rememberMeServices;
    private final LoginRateLimiter loginRateLimiter;
    private final UserSessionRegistry userSessionRegistry;

    public AuthController(AuthApplicationService auth, EmailCodeService emailCodeService,
                          AuthenticationManager authenticationManager,
                          RememberMeServices rememberMeServices,
                          LoginRateLimiter loginRateLimiter,
                          UserSessionRegistry userSessionRegistry) {
        this.auth = auth;
        this.emailCodeService = emailCodeService;
        this.authenticationManager = authenticationManager;
        this.rememberMeServices = rememberMeServices;
        this.loginRateLimiter = loginRateLimiter;
        this.userSessionRegistry = userSessionRegistry;
    }

    @PostMapping("/register")
    public ResponseEntity<UserView> register(@Valid @RequestBody RegisterCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(cmd));
    }

    @PostMapping("/register-code")
    public ResponseEntity<Map<String, String>> registerCode(@Valid @RequestBody RegisterCodeRequest req) {
        emailCodeService.issueRegisterCode(req.username(), req.password(), req.email());
        return ResponseEntity.ok(Map.of("message", "验证码已发送"));
    }

    @PostMapping("/reset-code")
    public ResponseEntity<Map<String, String>> resetCode(@Valid @RequestBody ResetCodeRequest req) {
        return ResponseEntity.ok(Map.of("message", emailCodeService.issueResetCode(req.identifier())));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody PasswordResetRequest req) {
        auth.resetPassword(req.identifier(), req.code(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "密码已重置"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                   HttpServletRequest request, HttpServletResponse response) {
        // B4：bcrypt 在线爆破防护——认证前先查限流；锁定期内密码正确也拒绝（429 + Retry-After），不触发 authenticate
        OptionalLong blocked = loginRateLimiter.blockedForSeconds(req.username());
        if (blocked.isPresent()) {
            long retryAfterSeconds = blocked.getAsLong();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "TOO_MANY_ATTEMPTS");
            body.put("message", "尝试次数过多，请 " + ((retryAfterSeconds + 59) / 60) + " 分钟后再试");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                    .body(body);
        }
        try {
            Authentication authn = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            User user = ((AuthenticatedUser) authn.getPrincipal()).user();
            if (user.status() == UserStatus.PENDING) {
                return error(HttpStatus.FORBIDDEN, "ACCOUNT_PENDING", "账号待审核，请等待管理员确认");
            }
            if (user.status() == UserStatus.REJECTED) {
                return error(HttpStatus.FORBIDDEN, "ACCOUNT_REJECTED", "账号已被拒绝，请重新注册");
            }
            if (!user.enabled()) {
                return error(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已被停用");
            }
            loginRateLimiter.onSuccess(req.username()); // 成功登录清零失败计数
            SecurityContextHolder.getContext().setAuthentication(authn);
            var session = request.getSession(true);
            // 会话固定防护：登录成功立即轮换 session id，再写入认证上下文
            request.changeSessionId();
            // Spring Security 7 的 SecurityContextHolderFilter 只加载 DeferredContext、不再自动 saveContext，
            // 手动登录必须显式把 SecurityContext 写入会话，否则下次请求（/me）加载不到认证态。
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    SecurityContextHolder.getContext());
            // B14：登记会话归属（changeSessionId 之后取轮换后的 id），重置密码时按用户名吊销全部会话。
            // 手动登录不经过过滤器内 SessionAuthenticationStrategy，不会自动登记。
            userSessionRegistry.register(session.getId(), user.username());
            if (req.rememberMe()) {
                rememberMeServices.loginSuccess(request, response, authn);
            }
            return ResponseEntity.ok(UserView.from(user));
        } catch (BadCredentialsException e) {
            loginRateLimiter.onFailure(req.username()); // 密码错误计入限流窗口
            return error(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser au)) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未登录");
        }
        return ResponseEntity.ok(UserView.from(au.user()));
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
