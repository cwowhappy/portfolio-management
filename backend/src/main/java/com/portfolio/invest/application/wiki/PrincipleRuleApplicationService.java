package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrincipleRuleApplicationService {

    private final PrincipleRuleRepository repository;

    public PrincipleRuleApplicationService(PrincipleRuleRepository repository) {
        this.repository = repository;
    }

    public List<PrincipleRuleView> rules(Long userId) {
        return repository.findByUserId(userId).stream().map(PrincipleRuleView::from).toList();
    }

    @Transactional
    public PrincipleRuleView createRule(Long userId, CreatePrincipleRuleCommand cmd) {
        PrincipleRule rule = PrincipleRule.create(userId, cmd.metric(), cmd.threshold(),
                cmd.enabled(), cmd.description(), Instant.now());
        try {
            return PrincipleRuleView.from(repository.save(rule));
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(user_id, metric) 兜底：同指标已配置 → 友好冲突（409）
            throw new WikiException(WikiErrorCode.DUPLICATE_METRIC, "该指标已有规则，请直接编辑既有规则");
        }
    }

    @Transactional
    public PrincipleRuleView updateRule(Long userId, Long ruleId, UpdatePrincipleRuleCommand cmd) {
        PrincipleRule existing = requireRule(userId, ruleId);
        PrincipleRule updated = existing.update(cmd.threshold(), cmd.enabled(), cmd.description());
        return PrincipleRuleView.from(repository.save(updated));
    }

    @Transactional
    public void deleteRule(Long userId, Long ruleId) {
        requireRule(userId, ruleId);
        repository.deleteById(ruleId);
    }

    private PrincipleRule requireRule(Long userId, Long ruleId) {
        return repository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> new WikiException(WikiErrorCode.NOT_FOUND, "规则不存在"));
    }
}
