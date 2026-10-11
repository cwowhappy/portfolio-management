package com.portfolio.invest.domain.memory;

import java.util.Optional;

/** 画像授权配置仓库端口（user_profile_config，每用户至多一行）。 */
public interface UserProfileConfigRepository {

    Optional<UserProfileConfig> findByUserId(Long userId);

    UserProfileConfig save(UserProfileConfig config);
}
