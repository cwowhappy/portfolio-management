package com.portfolio.invest.application.screening;

import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningException;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScreeningApplicationService {

    private final ScreeningRepository repository;

    public ScreeningApplicationService(ScreeningRepository repository) {
        this.repository = repository;
    }

    public List<StockScreeningResult> screen(ScreeningCriteria criteria) {
        if (!criteria.hasAnyCondition()) {
            throw new ScreeningException("SCREENING_NO_CONDITION", "至少需要一个筛选条件");
        }
        if (criteria.sortBy() == null || !ScreeningCriteria.SORTABLE_FIELDS.contains(criteria.sortBy())) {
            throw new ScreeningException("SCREENING_INVALID_SORT", "不支持的排序字段: " + criteria.sortBy());
        }
        if (criteria.limit() < 1 || criteria.limit() > 200) {
            throw new ScreeningException("SCREENING_INVALID_LIMIT", "结果上限需在 1~200 之间");
        }
        return repository.findStocks(criteria);
    }
}
