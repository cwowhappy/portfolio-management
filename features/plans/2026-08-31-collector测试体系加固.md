# Collector 测试体系加固计划

日期：2026-08-31
分支：`feature/collector-test-hardening`
状态：已完成（2026-08-31 收尾，`make collect-test` 全绿）

## 背景与现状

实测：`pytest --cov=collector` 103 通过 + 1 skip，总覆盖率 89%（886 语句 / 101 未覆盖）。未覆盖集中在装配链路（四个 registry 36–62%）与落库铁律（T3 落实率 1/6）上。

| # | 缺口 | 类型 |
|---|---|---|
| 1 | T3 幂等 upsert 仅 valuation_snapshot 一表（1/6） | 测试缺口 |
| 2 | 全量 YAML 装配冒烟缺失，registry `get()` 与 `load_task_defs` 未覆盖 | 测试缺口（L5/L2） |
| 3 | `seed_tasks` 无键校验；任务 job lambda 无异常兜底（S3） | **代码缺口** |
| 4 | `test_migrations.py` 本地永不执行；conftest 手工 DDL 与真实迁移会漂移 | 基建缺口 |
| 5 | executor 全源熔断终端分支未测（`executor.py:39-42`） | 测试缺口（D2 闭环） |
| 6 | L4「上游表为空」无用例 | 测试缺口 |
| 7 | `_coerce` 脏数据、calc/snapshot 脏记录、S1 调度属性、cli 小分支 | 测试缺口 |
| 8 | 性能零覆盖（5k 行 iterrows + executemany） | 测试缺口 |

## 关键决策（已确认）

- **schema 来源**：集成测试改用真实迁移建表——collector `alembic upgrade head`（4 张运维表）+ 后端 Flyway V3/V4 SQL（6 张业务目标表），废弃手工 `OPS_DDL`，消灭漂移
- **生产代码修复两项**：`seed_tasks` 键校验（必填缺失/未知键 fail-fast）、任务 job 异常兜底（S3）

## 实施计划

### P0 — 落库铁律与装配链路
1. `tests/conftest.py` 重构：`pg_conn` 的 schema 准备改为跑真实迁移（alembic + 后端 Flyway SQL），删除 OPS_DDL
2. `test_migrations.py` 改复用 `pg_url` fixture（本地 testcontainers 可跑）
3. 六张业务目标表各补一条真实 PG 幂等 upsert 用例（T3）
4. 全量 YAML 装配冒烟：`load_task_defs("tasks")` + `build_registries` + `assemble_collector` 遍历真实任务 YAML（L5），顺带覆盖四个 registry `get()` 与 `make_trigger`

### P1 — 场景补全（纯单测为主）
5. executor 全源熔断终端分支；6. IndustryUniverseSource 上游空表（L4）；7. `_coerce` 脏数据（None/"-"/畸形字符串/int）；8. calc/snapshot 脏记录跳过；9. S1 调度属性断言；10. cli 缺口（list/history 空/exit 1）；11. min_rows soft 等小分支

### P2 — 生产代码修复 + 性能
12. `seed_tasks` 键校验（fail-fast）+ 单测
13. 任务 job 异常兜底（S3）+ 单测
14. 5k 行规模性能 smoke 用例

### P3 — 文档同步
15. 04 规范：T3/L4/L5 落实状态更新、真实迁移 schema 约定、修复记录

## 验收标准

1. `make collect-test` 全绿（ruff + import-linter + pytest 含真实 PG）
2. T3 落实率 6/6；registry 覆盖率 ≥90%；总覆盖率 ≥90%
3. 缺口 #1–#8 全部闭环
4. CI collector job 通过

## 实施结果（2026-08-31 收尾）

### 前后对比

| 指标 | 加固前 | 加固后 |
|---|---|---|
| 用例数 | 103 | **148**（全绿，含真实 PG） |
| 总覆盖率 | 89%（886 语句 / 101 未覆盖） | **95.56%**（901 语句 / 40 未覆盖） |
| T3 幂等 upsert | 1/6（仅 valuation_snapshot） | **6/6** |
| registry 覆盖率 | 36–62% | calc/converters 100%、validators 91%、sources 86% |

### 落实情况（缺口 #1–#8）

1. **T3 6/6**：新增 `tests/test_writer_idempotency.py`（其余 5 表各一条真实 PG「重复 upsert 不产生重复行」用例）。
2. **全量 YAML 装配冒烟**：新增 `tests/test_yaml_assembly.py`（11 用例），真实 tasks/ 目录走 `load_task_defs → build_registries → assemble_collector` 全链路，顺带覆盖四个 registry `get()` 与 `make_trigger` 各分支。
3. **生产代码修复两项（P2）**：`seed_tasks` 键校验（`TASK_DEF_KEYS` + `_validate_task_keys`，缺必填/未知键 fail-fast ValueError，含存量 6 个 YAML 必过校验的回归用例）；任务 job 异常兜底（`_run_task_job` 统一 try/except + logger.exception，断言不外抛）。
4. **基建**：conftest 废弃手工 `OPS_DDL`，session 级 `pg_schema` fixture 用真实迁移建 schema（collector alembic upgrade head + 后端 Flyway V3/V4 SQL 整段回放），`pg_conn` 只做 10 表 TRUNCATE；`test_migrations.py` 改复用 `pg_url`（drop 运维表后重迁 head），本地 testcontainers 可跑。
5. **executor 全源熔断终端分支**（`executor.py:39-42`）已补测。
6. **L4 上游空表**：`test_industry_universe_empty_upstream_table_*` 两条（产出 0 行 + 经 min_rows hard 判失败）。
7. `_coerce` 脏数据（None/"-"/畸形字符串/int）、calc/snapshot 脏记录跳过、S1 调度属性断言（`test_task_job_scheduler_attributes`）、cli 缺口（list/history 空/exit 1）、min_rows soft 等 22 用例已补。
8. **性能 smoke**：新增 `tests/test_performance.py`（5k 行 iterrows + executemany，实测 0.08s，阈值 10s 防回归）。

### 验收核对

1. ✅ `make collect-test` 全绿：ruff check 通过、65 文件 format 通过、import-linter 1 kept / 0 broken、pytest 148 passed / 4.75s、总覆盖率 95.56%（≥80% 门槛通过）。
2. ✅ T3 落实率 6/6；总覆盖率 95.56% ≥ 90%；registry 覆盖率 sources 86% / validators 91% / calc、converters 100%——sources 86% 未达计划中「≥90%」的目标值，未覆盖行为 tushare `pro_api` 惰性构造闭包（`sources/registry.py:22-25`，执行即触真实外部 API），经评估接受（总覆盖率与装配链路目标均达成）。
3. ✅ 缺口 #1–#8 全部闭环。
4. ⏳ CI collector job 待分支推送后验证（本地与 CI 同命令、同 PG service 形态，风险低）。

### 遗留与边界

- **Flyway SQL 回放要求空库**：V3/V4 无 `IF NOT EXISTS`，`pg_schema` 约定测试库为空库（CI postgres:16 service / 本地一次性容器）；非空库重复建表会直接报错——属预期暴露而非兜底（conftest docstring 已声明）。
- **L4 上游空表仅 mock 级**：连接为 MagicMock 返回空结果，未走真实 PG JOIN；集成级用例待补。
- **`sources/registry.py` 86%**：未覆盖 3 行为 tushare `pro_api` 惰性构造闭包（22-25 行，执行即触真实外部 API）。
- **jobs.py 76%（34 语句未覆盖）**：集中在 `main()` 入口（265-293）、`refresh_calendar_job` 实跑包装（249-254）、`load_calendar`（258-261）与 `build_registries` 的真实 pro/conn 工厂闭包——均需真实 DB/外部 API，单测覆盖性价比低。
