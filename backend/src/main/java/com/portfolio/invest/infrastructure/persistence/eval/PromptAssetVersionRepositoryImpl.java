package com.portfolio.invest.infrastructure.persistence.eval;

import com.portfolio.invest.domain.eval.PromptAssetVersion;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 提示词资产版本仓库实现（prompt_asset_version，V5）。照 PushLog 先例
 * （IntelligencePushLogRepositoryImpl）全走 JdbcTemplate 原生 SQL，不建 JPA 门面——写入仅
 * registrar 启动对账与 eval 收割回流两处，「JPA 门面无运行时消费者」教训不再复制。id 为
 * IDENTITY 生成、registered_at 由库端 DEFAULT now() 生成，均不参与写入。
 *
 * <p>findVersionByHash 按 idx_prompt_asset_latest 前缀（asset_type + asset_key）定位后过滤
 * content_hash（max 聚合空集返回 null → Optional.empty）——Task 1 裁定不建
 * (asset_key, content_hash) 索引；latestSnapshot 用 DISTINCT ON 取每键最新行（PG 专有语法，
 * 本仓 PG-only）。事务边界在 application 层。
 */
@Repository
public class PromptAssetVersionRepositoryImpl implements PromptAssetVersionRepository {

    private static final RowMapper<PromptAssetVersion> ROW = (rs, i) -> new PromptAssetVersion(
            rs.getLong("id"), rs.getString("asset_type"), rs.getString("asset_key"),
            rs.getInt("version"), rs.getString("content_hash"), rs.getString("note"),
            toInstant(rs.getTimestamp("registered_at")));

    private final JdbcTemplate jdbc;

    public PromptAssetVersionRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Integer> findVersionByHash(String assetType, String assetKey, String contentHash) {
        Integer version = jdbc.queryForObject("""
                SELECT max(version) FROM prompt_asset_version
                 WHERE asset_type = ? AND asset_key = ? AND content_hash = ?
                """, Integer.class, assetType, assetKey, contentHash);
        return Optional.ofNullable(version);
    }

    @Override
    public int insert(PromptAssetVersion version) {
        return jdbc.update("""
                INSERT INTO prompt_asset_version (asset_type, asset_key, version, content_hash, note)
                VALUES (?, ?, ?, ?, ?)
                """,
                version.assetType(), version.assetKey(), version.version(),
                version.contentHash(), version.note());
    }

    @Override
    public List<PromptAssetVersion> latestSnapshot() {
        return jdbc.query("""
                SELECT DISTINCT ON (asset_type, asset_key)
                       id, asset_type, asset_key, version, content_hash, note, registered_at
                  FROM prompt_asset_version
                 ORDER BY asset_type, asset_key, version DESC
                """, ROW);
    }

    @Override
    public List<PromptAssetVersion> findVersionChain() {
        return jdbc.query("""
                SELECT id, asset_type, asset_key, version, content_hash, note, registered_at
                  FROM prompt_asset_version
                 ORDER BY asset_type, asset_key, version DESC
                """, ROW);
    }

    @Override
    public int updateNote(long id, String note) {
        return jdbc.update("UPDATE prompt_asset_version SET note = ? WHERE id = ?", note, id);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
