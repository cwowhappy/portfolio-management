-- ═════════ V6__ms30_eval_track.sql ═════════

-- MS-30 跟进项②（终审 I-2 拍板「并入」）：抽取轨（ExtractionEvalRunner 22 题）并入
-- 每晚全量——同一晚对话轨与抽取轨各落一行 eval_run，增 track 列区分：
--   AGENT  = 对话轨（既有回归判定/baseline 语义不变，V6 前全部存量行回填此值）；
--   EXTRACT = 抽取轨（落库留痕，不参与回归判定/baseline，v1 语义——收割 alert_status
--             恒 NONE，verdict_reasons 注明不参与判定）。
-- 轨别由调度侧 insertRunning 显式落值；判定输入查询（findBaseline/findLatestExcluding）
-- 按 track='AGENT' 过滤，EXTRACT 行仅运行历史可见。仅加一列，无索引（行量级个人系统，
-- 轨别过滤走既有 started_at 索引序扫描足够）。

ALTER TABLE eval_run ADD COLUMN track VARCHAR(16) NOT NULL DEFAULT 'AGENT';
