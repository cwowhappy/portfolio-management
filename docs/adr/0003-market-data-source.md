# ADR-0003 行情数据源：东方财富公开接口 + 新浪兜底

- 状态：已接受（2026-08-18）
- 决策者：项目负责人

## 背景

一期覆盖 A股行情/财务/新闻数据。候选：Tushare Pro（需 token、积分门槛）、
akshare（Python 库，与 Java 栈不符）、东方财富/新浪公开 HTTP 接口。

## 决策

主用 **东方财富公开接口**（push2 行情、push2his K线、searchapi 搜索、F10 财务、
search-api-web 新闻），**新浪行情接口兜底**。全部为免费公开 HTTP 接口，Java WebClient 直连。

## 后果

正面：无需注册 token，零成本起步；数据实时性好（行情秒级）。
风险：非官方契约接口，可能变更或限流 → 以 TTL 缓存 + 令牌桶限流（5 req/s）保护，
解析器全部离线 fixture 单测，变更时快速定位；接口变更时仅需替换 Client 层。
