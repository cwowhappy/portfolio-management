package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpProviderJpaRepository extends JpaRepository<McpProviderJpaEntity, Long> {
    List<McpProviderJpaEntity> findByEnabledTrueOrderByIdAsc();
    Optional<McpProviderJpaEntity> findByCode(String code);

    /** 管理员覆写 token 密文（P1-10）；调用方需在事务内。 */
    @Modifying
    @Query("update McpProviderJpaEntity p set p.authSecretEnc = :enc where p.id = :id")
    void updateAuthSecretEnc(@Param("id") Long id, @Param("enc") String enc);
}
