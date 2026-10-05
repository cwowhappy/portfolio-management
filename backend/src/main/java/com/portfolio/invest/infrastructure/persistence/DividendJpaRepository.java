package com.portfolio.invest.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DividendJpaRepository extends JpaRepository<DividendJpaEntity, Long> {

    List<DividendJpaEntity> findByPositionIdOrderByIdAsc(Long positionId);

    /** 组合级批量（跨持仓跨分组）：子查询圈定组合内全部持仓，避免逐持仓 N+1。 */
    @Query("select d from DividendJpaEntity d where d.positionId in "
            + "(select p.id from PositionJpaEntity p where p.portfolioId = :portfolioId) "
            + "order by d.positionId, d.id")
    List<DividendJpaEntity> findByPortfolioId(@Param("portfolioId") Long portfolioId);

    @Query("select d from DividendJpaEntity d where d.positionId in "
            + "(select p.id from PositionJpaEntity p where p.portfolioId = :portfolioId) "
            + "and d.exDate between :fromDate and :toDate order by d.positionId, d.id")
    List<DividendJpaEntity> findByPortfolioIdAndExDateBetween(@Param("portfolioId") Long portfolioId,
            @Param("fromDate") LocalDate from, @Param("toDate") LocalDate to);

    @Query("select d from DividendJpaEntity d where d.positionId in "
            + "(select p.id from PositionJpaEntity p where p.portfolioId = :portfolioId) "
            + "and d.exDate >= :fromDate order by d.positionId, d.id")
    List<DividendJpaEntity> findByPortfolioIdAndExDateGreaterThanEqual(@Param("portfolioId") Long portfolioId,
            @Param("fromDate") LocalDate from);

    @Query("select d from DividendJpaEntity d where d.positionId in "
            + "(select p.id from PositionJpaEntity p where p.portfolioId = :portfolioId) "
            + "and d.exDate <= :toDate order by d.positionId, d.id")
    List<DividendJpaEntity> findByPortfolioIdAndExDateLessThanEqual(@Param("portfolioId") Long portfolioId,
            @Param("toDate") LocalDate to);
}
