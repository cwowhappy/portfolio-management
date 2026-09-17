# AgentScope 官方文档离线摘录（vendored）

> 本目录是 **AgentScope Java 官方文档的摘录拷贝**（vendored），由 [`scripts/fetch_docs.py`](../../../../scripts/fetch_docs.py) 从上游仓库 [agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java) 的 `docs/v2/en/` 抓取，供离线参考；抓取未锁版本，各文内的版本标注以抓取时点为准，**要查全文/最新版请到上游**。

## 预期断链（不要修）

官方文档的站内相对链接（`../harness/*`、`../building-blocks/*`、`../distributed/*`、`../../integration/*`、`./workspace.md` 等，现共 60 余处）指向**未拷贝**的上游章节，在本仓库内必然不可达——这是预期行为，不是待修断链，链接门禁对本目录豁免。

## 协议勘误口径

P0 协议勘误的**正式沉淀**在 [features/mcp-hitl/03-实施计划/验证记录.md](../../../../features/mcp-hitl/03-实施计划/验证记录.md)。本目录 `faq.md` / `agui.md`（尤其 `agui.md` 内针对 2.0.1 的版本警告块）与本项目实测结论有出入时，**以验证记录为准**（勘误自 2.0.3 起全部消解，详见验证记录 §7）。

## copilotkit/（姊妹目录）

[`../copilotkit/`](../copilotkit/) 同理：其中 `agui-hitl.md` 是 CopilotKit 与 AG-UI 协议 HITL 支持的调研沉淀（结论追至一手来源，2026-09-08），非官方文档拷贝；其结论如与后续实施验证记录冲突，以验证记录为准。
