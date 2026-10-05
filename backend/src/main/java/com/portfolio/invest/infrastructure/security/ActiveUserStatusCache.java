package com.portfolio.invest.infrastructure.security;

import com.portfolio.invest.application.useradmin.UserStatusChangedEvent;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 认证用户状态短 TTL 缓存（B9）：ActiveUserFilter 每请求查库改为 60s TTL 内复用 canLogin 判定；
 * 管理员变更（审核/启用/停用）经 {@link UserStatusChangedEvent} 立即逐出，停用即时生效不受 TTL 拖延。
 *
 * <p>存储为 ConcurrentHashMap（非 TtlCache）：判定需读-改-写原子（TtlCache get-then-put 非原子，
 * 并发 miss 会重复查库），值对象整体替换由 {@link ConcurrentMap#compute} 保证 per-key 原子。
 * 用户不存在时 compute 返回 null 即不落缓存、每次请求重查——删除用户即时生效，
 * 不因一次 miss 被 TTL 钉死。
 */
@Component
public class ActiveUserStatusCache {

    private static final long TTL_MILLIS = 60_000;

    /** 不可变值对象：整个替换而非字段细粒度修改，配合 compute 天然原子。 */
    private record State(boolean active, long cachedAtMillis) {}

    private final UserRepository userRepository;
    private final LongSupplier nowMillis;
    private final ConcurrentMap<String, State> cache = new ConcurrentHashMap<>();

    /** 生产装配：真实墙钟。 */
    @Autowired
    public ActiveUserStatusCache(UserRepository userRepository) {
        this(userRepository, System::currentTimeMillis);
    }

    /** 测试注入：自定义时钟（TTL 过期可确定性推进，避免真实墙钟等待）。 */
    ActiveUserStatusCache(UserRepository userRepository, LongSupplier nowMillis) {
        this.userRepository = userRepository;
        this.nowMillis = nowMillis;
    }

    /** TTL 内复用判定；无缓存或已过期时查库。用户不存在返回 false 且不缓存。 */
    public boolean isActive(String username) {
        State state = cache.compute(username, (k, s) -> {
            long now = nowMillis.getAsLong();
            if (s != null && now - s.cachedAtMillis() < TTL_MILLIS) {
                return s;
            }
            Boolean active = userRepository.findByUsername(username).map(User::canLogin).orElse(null);
            return active == null ? null : new State(active, now);
        });
        return state != null && state.active();
    }

    /** 状态/启用变更即时失效：逐出该 username，下次请求以新状态重查。 */
    @EventListener
    public void onUserStatusChanged(UserStatusChangedEvent event) {
        cache.remove(event.username());
    }
}
