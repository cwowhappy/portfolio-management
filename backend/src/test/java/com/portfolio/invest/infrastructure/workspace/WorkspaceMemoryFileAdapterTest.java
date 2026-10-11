package com.portfolio.invest.infrastructure.workspace;

import static com.portfolio.invest.application.memory.SectionKind.EXTERNAL_EVENT;
import static com.portfolio.invest.application.memory.SectionKind.FLUSH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.application.memory.CuratedMutationResult;
import com.portfolio.invest.application.memory.LedgerFileView;
import com.portfolio.invest.application.memory.LedgerSection;
import com.portfolio.invest.application.memory.MemoryConflictException;
import com.portfolio.invest.application.memory.MemoryFilesView;
import com.portfolio.invest.application.memory.MemoryReadOnlyException;
import com.portfolio.invest.application.memory.MemoryStaleException;
import com.portfolio.invest.application.memory.WorkspaceMemoryPort;
import com.portfolio.invest.config.InvestProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorkspaceMemoryFileAdapter 单元层（tmpdir 真文件）：解析口径（策展层非空行/日账本节/kind 判定）、
 * 三层乐观并发校验（hash 409 / 行漂移 410 / 只读标题 400）、归一化两层删（≥12 阈值/短行跳过/跨文件计数）、
 * append 建文件语义、隐藏文件与 archive 排除。
 */
class WorkspaceMemoryFileAdapterTest {

    /** 全角连字符（U+FF0D）与全角空格（U+3000）：归一化匹配测试的变体字符。 */
    private static final String FW_DASH = "－";
    private static final String FW_SPACE = "　";

    private static final Long USER = 42L;
    private static final LocalDate OCT_9 = LocalDate.of(2026, 10, 9);

    @TempDir
    Path tempDir;

    private WorkspaceMemoryPort port;

    @BeforeEach
    void setUp() {
        InvestProperties props = new InvestProperties();
        props.getMcp().getHarness().setWorkspace(tempDir.toString());
        port = new WorkspaceMemoryFileAdapter(props);
    }

    @DisplayName("load 解析策展层非空行与日账本节，kind 按节头前缀判定")
    @Test
    void givenCuratedAndLedgerFiles_whenLoad_thenLinesAndSectionsParsedWithKind() throws IOException {
        write("MEMORY.md", "# MEMORY.md — Curated Long-Term Memory\n\n## 偏好\n- 偏好白酒股\n");
        write("memory/2026-10-09.md",
                "\n## Memory Flush — 2026-10-09T13:58:45Z\n这是要点\n\n## 外部事件摘要 — 2026-10-10（a ~ b）\n- 买入 600519\n");

        MemoryFilesView view = port.load(USER);

        assertThat(view.curated().hash()).isNotBlank();
        assertThat(view.curated().lines())
                .containsExactly("# MEMORY.md — Curated Long-Term Memory", "## 偏好", "- 偏好白酒股");
        assertThat(view.ledgers()).hasSize(1);
        LedgerFileView ledger = view.ledgers().get(0);
        assertThat(ledger.date()).isEqualTo(OCT_9);
        assertThat(ledger.hash()).isNotBlank();
        assertThat(ledger.sections())
                .extracting(LedgerSection::kind)
                .containsExactly(FLUSH, EXTERNAL_EVENT);
        assertThat(ledger.sections().get(0).header()).isEqualTo("## Memory Flush — 2026-10-09T13:58:45Z");
        assertThat(ledger.sections().get(0).lines()).containsExactly("这是要点");
        assertThat(ledger.sections().get(1).header()).isEqualTo("## 外部事件摘要 — 2026-10-10（a ~ b）");
        assertThat(ledger.sections().get(1).lines()).containsExactly("- 买入 600519");
    }

    @DisplayName("删策展层行 → 日账本归一化匹配 bullet 行跨文件清除，短行跳过，计数如实")
    @Test
    void givenLedgerVariants_whenDeleteCuratedLine_thenNormalizedMatchesPurgedWithCount() throws IOException {
        write("MEMORY.md", "# MEMORY.md — Curated Long-Term Memory\n\n## 偏好\n- 偏好白酒股\n");
        // 两个日账本：全角变体（≥12 清除×2）、空白变体（≥12 清除×1）、短于 12 阈值变体（保留）、
        // 无关长行（≥12 但不匹配，保留）
        write("memory/2026-10-09.md", """
                ## Memory Flush — 2026-10-09T13:58:45Z
                %s%s用户长期偏好白酒股，逢低加仓
                - 每周复盘自选股列表并调整仓位提醒
                %s%s偏好白酒股
                ## Memory Flush — 2026-10-09T20:00:00Z
                -  偏好白酒股，近期逢低加仓白酒仓位%s
                """.formatted(FW_DASH, FW_SPACE, FW_DASH, FW_SPACE, FW_SPACE));
        write("memory/2026-10-08.md", """
                ## Memory Flush — 2026-10-08T09:00:00Z
                %s%s%s长期偏好白酒股的波段操作，仓位上限两成
                """.formatted(FW_DASH, FW_SPACE, FW_SPACE));
        MemoryFilesView view = port.load(USER);

        CuratedMutationResult result =
                port.deleteCuratedLine(USER, 2, "- 偏好白酒股", view.curated().hash());

        assertThat(result.purgedLedgerLines()).isEqualTo(3);
        MemoryFilesView after = port.load(USER);
        assertThat(after.curated().lines())
                .containsExactly("# MEMORY.md — Curated Long-Term Memory", "## 偏好");
        assertThat(after.ledgers()).extracting(LedgerFileView::date)
                .containsExactly(OCT_9, LocalDate.of(2026, 10, 8));
        LedgerFileView day9 = after.ledgers().get(0);
        assertThat(day9.sections()).hasSize(2);
        assertThat(day9.sections().get(0).lines())
                .containsExactly("- 每周复盘自选股列表并调整仓位提醒", FW_DASH + FW_SPACE + "偏好白酒股");
        assertThat(day9.sections().get(1).lines()).isEmpty();
        assertThat(after.ledgers().get(1).sections().get(0).lines()).isEmpty();
    }

    @DisplayName("读取后文件被并发写入（hash 不符）→ 删除抛 409 冲突")
    @Test
    void givenFileChangedSinceRead_whenDeleteCuratedLine_thenConflict() throws IOException {
        write("MEMORY.md", "# MEMORY.md — Curated Long-Term Memory\n\n## 偏好\n- 偏好白酒股\n");
        MemoryFilesView view = port.load(USER);

        appendConcurrently("MEMORY.md", "## 新节\n");

        assertThatThrownBy(() -> port.deleteCuratedLine(USER, 2, "- 偏好白酒股", view.curated().hash()))
                .isInstanceOf(MemoryConflictException.class);
    }

    @DisplayName("编辑命中 # 标题行 → 抛只读拒绝且文件原样")
    @Test
    void givenTitleLineTargeted_whenEditCuratedLine_thenReadOnlyRejected() throws IOException {
        write("MEMORY.md", "# MEMORY.md — Curated Long-Term Memory\n\n## 偏好\n- 偏好白酒股\n");
        MemoryFilesView view = port.load(USER);

        assertThatThrownBy(() -> port.editCuratedLine(USER, 0,
                "# MEMORY.md — Curated Long-Term Memory", "- 新行", view.curated().hash()))
                .isInstanceOf(MemoryReadOnlyException.class);

        assertThat(port.load(USER).curated().lines())
                .containsExactly("# MEMORY.md — Curated Long-Term Memory", "## 偏好", "- 偏好白酒股");
    }

    @DisplayName("行漂移（内容不符/越界）→ 编辑抛过期异常")
    @Test
    void givenLineDriftedOrOutOfRange_whenEditCuratedLine_thenStale() throws IOException {
        write("MEMORY.md", "# MEMORY.md — Curated Long-Term Memory\n\n## 偏好\n- 偏好白酒股\n");
        MemoryFilesView view = port.load(USER);

        assertThatThrownBy(() -> port.editCuratedLine(USER, 2, "- 已被改写的旧行", "- 新行", view.curated().hash()))
                .isInstanceOf(MemoryStaleException.class);
        assertThatThrownBy(() -> port.editCuratedLine(USER, 9, "- 偏好白酒股", "- 新行", view.curated().hash()))
                .isInstanceOf(MemoryStaleException.class);
    }

    @DisplayName("hash 与行校验通过 → 编辑替换该行且空行原样保留")
    @Test
    void givenMatchingBaseHashAndLine_whenEditCuratedLine_thenLineReplacedWithBlanksKept() throws IOException {
        write("MEMORY.md", "# MEMORY.md — Curated Long-Term Memory\n\n## 偏好\n- 偏好白酒股\n");
        MemoryFilesView view = port.load(USER);

        port.editCuratedLine(USER, 2, "- 偏好白酒股", "- 偏好白酒股与医药股", view.curated().hash());

        assertThat(read("MEMORY.md"))
                .isEqualTo("# MEMORY.md — Curated Long-Term Memory\n\n## 偏好\n- 偏好白酒股与医药股\n");
    }

    @DisplayName("appendExternalEvent 无文件则建当日账本，已有则追加节")
    @Test
    void givenNoLedgerFile_whenAppendExternalEvent_thenDailyFileCreatedAndSectionAppended() throws IOException {
        port.appendExternalEvent(USER, LocalDate.of(2026, 10, 10),
                "## 外部事件摘要 — 2026-10-10（a ~ b）\n- 买入 600519\n- 卖出 000858\n");

        MemoryFilesView first = port.load(USER);
        assertThat(first.ledgers()).hasSize(1);
        assertThat(first.ledgers().get(0).date()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(first.ledgers().get(0).sections()).hasSize(1);
        assertThat(first.ledgers().get(0).sections().get(0).kind()).isEqualTo(EXTERNAL_EVENT);
        assertThat(first.ledgers().get(0).sections().get(0).lines())
                .containsExactly("- 买入 600519", "- 卖出 000858");

        port.appendExternalEvent(USER, LocalDate.of(2026, 10, 10),
                "## 外部事件摘要 — 2026-10-10（c ~ d）\n- 加仓 600519\n");

        assertThat(port.load(USER).ledgers().get(0).sections())
                .extracting(LedgerSection::header)
                .containsExactly("## 外部事件摘要 — 2026-10-10（a ~ b）", "## 外部事件摘要 — 2026-10-10（c ~ d）");
    }

    @DisplayName("无任何记忆文件 → 空视图（hash=null、lines=[]、ledgers=[]）")
    @Test
    void givenNoFiles_whenLoad_thenEmptyView() {
        MemoryFilesView view = port.load(USER);

        assertThat(view.curated().hash()).isNull();
        assertThat(view.curated().lines()).isEmpty();
        assertThat(view.ledgers()).isEmpty();
    }

    @DisplayName("隐藏文件、archive 子目录与非日期名文件不入视图")
    @Test
    void givenArchiveHiddenAndUnparseableFiles_whenLoad_thenOnlyDatedLedgersVisible() throws IOException {
        write("MEMORY.md", "# MEMORY.md — Curated Long-Term Memory\n\n- 偏好白酒股\n");
        write("memory/2026-10-09.md", "## Memory Flush — 2026-10-09T13:58:45Z\n- 要点\n");
        write("memory/.consolidation_state", "{\"v\":1}");
        write("memory/.hidden.md", "# 隐藏\n");
        write("memory/notes.md", "非日期名");
        write("memory/2026-13-99.md", "## 非法日期\n- x\n");
        write("memory/archive/2026-01-01.md", "## Memory Flush — 旧\n- 归档\n");

        MemoryFilesView view = port.load(USER);

        assertThat(view.ledgers()).extracting(LedgerFileView::date).containsExactly(OCT_9);
    }

    @DisplayName("节删除 → 整节移除（节头+行），其余节原样")
    @Test
    void givenTwoSections_whenDeleteLedgerSection_thenOnlyThatSectionRemoved() throws IOException {
        write("memory/2026-10-09.md",
                "\n## Memory Flush — 2026-10-09T13:58:45Z\n这是要点\n\n## 外部事件摘要 — 2026-10-10（a ~ b）\n- 买入 600519\n");
        MemoryFilesView view = port.load(USER);

        port.deleteLedgerSection(USER, OCT_9, "## Memory Flush — 2026-10-09T13:58:45Z",
                view.ledgers().get(0).hash());

        MemoryFilesView after = port.load(USER);
        assertThat(after.ledgers()).hasSize(1);
        assertThat(after.ledgers().get(0).sections()).hasSize(1);
        assertThat(after.ledgers().get(0).sections().get(0).kind()).isEqualTo(EXTERNAL_EVENT);
        assertThat(read("memory/2026-10-09.md"))
                .isEqualTo("## 外部事件摘要 — 2026-10-10（a ~ b）\n- 买入 600519\n");
    }

    @DisplayName("日账本节删除读取后文件变化 → 抛 409 冲突")
    @Test
    void givenLedgerChangedSinceRead_whenDeleteLedgerSection_thenConflict() throws IOException {
        write("memory/2026-10-09.md", "## Memory Flush — 2026-10-09T13:58:45Z\n- 要点\n");
        MemoryFilesView view = port.load(USER);

        appendConcurrently("memory/2026-10-09.md", "- 新增行\n");

        assertThatThrownBy(() -> port.deleteLedgerSection(USER, OCT_9,
                "## Memory Flush — 2026-10-09T13:58:45Z", view.ledgers().get(0).hash()))
                .isInstanceOf(MemoryConflictException.class);
    }

    @DisplayName("唯一节删除后文件清空移除，再删同节 → 抛过期异常")
    @Test
    void givenOnlySectionDeleted_whenDeleteLedgerSectionAgain_thenFileGoneAndStale() throws IOException {
        write("memory/2026-10-09.md", "## Memory Flush — 2026-10-09T13:58:45Z\n- 要点\n");
        MemoryFilesView view = port.load(USER);

        port.deleteLedgerSection(USER, OCT_9, "## Memory Flush — 2026-10-09T13:58:45Z",
                view.ledgers().get(0).hash());

        assertThat(port.load(USER).ledgers()).isEmpty();
        assertThat(Files.exists(tempDir.resolve(String.valueOf(USER)).resolve("memory/2026-10-09.md")))
                .isFalse();
        assertThatThrownBy(() -> port.deleteLedgerSection(USER, OCT_9,
                "## Memory Flush — 2026-10-09T13:58:45Z", null))
                .isInstanceOf(MemoryStaleException.class);
    }

    private Path userDir() {
        return tempDir.resolve(String.valueOf(USER));
    }

    private void write(String relPath, String content) throws IOException {
        Path path = userDir().resolve(relPath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String read(String relPath) throws IOException {
        return Files.readString(userDir().resolve(relPath), StandardCharsets.UTF_8);
    }

    /** 模拟 harness 库在并发窗口内的追加写入（不经端口）。 */
    private void appendConcurrently(String relPath, String content) throws IOException {
        Files.writeString(userDir().resolve(relPath), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
