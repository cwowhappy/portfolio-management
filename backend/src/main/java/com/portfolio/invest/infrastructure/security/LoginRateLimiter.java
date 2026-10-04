package com.portfolio.invest.infrastructure.security;

import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流（B4）：按 username 计数，连续失败达阈 → 锁定一段时间，锁定期内即使密码正确也拒绝。
 *
 * <p>窗口语义（固定窗口）：自首次失败起一个窗口时长，窗口内累计失败次数；窗口过期后首次失败重新开窗。
 * 锁定自达阈那一刻起算锁定时长，锁定期内的失败调用不改变状态（不重置、不延长），锁定期满自动解锁
 * 并以清白计数重新开始。成功登录即清零该 username 全部计数。
 *
 * <p>存储为 ConcurrentHashMap（非 TtlCache）：计数需要读-改-写原子性（TtlCache get-then-put 非原子，
 * 并发失败会丢更新导致少计），值对象整体替换由 {@link ConcurrentMap#compute} 保证 per-key 原子。
 * 键按小写归一，防大小写轮换绕过。内存态丢失（重启）即限流清零，属可接受语义。
 */
@Component
public class LoginRateLimiter {

    static final int DEFAULT_MAX_FAILURES = 5;
    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);
    static final Duration DEFAULT_LOCK = Duration.ofMinutes(5);

    /** 不可变值对象：整个替换而非字段细粒度修改，配合 compute 天然原子。 */
    private record Attempts(int count, long windowStartMillis, long lockedUntilMillis) {}

    private final int maxFailures;
    private final long windowMillis;
    private final long lockMillis;
    private final LongSupplier nowMillis;
    private final ConcurrentMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    /** 生产装配：5 次失败锁 5 分钟。 */
    public LoginRateLimiter() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_WINDOW, DEFAULT_LOCK, System::currentTimeMillis);
    }

    /** 测试注入：自定义阈值/窗口/锁定时长与时钟（避免真实墙钟等待）。 */
    LoginRateLimiter(int maxFailures, Duration window, Duration lock, LongSupplier nowMillis) {
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("maxFailures 必须为正数: " + maxFailures);
        }
        this.maxFailures = maxFailures;
        this.windowMillis = window.toMillis();
        this.lockMillis = lock.toMillis();
        this.nowMillis = nowMillis;
    }

    /** 锁定期内返回剩余秒数（向上取整，供 Retry-After），未锁定返回 empty。 */
    public OptionalLong blockedForSeconds(String username) {
        Attempts a = attempts.get(key(username));
        if (a == null) {
            return OptionalLong.empty();
        }
        long remainingMillis = a.lockedUntilMillis() - nowMillis.getAsLong();
        if (remainingMillis <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of((remainingMillis + 999) / 1000);
    }

    /** 记录一次失败：窗口内计数，达阈即锁定；锁定期内调用为 no-op（由 blockedForSeconds 拦截）。 */
    public void onFailure(String username) {
        long now = nowMillis.getAsLong();
        attempts.compute(key(username), (k, a) -> {
            if (a == null) {
                return new Attempts(1, now, 0);
            }
            if (a.lockedUntilMillis() > now) {
                return a; // 锁定中：不重置、不延长
            }
            if (now - a.windowStartMillis() >= windowMillis) {
                return new Attempts(1, now, 0); // 窗口过期：重新开窗计次
            }
            int count = a.count() + 1;
            long lockedUntil = count >= maxFailures ? now + lockMillis : a.lockedUntilMillis();
            return new Attempts(count, a.windowStartMillis(), lockedUntil);
        });
    }

    /** 成功登录：清零该 username 全部计数。 */
    public void onSuccess(String username) {
        attempts.remove(key(username));
    }

    /** 键归一化：登录名大小写不敏感地共享限流额度，防大小写轮换绕过。 */
    private static String key(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }
}
