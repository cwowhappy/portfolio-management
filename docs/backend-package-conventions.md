# 后端分包规范（backend package conventions）

- 状态：已确认（2026-08-18）
- 适用范围：`backend` 单模块 Spring Boot 应用，根包 `com.portfolio.invest`
- 强制方式：ArchUnit 架构测试（`PackageConventionsTest`），违反即构建失败
- 相关文档：[ADR-0001 Agent 框架选型](0001-agent-framework.md) · [ADR-0002 交互协议](0002-interaction-protocol.md) · [ADR-0003 行情数据源](0003-market-data-source.md)

## 一、为什么这样分包（参考同类开源项目）

同类 Agent/LLM 开源项目的包组织共同点是**按“能力域”分包，而不是按技术层（controller/service/dao）分包**：

| 项目 | 顶层包组织 | 特征 |
|---|---|---|
| Spring AI（spring-projects/spring-ai） | `org.springframework.ai` 下：`chat`（client/memory/advisor/prompt）、`model`、`tool`、`embedding`、`vectorstore`、`document`、`rag` | 一个能力域一个包，域内再按职责细分 |
| LangChain4j | `dev.langchain4j` 下：`model`、`agent`（tool）、`memory`、`store`、`rag`、`service` | 同上，域包 = 单一职责模块 |
| AgentScope Java（io.agentscope） | `io.agentscope.core` 下：`agent`、`model`、`tool`、`memory`、`middleware`、`message` | 同上 |

按技术层分包（controller/service/repository 大平层）在业务域变多后会让每个包膨胀、跨域改动要横切多个包；按能力域分包则让每个域自成一体、可独立演进和拆分。

本项目一期是 Spring Boot 应用而非框架库，因此采用**“能力域分包 + 依赖方向分层”**：业务能力域（`market`、`agent`）各自内聚，全局基础设施（`web` 接入层、`config` 配置）独立成包，并用依赖方向保证域之间不互相缠绕。

## 二、总体结构

```
com.portfolio.invest                    # 根包：仅启动类
├── InvestAgentApplication              # @SpringBootApplication 启动类（组件扫描锚点）
├── web/                                # 接入层：HTTP 接口与异常映射
├── agent/                              # Agent 能力域：工具、提示词、装配
├── market/                             # 行情数据能力域
│   └── dto/                            #   域数据对象（对外传输载体）
└── config/                             # 全局配置：配置属性
```

## 三、各分包的作用、定位与理由

### 根包 com.portfolio.invest
- **作用**：只放 `InvestAgentApplication` 启动类，它是组件扫描（`@SpringBootApplication`）与配置扫描（`@ConfigurationPropertiesScan`）的锚点。
- **理由**：与 Spring Boot 官方建议一致——主类置于根包，使 `@ComponentScan` 无需额外配置即可覆盖全部子包；根包不放业务代码，避免业务类挂在“无名分”的位置、规避扫描边界歧义。

### web（接入层）
- **作用**：HTTP 边界。`@RestController`（Market/Health 接口）、`@RestControllerAdvice`（`GlobalExceptionHandler` 异常→HTTP 状态映射）、Web 专属响应体（`ApiError`）。
- **定位**：系统最外层，**只能被调用、不能调用别人的业务逻辑实现**——只做路由、参数校验、把领域服务/领域异常翻译成 HTTP 语义；不承载业务规则、不直接访问外部数据源。
- **理由**：接入层与业务解耦后，协议演进（REST→gRPC/消息）不影响业务域；顶层不被依赖是分层架构的根规则，可防止领域代码反向耦合到 HTTP 框架。

### agent（Agent 能力域）
- **作用**：投研 Agent 的全部资产——系统提示词（`InvestSystemPrompt`）、6 个数据工具（`InvestTools` 的 `@Tool` 方法）、Agent 装配（`AgentConfig`：Model + ReActAgent + Toolkit 的 bean 定义）。
- **定位**：业务域之一，可消费 `market` 域提供的数据能力（工具内部调用 `MarketDataService`），对外不暴露实现。
- **理由**：与 AgentScope 把 `tool` 作为独立能力域一致；装配类放域内（而非全局 config），因为 Model/Agent bean 就是 Agent 域自身的产物，域内聚后全局 config 才能保持“纯配置”的职责单一，且避免 config→agent 的反向依赖形成包环。

### market（行情数据能力域）
- **作用**：行情数据的完整闭环——编排服务 `MarketDataService`（缓存 + 限流 + 东财主源/新浪兜底）、数据源客户端 `EastmoneyClient`/`SinaClient`（仅 HTTP）、纯函数解析器 `MarketDataParser`、基础设施件 `TtlCache`/`RateLimiter`/`RestClientFactory`、代码规范化 `StockRef`、领域异常 `MarketDataException`。
- **定位**：业务域，向 `web`、`agent` 提供行情查询能力；对外只通过 `MarketDataService`（服务门面）+ `dto`（数据载体）+ `MarketDataException`（领域错误）。
- **理由**：把一个数据域的全套零件（客户端、解析、缓存、限流）收在域内，外部不关心数据源细节；“东财主源/新浪兜底”更换策略只动本包。

### market.dto（域数据对象）
- **作用**：对外传输载体（`Quote`、`KlineBar`、`Financials`、`MarketOverview`、`NewsItem`、`StockHit`、`IndexQuote`、`FinancialIndicator`），纯 POJO/record，无业务逻辑。
- **定位**：域的“出口合同”，web 与 agent 引用它而不引用域内实现类。
- **理由**：DTO 与实现类同域但分包子包，外部 import 一眼可知只依赖数据契约；dto 零内部依赖保证它是可被任何上层安全引用的稳定层。

### config（全局配置）
- **作用**：仅配置属性——`InvestProperties`（`@ConfigurationProperties(prefix="invest")`，承载 `invest.llm.*` / `invest.market.*`）。
- **定位**：最底层基础设施，**不得依赖任何业务包**。
- **理由**：配置是各域共同的底座（market 客户端要超时、agent 要模型参数），只应“被依赖”；反向依赖会把它拖入业务环，破坏分层。Bean 装配（`@Configuration`）不属于本包——谁家的 bean 谁自己装配（当前仅 agent 有装配需求，故在 agent 包内）。

## 四、依赖方向规则（分层白名单）

方向总原则：**只能向下依赖，禁止反向，禁止成环**。

```
web ──→ agent ──→ market ──→ config
 └──────────→┘        └──→┘
```

| 包 | 允许依赖的项目内包 | 禁止 |
|---|---|---|
| `web` | `agent`、`market`（含 `market.dto`）、`config` | 被任何包依赖；直接访问外部数据源 |
| `agent` | `market`（含 `market.dto`）、`config` | 依赖 `web` |
| `market` | `config`（自身域内自由） | 依赖 `web`、`agent` |
| `market.dto` | 无（只能依赖自身与 JDK/外部库） | 依赖项目内任何包 |
| `config` | 无 | 依赖任何业务包 |
| 根包 | 无 | 承载业务类 |

> 外部库（Spring、AgentScope、Jackson、SLF4J 等）不在限制范围内；以上白名单只约束 `com.portfolio.invest` 内部的互相依赖。

## 五、落位规则（类应该放哪）

| 规则 | 说明 |
|---|---|
| `@RestController` / `@RestControllerAdvice` 只能在 `web` | HTTP 边界收敛在接入层 |
| `@ConfigurationProperties` 只能在 `config` | 配置属性统一在全局配置包 |
| `@Controller` 类以 `Controller` 结尾 | 与 Spring 命名惯例一致 |
| 根包只允许 `@SpringBootApplication` 启动类 | 其余业务类必须进对应域包 |

## 六、执行与演进

- **强制执行**：ArchUnit 测试 `backend/src/test/java/com/portfolio/invest/architecture/PackageConventionsTest.java` 将以上规则全部断言，随 `make test` / `./gradlew test` 运行。
- **新增能力域**（如二期引入 `portfolio` 组合、`user` 账户）时：
  1. 新建根包下的同名域包，域内按需要拆 `dto` 等子包；
  2. 在 ArchUnit 测试中登记新包的依赖白名单；
  3. 依赖方向保持“域→config”，域之间如需互调，只允许新域依赖已有底层域（方向自上而下），禁止形成环。
