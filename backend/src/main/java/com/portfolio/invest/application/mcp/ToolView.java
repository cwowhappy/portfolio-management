package com.portfolio.invest.application.mcp;

/** 工具视图：live 发现的工具描述 + 用户是否已禁用。 */
public record ToolView(String name, String description, boolean enabled) {}
