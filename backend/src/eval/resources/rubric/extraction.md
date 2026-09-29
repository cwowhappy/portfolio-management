# 新闻抽取质量抽验口径（eval-extraction）

> **变更流程约束（先读）**：凡变更 `NewsExtractPrompt`（提示词）、被评模型（`DEEPSEEK_MODEL`）
> 或抽取相关 `GenerateOptions` 参数的 PR，**必须附最新一次 `make eval-extraction` 报告**
> （`backend/build/reports/eval-extraction/eval-report.md`）作为效果对照证据；题库期望值
> （`src/eval/resources/extraction/*.yaml`）的调整须在 PR 描述中说明理由。
> 报告不挂 CI 门禁、退出码恒 0（D21「报告即产出」）——本文件是人工抽验口径，不是自动门槛。

## 运行方式

```bash
make eval-extraction                        # 真跑（需 DEEPSEEK_API_KEY，进程 env 或仓库根 .env）
make eval-extraction EVAL_ARGS="--list"     # 干跑：只装载校验题库，不起 LLM
DEEPSEEK_MODEL=<其他模型> make eval-extraction   # 临时换被评模型
```

- 被评通道与生产同款：`NewsExtractor.extractOne` × `AgentScopeIntelligenceChatPort`
  （Model 直构自 `ModelRegistry.resolve("deepseek:"+model)`，抽取调用时端口钉死 temperature 0）。
- LLM 未配置（无 `DEEPSEEK_API_KEY`）时报告写 SKIP 后正常退出；框架异常写 ERROR 报告，退出码恒 0。

## 抽验口径（review 时人工对照报告汇总四指标）

| 指标 | 口径 | 量级要求 |
|---|---|---|
| parse 成功率 | `SUCCESS / 总题数`（解析失败重试 1 次后仍失败计 PARSE_FAILED） | 应为 100%；出现 PARSE_FAILED 或 LLM_UNAVAILABLE 须排查（提示词契约 / key / 限流） |
| stock 命中率（标签一致率） | 期望 `stock_codes` 与实得**全集相等**的题占比（空数组=断言无标的） | ≥ 90% 量级（10 题起步题库允许 1 题内偏差；题库扩充后按比例） |
| direction 一致率 | 期望 direction 非 null 的题中实得方向一致占比（null=容忍不确定，不断言、不入分母） | 100%（方向断言题必须全对，错 1 题即应复查提示词） |
| importance 档位一致率 | 按 80/50 阈值判档（`ImportanceGrade.grade(score, 80, 50)`）；跨档但分数落在阈值 ±10 内按容差不计错 | **档位偏差 ≤ 1 档**（MAJOR↔WATCH↔IGNORE 相邻档，即含容差口径应 100%）；严格一致率以 ≥ 90% 为量级参考——边界题真跑中会在阈值 ±10 容差带内摆动（先例：WATCH 期望题三连跑得 80/82/85） |

补充判定细则：

- **event_type**：期望非空的题精确一致（大小写不敏感）；期望 null 的题不断言，仅记录实得值供巡检。
  注意提示词中的标签词表仅为示例（"如 EARNINGS、POLICY、…"）非受控枚举——**期望值只在标签无歧义处
  断言**（如年报→EARNINGS、立案调查→LEGAL）；真跑先例：产销快报的标签在 EARNINGS/SALES 间
  摆动且两者均成立，此类题 event_type 应为 null。
- **industry_codes / key_numbers / summary**：本期不断言（抽取契约由单测/集成测试守护），
  报告逐题记录实得值，供人工抽阅有无编造。
- **跨 2 档偏差**（MAJOR↔IGNORE）无论分数如何计 FAIL，不受 ±10 容差保护。
- 单题总判定：outcome=SUCCESS 且四个断言维度（stock / direction / importance / event_type）
  无 FAIL（TOLERATED 不算 FAIL）记 PASS。

## 题库边界覆盖（维护时保持）

重大利好（政策/业绩）、重大利空（暴雷/减持）、中性公告、多标的命中、无标的宏观消息、
低重要度水闻、长摘要、方向不明——新增题目沿用 `news-001.yaml` 的 `expect` 口径注释。
