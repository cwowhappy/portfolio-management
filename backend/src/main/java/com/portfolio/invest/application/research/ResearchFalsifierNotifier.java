package com.portfolio.invest.application.research;

import java.util.List;

/**
 * 证伪命中提醒端口（D21/D15，infrastructure.im 实现）：日终扫描聚合单项目新增命中后调用。
 * 文案最小化——仅项目名 + 条件名，不含策略详情/持仓/数字（D15 隐私裁定）。
 * 尽力而为：失败返回 false，实现内部记日志不抛（扫描主流程不被推送失败打断）。
 */
public interface ResearchFalsifierNotifier {

    /** 推送单项目新增命中提醒；userId 预留按人路由扩展位（v1 飞书单群，照 AlertNotifier 模式）。 */
    boolean notify(Long userId, String projectTitle, List<String> conditionNames);
}
