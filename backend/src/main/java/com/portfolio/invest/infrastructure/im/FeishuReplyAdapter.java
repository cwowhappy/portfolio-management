package com.portfolio.invest.infrastructure.im;

import com.portfolio.invest.application.im.ImReplyPort;
import org.springframework.stereotype.Component;

@Component
public class FeishuReplyAdapter implements ImReplyPort {

    private final FeishuClient client;

    public FeishuReplyAdapter(FeishuClient client) {
        this.client = client;
    }

    @Override
    public boolean reply(String messageId, String text) {
        return client.sendReply(messageId, text);
    }
}
