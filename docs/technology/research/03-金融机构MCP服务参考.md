# 金融机构与券商 MCP 服务参考

> **调研核实日期：2026-09-06**。梳理各大金融机构、券商、数据商提供的 MCP（Model Context Protocol）服务及使用方法。
> 核实标注：✅ = 实际打开页面/文档核实；📋 = 目录转录未逐一核实。
> 与本仓库的关系：本系统数据链路是「后端直连东财接口 + collector 用 akshare/tushare」（见 [architecture/05](../architecture/05-数据类型与来源.md)、[research/01](01-财经新闻与股市数据来源参考.md)、[research/02](02-akshare与tushare接口详析.md)），MCP 是「把数据能力暴露给任意 Agent 客户端」的另一条路径，本文档为后续可能接入/自建 MCP 提供参考。

## 速查结论

- **国内官方数据 MCP 已成型**（2026 年集中上线）：同花顺 iFinD、东方财富妙想、Wind AIFin、Tushare 官方，均为**远程 HTTP + API Key**、纯数据不含交易。
- **能实盘交易的券商 MCP**：长桥 LongPort（港美股，官方开源）、老虎 tigermcp（官方）、Alpaca（美股，官方）、Binance/Coinbase（加密，官方）。**建议一律开只读/paper 模式**。
- **A 股免费 MCP**：社区封装为主——`mcp-eastmoney`（免 Key）、`akshare-one-mcp` 等，稳定性同爬虫源。
- **国际源几乎都不覆盖 A 股**；例外是 EODHD（覆盖沪深 EOD）和 yfinance 系（`.SS/.SZ`）。
- **形态趋势**：头部厂商共识是「官方远程 Streamable-HTTP endpoint + OAuth」，本地 stdio 包装器是社区方案主流。

## 一、国内官方 MCP

### 1.1 东方财富「妙想 MCP」✅（2026-06 上线，底层 Choice 数据）

- 端点：`https://mxapi.eastmoney.com/mxds/mcp`（Streamable HTTP），认证头 `em_api_key: <EM_API_KEY>`
- 配置：

```json
{
  "mcpServers": {
    "mx-ds-mcp": {
      "type": "http",
      "url": "https://mxapi.eastmoney.com/mxds/mcp",
      "headers": { "em_api_key": "<EM_API_KEY>" }
    }
  }
}
```

- 能力：A股/港股/美股、基金、债券、指数/板块、宏观；行情、财务、估值、股东结构、公司事件、资讯公告、智能选股。**纯数据，不含交易**
- 费用：积分制（每日登录送积分，Choice 终端/量化 API 权限可折算）
- 参考：[官方安装文档](https://mxapi.eastmoney.com/mxds/doc/mcp-install.md)（含 Claude Code/Cursor/Codex/Trae 等 8 种客户端配置）

### 1.2 同花顺 iFinD MCP ✅（2026-03 上线）

- 密钥入口 `mcp.51ifind.com`（iFinD 个人中心 → 密钥），支持 Cursor/Trae/Claude/CherryStudio/百炼等客户端
- 能力：四大模块 20+ 工具——A 股分析（智能选股/行情/财报/风险模型 Alpha/Beta/Sharpe/VaR/ESG）、公募基金、宏观与行业、公告资讯语义检索。**纯数据**
- 费用：需 iFinD 账号（机构付费终端），每用户赠 2000 次
- 注意：与 Dify 集成有 SSE/StreamableHTTP 兼容性坑
- 参考：[官方公告](https://stock.10jqka.com.cn/20260312/c675234890.shtml)

### 1.3 Wind AIFin Market ✅（2026-05 发布）

- 平台 `aifinmarket.wind.com.cn`，注册后个人中心取 `WIND_API_KEY`（独立于 Wind 终端，直接注册可用）
- 能力：与 Wind 终端同源的 A股/港美股行情、财报、公告、宏观、债券、基金、指数（按 7~8 个 server_type 划分技能路由）+ 官方 Alice Agent
- 费用：积分制，每日赠 1000 积分
- 参考：[官方公告](https://www.wind.com.cn/mobile/News/NewsDetail/zh.html?id=DC8922BEA8811DF636A2CAC8F361BFE3&lang=ON2002)

### 1.4 Tushare 官方 MCP ✅

- 远程 HTTP，无需安装；登录 tushare.pro → 个人中心 → MCP Server 复制 key
- 配置：

```json
{ "mcpServers": { "tushareMcp": { "url": "https://api.tushare.pro/mcp/token=你的Tushare token" } } }
```

- 能力：Tushare Pro 全部数据接口（覆盖面取决于账号积分等级，积分体系见 [research/01](01-财经新闻与股市数据来源参考.md)）
- 参考：[官方配置文档 doc_id=463](https://tushare.pro/document/1?doc_id=463)

### 1.5 长桥 LongPort MCP ✅（券商，支持实盘交易）

- 开源：[github.com/longportapp/openapi](https://github.com/longportapp/openapi)（`mcp/` 子目录）；[官方文档](https://open.longbridge.com/zh-CN/docs/llm)
- 安装（Rust 单二进制）：`curl -sSL https://raw.githubusercontent.com/longportapp/openapi/refs/heads/main/mcp/install | bash`（装到 `/usr/local/bin/longport-mcp`）
- 配置（stdio）：

```json
{
  "mcpServers": {
    "longport-mcp": {
      "command": "/usr/local/bin/longport-mcp",
      "env": {
        "LONGPORT_APP_KEY": "<app-key>",
        "LONGPORT_APP_SECRET": "<app-secret>",
        "LONGPORT_ACCESS_TOKEN": "<access-token>"
      }
    }
  }
}
```

- 能力：港美股实时行情、历史 K 线、资产/持仓查询、**实盘下单**
- 认证：长桥证券账户 + 开通 OpenAPI；Access Token 3 个月需刷新

### 1.6 老虎证券 tigermcp ✅（券商，支持实盘交易）

- 安装：`pip install tigermcp` 或 `uvx tigermcp`（stdio）
- 配置：

```json
{
  "mcpServers": {
    "tigermcp": {
      "command": "uvx",
      "args": ["--python", "3.13", "tigermcp"],
      "env": {
        "TIGEROPEN_TIGER_ID": "<tiger-id>",
        "TIGEROPEN_PRIVATE_KEY": "<private-key>",
        "TIGEROPEN_ACCOUNT": "<account>",
        "TIGERMCP_READONLY": "true"
      }
    }
  }
}
```

- 能力：行情 12 工具（实时/盘口/逐笔/K线/分时/资金流/期权链/市场状态）+ 交易 10 工具（下单/撤单/订单/成交/持仓/资产）；`TIGERMCP_READONLY=true` 禁用交易
- 认证：老虎账户开通 OpenAPI（开户入金后免费），私钥签名（PKCS#1）
- 参考：[官方文档](https://docs.itigerup.com/docs/mcp)、[skill 仓库](https://github.com/tigerfintech/tigeropen-skill)

### 1.7 其他国内官方

- **恒生聚源**（数据地图 MCP / 综合问数 MCP，✅ B 端）：已上架阿里云/腾讯云/百度云市场，覆盖股票/基金/宏观/研报问数；机构销售制，无自助注册。[产品页](https://www.gildata.com/products/datamap)
- **支付宝支付 MCP** / **微信支付 MCP**：均为**支付交易能力**（创建订单/退款），不是金融数据，与本项目无关。

## 二、国际官方/社区 MCP

### 2.1 市场数据商

| 服务 | 形态 | 工具数/能力 | 费用 | A 股 |
|---|---|---|---|---|
| **Alpha Vantage** ✅ | 远程 `https://mcp.alphavantage.co/mcp`（OAuth）或 `uvx marketdata-mcp-server <KEY>`；已上架 Claude/ChatGPT/Azure 目录 | 全部 API 函数即工具：时序行情、60+ 技术指标、基本面、外汇、加密、大宗、宏观 | 免费 key（约 25 req/天），$29.99/月起 | ❌ 基本不可用 |
| **Massive（原 Polygon.io）** ✅ 实验性 | [massive-com/mcp_massive](https://github.com/massive-com/mcp_massive)，stdio/SSE/HTTP | **3 个可组合工具**：`search_endpoints`/`call_api`/`query_data`（内嵌 SQL），覆盖股票/期权/外汇/加密/期货 | Stocks $29/月起 | ❌ |
| **Twelve Data** ✅ | 远程 `https://mcp.twelvedata.com/mcp`（OAuth）或本地 stdio | ~46 工具：OHLCV、60+ 指标、三表、分红、ETF、新闻、评级、EDGAR | 免费 800 credits/天；$29/月起 | ⚠️ 声称覆盖但质量参差，需实测 |
| **EODHD** ✅ | v1 `https://mcp.eodhd.com/v1/mcp?apikey=KEY`；v2 OAuth；本地 stdio 亦可 | **72 个只读工具** + 内嵌 API 文档 resources + 3 个 prompt 模板；基本面/新闻情绪/宏观/期权/ESG | 免费 20 req/天；$19.99/月起 | ✅ **覆盖 SHG/SHE（EOD）**，可作 A 股兜底 |
| **Financial Datasets** ✅ | `https://mcp.financialdatasets.ai/`（OAuth）或 `/api` + `X-API-KEY` | 30+：美股三表/指标、SEC filings 章节抽取、内部人交易、13F、新闻、筛选器 | ❗无免费档，$200/月起 | ❌ |
| **FMP** 📋 | 无官方自营 MCP；社区 `npx -y fmp-mcp --fmp-token=KEY`（250+ 工具） | 基本面/行情/宏观 | 免费 250 req/天；$19/月起 | ❌ |
| **Databento / Tiingo / Marketstack** 📋 | 仅社区封装（如 `pip install databento-mcp`、PyPI `tiingo-mcp` 17 工具） | tick 级/实时/新闻 | $10–199/月量级 | ❌ |

### 2.2 大型机构（企业级，机构合同制）

| 机构 | 官方 MCP | 端点/要点 |
|---|---|---|
| **FactSet** ✅（2025-12 发布） | 远程 | `https://mcp.factset.com/content/v1`；基本面/一致预期/ownership/M&A/供应链；2026-06 扩展 Portfolio Analytics |
| **S&P Global** ✅（Kensho 构建） | 远程 OAuth | `https://kfinance.kensho.com/integrations/mcp`；Capital IQ 数据，答案带溯源链接 |
| **Morningstar** ✅ | 远程 | `https://mcp.morningstar.com/mcp`；公允价值/护城河评级/基金/ESG |
| **LSEG（Refinitiv）** ✅ | 远程 | `https://api.analytics.lseg.com/lfa/mcp/server-cl`，面向 Copilot Studio/企业客户 |
| **Nasdaq Data Link** ✅ | 官方 | 连接其数据集市场，[文档](https://www.nasdaq.com/docs/data/nasdaq-data-link/mcp) |
| **Bloomberg** ❌ 无自助 MCP | 仅社区 [blpapi-mcp](https://mcp.aibase.com/server/1916355837718142978)（需本机跑着 Bloomberg Terminal，~$2.4万/年） | Terminal 内部已采用 MCP，但未开放 |

### 2.3 券商

- **Alpaca ✅ 官方**：[alpacahq/alpaca-mcp-server](https://github.com/alpacahq/alpaca-mcp-server)，`pip install alpaca-mcp-server`，env `ALPACA_API_KEY/ALPACA_SECRET_KEY/ALPACA_PAPER_TRADE=true`。美股/ETF/加密/期权**交易 + 数据**。⚠️ 读写型，务必 paper trading。仅美股。
- **Tradier ✅ 官方**：暴露全部标准 Tradier API（报价/历史/交易），[官方文档](https://support.tradier.com/kb/guide/en/tradiers-mcp-server-eoA0tv3GlS/Steps/4946334)。美股。
- **Interactive Brokers** 📋：无官方，社区包装 TWS API（需本地跑 TWS/IB Gateway）。
- **Charles Schwab** 📋：无官方，社区 [sudowealth/schwab-mcp](https://github.com/sudowealth/schwab-mcp)。

### 2.4 加密交易所

- **Binance ✅ 官方 Agent OS**（2026-08）：STDIO + SSE，现货/杠杆/合约交易 + 行情；强制隔离子账户、默认禁提币、$20/日 x402 上限
- **Coinbase ✅ 官方**：Coinbase for Agents（CLI + MCP，可交易）、Payments MCP（x402 协议）
- **Kraken**（2026-03 已推）、**OKX**（MCP toolkit）；通用：CCXT MCP（20+ 交易所）

### 2.5 资讯/研究/宏观

- **SEC EDGAR**：社区 [stefanoamorelli/sec-edgar-mcp](https://sec-edgar-mcp.amorelli.tech/introduction)（最流行，免费，仅需 `SEC_EDGAR_USER_AGENT`）；商业 [SEC-API.io 官方 MCP](https://sec-api.io/docs/mcp-server)。仅美股申报体系。
- **FRED（美联储经济数据库）**：社区事实标准 [stefanoamorelli/fred-mcp-server](https://github.com/stefanoamorelli/fred-mcp-server)（npm `fred-mcp-server`，80 万+ 经济序列，免费 key）。**对本项目有实际价值**：美债收益率/美元指数/美国 CPI 等宏观上下文，与国内国债曲线互补。
- **TradingView**：仅非官方社区封装，无官方 MCP。
- **IEX Cloud**：⚠️ 已于 2024-08-31 关停，网上旧教程作废。

## 三、A 股社区 MCP 封装（非官方）

| 项目 | 安装 | 能力 | 备注 |
|---|---|---|---|
| [27dream/mcp-eastmoney](https://github.com/27dream/mcp-eastmoney) | `uvx mcp-eastmoney` | 实时行情/搜股/主力资金排名/板块资金流/K线（5 工具） | **免 Key**，东财公开延时接口（约 15 分钟延迟），MIT |
| [zwldarren/akshare-one-mcp](https://smithery.ai/server/@zwldarren/akshare-one-mcp) | uv，Smithery 收录（~219 stars） | akshare 封装 9 工具 | 与本项目 collector 数据源同源 |
| [PanYouFu/akshare-mcp](https://github.com/PanYouFu/akshare-mcp) | — | 直调新浪/东财 HTTP，14 工具 | 不依赖 akshare 库，零 C 依赖 |
| [shuizhengqi1/futu-stock-mcp-server](https://github.com/shuizhengqi1/futu-stock-mcp-server) | `uvx futu-stock-mcp-server` | 富途行情订阅/账户/衍生品 | 需本地跑富途 OpenD 网关 + 富途账户 |
| [Litash/moomoo-api-mcp](https://github.com/Litash/moomoo-api-mcp) | — | Moomoo 封装，**支持实盘下单** | 有安全风险提示 |
| [tdx-mcp（PyPI）](https://pypi.org/project/tdx-mcp/) | pip | TCP 直连通达信公开行情服务器，23 工具，免 Key | 作者声明严禁商用 |
| [liqiongyu/xueqiu_mcp](https://github.com/liqiongyu/xueqiu_mcp) 等雪球系 | uv | 雪球行情/搜索 | 逆向 API，需 cookie，稳定性自担 |
| [`lxr-data-mcp`（npm）](https://npmx.dev/package/lxr-data-mcp) | npx | 理杏仁数据（A股/港股/美股指数/宏观） | 需理杏仁会员 token |
| yfinance 系（[narumiruna/yfinance-mcp](https://github.com/narumiruna/yfinance-mcp) 等十余个） | `uvx yfinance-mcp` | Yahoo 免费数据 | Yahoo 支持 `.SS/.SZ`（如 `600519.SS`），是国际社区方案中对 A 股最友好的，但稳定性差 |

## 四、MCP 接入方式通识

- **传输协议**（[官方 Transports 文档](https://modelcontextprotocol.io/docs/concepts/transports)）：
  - **stdio**：客户端 spawn 子进程（`command`/`args`/`env`），本地场景首选
  - **Streamable HTTP**：单一 HTTP 端点（`url` + 可选 `headers`），2025-03 起取代旧 HTTP+SSE 双端点方案（旧 SSE 已 deprecated）
- **stdio 配置示例**（通用 `mcpServers` 格式）：

```json
{
  "mcpServers": {
    "alphavantage": { "command": "uvx", "args": ["marketdata-mcp-server", "<KEY>"] },
    "github": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-github"],
                "env": { "GITHUB_PERSONAL_ACCESS_TOKEN": "<TOKEN>" } }
  }
}
```

- **远程配置示例**：`{ "mcpServers": { "av": { "url": "https://mcp.alphavantage.co/mcp" } } }`；需自定义认证头时用 `"type": "http"` + `"headers"`（见 1.1 东财妙想示例）
- **各客户端配置文件位置**：Claude Desktop `claude_desktop_config.json`（仅 stdio，远程走 Connectors 目录）；Cursor `~/.cursor/mcp.json`（或项目内 `.cursor/mcp.json`）；VS Code `.vscode/mcp.json`（`servers` 键）；Kimi Code `~/.kimi-code/mcp.json` + 项目级；Gemini CLI `~/.gemini/settings.json`

## 五、发现渠道与质量评估

- **官方 MCP Registry**：[registry.modelcontextprotocol.io](https://registry.modelcontextprotocol.io/)（API `GET /v0.1/servers?search=...` 实测可用，2026 年权威源）。注意：官方 GitHub `modelcontextprotocol/servers` 仓库已收缩为 7 个教学用 reference server，**不再维护第三方列表**
- **聚合目录**：glama.ai（每个 server 有 license/quality/maintenance A–F 评级，适合初筛）、smithery.ai（显示用量数、支持托管部署）、mcp.so（按星数排序好用）、pulsemcp.com（本次抓取被 403 未覆盖）
- **清单仓库**：[wong2/awesome-mcp-servers](https://github.com/wong2/awesome-mcp-servers)、[BlockRunAI/awesome-finance-mcp](https://github.com/BlockRunAI/awesome-finance-mcp)（按股票/加密/支付分类，含定价）

## 六、安全注意（接入第三方 MCP 前必读）

- **Tool Poisoning Attack**：恶意指令藏在 tool description 里，用户不可见但模型可见（[Invariant Labs 2025-04 披露](https://invariantlabs.ai/blog/mcp-security-notification-tool-poisoning-attacks)）
- **凭证泄露**：stdio 的 `env` 里的 API key 对 server 进程明文可见；key 拼 URL 会进日志
- **供应链/rug pull**：`npx -y pkg@latest` 每次拉新版加剧风险——**锁定版本、优先官方维护的 server**
- **交易类 server**（长桥/老虎/Alpaca/Binance）：一律先只读或 paper trading 凭证
- 质量信号（不等于审计）：Glama 评级、Smithery 用量、官方 Registry 元数据、Claude/ChatGPT "Verified Connector"

## 七、对本项目的结论

1. **主数据链路无需改变**：东财直连 + akshare/tushare 已覆盖需求；MCP 的价值在「把数据能力开放给外部 Agent 客户端」或「接入别的 Agent 生态」。
2. **如需免费 A 股 MCP 给外部 agent 用**：`mcp-eastmoney`（免 Key）或 akshare-one-mcp 起步；要权威数据且有预算：东财妙想 / iFinD / Wind AIFin / Tushare 官方 MCP（远程 HTTP 接入最简）。
3. **有间接价值的接入**：FRED MCP（美国宏观序列，免费）补宏观上下文；EODHD（SHG/SHE EOD）作交叉验证兜底。
4. **若未来自建 MCP 开放本系统能力**，可对标两种设计范式：Massive 的「3 个可组合工具 + 内嵌 SQL」、EODHD 的「72 只读工具 + 内嵌 API 文档 resources + prompt 模板」；形态上采用官方远程 Streamable-HTTP + OAuth/Key。

## 八、明确「未发现」清单（2026-09-06）

国泰君安/中信等头部内资券商对外 MCP、交易所（上交所/深交所/中证指数）MCP、财联社/华尔街见闻/金十官方 MCP、通联数据/米筐/聚宽数据 MCP、华盛通/盈立 MCP、Bloomberg 自助 MCP、Koyfin MCP、Schwab 官方 MCP、FMP 官方自营 MCP、美联储官方 FRED MCP、SEC 官方 MCP。
