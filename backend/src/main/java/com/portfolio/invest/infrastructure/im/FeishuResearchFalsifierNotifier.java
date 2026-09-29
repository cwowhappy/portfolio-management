package com.portfolio.invest.infrastructure.im;

import com.portfolio.invest.application.research.ResearchFalsifierNotifier;
import com.portfolio.invest.config.InvestProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 证伪命中飞书提醒（D15 最小文案）：`【证伪提醒】&lt;项目名&gt;：&lt;条件名列表&gt;，详情见研究项目页`
 * ——仅项目名 + 条件名，不含策略详情/持仓/数字。照 {@link FeishuAlertNotifier} 模式：
 * v1 单群推送（chatId 配置群，userId 预留按人路由扩展位）。
 * 尽力而为：任何失败记 WARN 返回 false 绝不抛（扫描主流程不被推送失败打断）。
 */
@Component
public class FeishuResearchFalsifierNotifier implements ResearchFalsifierNotifier {

    private static final Logger log = LoggerFactory.getLogger(FeishuResearchFalsifierNotifier.class);

    private final FeishuClient client;
    private final InvestProperties props;

    public FeishuResearchFalsifierNotifier(FeishuClient client, InvestProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public boolean notify(Long userId, String projectTitle, List<String> conditionNames) {
        try {
            return client.sendCard(props.getIm().getChatId(),
                    "【证伪提醒】" + projectTitle, "red",
                    List.of(String.join("、", conditionNames) + "，详情见研究项目页"));
        } catch (Exception e) { // 降级保护：FeishuClient 契约本不抛，防御兜底
            log.warn("证伪提醒推送异常（projectTitle={}）：{}", projectTitle, e.getMessage());
            return false;
        }
    }
}
