-- ═════════ V4__p2_indexes_and_cleanup.sql ═════════

-- MS-28 P2 修复（B11）：
-- ① intelligence_push_log 幂等查重补四列复合索引——existsAnnouncementPush 谓词
--    (push_type, ref_table, ref_id, user_id) 全命中；V3 建表时仅 PK，群推留痕增长后
--    公告查重退化为全表扫。查询逻辑不变，索引对写入/读取路径透明。
-- ② 验证码 90 天滚动清理（B11 同项）为代码侧行为（IntelligenceCleanupService 第三步
--    deleteCreatedBefore），无 DDL，此处不涉。
-- IF NOT EXISTS 幂等（重复执行不炸）。

CREATE INDEX IF NOT EXISTS idx_intelligence_push_log_idem ON intelligence_push_log(push_type, ref_table, ref_id, user_id);
