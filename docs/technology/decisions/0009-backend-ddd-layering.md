# ADR-0009 后端分层演进：DDD 洋葱分层 + 独立能力域

- 状态：已接受（2026-08-21）
- 决策者：项目负责人
- 相关：[后端分包规范](../conventions/01-后端DDD分包规范.md)

## 背景

一期按"能力域分包 + 依赖方向分层"（web / agent / market / config）构建，效果良好。
引入用户管理与会话持久化后，出现**信息管理类**域（user、conversation）：实体、仓库、
服务、安全混合在单一域包内，且需要 JPA 持久化。继续沿用单层能力域会让这些域的实体
与数据访问、用例编排耦合，领域规则难以脱离 DB 单测。

## 决策

调整为 **DDD 洋葱分层 + 独立能力域**并存：

1. **新增三个顶层层**：
   - `domain`：纯业务——实体/值对象/仓库接口/领域规则，**零 Spring/JPA 依赖**；
   - `application`：用例编排、事务、对外 DTO；
   - `infrastructure`：JPA 实体与仓库实现（含映射器）、Spring Security、外部数据源客户端、
     缓存/限流等基础设施件。
2. **信息管理域走 DDD**：`user`、`conversation` 分别拆入 `domain.user` / `domain.conversation`、
   `application.*`、`infrastructure.persistence`。
3. **`market` 迁入 DDD**：值对象（原 `market.dto`）→ `domain.market`；主源/兜底编排
   `MarketDataService` → `application.market`；`EastmoneyClient`/`SinaClient` 实现
   `domain.market.MarketDataSource` 端口 → `infrastructure.market`；**缓存 + 限流用装饰器**
   包裹 `MarketDataService`（infrastructure 最外层，保证依赖方向干净）。
4. **`agent` 保持独立能力域**：不并入 DDD，仅其依赖由 `market` 上移为 `application.market`
   （`agent → {application, domain, config}`）。
5. **JPA 映射**：`domain` 实体为纯 POJO，`infrastructure.persistence` 用独立 JPA 实体 +
   映射器实现仓库接口。
6. **ArchUnit 规则重写**：见依赖方向白名单；`PackageConventionsTest` 全量更新。

### 依赖方向白名单

```
web → {application, agent, domain, config, infrastructure.security}     application → {domain, config}
domain →（不依赖任何项目内包）                    infrastructure → {domain, application, config}
agent → {application, domain, config}           config →（无）
```

## 后果

正面：领域规则可脱离 DB 单测；层间职责清晰、可独立演进；market 迁入后行为不变（测试兜底）。
代价：结构性重构范围大——`market.dto.*` import 全部迁移至 `domain.market.*`，market 相关
测试与 ArchUnit 需同步调整；代码文件增多（JPA 映射层）。此重构为纯结构移动，不改变运行时行为。
