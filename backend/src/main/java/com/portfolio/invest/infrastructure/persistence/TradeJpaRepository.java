package com.portfolio.invest.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeJpaRepository extends JpaRepository<TradeJpaEntity, Long> {

    List<TradeJpaEntity> findByPositionIdOrderByIdAsc(Long positionId);

    /** 组合级批量（跨持仓跨分组）：子查询圈定组合内全部持仓，避免逐持仓 N+1。 */
    @Query("select t from TradeJpaEntity t where t.positionId in "
            + "(select p.id from PositionJpaEntity p where p.portfolioId = :portfolioId) "
            + "order by t.positionId, t.id")
    List<TradeJpaEntity> findByPortfolioId(@Param("portfolioId") Long portfolioId);

    @Query("select t from TradeJpaEntity t where t.positionId in "
            + "(select p.id from PositionJpaEntity p where p.portfolioId = :portfolioId) "
            + "and t.tradeDate between :fromDate and :toDate order by t.positionId, t.id")
    List<TradeJpaEntity> findByPortfolioIdAndTradeDateBetween(@Param("portfolioId") Long portfolioId,
            @Param("fromDate") LocalDate from, @Param("toDate") LocalDate to);

    @Query("select t from TradeJpaEntity t where t.positionId in "
            + "(select p.id from PositionJpaEntity p where p.portfolioId = :portfolioId) "
            + "and t.tradeDate >= :fromDate order by t.positionId, t.id")
    List<TradeJpaEntity> findByPortfolioIdAndTradeDateGreaterThanEqual(@Param("portfolioId") Long portfolioId,
            @Param("fromDate") LocalDate from);

    @Query("select t from TradeJpaEntity t where t.positionId in "
            + "(select p.id from PositionJpaEntity p where p.portfolioId = :portfolioId) "
            + "and t.tradeDate <= :toDate order by t.positionId, t.id")
    List<TradeJpaEntity> findByPortfolioIdAndTradeDateLessThanEqual(@Param("portfolioId") Long portfolioId,
            @Param("toDate") LocalDate to);
}
