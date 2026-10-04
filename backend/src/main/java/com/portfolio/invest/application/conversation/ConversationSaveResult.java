package com.portfolio.invest.application.conversation;

import java.time.Instant;

/** PUT 消息写响应（B6）：返回写入后的 updatedAt（ISO 字符串序列化），供写端续跑乐观校验循环。 */
public record ConversationSaveResult(Instant updatedAt) {}
