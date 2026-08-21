package com.portfolio.invest.domain.user;

import java.util.List;
import java.util.Optional;

/** 用户仓库端口：实现见 infrastructure.persistence。 */
public interface UserRepository {
    Optional<User> findByUsername(String username);
    Optional<User> findById(Long id);
    List<User> findAll();
    User save(User user);
}
