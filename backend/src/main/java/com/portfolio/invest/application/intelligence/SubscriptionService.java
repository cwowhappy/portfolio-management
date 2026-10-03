package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BindingCodeRepository;
import com.portfolio.invest.domain.intelligence.FeishuBindingRepository;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订阅用例（F16，决策 #18）：get 物化缺省（无行 = pushEnabled=true 空集，不落库）、
 * update 全量替换语义（前端表单整体提交——基于当前聚合 wither 出新聚合整体保存，
 * 仓库差集同步保留未动标的的 added_at）。PUT 幂等：重复提交同一命令结果一致。
 *
 * <p>P4 Task 2 绑定数据面（D8）：generateCode（6 位数字、TTL
 * {@code invest.intelligence.binding-code-ttl-minutes}、PK 冲突重生成 ≤3）/ unbind /
 * findOpenId——核销（bindByCode）属 P4 Task 5 绑定闭环（ImCommandRouter +
 * BindingCommandHandler 联动），签名先行占位。
 *
 * <p>视图排序单点收口在本服务（行业/标的按码升序），REST 与后续消费方不各自排序。
 */
@Service
public class SubscriptionService {

    /** 绑定码生成最大尝试次数（D8：PK 冲突即换码重生成，≤3 次——10^6 码空间下撞满近乎不可能）。 */
    static final int MAX_CODE_ATTEMPTS = 3;

    /** 绑定码码空间（6 位数字 000000..999999）。 */
    private static final int CODE_SPACE = 1_000_000;

    private final SubscriptionRepository repository;
    private final BindingCodeRepository bindingCodeRepository;
    private final FeishuBindingRepository bindingRepository;
    private final InvestProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SubscriptionService(SubscriptionRepository repository,
                               BindingCodeRepository bindingCodeRepository,
                               FeishuBindingRepository bindingRepository,
                               InvestProperties props) {
        this(repository, bindingCodeRepository, bindingRepository, props, Clock.systemUTC());
    }

    /** 测试构造器：注入时钟（绑定码 TTL 断言确定性）。 */
    SubscriptionService(SubscriptionRepository repository,
                        BindingCodeRepository bindingCodeRepository,
                        FeishuBindingRepository bindingRepository,
                        InvestProperties props, Clock clock) {
        this.repository = repository;
        this.bindingCodeRepository = bindingCodeRepository;
        this.bindingRepository = bindingRepository;
        this.props = props;
        this.clock = clock;
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

    // ===== P4 Task 2：绑定数据面（D8；核销闭环见 Task 5 BindingCommandHandler）=====

    /**
     * 生成绑定码（POST /api/intelligence/subscription/binding-code）：6 位数字、
     * TTL 取 {@code invest.intelligence.binding-code-ttl-minutes}（缺省 10 分钟）；
     * PK 冲突换码重生成，至多 {@link #MAX_CODE_ATTEMPTS} 次，超限抛
     * {@link IllegalStateException}（服务端罕见态，映射 500——非调用方过错）。
     */
    public BindingCodeView generateCode(Long userId) {
        Instant expiresAt = Instant.now(clock).plus(Duration.ofMinutes(
                props.getIntelligence().getBindingCodeTtlMinutes()));
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = String.format("%06d", random.nextInt(CODE_SPACE));
            if (bindingCodeRepository.trySave(code, userId, expiresAt)) {
                return new BindingCodeView(code, expiresAt);
            }
        }
        throw new IllegalStateException("绑定码生成冲突重试超限（" + MAX_CODE_ATTEMPTS + " 次）");
    }

    /** 解绑（DELETE /api/intelligence/subscription/binding）：删除绑定行，open_id 随行释放（幂等）。 */
    public void unbind(Long userId) {
        bindingRepository.deleteByUserId(userId);
    }

    /** 用户已绑定的飞书 open_id；未绑定返回 empty（推送侧以 empty 判 SKIPPED_NO_BINDING）。 */
    public Optional<String> findOpenId(Long userId) {
        return bindingRepository.findOpenIdByUserId(userId);
    }

    /**
     * 绑定码核销（D8 一次性 + open_id 冲突拒绝）——属 P4 Task 5 绑定闭环
     * （ImCommandRouter + BindingCommandHandler 联动），本签名先行占位；
     * 失败态错误码 {@code BINDING_CODE_EXPIRED} / {@code OPEN_ID_TAKEN} 已入
     * GlobalExceptionHandler 422 分支。
     */
    public void bindByCode(String code, String openId) {
        throw new UnsupportedOperationException("绑定核销由 P4 Task 5（BindingCommandHandler）落地");
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
     * 绑定码视图（POST /api/intelligence/subscription/binding-code 201 回执，D8）：
     * code 为 6 位数字，expiresAt 为失效时刻（TTL 缺省 10 分钟，前端倒计时消费）。
     */
    public record BindingCodeView(String code, Instant expiresAt) {
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
