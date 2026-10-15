# language: zh-CN
功能: 评测运行生命周期
  管理员手动触发评测：202 受理即返回 runId，运行历史出现 RUNNING 行（前端轮询口径，
  RUNNING 行原样返回不过滤）；子进程退出且报告产出后收割器落终态，历史可见终态行、
  判定留痕与报告归档（2026-10-15 审查 §三.6：eval 域 BDD 零覆盖补齐）。

  评测子进程以 EvalScheduler mock 替身（不真起 JVM 进程——互斥/CAS/白名单/超时看护已由
  EvalSchedulerTest 钉死，真子进程全链归 opt-in 的 EvalSchedulerEndToEndTest）；
  触发受理（HTTP 202→控制器→门面）、RUNNING 行、收割落库与运行历史全链真实（真 PG）。

  场景: 手动触发到收割终态全链
    当 管理员手动触发评测
    那么 触发受理返回 202 且 runId 为正
    而且 该运行状态为 "RUNNING" 且触发方为 "MANUAL"
    当 该运行的评测子进程退出，报告 pass 2 fail 0 error 0
    而且 收割该评测运行
    那么 该运行状态为 "COMPLETED" 且通过数为 2
    而且 该运行判定留痕含 "无 baseline"
    而且 该运行报告已归档为 per-run 副本
