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
DELETE FROM journal_entry    WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM portfolio        WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'e2e\_%');
DELETE FROM app_user         WHERE username LIKE 'e2e\_%';
SQL
