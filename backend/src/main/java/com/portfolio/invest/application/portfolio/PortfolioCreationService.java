package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组合的幂等创建边界。
 *
 * <p>{@link PortfolioRepository#insertPortfolioIfAbsent(Long)} 是一条 {@code @Modifying} 原生查询，
 * 要求处于活动事务中；而「事务是用例语义」规范只允许 {@code @Transactional} 出现在 application 层，
 * 且 {@link PortfolioApplicationService#getOrCreatePortfolio} 同时被事务写方法与无事务读方法调用，
 * 因此把这条创建单独收敛到本 bean 的方法级事务边界：无外层事务时自开事务提交，有外层事务时直接加入。
 */
@Service
public class PortfolioCreationService {

    private final PortfolioRepository repository;

    public PortfolioCreationService(PortfolioRepository repository) {
        this.repository = repository;
    }

    /** 幂等创建组合：已存在则无副作用；并发首次访问时仅一行生效。 */
    @Transactional
    public void ensureCreated(Long userId) {
        repository.insertPortfolioIfAbsent(userId);
    }
}
