package com.portfolio.invest.domain.wiki;

import java.util.List;
import java.util.Optional;

/** 知识库条目仓库端口：归属过滤（userId）在用例层双重保障。 */
public interface WikiEntryRepository {
    List<WikiEntry> findByUserId(Long userId, WikiEntryType type);

    /** 按研究项目反查该用户条目（project_id 软引用，F08 项目详情关联区用）。 */
    List<WikiEntry> findByUserIdAndProjectId(Long userId, Long projectId);

    Optional<WikiEntry> findByIdAndUserId(Long id, Long userId);
    WikiEntry save(WikiEntry entry);
    void deleteById(Long id);
}
