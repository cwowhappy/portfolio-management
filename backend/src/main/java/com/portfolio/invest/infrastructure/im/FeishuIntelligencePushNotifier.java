package com.portfolio.invest.infrastructure.im;

import com.portfolio.invest.application.intelligence.IntelligencePushPort;
import com.portfolio.invest.config.InvestProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 情报推送飞书实现（D10/D11）：群推走配置 chatId，单发走 open_id（不要求群配置）。
 * 照 {@link FeishuResearchFalsifierNotifier} 模式：@Component 无条件注册（未配置由
 * {@link FeishuClient} 门禁返回 false），失败 false 不抛——推送失败不拖垮简报主流程。
 */
@Component
public class FeishuIntelligencePushNotifier implements IntelligencePushPort {

    private static final Logger log = LoggerFactory.getLogger(FeishuIntelligencePushNotifier.class);

    private final FeishuClient client;
    private final InvestProperties props;

    public FeishuIntelligencePushNotifier(FeishuClient client, InvestProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public boolean sendToGroup(String title, String template, List<String> bodyLines) {
        try {
            return client.sendCard(props.getIm().getChatId(), title, template, bodyLines);
        } catch (Exception e) { // 降级保护：FeishuClient 契约本不抛，防御兜底
            log.warn("情报群推异常（title={}）：{}", title, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendToUser(String openId, String title, String template, List<String> bodyLines) {
        try {
            return client.sendCardByOpenId(openId, title, template, bodyLines);
        } catch (Exception e) { // 降级保护：同上
            log.warn("情报单发异常（openId={}，title={}）：{}", openId, title, e.getMessage());
            return false;
        }
    }
}
