package com.portfolio.invest.domain.conversation;

/** 会话消息角色：仅允许 user/assistant（wire 值全小写）。 */
public enum ChatMessageRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String wire;

    ChatMessageRole(String wire) {
        this.wire = wire;
    }

    /** wire/存储值（全小写）。 */
    public String wire() {
        return wire;
    }

    /** 由 wire/存储值解析；未知值抛异常（域内角色白名单，不静默吞未知）。 */
    public static ChatMessageRole fromWire(String wire) {
        for (ChatMessageRole r : values()) {
            if (r.wire.equals(wire)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知消息角色: " + wire);
    }
}
