package com.portfolio.invest.application.auth;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.user.PasswordPolicy;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserErrorCode;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.domain.user.UsernamePolicy;
import com.portfolio.invest.domain.user.VerificationCode;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import com.portfolio.invest.domain.user.VerificationPurpose;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 邮箱验证码核心：发码（预检+频控+哈希落库+发信）与消费型验码。邮箱统一 trim+小写归一化后查库/落库/发信。 */
@Service
public class EmailCodeService {

    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration COOLDOWN = Duration.ofSeconds(60);
    static final int DAILY_LIMIT = 10;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    private final UserRepository userRepository;
    private final VerificationCodeRepository codeRepository;
    private final MailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final InvestProperties props;
    private final Clock clock;

    @Autowired
    public EmailCodeService(UserRepository userRepository, VerificationCodeRepository codeRepository,
                            MailSender mailSender, PasswordEncoder passwordEncoder, InvestProperties props) {
        this(userRepository, codeRepository, mailSender, passwordEncoder, props, Clock.system(ZONE));
    }

    EmailCodeService(UserRepository userRepository, VerificationCodeRepository codeRepository,
                     MailSender mailSender, PasswordEncoder passwordEncoder, InvestProperties props,
                     Clock clock) {
        this.userRepository = userRepository;
        this.codeRepository = codeRepository;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
        this.clock = clock;
    }

    /** FR-A1/A2：注册发码——发码前完成用户名/密码/邮箱全部预检（FR-A3 落库时再校验一次兜底）。 */
    public void issueRegisterCode(String username, String password, String email) {
        String e = normalize(email);
        PasswordPolicy.validate(password);
        UsernamePolicy.validate(username);
        assertEmailAvailable(e);
        Optional<User> existing = userRepository.findByUsername(username.trim());
        if (existing.isPresent() && existing.get().status() != UserStatus.REJECTED) {
            throw new UserException(UserErrorCode.USERNAME_TAKEN, "用户名已存在");
        }
        issueCode(e, VerificationPurpose.REGISTER, "九和账号验证码",
                "您正在注册九和投资账号，验证码 %s，5 分钟内有效。若非本人操作请忽略本邮件。");
    }

    /** B5 中性响应文案：可找回与否对外逐字节一致，防账号存在性/状态枚举。 */
    public static final String RESET_NEUTRAL_MESSAGE = "如果该账号可以找回密码，验证码已发送至绑定邮箱";

    /** FR-B2：找回发码——仅真正可找回的账号真实发信；可找回与否响应完全一致（B5 防枚举）。 */
    public String issueResetCode(String identifier) {
        findResettableUser(identifier).ifPresent(user -> issueCode(
                normalize(user.email()), VerificationPurpose.RESET, "九和密码重置验证码",
                "您正在重置九和投资账号密码，验证码 %s，5 分钟内有效。若非本人操作请忽略本邮件。"));
        return RESET_NEUTRAL_MESSAGE;
    }

    /**
     * FR-B2 定位规则：用户名或邮箱皆可；仅 USER、APPROVED 且启用、已绑邮箱可找回。
     * 任何不可找回情形（不存在/管理员/未绑邮箱/停用/空白标识）一律返回 empty、不区分原因（B5 防枚举）。
     */
    public Optional<User> findResettableUser(String identifier) {
        String id = identifier == null ? "" : identifier.trim();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Optional<User> found = id.contains("@")
                ? userRepository.findByEmail(normalize(id))
                : userRepository.findByUsername(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        User user = found.get();
        if (user.role() == UserRole.ADMIN || user.email() == null || !user.canLogin()) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    /** 消费型验码：哈希比对通过后 tryMarkUsed 原子消费（并发同码双验恰一成功，B8）；失败 attemptFailed 计数（5 次作废）。 */
    public void verify(String email, VerificationPurpose purpose, String rawCode) {
        String e = normalize(email);
        Instant now = clock.instant();
        VerificationCode code = codeRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(e, purpose)
                .orElseThrow(() -> new UserException(UserErrorCode.CODE_INVALID, "验证码错误或已失效"));
        if (code.isUsed() || code.isExpired(now) || code.attemptsExhausted()) {
            throw new UserException(UserErrorCode.CODE_INVALID, "验证码错误或已失效");
        }
        if (!passwordEncoder.matches(rawCode, code.codeHash())) {
            codeRepository.save(code.attemptFailed());
            throw new UserException(UserErrorCode.CODE_INVALID, "验证码错误或已失效");
        }
        if (!codeRepository.tryMarkUsed(code.id(), now)) {
            throw new UserException(UserErrorCode.CODE_INVALID, "验证码错误或已失效");
        }
    }

    /** 邮箱格式 + 未被绑定（注册与代填共用）。校验与查库均针对归一化后的邮箱。 */
    public void assertEmailAvailable(String email) {
        String e = normalize(email);
        if (e == null || e.isBlank() || !EMAIL_PATTERN.matcher(e).matches()) {
            throw new UserException(UserErrorCode.EMAIL_INVALID, "邮箱格式不正确");
        }
        if (userRepository.findByEmail(e).isPresent()) {
            throw new UserException(UserErrorCode.EMAIL_TAKEN, "邮箱已被其他账号绑定");
        }
    }

    /** 邮箱归一化：trim + 小写（V21 查询与唯一约束区分大小写，归一化避免同人双账号/查库落空）。 */
    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void issueCode(String email, VerificationPurpose purpose, String subject, String template) {
        if (!mailSender.enabled()) {
            throw new UserException(UserErrorCode.MAIL_NOT_CONFIGURED, "系统未配置邮件服务");
        }
        Instant now = clock.instant();
        Optional<VerificationCode> latest =
                codeRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);
        if (latest.isPresent() && latest.get().createdAt().isAfter(now.minus(COOLDOWN))) {
            throw new UserException(UserErrorCode.CODE_SEND_TOO_FREQUENT, "发送过于频繁，请 1 分钟后再试");
        }
        Instant dayStart = LocalDate.ofInstant(now, ZONE).atStartOfDay(ZONE).toInstant();
        if (codeRepository.countByEmailAndCreatedAtAfter(email, dayStart) >= DAILY_LIMIT) {
            throw new UserException(UserErrorCode.CODE_DAILY_LIMIT, "该邮箱今日发送次数已达上限");
        }
        String code = generateCode();
        codeRepository.save(VerificationCode.reconstitute(null, email, purpose,
                passwordEncoder.encode(code), 0, null, now.plus(TTL), now));
        try {
            mailSender.send(email, subject, template.formatted(code));
        } catch (UserException e) {
            throw e;
        } catch (Exception e) {
            // 发信失败必须留痕：曾因无声吞异常导致 SMTP 故障无线索可查（观测盲区，v1 发布前修复）
            log.warn("[mail] 验证码发信失败 to={} purpose={}", email, purpose, e);
            throw new UserException(UserErrorCode.MAIL_SEND_FAILED, "邮件发送失败，请稍后重试");
        }
    }

    private String generateCode() {
        String fixed = props.getMail().getTestFixedCode();
        return !fixed.isBlank() ? fixed : String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
