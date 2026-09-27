# P2 spike：agentscope-extensions-channel-feishu:2.0.3 真实 jar 解包分析

- **日期**：2026-09-27（真实 jar 下载于阿里云镜像，`unzip -l` + `javap -p -c/-v` 字节码逆向；产物在仓库外，未入 git）
- **背景**：P2（飞书内对话）原倾向直接采用该适配器；本 spike 回答接线/连接模式/配置/3 秒时限四个关键问题
- **结论先行**：**可用但非长连接**——适配器走回调（Webhook）模式，且 3 秒 ACK 不达标、仅纯文本；是否采用需与"oapi-sdk ws 长连接 + 自实现 Channel SPI"权衡（§六、§七）

## 一、包体概况

jar 极小：仅 8 个类，全在 `io.agentscope.extensions.channel.feishu`。**无任何 Spring Boot 自动装配**（无 META-INF/spring、无 spring.factories、无 services）——纯手动接线库。POM 零依赖 `com.larksuite.oapi:oapi-sdk`，HTTP 全部用 spring-webflux `WebClient` 自建（**webflux 在其 POM 里是 provided，本仓库 webmvc 栈需自行补依赖**）。

## 二、接线方式（字节码实证）

```java
// 1) 建渠道（Map key：appId/appSecret/encryptKey/verificationToken/callbackPath/apiBase）
FeishuChannel ch = FeishuChannel.fromProperties("channel-id", ChannelConfig.of(...), Map.of("appId","cli_xxx","appSecret","yyy"));
// 2) 挂载二选一：
GatewayBootstrap.builder().agent("user:42", agent).channel(ch).build().start();  // 推荐
// 或 agent.channel(ch) 之后【必须手动 ch.start()】——agent.channel() 只 init 不 start，漏调则回调 404
```

- HTTP 入口：`FeishuCallbackController`（`@RestController`，`POST /api/channels/feishu/{channelId}/callback`），须自行为 Spring Bean；与渠道靠 JVM 单例 `FeishuChannelRegistry` 桥接
- 多 agent 路由：`ChannelRouter` 分层匹配（peer > guild > team > account > channel 兜底），`ChannelBinding.forPeer(...)` 配置
- **形态冲突提示**：渠道是进程级单 gateway 绑定；本仓库 HarnessAgentFactory 按 userId 建 agent——单用户模型下可绕开（owner 一个 agent），多用户需 bindings 映射

## 三、连接模式：回调（Webhook），非长连接

- 入站唯一入口 = Spring MVC 回调 Controller；全 jar 无 ws/websocket/com.lark 字样、无重连逻辑
- 回调协议三件套齐全且实现正确：URL challenge（url_verification）、签名校验、AES-CBC 解密（key=sha256(encryptKey)、iv=密文前 16 字节、pkcs7 手工 unpad）
- **含义**：公网入站 + nginx 路由 + Spring Security 白名单是硬前提（阿里云部署可满足，本地开发收不到事件）

## 四、配置全表（FeishuChannelProperties，from(channelId, Map)）

| 字段 | Map key | 必填 | 默认 |
|---|---|---|---|
| appId / appSecret | 同名 | ✅ | — |
| encryptKey | encryptKey | 否 | null（非空即启用解密） |
| verificationToken | verificationToken | 否 | null（未配则跳过 challenge 校验） |
| callbackPath | callbackPath | 否 | `/api/channels/feishu/{channelId}/callback` |
| apiBase | apiBase | 否 | `https://open.feishu.cn`（Lark 国际版改 open.larksuite.com） |

无事件白名单/线程池/重试配置；`IdempotencyStore`（TTL 5 分钟、1 万条）与 `BotLoopGuard`（20 事件/60s、超限冷却 60s）硬编码 new，不可注入。

## 五、3 秒时限与幂等（关键缺陷区）

- **ACK 等 agent 跑完才回**：`channel.dispatch(...).then(ok("{}"))`——无 timeout 包裹、无线程池转交；LLM 响应必超 3 秒 → 飞书重推
- 重推兜底：`idempotency.firstSeen(channelId + "|" + event_id)` 去重（重推不重复触发 agent）✅
- **首处理失败则消息永久丢失**：firstSeen 在 dispatch 之前标记，失败后重推被当重复吞掉 ❌
- 去重/限速均为单机内存（多实例失效）

## 六、出站能力与 Channel SPI（手工桥接的对接面）

- 出站 `FeishuOutboundClient`：`im/v1/messages` **仅 msg_type=text**（无卡片/富文本/回复 API）；token 管理完善（80% TTL 提前刷新、失效码 invalidate，但失效当次不重发仅 WARN）
- 入站 `message_type != "text"` 静默丢弃；群消息不剥离 @mention 占位符（"@_user_1" 会进 prompt）
- **Channel SPI 在 agentscope-harness（io.agentscope.harness.agent.gateway.channel）**：`Channel`（init/start/stop/discount/deliver）、`ChannelFactory`、`InboundMessage/Peer/PeerKind`、`OutboundAddress`、`ChannelConfig/ChannelBinding`、宿主 `ChannelManager`/`GatewayBootstrap`——**自实现一个基于 oapi-sdk ws 长连接的 Channel 即可保留 AgentScope 会话机制、替换传输层**

## 七、P2 决策输入（两路径权衡，P2 计划时定）

| | 路径 A：直接用 channel-feishu | 路径 B：自实现 Channel SPI + oapi-sdk ws 长连接 |
|---|---|---|
| 公网入站 | 需要（回调+nginx+安全白名单） | **不需要**（长连接仅出站） |
| 3s ACK | 不达标（重推噪声+首败丢消息） | 可控（ws 事件即收即 ack，异步处理） |
| 消息能力 | 仅 text，@占位符不剥离 | 全类型（复用 P1 FeishuClient 可发卡片） |
| 工作量 | 小（接线+补 webflux+打安全补丁） | 中（实现 SPI 薄适配层，接口小） |
| 与 AgentScope 会话机制 | 原生 | 原生（Gateway/ChannelManager 照用） |

单用户模型消解了"单 gateway"冲突；倾向 **路径 B**（契合免公网入站约束 + 修复 ACK/文本局限 + 复用 P1 出站），最终随 P2 需求规格定。

## 附：未能从字节码确认

飞书平台侧重推次数/超时判定（jar 外行为）；GatewayBootstrap.build() 装配细节（由 Builder 字段 + start() 调用链推断，未见反证）。
