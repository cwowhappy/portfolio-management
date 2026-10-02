package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订阅用例（F16，决策 #18）：get 物化缺省（无行 = pushEnabled=true 空集，不落库）、
 * update 全量替换语义（前端表单整体提交——基于当前聚合 wither 出新聚合整体保存，
 * 仓库差集同步保留未动标的的 added_at）。PUT 幂等：重复提交同一命令结果一致。
 *
 * <p>视图排序单点收口在本服务（行业/标的按码升序），REST 与后续消费方不各自排序。
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository repository;

    public SubscriptionService(SubscriptionRepository repository) {
        this.repository = repository;
    }

    /** 取订阅视图；无行物化缺省实例（内存表达，不落库）。 */
    public SubscriptionView get(Long userId) {
        return toView(currentOf(userId));
    }

    /**
     * 全量替换保存。@Transactional 收口两表一致性（主表 upsert + 子表差集同步，
     * ArchUnit A2：事务是用例语义，注解只允许出现在 application 层）。
     */
    @Transactional
    public SubscriptionView update(Long userId, UpdateSubscriptionCommand cmd) {
        IntelligenceSubscription saved = repository.save(currentOf(userId)
                .togglePush(cmd.pushEnabled())
                .withIndustries(cmd.industries())
                .withStocks(cmd.stocks() == null ? List.of() : cmd.stocks().stream()
                        .map(item -> new SubscriptionStock(item.code(), item.name())).toList()));
        return toView(saved);
    }

    /** 当前聚合：无行取缺省实例（pushEnabled=true 空集）。 */
    private IntelligenceSubscription currentOf(Long userId) {
        return repository.findByUserId(userId)
                .orElseGet(() -> IntelligenceSubscription.defaults(userId));
    }

    private static SubscriptionView toView(IntelligenceSubscription subscription) {
        return new SubscriptionView(subscription.pushEnabled(),
                subscription.industries().stream().sorted().toList(),
                subscription.stocks().stream()
                        .sorted(Comparator.comparing(SubscriptionStock::stockCode))
                        .map(stock -> new SubscriptionView.StockView(
                                stock.stockCode(), stock.stockName()))
                        .toList(),
                subscription.updatedAt());
    }

    /**
     * 订阅视图（REST GET/PUT 回执共用）：industries 为申万一级行业码（如 801010），
     * stocks 为标的对；updatedAt 为最后保存时间（缺省实例为 null——从未落库）。
     */
    public record SubscriptionView(boolean pushEnabled, List<String> industries,
                                   List<StockView> stocks, Instant updatedAt) {
        public SubscriptionView {
            industries = industries == null ? List.of() : List.copyOf(industries);
            stocks = stocks == null ? List.of() : List.copyOf(stocks);
        }

        /** 标的视图。 */
        public record StockView(String code, String name) {
        }
    }

    /**
     * 全量替换命令（PUT /api/intelligence/subscription）：pushEnabled 必填；
     * industries 至多 31 个申万一级行业码（元素非空白）；stocks 至多 100 只
     * （code 必填 ≤12 字符，name 可空 ≤32 字符）。缺省字段（null 集合）按空集处理。
     */
    public record UpdateSubscriptionCommand(
            @NotNull Boolean pushEnabled,
            @Size(max = 31) List<@NotBlank String> industries,
            @Size(max = 100) List<@Valid StockItem> stocks) {

        /** 标的项。 */
        public record StockItem(@NotBlank @Size(max = 12) String code,
                                @Size(max = 32) String name) {
        }
    }
}
