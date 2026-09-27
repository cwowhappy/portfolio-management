package com.portfolio.invest.web;

import com.portfolio.invest.application.auth.AuthApplicationService;
import com.portfolio.invest.application.auth.EmailCodeService;
import com.portfolio.invest.application.auth.RegisterCommand;
import com.portfolio.invest.application.auth.UserView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.web.dto.LoginRequest;
import com.portfolio.invest.web.dto.PasswordResetRequest;
import com.portfolio.invest.web.dto.RegisterCodeRequest;
import com.portfolio.invest.web.dto.ResetCodeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
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

    public AuthController(AuthApplicationService auth, EmailCodeService emailCodeService,
                          AuthenticationManager authenticationManager,
                          RememberMeServices rememberMeServices) {
        this.auth = auth;
        this.emailCodeService = emailCodeService;
        this.authenticationManager = authenticationManager;
        this.rememberMeServices = rememberMeServices;
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
        emailCodeService.issueResetCode(req.identifier());
        return ResponseEntity.ok(Map.of("message", "验证码已发送"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody PasswordResetRequest req) {
        auth.resetPassword(req.identifier(), req.code(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "密码已重置"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                   HttpServletRequest request, HttpServletResponse response) {
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
            SecurityContextHolder.getContext().setAuthentication(authn);
            var session = request.getSession(true);
            // 会话固定防护：登录成功立即轮换 session id，再写入认证上下文
            request.changeSessionId();
            // Spring Security 7 的 SecurityContextHolderFilter 只加载 DeferredContext、不再自动 saveContext，
            // 手动登录必须显式把 SecurityContext 写入会话，否则下次请求（/me）加载不到认证态。
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    SecurityContextHolder.getContext());
            if (req.rememberMe()) {
                rememberMeServices.loginSuccess(request, response, authn);
            }
            return ResponseEntity.ok(UserView.from(user));
        } catch (BadCredentialsException e) {
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
