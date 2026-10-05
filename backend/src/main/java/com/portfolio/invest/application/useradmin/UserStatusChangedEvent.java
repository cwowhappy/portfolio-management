package com.portfolio.invest.application.useradmin;

/**
 * 用户状态变更事件：管理员审核（通过/拒绝）或启用/停用改变了 canLogin 判定输入（status/enabled）后由
 * {@link UserAdminApplicationService} 发布；{@code infrastructure/security/ActiveUserStatusCache}
 * 监听后逐出该 username 的缓存判定，使下一次请求以新状态重查——变更即时生效，不受短 TTL 拖延。
 *
 * @param username 状态发生变更的用户名
 */
public record UserStatusChangedEvent(String username) {
}
