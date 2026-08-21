package com.portfolio.invest.application.conversation;

import com.portfolio.invest.domain.conversation.Conversation;

public record ConversationView(String id, String title, long updatedAt) {
    public static ConversationView from(Conversation c) {
        return new ConversationView(c.id(), c.title(), c.updatedAt().toEpochMilli());
    }
}
