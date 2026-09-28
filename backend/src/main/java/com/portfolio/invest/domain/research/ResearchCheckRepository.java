package com.portfolio.invest.domain.research;

import java.util.List;

/**
 * 纪律检查留痕 + 证伪命中留痕仓库端口（M16-F10/F12，NFR-2 append-only）：
 * <b>只提供 insert 与读方法，不暴露任何 update</b>（domain 侧亦无更新用例）。
 * 证伪命中落表口径见 {@link FalsifierHit}（Ruling-18：仅 PREDICATE 命中行）。
 */
public interface ResearchCheckRepository {

    /** 追加一条检查留痕（CheckRecord.create 已完成域校验）。 */
    CheckRecord insert(CheckRecord record);

    /** 追加一条证伪命中留痕（日终扫描/实时命中落历史，T5 消费）。 */
    FalsifierHit insertHit(FalsifierHit hit);

    /** 项目的证伪命中留痕（createdAt 倒序，与实时求值合并展示用，D21）。 */
    List<FalsifierHit> findHits(Long projectId);
}
