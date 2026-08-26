# 02 · Agent 实现

> 后端 Agent 装配与工具实现。对应代码：`backend/src/main/java/com/portfolio/invest/agent/`。
> 产品能力视角（Agent 能回答什么、回答规范）见 [docs/function/04-Agent-能力.md](../function/04-Agent-能力.md)。

## 1. Agent 装配（AgentConfig）

`AgentConfig` 用两个条件 Bean 装配 Model 与 Agent：

```java
@Bean
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${DEEPSEEK_API_KEY:}')")
public Model investModel(InvestProperties props) { ... }

@Bean(name = "invest")
@ConditionalOnExpression("...")   // 同上条件
public Agent investAgent(Model investModel, InvestTools investTools) { ... }
```

要点：

- **条件装配**：无 `DEEPSEEK_API_KEY` 时两个 Bean 均不创建，服务仍可启动（仅行情 API 可用）。
- **Bean 名与 agent id 一致**：`invest`，与 `agentscope.agui.default-agent-id` 对应。
- **Model 与 Agent 必须同配置类**：跨配置类的 `ConditionalOnBean` 求值顺序不可靠。
- **分包**：Agent 装配归 `agent` 包（Agent 能力域），`config` 包只放配置属性（ArchUnit 强制）。

## 2. 模型配置

```java
ModelRegistry.resolve(
    props.getLlm().getProvider() + ":" + props.getLlm().getModel(),   // deepseek:<model>
    ModelCreationContext.builder()
        .baseUrl(props.getLlm().getBaseUrl())
        .stream(true)
        .component(GenerateOptions.class, GenerateOptions.builder()
            .parallelToolCalls(false)   // 行情工具串行更稳
            .temperature(0.3)
            .build())
        .build());
```

- provider 默认 `deepseek`、model 默认 `deepseek-v4-flash`（可配 `deepseek-v4-pro`）。
- `DEEPSEEK_API_KEY` 由 `ModelRegistry` 自动读取，DeepSeek 使用专用 formatter 处理思考块。

## 3. ReActAgent

```java
return ReActAgent.builder()
    .name("invest")
    .sysPrompt(InvestSystemPrompt.TEXT)
    .model(investModel)
    .toolkit(toolkit)          // Toolkit 注册 InvestTools 的 6 个 @Tool
    .maxIters(10)
    .build();
```

## 4. 系统提示词（InvestSystemPrompt）

中文投研助手人设，核心约束（全文见 `InvestSystemPrompt.java`）：

- **能力**：查询个股实时行情、K线、财务、新闻与大盘指数，基于工具数据分析。
- **工具规范**：名称→先 `search_stock`；走势→`get_kline`；估值/财务→`get_financials`；大盘→`get_market_overview`；工具返回 `error` 时如实说明、**禁止编造**。
- **回答规范**：先结论后数据；要点/表格；多维度（趋势/成交量/估值/消息面）+ 风险点；区分事实与观点。
- **免责声明**：数据来自公开接口，分析仅供参考，不构成投资建议。

## 5. 工具实现（InvestTools）

6 个 `@Tool` 方法，均 `readOnly=true`、`concurrencySafe=true`，返回 JSON 文本：

| 工具 | 方法签名 | 返回要点 |
|---|---|---|
| `search_stock` | `searchStock(query)` | `[{code,name,market,marketName}]` |
| `get_quote` | `getQuote(code)` | 价格/涨跌/量额/高低开/PE/PB/时间 |
| `get_kline` | `getKline(code, period, limit)` | `[{date,open,close,high,low,volume,amount,amplitudePct}]` |
| `get_financials` | `getFinancials(code)` | `{code,name,pe,pb,periods:[报告期/EPS/BPS/营收/净利/ROE/毛利率]}` |
| `get_news` | `getNews(code, limit)` | `[{title,summary,source,date,url}]` |
| `get_market_overview` | `getMarketOverview()` | 三大指数点位与涨跌幅 |

**统一错误处理**：`run(...)` 捕获 `MarketDataException` 与普通异常，返回结构化错误 JSON（`{"error":..., "hint":...}`）而非抛出，供模型识别并换问法；用 `ObjectMapper` 序列化避免手工拼 JSON。

**参数默认与上限**（工具层）：`get_kline` period 默认 `day`、limit 默认 60（描述上限 120）；`get_news` limit 默认 10（上限 20）。

## 6. AG-UI 端点配置（application.yml）

```yaml
agentscope:
  agui:
    path-prefix: /agui
    default-agent-id: invest
    server-side-memory: false      # 前端持有工作内存，历史服务端持久化（ADR-0008）
    enable-reasoning: true         # 输出思考过程
    emit-tool-call-args: true      # 输出工具参数
    emit-token-usage: true         # 输出 token 用量（CUSTOM 事件）
    cors-enabled: false            # 经 Next.js 同源反代
    run-timeout: 5m
```

端点由 AgentScope `agui-spring-boot-starter` 自动注册（`POST /agui/run`），`AgentEvent → AG-UI 事件` 官方映射，后端无协议代码。
