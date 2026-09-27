package com.portfolio.invest.agent;

import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.application.im.ImMessageListener;
import com.portfolio.invest.application.im.ImReplyPort;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 飞书对话桥接（feishu-messaging P2）：单聊文本 → owner 白名单 → HarnessAgent → reply。
 * 尽力而为：一切异常吞掉记日志/回复失败提示，绝不外溢（executor 线程保护）。
 */
@Component
public class FeishuDialogueBridge implements ImMessageListener {

    private static final Logger log = LoggerFactory.getLogger(FeishuDialogueBridge.class);
    private static final int MAX_REPLY_CHARS = 3000;

    private final FeishuAgentInvoker invoker;
    private final ImReplyPort replyPort;
    private final UserRepository userRepo;
    private final InvestProperties props;

    public FeishuDialogueBridge(FeishuAgentInvoker invoker, ImReplyPort replyPort,
                                UserRepository userRepo, InvestProperties props) {
        this.invoker = invoker;
        this.replyPort = replyPort;
        this.userRepo = userRepo;
        this.props = props;
    }

    @Override
    public void onMessage(ImInboundMessage message) {
        try {
            doHandle(message);
        } catch (Exception e) {
            log.error("飞书对话处理异常（messageId={}）", message.messageId(), e);
            replyQuietly(message.messageId(), "处理失败，请稍后重试；涉及审批的写操作请到网页端完成。");
        }
    }

    private void doHandle(ImInboundMessage message) {
        var im = props.getIm();
        if (!im.isDialogueEnabled()) {
            return; // ws 未启用理论上收不到，防御性早退
        }
        if (im.getOwnerOpenId() == null || im.getOwnerOpenId().isBlank()) {
            log.info("飞书消息来自 open_id={}，未配置 invest.im.owner-open-id，忽略"
                    + "（将该 open_id 填入 FEISHU_OWNER_OPEN_ID 后重启即可启用对话）", message.openId());
            return;
        }
        if (!im.getOwnerOpenId().equals(message.openId())) {
            log.info("飞书消息来自非 owner（open_id={}），忽略", message.openId());
            return;
        }
        if (!"p2p".equals(message.chatType())) {
            return;
        }
        if (!"text".equals(message.msgType()) || message.text() == null) {
            replyQuietly(message.messageId(), "目前仅支持文本消息。");
            return;
        }
        var owner = userRepo.findByUsername(im.getOwnerUsername());
        if (owner.isEmpty()) {
            log.warn("飞书对话 owner 用户不存在（username={}）", im.getOwnerUsername());
            return;
        }
        Long userId = owner.get().id();
        String reply = invoker.ask(userId, "feishu-" + message.openId(), message.text());
        replyQuietly(message.messageId(), truncate(reply));
    }

    private String truncate(String reply) {
        return reply.length() <= MAX_REPLY_CHARS ? reply
                : reply.substring(0, MAX_REPLY_CHARS) + "…（已截断）";
    }

    private void replyQuietly(String messageId, String text) {
        boolean ok = replyPort.reply(messageId, text);
        if (!ok) {
            log.warn("飞书回复发送失败（messageId={}）", messageId);
        }
    }
}
