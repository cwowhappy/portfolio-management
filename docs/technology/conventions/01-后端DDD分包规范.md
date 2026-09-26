# 后端分包规范（backend package conventions）

- 状态：已确认（2026-08-18，2026-08-21 更新为 DDD 分层，2026-09-12 对齐现状：分层域扩至 10 个，2026-09-18 登记 industry 域：扩至 11 个，2026-09-22 登记 wiki 域：扩至 12 个；同日补登 MS-07 漏登记的 analytics 域：扩至 13 个，控制器清单对齐 16 个；2026-09-26 MS-14 后对齐：screening 扩 ETF 筛选、industry 扩行业关注——**均无新包**，控制器清单对齐 17 个；同日补登 MS-10 漏登记的 `IndustryCurationController`（issue #39 文档卫生）：控制器清单对齐 **18 个**，与 `web` 包实测一致）
- 适用范围：`backend` 单模块 Spring Boot 应用，根包 `com.portfolio.invest`
- 强制方式：ArchUnit 架构测试（`PackageConventionsTest`），违反即构建失败
- 相关文档：[ADR-0001 Agent 框架选型](../decisions/0001-agent-framework.md) · [ADR-0002 交互协议](../decisions/0002-interaction-protocol.md) · [ADR-0003 行情数据源](../decisions/0003-market-data-source.md) · [ADR-0009 后端分层 DDD](../decisions/0009-backend-ddd-layering.md)

## 一、为什么这样分包

同类 Agent/LLM 开源项目按**能力域**分包（domain 一词取其"业务能力"义），而非按技术层大平层
（controller/service/dao）。本项目在此基础上，为**信息管理/信息处理**类域引入 DDD 洋葱分层
（domain/application/infrastructure），两者并存：

- **独立能力域**（`agent`）：以 Agent 装配为产出的能力域，保持单层内聚、自成一体；
- **DDD 分层域**（`user`、`conversation`、`market`、`valuation`、`portfolio`、`journal`、`allocation`、`analytics`、`screening`、`skill`、`mcp`、`industry`、`wiki`，共 13 个）：涉及持久化/多源编排的信息处理域，
  拆为纯领域（`domain.*`）、用例编排（`application.*`）、基础设施实现（`infrastructure.*`）三层，
  领域规则可脱离 Spring/JPA 单独测试。

区分判据：能力域内部"零件是否需要持久化/外部端口"？需要 → 走 DDD 分层；否则维持单层能力域。

## 二、总体结构

```
com.portfolio.invest                    # 根包：仅启动类
├── InvestAgentApplication              # @SpringBootApplication 启动类（组件扫描锚点）
├── web/                                # 接入层：HTTP 接口与异常映射
├── application/                        # 应用层：用例编排、事务、对外 DTO
├── domain/                             # 领域层：纯业务（实体/值对象/仓库接口），零 Spring/JPA
├── infrastructure/                     # 基础设施层：JPA 实现、Security、外部客户端、缓存/限流
├── agent/                              # 独立能力域：工具、提示词、装配
└── config/                             # 全局配置：配置属性
```

## 三、各分包的作用、定位与理由

### 根包 com.portfolio.invest
- **作用**：只放 `InvestAgentApplication` 启动类，它是组件扫描（`@SpringBootApplication`）与配置扫描（`@ConfigurationPropertiesScan`）的锚点。
- **理由**：与 Spring Boot 官方建议一致——主类置于根包，使 `@ComponentScan` 无需额外配置即可覆盖全部子包；根包不放业务代码，避免业务类挂在"无名分"的位置、规避扫描边界歧义。

### web（接入层）
- **作用**：HTTP 边界。`@RestController` 共 18 个（Auth/UserAdmin/Conversation/Market/Health/Valuation/Portfolio/Journal/Allocation/Analytics/Screening/Watchlist/McpConfig/SkillConfig/Industry/IndustryWatch/Wiki/IndustryCuration；2026-09-26 MS-14 新增 IndustryWatch——P1 CSV 导入两端点并入 Portfolio、P3 ETF 筛选两端点并入 Screening；同日补登 MS-10 的 IndustryCuration——`/api/industry-curation` 策展写接口 11 端点，此前漏登记，见 issue #39）。其余同前：`@RestControllerAdvice`（`GlobalExceptionHandler` 异常→HTTP 状态映射）、`@Component`（`InvestAguiRuntimeContextResolver`：AG-UI 运行时上下文解析）、Web 专属响应体（`ApiError`）与出入参 DTO（`web.dto`）。
- **定位**：系统最外层，**只能被调用、不能调用别人的业务逻辑实现**——只做路由、参数校验、把用例服务/领域异常翻译成 HTTP 语义；不承载业务规则、不直接访问外部数据源。
- **理由**：接入层与业务解耦后，协议演进（REST→gRPC/消息）不影响业务；顶层不被依赖是分层架构的根规则。

### application（应用层）
- **作用**：用例编排与事务边界，15 个子包——`auth`（`AuthApplicationService`：注册/登录/登出/me）、`useradmin`（`UserAdminApplicationService`：审核/停用/重置密码）、`conversation`（`ConversationApplicationService`：会话/消息）、`market`（`MarketDataService` 接口 + `OrchestratingMarketDataService` 等实现：行情主源/兜底编排）、`valuation`（`ValuationApplicationService`）、`portfolio`（`PortfolioApplicationService`/`PortfolioCreationService`）、`journal`（`JournalApplicationService`）、`allocation`（`AllocationApplicationService`）、`analytics`（`AnalyticsApplicationService`：总览/净值序列/年化收益/交易统计读端点编排，2026-09-22 补登）、`screening`（`ScreeningApplicationService` 多条件筛选——个股 + 2026-09-26 MS-14 扩 ETF 四维筛选与导出；`WatchlistApplicationService` 自选观察列表（存在性校验为股票快照 ∪ etf_basic 目录并集，自选代码可直接存 ETF），2026-09-22 补登）、`industry`（`IndustryApplicationService`：行业榜单/行业成员两读端点；`IndustryWatchApplicationService`：行业关注 list/watch/unwatch，2026-09-26 MS-14 登记）、`mcp`（`McpConfigApplicationService`）、`skill`（`SkillApplicationService`）、`wiki`（`WikiApplicationService`：条目 CRUD + 概念首次拉取幂等 seeding、`PrincipleRuleApplicationService`：原则规则增改删）、`cache`（`ApplicationCache` 端口：应用层缓存抽象，由 infrastructure.cache 实现）。持有对外 DTO。
- **定位**：依赖 `domain`（仓库接口 + 领域规则），是 `web` 与 `agent` 调用的门面；不包含具体数据访问实现（仓库由 infrastructure 实现）。
- **理由**：把"干什么"（用例）与"怎么做"（基础设施）分离；多用例可编排同一领域，事务边界收敛在此层。

### domain（领域层）
- **作用**：纯业务，13 个子包——`user`（User/UserRole/UserStatus/UserRepository 接口）、`conversation`（Conversation/ChatMessage/ConversationRepository 接口）、`market`（Quote/KlineBar 等值对象、StockRef、MarketDataParser、MarketDataException、MarketDataSource 端口）、`valuation`（ValuationSnapshot/Percentile 等值对象、ValuationRepository 端口）、`portfolio`（Portfolio/Position/Trade/HoldingGroup 聚合、PortfolioRepository 端口）、`journal`（JournalEntry、JournalEntryRepository 端口）、`allocation`（AllocationPlan/AllocationTemplate、AllocationPlanRepository 端口）、`analytics`（收益分析计算：NavSeries/DailyPoint/ExternalFlow 等值对象、NavReconstructor 净值重建与 Irr/Twr/AnnualReturn/TradeStats 纯函数计算器、AnalyticsException/AnalyticsErrorCode、IndexClosePort/StockClosePort 端口——由 infrastructure.persistence 的 IndexCloseAdapter/StockCloseAdapter 实现；无聚合根无自有表，读 portfolio 交易事件与共享行情表，MS-07 交付、2026-09-22 补登）、`screening`（ScreeningCriteria/StockScreeningResult、ScreeningRepository 端口——2026-09-26 MS-14 扩 FundScreeningCriteria/FundScreeningResult ETF 四维筛选，仓库端口新增 findFunds/existsFund，读写 etf_basic）、`skill`（SkillUserConfig、SkillConfigRepository 端口）、`mcp`（McpEndpoint/McpProvider/McpUserConfig、McpConfigRepository 端口）、`industry`（行业研究中心读侧：IndustryValuationRow/IndustryValuationPoint/IndustryStock/IndustryProsperitySnapshot 读模型值对象、WindowedPercentile/Prosperity 纯函数、IndustryRepository 端口；2026-09-26 MS-14 扩 IndustryWatchItem/IndustryWatchRepository 行业关注（记录实体+仓储，该域首个写模型，表 industry_watch 独立）；原读模型无聚合根，表 industry_valuation 与估值域共享——跨域读先例同 shenwan_industry_mapping）、`wiki`（投资知识库：WikiEntry 条目 + PrincipleRule 原则规则聚合、PrincipleMetric/WikiEntryType 枚举、WikiEntryRepository/PrincipleRuleRepository/WikiSeedStateRepository 端口、WikiException/WikiErrorCode；MS-11 交付，详见 features/wiki-knowledge-base/）。
- **定位**：**零 Spring/JPA 依赖**的纯 POJO；定义仓库/端口接口但由 infrastructure 实现；承载状态机、密码策略、归属校验等业务规则。
- **理由**：领域规则可脱离 DB 单测（受益于纯 domain + infra 映射）；依赖方向最底层，被所有上层安全引用。

### infrastructure（基础设施层）
- **作用**：`persistence`（JPA 实体 + 仓库实现 + 映射器 + Flyway）、`security`（SecurityConfig、UserDetailsService、remember-me）、`seed`（AdminSeedRunner）、`cache`（TtlCache：`ApplicationCache` 端口实现，有界 LRU + TTL）、`market`（EastmoneyClient/SinaClient 实现 `MarketDataSource`、RateLimiter、RestClientFactory、缓存/限流装饰器）。
- **定位**：最外层技术实现，实现 `domain` 定义的接口；**缓存/限流等横切用装饰器包裹**应用层服务，保证应用层不被技术细节污染。
- **理由**：技术栈可替换而不影响领域/应用层；外部客户端、持久化、安全都收敛于此。

### agent（独立能力域）
- **作用**：投研 Agent 的全部资产——系统提示词（`InvestSystemPrompt`）、数据工具（`InvestTools` 的 `@Tool` 方法）、图表契约（`chart/ChartSpec`+`ChartSpecs`：域数据→图表载荷）、MCP 客户端池（`McpClientPool`）、Agent 装配（`AgentConfig`/`HarnessAgentFactory`）。
- **定位**：独立能力域，**不并入 DDD 分层**；按真实 import 消费 application 的 `market`（`MarketDataService`）/`valuation`（`ValuationApplicationService`）/`skill`（`SkillApplicationService`）与 domain 的 `market`/`valuation` 值对象及 `mcp` 端口（`McpConfigRepository` 等）。
- **理由**：Agent 装配是域自身的产物，域内聚后全局 config 保持"纯配置"职责单一；保持 ADR-0001 的能力域独立原则。

### config（全局配置）
- **作用**：仅配置属性——`InvestProperties`（`@ConfigurationProperties(prefix="invest")`）及安全/数据源相关配置属性。
- **定位**：最底层基础设施，**不得依赖任何业务包**。
- **理由**：配置是各域共同的底座，只应"被依赖"；反向依赖会把它拖入业务环。

## 四、依赖方向规则（分层白名单）

方向总原则：**只能向下依赖，禁止反向，禁止成环**。

```
web ──→ application ──→ domain
 │   ──→ agent ──→ application/domain ──→ config
infrastructure ──→ {domain, application, config}
```

| 包 | 允许依赖的项目内包 | 禁止 |
|---|---|---|
| `web` | `application`、`agent`、`domain`、`config`、`infrastructure.security`（仅认证主体 `AuthenticatedUser`） | 被任何包依赖；直接访问外部数据源 |
| `application` | `domain`、`config` | 依赖 `web`、`agent` |
| `domain` | 无（只能依赖自身与 JDK/外部库） | 依赖项目内任何包；出现 Spring/JPA 注解 |
| `infrastructure` | `domain`、`application`、`config` | 依赖 `web` |
| `agent` | `application`、`domain`、`config` | 依赖 `web` |
| `config` | 无 | 依赖任何业务包 |
| 根包 | 无 | 承载业务类 |

> 外部库（Spring、AgentScope、Jackson、SLF4J 等）不在限制范围内；以上白名单只约束 `com.portfolio.invest` 内部的互相依赖。
> 2026-08-21 调整：`market` 顶层包消失，其职责并入 `domain.market` / `application.market` / `infrastructure.market`。

## 五、落位规则（类应该放哪）

| 规则 | 说明 |
|---|---|
| `@RestController` / `@RestControllerAdvice` 只能在 `web` | HTTP 边界收敛在接入层 |
| `@ConfigurationProperties` 只能在 `config` | 配置属性统一在全局配置包 |
| `@Controller` 类以 `Controller` 结尾 | 与 Spring 命名惯例一致 |
| `domain` 包内不得出现 Spring/JPA 注解（`@Entity`/`@Column`/`@Service` 等） | 领域层纯业务 |
| 仓库/端口接口定义在 `domain`，实现（JPA 等）在 `infrastructure` | 依赖倒置 |
| `application` 类以 `ApplicationService` 结尾 | 用例编排命名一致 |
| 根包只允许 `@SpringBootApplication` 启动类 | 其余业务类必须进对应包 |

## 六、执行与演进

- **强制执行**：ArchUnit 测试 `backend/src/test/java/com/portfolio/invest/architecture/PackageConventionsTest.java` 将以上规则全部断言，随 `make test` / `./gradlew test` 运行。
- **新增能力域/域**时：
  1. 判定类型：信息管理/处理且需持久化或外部端口 → 走 DDD 分层（`domain.*` + `application.*` + `infrastructure.*`）；否则维持单层能力域；
  2. 在 ArchUnit 测试中登记新包的依赖白名单；
  3. 依赖方向保持"上层→application→domain"，域之间如需互调只允许自上而下，禁止成环。
