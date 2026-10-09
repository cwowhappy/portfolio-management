package com.portfolio.invest.domain.eval;

import java.util.List;
import java.util.Optional;

/**
 * eval 运行留痕仓库端口（infrastructure/persistence/eval 实现，风格 A JdbcTemplate——
 * 写入仅调度触发与收割两处，不建 JPA 门面）：RUNNING 行在触发时先插（triggeredBy 落
 * 调度侧真值），收割时 update 全列；查询服务基线行与历史序上一跑（回归判定/恢复判定的
 * 输入通道）。
 */
public interface EvalRunRepository {

    /**
     * 触发时插入 RUNNING 行（started_at 由库端 DEFAULT now() 生成）。
     *
     * @return 自增主键（runId，收割/看护的关联键）
     */
    long insertRunning(String triggeredBy, String reportPath);

    /** 收割 update 全列（判定先于本调用组装，见 {@link EvalRunHarvest} javadoc）。 */
    void updateHarvested(long id, EvalRunHarvest harvest);

    /** 兜底终态：子进程启动失败/收割异常时标 FAILED + 理由留痕（finished_at=now()）。 */
    void markFailed(long id, List<String> reasons);

    /** 当前基准行（baseline=true，部分唯一索引保证至多一行；无基准返回 empty → 首跑语义）。 */
    Optional<EvalRunRow> findBaseline();

    /** 历史序上一跑（started_at DESC, id DESC 排除指定 id 的最近一行；恢复判定的输入来源）。 */
    Optional<EvalRunRow> findLatestExcluding(long runId);

    /**
     * 按 id 定位运行行（admin baseline 置位前的资格校验输入；缺失→调用方抛 NOT_FOUND）。
     * MS-30 B5 扩展。
     */
    Optional<EvalRunRow> findById(long id);

    /**
     * 运行历史倒序（started_at DESC, id DESC 截 limit 行；RUNNING 行原样返回——停机残留
     * 清扫归部署文档，查询侧不过滤）。MS-30 B5 扩展。
     */
    List<EvalRunRow> findRecent(int limit);

    /** 清除当前基准行（置位新基准前先调，恒一基准部分唯一索引的前提；MS-30 B5 扩展）。 */
    void clearBaseline();

    /** 置/清指定行 baseline 标记（与 {@link #clearBaseline} 同事务内先清后置；MS-30 B5 扩展）。 */
    void updateBaseline(long id, boolean baseline);
}
