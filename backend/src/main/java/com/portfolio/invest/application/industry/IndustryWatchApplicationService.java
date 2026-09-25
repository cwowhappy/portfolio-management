package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryWatchItem;
import com.portfolio.invest.domain.industry.IndustryWatchRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 行业关注用例：列表 / 关注（校验·幂等）/ 取关（幂等）。行业 31 个有界，无上限口径。 */
@Service
public class IndustryWatchApplicationService {

    private final IndustryWatchRepository watchRepository;
    private final IndustryRepository industryRepository;

    public IndustryWatchApplicationService(IndustryWatchRepository watchRepository,
                                           IndustryRepository industryRepository) {
        this.watchRepository = watchRepository;
        this.industryRepository = industryRepository;
    }

    public List<IndustryWatchView> listWatched(Long userId) {
        return watchRepository.findByUserId(userId).stream()
                .map(item -> new IndustryWatchView(item.industryCode(), item.addedAt()))
                .toList();
    }

    @Transactional
    public void watch(Long userId, String industryCode) {
        String code = industryCode.trim();
        if (watchRepository.existsByUserIdAndIndustryCode(userId, code)) {
            return; // 幂等：重复关注直接成功
        }
        if (!industryRepository.existsIndustry(code)) {
            throw new IndustryException(IndustryErrorCode.INDUSTRY_NOT_FOUND, "行业不存在: " + code);
        }
        // 并发双击的 UNIQUE 冲突不在此 catch：SimpleJpaRepository.save 自带 @Transactional，
        // 参与本事务的写失败会将外层标记 rollback-only，吞掉后 commit 反抛
        // UnexpectedRollbackException(500)；放行由 GlobalExceptionHandler 映射 400 更诚实。
        watchRepository.save(new IndustryWatchItem(null, userId, code, Instant.now()));
    }

    @Transactional
    public void unwatch(Long userId, String industryCode) {
        watchRepository.deleteByUserIdAndIndustryCode(userId, industryCode.trim());
    }
}
