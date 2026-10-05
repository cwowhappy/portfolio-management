package com.portfolio.invest.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.useradmin.UserStatusChangedEvent;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ActiveUserStatusCache：TTL 内复用、过期重查、用户不存在不缓存、事件失效即时重查。 */
class ActiveUserStatusCacheTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final AtomicLong now = new AtomicLong();
    private final ActiveUserStatusCache cache = new ActiveUserStatusCache(repo, now::get);

    private User user(boolean enabled) {
        return User.reconstitute(1L, "alice", "h", UserRole.USER, UserStatus.APPROVED, enabled, null, null);
    }

    @DisplayName("TTL内同username二次查询仅查库一次")
    @Test
    void givenCachedWithinTtl_whenIsActiveAgain_thenRepositoryQueriedOnce() {
        when(repo.findByUsername("alice")).thenReturn(Optional.of(user(true)));

        assertThat(cache.isActive("alice")).isTrue();
        assertThat(cache.isActive("alice")).isTrue();

        verify(repo, times(1)).findByUsername("alice");
    }

    @DisplayName("不可用结果同样缓存：停用用户TTL内不重复打库")
    @Test
    void givenDisabledUser_whenIsActiveTwice_thenRepositoryQueriedOnce() {
        when(repo.findByUsername("alice")).thenReturn(Optional.of(user(false)));

        assertThat(cache.isActive("alice")).isFalse();
        assertThat(cache.isActive("alice")).isFalse();

        verify(repo, times(1)).findByUsername("alice");
    }

    @DisplayName("TTL过期后再查询库")
    @Test
    void givenCacheExpired_whenIsActive_thenRequeriesRepository() {
        when(repo.findByUsername("alice")).thenReturn(Optional.of(user(true)));
        cache.isActive("alice");

        now.set(61_000); // TTL=60s，超出即过期
        assertThat(cache.isActive("alice")).isTrue();

        verify(repo, times(2)).findByUsername("alice");
    }

    @DisplayName("用户不存在返回false且不缓存：每次都查库")
    @Test
    void givenUnknownUser_whenIsActive_thenFalseAndNotCached() {
        when(repo.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(cache.isActive("ghost")).isFalse();
        assertThat(cache.isActive("ghost")).isFalse();

        verify(repo, times(2)).findByUsername("ghost");
    }

    @DisplayName("收到UserStatusChangedEvent后立即重查（缓存被逐出）")
    @Test
    void givenCachedActiveUser_whenUserStatusChangedEvent_thenEvictsAndRequeries() {
        when(repo.findByUsername("alice")).thenReturn(Optional.of(user(true)));
        assertThat(cache.isActive("alice")).isTrue();

        when(repo.findByUsername("alice")).thenReturn(Optional.of(user(false))); // 停用后
        cache.onUserStatusChanged(new UserStatusChangedEvent("alice"));

        assertThat(cache.isActive("alice")).isFalse();
        verify(repo, times(2)).findByUsername("alice");
    }
}
