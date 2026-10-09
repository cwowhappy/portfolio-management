package com.portfolio.invest.infrastructure.persistence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.eval.EvalRunHarvest;
import com.portfolio.invest.domain.eval.EvalRunRepository;
import com.portfolio.invest.domain.eval.EvalRunRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * eval 运行留痕仓库实现（eval_run，V5）。照 PushLog 先例全走 JdbcTemplate 原生 SQL
 * 不建 JPA 门面（写入仅调度触发与收割两处）；三个 JSONB 列（by_category/prompt_versions/
 * verdict_reasons）以 {@code ?::jsonb} + Jackson 序列化（沿 IntelligenceAnnouncement
 * RepositoryImpl upsert 形态）。id 为 IDENTITY 生成（RETURNING 取回）、started_at 由库端
 * DEFAULT now() 生成，均不参与写入；事务边界在 application 层。
 */
@Repository
public class EvalRunRepositoryImpl implements EvalRunRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT_COLS = """
            SELECT id, triggered_by, status, started_at, finished_at, total_pass, total_fail,
                   total_error, by_category, prompt_versions, question_bank_hash, alert_status,
                   baseline, baseline_candidate, verdict_reasons, duration_ms, report_path
              FROM eval_run
            """;

    private static final RowMapper<EvalRunRow> ROW = (rs, i) -> new EvalRunRow(
            rs.getLong("id"),
            rs.getString("triggered_by"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at")),
            rs.getInt("total_pass"),
            rs.getInt("total_fail"),
            rs.getInt("total_error"),
            byCategory(rs.getString("by_category")),
            versions(rs.getString("prompt_versions")),
            rs.getString("question_bank_hash"),
            rs.getString("alert_status"),
            rs.getBoolean("baseline"),
            rs.getBoolean("baseline_candidate"),
            reasons(rs.getString("verdict_reasons")),
            (Long) rs.getObject("duration_ms"),
            rs.getString("report_path"));

    private final JdbcTemplate jdbc;

    public EvalRunRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insertRunning(String triggeredBy, String reportPath) {
        Long id = jdbc.queryForObject("""
                INSERT INTO eval_run (triggered_by, status, report_path)
                VALUES (?, 'RUNNING', ?)
                RETURNING id
                """, Long.class, triggeredBy, reportPath);
        if (id == null) {
            throw new IllegalStateException("eval_run RUNNING 行插入未返回 id");
        }
        return id;
    }

    @Override
    public void updateHarvested(long id, EvalRunHarvest h) {
        jdbc.update("""
                UPDATE eval_run SET
                    status = ?, finished_at = ?, total_pass = ?, total_fail = ?, total_error = ?,
                    by_category = ?::jsonb, prompt_versions = ?::jsonb, question_bank_hash = ?,
                    alert_status = ?, baseline_candidate = ?, verdict_reasons = ?::jsonb,
                    duration_ms = ?, report_path = ?
                 WHERE id = ?
                """,
                h.status(), Timestamp.from(h.finishedAt()), h.totalPass(), h.totalFail(),
                h.totalError(), toJson(h.byCategory()), toJson(h.promptVersions()),
                h.questionBankHash(), h.alertStatus(), h.baselineCandidate(),
                toJson(h.verdictReasons()), h.durationMs(), h.reportPath(), id);
    }

    @Override
    public void markFailed(long id, List<String> reasons) {
        jdbc.update("""
                UPDATE eval_run SET status = 'FAILED', finished_at = now(),
                                    verdict_reasons = ?::jsonb
                 WHERE id = ?
                """, toJson(reasons), id);
    }

    @Override
    public Optional<EvalRunRow> findBaseline() {
        return jdbc.query(SELECT_COLS + " WHERE baseline LIMIT 1", ROW).stream().findFirst();
    }

    @Override
    public Optional<EvalRunRow> findLatestExcluding(long runId) {
        return jdbc.query(SELECT_COLS + """
                 WHERE id <> ?
                 ORDER BY started_at DESC, id DESC LIMIT 1
                """, ROW, runId).stream().findFirst();
    }

    // ———— JSONB 列读装配 ————

    /** by_category：{分类: [pass, fail, error]} → Map（缺值容忍旧档，短数组按 0 补齐）。 */
    private static Map<String, int[]> byCategory(String json) {
        Map<String, int[]> result = new LinkedHashMap<>();
        readTree(json).fields().forEachRemaining(entry -> {
            int[] counts = new int[3];
            for (int i = 0; i < 3 && i < entry.getValue().size(); i++) {
                counts[i] = entry.getValue().get(i).asInt();
            }
            result.put(entry.getKey(), counts);
        });
        return result;
    }

    /** prompt_versions：{asset_key: version} → Map。 */
    private static Map<String, Integer> versions(String json) {
        Map<String, Integer> result = new LinkedHashMap<>();
        readTree(json).fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asInt()));
        return result;
    }

    /** verdict_reasons：[理由...] → List。 */
    private static List<String> reasons(String json) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : readTree(json)) {
            result.add(item.asText());
        }
        return result;
    }

    private static JsonNode readTree(String json) {
        try {
            return JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalStateException("eval_run JSONB 列解析失败", e);
        }
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 参数序列化失败", e);
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
