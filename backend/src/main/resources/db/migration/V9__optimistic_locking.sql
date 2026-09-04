-- 钱账聚合乐观锁：为 5 个聚合对应表增加 version 列（默认 0，NOT NULL），
-- JPA @Version 以「读-改-整行 merge 写」时校验版本，防并发丢更新。

ALTER TABLE portfolio        ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE holding_group    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE position         ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE allocation_plan  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE journal_entry    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
