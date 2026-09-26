#!/usr/bin/env bash
# 清理 e2e 测试残留用户及其关联数据（仅本地持久化 DB 需要；CI 用临时 DB，由 global-teardown 跳过）。
# 顶层 FK 均为 NO ACTION，须先删子表；其下级多已 ON DELETE CASCADE。
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${POSTGRES_USER:-invest}"
PGDATABASE="${POSTGRES_DB:-invest}"
export PGPASSWORD="${POSTGRES_PASSWORD:-invest}"

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 <<'SQL'
DELETE FROM conversation     WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM allocation_plan WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM risk_assessment WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM journal_entry    WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM portfolio        WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM mcp_user_config  WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM skill_user_config WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM watchlist_item   WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM industry_watch   WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM wiki_entry       WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM principle_rule   WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM wiki_seed_state  WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
-- 全局策展数据无 user 归属（MS-10 §九#1）：按 e2e 命名前缀清残留（industry_chain_member
-- 对其引用为 ON DELETE SET NULL，先行删除安全；V19 迁移种子「示例%」不在此列）
DELETE FROM industry_unlisted_company WHERE company_name LIKE 'E2E策展%';
DELETE FROM app_user         WHERE username LIKE 'e2e\_%';
SQL
