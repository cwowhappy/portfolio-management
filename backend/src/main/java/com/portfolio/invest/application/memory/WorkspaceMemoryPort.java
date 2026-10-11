package com.portfolio.invest.application.memory;

import java.time.LocalDate;

/**
 * 工作区长期记忆读写端口（infrastructure/workspace 实现，设计规格 §2.1/§2.2）：harness 库把
 * 长期记忆落成工作区 Markdown 文件（策展层 {@code <workspace>/<userId>/MEMORY.md} + 日账本
 * {@code memory/yyyy-MM-dd.md}），本端口为管理链（MemoryController/MemoryManagementApplicationService）
 * 与摘要链（F03）提供后端自有读写通道。
 *
 * <p>解析口径：策展层行级——非空行即条目（{@code lineIndex} 按文件内行序编号、含标题行，
 * 「# 」标题行只读保护）；日账本节级——「## 」节头切分，整节为操作单元（flush 节是原子语义单元，不拆行）。
 *
 * <p>乐观并发三层校验（写入前重读重算）：{@code baseHash}（整文件 sha-256，不符 →
 * {@link MemoryConflictException}，web 层 409）→ 寻址（lineIndex/行内容/节头漂移 →
 * {@link MemoryStaleException}，web 层 410）→ 只读标题（{@link MemoryReadOnlyException}，web 层 400）。
 * IO 失败以 {@link java.io.UncheckedIOException} 抛出（web 层映射 503 语义）。
 */
public interface WorkspaceMemoryPort {

    /** 策展层 + 当期日账本全视图（各文件 sha-256；文件缺失 → hash=null、lines=[]；archive 与隐藏文件排除）。 */
    MemoryFilesView load(Long userId);

    /**
     * 删策展层行（决议 #2 两层同步）：删行后对全部日账本执行归一化相似行清除——行文本
     * strip + 全角→半角 + 连续空白折叠后与被删行互为子串、且行体 ≥12 字符的 bullet 行才清除
     * （短行跳过——匹配过泛），返回清除计数。尽力清除为软保证：LLM 改写导致匹配不全的残余
     * 由用户在日账本分栏节级手删兜底。
     */
    CuratedMutationResult deleteCuratedLine(Long userId, int lineIndex, String expectLine, String baseHash);

    /** 编辑策展层行（仅替换该行；不做日账本同步清除——两层同步是删除独有的决议语义）。 */
    void editCuratedLine(Long userId, int lineIndex, String expectLine, String newLine, String baseHash);

    /**
     * 删日账本整节（节头 + 节内全部行）。
     *
     * @param sectionHeader 视图中的完整节头行（如 {@code ## Memory Flush — 2026-10-09T13:58:45Z}）
     */
    void deleteLedgerSection(Long userId, LocalDate date, String sectionHeader, String baseHash);

    /**
     * 追加外部事件节到指定日期账本（文件不存在则创建，含 memory/ 目录；F03 摘要链消费）。
     *
     * @param content 完整节文本：含「## 外部事件摘要 — …」节头行与 bullet 行（节头由调用方
     *                格式化——窗口起止信息在摘要链侧）
     */
    void appendExternalEvent(Long userId, LocalDate date, String content);
}
