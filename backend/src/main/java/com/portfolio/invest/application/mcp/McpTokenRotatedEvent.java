package com.portfolio.invest.application.mcp;

/**
 * MCP provider token 轮换事件：管理员设置 token 且密文落库成功后由
 * {@link McpConfigApplicationService} 发布；{@code agent/McpClientPool} 监听后驱逐该
 * provider 全部缓存客户端（close），使下一次 acquire 以新 token 重建——凭证轮换即时生效，
 * 无需重启后端。
 *
 * @param providerId 轮换 token 的 provider 主键
 */
public record McpTokenRotatedEvent(Long providerId) {
}
