# 抽取质量抽验口径（eval-extraction）

> **变更流程约束（先读）**：凡变更 `NewsExtractPrompt` / `AnnouncementExtractPrompt`（提示词）、
> 被评模型（`DEEPSEEK_MODEL`）或抽取相关 `GenerateOptions` 参数的 PR，**必须附最新一次
> `make eval-extraction` 报告**（`backend/build/reports/eval-extraction/eval-report.md`）作为
> 效果对照证据；题库期望值（`src/eval/resources/extraction/*.yaml`）的调整须在 PR 描述中
> 说明理由。报告不挂 CI 门禁、退出码恒 0（D21「报告即产出」）——本文件是人工抽验口径，
> 不是自动门槛。

## 运行方式

```bash
make eval-extraction                        # 真跑（需 DEEPSEEK_API_KEY，进程 env 或仓库根 .env）
make eval-extraction EVAL_ARGS="--list"     # 干跑：只装载校验题库，不起 LLM
DEEPSEEK_MODEL=<其他模型> make eval-extraction   # 临时换被评模型
```

- 题库双 kind（MS-21 Task 9）：`news`（缺省，`news-001.yaml`）直调 `NewsExtractor.extractOne`；
  `announcement`（`announcement-001.yaml` 起）直调 `AnnouncementExtractor.extractOne`
  （标题 + `pdf_text`，`source_types` 传空——eval 只评 LLM 精判面，栏目直判并集路径由单测守护）。
- 被评通道与生产同款：`AgentScopeIntelligenceChatPort`（Model 直构自
  `ModelRegistry.resolve("deepseek:"+model)`，抽取调用时端口钉死 temperature 0）。
- LLM 未配置（无 `DEEPSEEK_API_KEY`）时报告写 SKIP 后正常退出；框架异常写 ERROR 报告，退出码恒 0。

## 抽验口径（review 时人工对照报告汇总指标）

### 新闻题（kind=news，四指标）

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

### 公告题（kind=announcement，MS-21 Task 9 新增三指标）

| 指标 | 口径 | 量级要求 |
|---|---|---|
| metrics 一致率 | 期望数值字段（revenueYi/netProfitYi/netProfitYoyPct/deductedProfitYi/grossMarginPct，camelCase 键）与实得 **BigDecimal compareTo 等值**（容忍尾零差异）的题占比（期望 map 非空的题为分母；缺失键=不断言该字段——区间披露的预告题只断言单锚数值） | 100%（数值抽取错即编造/口径错，错 1 题即应复查提示词） |
| undisclosed 命中率 | 期望 undisclosed 每项在实际 undisclosed 中命中、且六个规范名（营业收入/归母净利润/归母净利润同比/扣非净利润/毛利率/分红）**联动断言对应字段为 null**（未披露字段 null 且进 undisclosed 的契约）的题占比 | 100%（漏报未披露=编造风险，误报未披露=信息丢失，均须复查） |
| annTypes 命中率 | 期望枚举名集 **⊆ 实际集**（栏目直判 ∪ LLM 精判并集语义，允许多）的题占比 | ≥ 90% 量级（十类 + OTHER 受控枚举；漏标即检索面丢失，多标不计错） |

补充判定细则：

- **dividendDesc**：期望非 null=断言实际非 null（披露识别命中；**文本措辞不强一致**——
  报告逐题记录实得值供人工抽阅）。null 断言不经该字段（YAML 显式 null 与缺省不可区分），
  改由 undisclosed 含「分红」联动守护（无分红方案→dividendDesc null + undisclosed 含「分红」）。
- **pdf_text / 非业绩类契约**：非业绩类公告（增持/回购/关联交易/定增）metrics 六字段应全 null
  且六字段名全部进 undisclosed——题库以全六项 undisclosed 期望断言（announcement-005 先例）。
- 单题总判定：outcome=SUCCESS 且四个断言维度（metrics / dividendDesc / undisclosed / annTypes）
  无 FAIL 记 PASS；parse 成功率与总体 PASS 为混合题库全局口径。

## 题库边界覆盖（维护时保持）

- 新闻（`news-001.yaml`）：重大利好（政策/业绩）、重大利空（暴雷/减持）、中性公告、多标的命中、
  无标的宏观消息、低重要度水闻、长摘要、方向不明——新增题目沿用文件头 `expect` 口径注释。
- 公告（`announcement-001.yaml`）：业绩预告（预增单锚/预亏负数）、业绩快报、正式年报（全字段）、
  未披露字段边界（无分红方案）、多类型标签（回购+关联交易双标签）、宽栏目类（增持）——
  新增题目沿用文件头 `expect` 口径注释。

## 基线（deepseek-flash）

- 2026-09-29 新闻基线（10 题）：parse 10/10、stock 10/10、direction 6/6、importance 严格 7/10
  （容差 10/10）、总体 PASS 10/10。
- 2026-10-02 混合基线（17 题 = 新闻 10 + 公告 7，MS-21 Task 9）：parse 17/17、总体 PASS 17/17；
  公告 metrics 5/5、undisclosed 5/5、annTypes 7/7（含负数净利 -3.10 与无分红边界题全对）；
  新闻 stock 10/10、direction 6/6、importance 严格 8/10（容差 10/10）。
