# ADR-0013 即时通信：飞书混合双通道（群机器人 webhook + 自建应用 oapi）

- 状态：已接受（2026-09-27；2026-10-03 增补「按用户绑定」变更，见下）
- 决策者：项目负责人

## 背景

原 MS-15 以 SMTP 为预警载体（长期待决策）；采集告警（数据断流等）需要独立于 backend
存活的推送通道（backend 故障时采集告警仍可达）；本机部署形态无公网入站强需求；
选型对比三家 IM（飞书/钉钉/企业微信），飞书群自定义机器人 100 条/分钟限流为三家最宽。

## 决策

即时通信选型 **飞书**，采用混合双通道架构：

- **collector 采集告警**：群自定义机器人 webhook——通道独立于 backend 存活（P0 已交付）；
- **backend 原则预警**（P1 已交付）与 **飞书对话**（P2 已交付，2026-09-27 PR #73）：自建应用 + oapi 通道
  （tenant_access_token + im/v1/messages 卡片；P2 对话事件走 ws 长连接收，回复经 oapi 发送）。

## 后果

正面：opt-in 零回归（未配置即静默跳过）；自建应用长连接免公网入站，为 P2 对话铺路；
凭证集中于 env 配置。
负面：两套凭证与格式化逻辑分置 Python（collector）/Java（backend）；飞书平台契约耦合——
HTTP 200 + body 非零码仍为失败的隐性契约、群机器人签名算法。
缓解：collector 侧 `_validate_response` 钩子统一校验响应；官方签名实现以离线单测锁定
契约漂移；秘密全部走 env 不入库不入仓。

## 2026-09~10 变更：按用户绑定落地（推翻本特性「不建绑定表」YAGNI）

feishu-messaging 需求规格原 YAGNI 裁定「不做多用户绑定（open_id↔系统用户绑定表、绑定流程、按用户路由）」。**2026-10-03 M15 智能情报（决策 #18）推翻该条**：个性化推送（盘前简报逐用户附节、重大公告定向触达）必须按用户寻址，YAGNI 前提消失。落地四件：

- **绑定表**：Flyway V3 新增 `intelligence_feishu_binding`（user_id PK + open_id UNIQUE，同用户重绑覆盖）与 `intelligence_binding_code`（一次性 6 位码，TTL 10 分钟，原子核销防双花）；
- **open_id 单发**：`FeishuClient.sendCardByOpenId(openId, …)`（oapi `im/v1/messages` 卡片单发，与群推 `sendCard` 同层）；
- **`ImCommandRouter` 命令前置路由**：飞书 ws 入站消息在进对话桥之前先过命令路由端口（infrastructure 层拦截，`BindingCommandHandler` 匹配 6 位绑定码完成核销并回复话术，未命中照旧透传 LLM）——绑定流程零公网入站、零前端轮询；
- **受众账号门**：推送受众查询补 `status='APPROVED' AND enabled=TRUE`（PENDING/REJECTED/停用账号不入受众，审批门不被推送旁路）；受众口径 = 默认开除非显式关（LEFT JOIN，含无订阅行用户）。

群机器人通道与群推兜底不变：未绑定用户仍走配置群版简报，全部绑定则不发群（防双发）。

## 关联

- 特性文档：features/feishu-messaging/（可行性方案 / 需求规格 / 实施计划）；变更来源 [features/research-intelligence/](../../../features/research-intelligence/)（M15 决策 #18 按用户绑定）
- ADR-0003（行情主源选择，同为「本机部署外联」类决策）
