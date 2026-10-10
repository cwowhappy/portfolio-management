package com.portfolio.invest.infrastructure.workspace;

import com.portfolio.invest.application.memory.CuratedMutationResult;
import com.portfolio.invest.application.memory.LedgerFileView;
import com.portfolio.invest.application.memory.LedgerSection;
import com.portfolio.invest.application.memory.MemoryConflictException;
import com.portfolio.invest.application.memory.MemoryFileView;
import com.portfolio.invest.application.memory.MemoryFilesView;
import com.portfolio.invest.application.memory.MemoryReadOnlyException;
import com.portfolio.invest.application.memory.MemoryStaleException;
import com.portfolio.invest.application.memory.SectionKind;
import com.portfolio.invest.application.memory.WorkspaceMemoryPort;
import com.portfolio.invest.config.InvestProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * WorkspaceMemoryPort 的文件适配器：读写 harness 库落在工作区的长期记忆 Markdown 文件
 * （{@code <workspace>/<userId>/MEMORY.md} 策展层 + {@code memory/yyyy-MM-dd.md} 当期日账本；
 * {@code archive/}、{@code .consolidation_state} 及工作区其余内容一概不碰——需求 §三 F01 边界）。
 * workspace 根取 {@link InvestProperties.Mcp.Harness#getWorkspace()}，相对路径相对进程 cwd
 * 解析（与 HarnessAgentFactory 装配口径一致）。
 *
 * <p>并发窗口：所有校验与写入基于「单次读快照（hash 与行同源）+ 同目录 temp 文件
 * {@code ATOMIC_MOVE} 覆盖」，与库 consolidation 的读交错不会产出半文件；读后写前的库写入
 * 由 baseHash 三层校验拦截。校验通过到落盘之间仍存在不可消除的微小窗口，属乐观并发既定
 * 语义（交错写入由下一轮读取以 hash 变化暴露）。
 */
@Component
public class WorkspaceMemoryFileAdapter implements WorkspaceMemoryPort {

    private static final String CURATED_FILE = "MEMORY.md";
    private static final String LEDGER_DIR = "memory";
    /** 归一化相似行清除的最小行体长度（短行跳过——匹配过泛，设计规格 §2.2）。 */
    private static final int MIN_PURGE_BODY_LEN = 12;
    /** 日账本文件名 yyyy-MM-dd.md；glob *.md 不递归且不匹配 {@code .consolidation_state} 与 archive/ 子目录。 */
    private static final Pattern LEDGER_FILE_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.md");
    private static final String SECTION_PREFIX = "## ";
    private static final String TITLE_PREFIX = "# ";

    private final InvestProperties.Mcp.Harness harnessConfig;

    public WorkspaceMemoryFileAdapter(InvestProperties props) {
        this.harnessConfig = props.getMcp().getHarness();
    }

    @Override
    public MemoryFilesView load(Long userId) {
        Path userDir = userDir(userId);
        return new MemoryFilesView(loadCurated(userDir), loadLedgers(userDir));
    }

    @Override
    public CuratedMutationResult deleteCuratedLine(Long userId, int lineIndex, String expectLine, String baseHash) {
        Path userDir = userDir(userId);
        Path file = userDir.resolve(CURATED_FILE);
        Snapshot snapshot = readOrEmpty(file);
        validateCurated(snapshot, lineIndex, expectLine, baseHash);

        List<String> rawLines = new ArrayList<>(snapshot.lines());
        String deletedLine = rawLines.remove(rawIndexOf(rawLines, lineIndex));
        atomicWrite(file, rawLines);

        // 两层同步（决议 #2）：跨日账本归一化清除同源 bullet 行，残余由用户节级手删兜底（软保证）
        return new CuratedMutationResult(purgeAcrossLedgers(userDir, normalizeBody(deletedLine)));
    }

    @Override
    public void editCuratedLine(Long userId, int lineIndex, String expectLine, String newLine, String baseHash) {
        Path file = userDir(userId).resolve(CURATED_FILE);
        Snapshot snapshot = readOrEmpty(file);
        validateCurated(snapshot, lineIndex, expectLine, baseHash);

        List<String> rawLines = new ArrayList<>(snapshot.lines());
        rawLines.set(rawIndexOf(rawLines, lineIndex), newLine);
        atomicWrite(file, rawLines);
    }

    @Override
    public void deleteLedgerSection(Long userId, LocalDate date, String sectionHeader, String baseHash) {
        Path file = ledgerFile(userId, date);
        Snapshot snapshot = readOrEmpty(file);
        if (!Objects.equals(snapshot.hash(), baseHash)) {
            throw new MemoryConflictException("日账本已变化（hash 不符），请刷新后重试: " + file.getFileName());
        }
        List<String> rawLines = snapshot.lines();
        int start = rawLines.indexOf(sectionHeader);
        if (start < 0) {
            throw new MemoryStaleException("节已不存在或已变化: " + sectionHeader);
        }
        int end = start + 1;
        while (end < rawLines.size() && !rawLines.get(end).startsWith(SECTION_PREFIX)) {
            end++;
        }
        List<String> kept = new ArrayList<>(rawLines.subList(0, start));
        kept.addAll(rawLines.subList(end, rawLines.size()));
        stripLeadingAndTrailingBlanks(kept);
        if (kept.isEmpty()) {
            deleteFile(file);
        } else {
            atomicWrite(file, kept);
        }
    }

    @Override
    public void appendExternalEvent(Long userId, LocalDate date, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空（须含「## 外部事件摘要 — …」节头与 bullet 行）");
        }
        Path file = ledgerFile(userId, date);
        List<String> out = new ArrayList<>(Files.isRegularFile(file) ? read(file).lines() : List.<String>of());
        stripTrailingBlanks(out);
        List<String> section = new ArrayList<>(content.lines().toList());
        stripTrailingBlanks(section);
        if (!out.isEmpty()) {
            out.add("");   // 节间空行分隔
        }
        out.addAll(section);
        atomicWrite(file, out);
    }

    // ---- load 解析 ----

    private MemoryFileView loadCurated(Path userDir) {
        Path file = userDir.resolve(CURATED_FILE);
        if (!Files.isRegularFile(file)) {
            return new MemoryFileView(null, List.of());
        }
        Snapshot snapshot = read(file);
        List<String> entries = snapshot.lines().stream().filter(line -> !line.isBlank()).toList();
        return new MemoryFileView(snapshot.hash(), entries);
    }

    private List<LedgerFileView> loadLedgers(Path userDir) {
        Path memoryDir = userDir.resolve(LEDGER_DIR);
        if (!Files.isDirectory(memoryDir)) {
            return List.of();
        }
        List<LedgerFileView> ledgers = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(memoryDir, "*.md")) {
            for (Path file : stream) {
                if (!isDatedLedger(file)) {
                    continue;
                }
                LocalDate date;
                try {
                    date = LocalDate.parse(fileNameStem(file));
                } catch (DateTimeParseException e) {
                    continue;   // 命名损坏降级：正则已限定形如日期，此为月/日越界兜底，单文件跳过
                }
                Snapshot snapshot = read(file);
                List<LedgerSection> sections = parseSections(snapshot.lines());
                if (!sections.isEmpty()) {
                    ledgers.add(new LedgerFileView(date, snapshot.hash(), sections));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("日账本目录读取失败: " + memoryDir, e);
        }
        ledgers.sort(Comparator.comparing(LedgerFileView::date).reversed());
        return List.copyOf(ledgers);
    }

    /** 仅当期日期名日账本入管理视图：常规文件 + 非隐藏 + yyyy-MM-dd.md 命名（archive/ 与 .consolidation_state 天然排除）。 */
    private static boolean isDatedLedger(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && !name.startsWith(".") && LEDGER_FILE_NAME.matcher(name).matches();
    }

    /** 节切分：「## 」节头起始；首个节头之前的行（preamble）不入视图。 */
    static List<LedgerSection> parseSections(List<String> rawLines) {
        List<LedgerSection> sections = new ArrayList<>();
        String header = null;
        List<String> body = new ArrayList<>();
        for (String line : rawLines) {
            if (line.startsWith(SECTION_PREFIX)) {
                addSection(sections, header, body);
                header = line;
                body = new ArrayList<>();
            } else if (header != null) {
                body.add(line);
            }
        }
        addSection(sections, header, body);
        return sections;
    }

    private static void addSection(List<LedgerSection> sections, String header, List<String> body) {
        if (header == null) {
            return;
        }
        List<String> lines = new ArrayList<>(body);
        stripTrailingBlanks(lines);
        sections.add(new LedgerSection(header, kindOf(header), List.copyOf(lines)));
    }

    /** kind 判定：「## 外部事件摘要」→ EXTERNAL_EVENT；「## Memory Flush —」与未知节头均归 FLUSH（兜底只读展示）。 */
    private static SectionKind kindOf(String header) {
        if (header.startsWith("## 外部事件摘要")) {
            return SectionKind.EXTERNAL_EVENT;
        }
        return SectionKind.FLUSH;
    }

    // ---- 三层校验与寻址 ----

    /** 三层校验：hash（409）→ 非空行寻址（410）→ 只读标题（400）→ 行内容比对（410）。 */
    private static void validateCurated(Snapshot snapshot, int lineIndex, String expectLine, String baseHash) {
        if (!Objects.equals(snapshot.hash(), baseHash)) {
            throw new MemoryConflictException("记忆文件已变化（hash 不符），请刷新后重试");
        }
        List<String> entries = snapshot.lines().stream().filter(line -> !line.isBlank()).toList();
        if (lineIndex < 0 || lineIndex >= entries.size()) {
            throw new MemoryStaleException("行序已漂移：lineIndex=" + lineIndex + " 超出当前范围（" + entries.size() + " 行）");
        }
        if (entries.get(lineIndex).startsWith(TITLE_PREFIX)) {
            throw new MemoryReadOnlyException("「# 」标题行只读: " + entries.get(lineIndex));
        }
        if (!entries.get(lineIndex).equals(expectLine)) {
            throw new MemoryStaleException("行内容已漂移: lineIndex=" + lineIndex);
        }
    }

    /** lineIndex 按非空行序寻址 → 映射回原始文件行下标（空行在写回时原样保留）。 */
    private static int rawIndexOf(List<String> rawLines, int lineIndex) {
        int seen = 0;
        for (int i = 0; i < rawLines.size(); i++) {
            if (!rawLines.get(i).isBlank()) {
                if (seen == lineIndex) {
                    return i;
                }
                seen++;
            }
        }
        throw new MemoryStaleException("行序已漂移: lineIndex=" + lineIndex);
    }

    // ---- 归一化两层删 ----

    /** 跨全部日账本清除与被删行归一化互为子串的 bullet 行，返回清除计数（尽力清除）。 */
    private int purgeAcrossLedgers(Path userDir, String deletedNorm) {
        Path memoryDir = userDir.resolve(LEDGER_DIR);
        if (deletedNorm.isBlank() || !Files.isDirectory(memoryDir)) {
            return 0;
        }
        int purged = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(memoryDir, "*.md")) {
            for (Path file : stream) {
                if (!isDatedLedger(file)) {
                    continue;
                }
                List<String> rawLines = read(file).lines();
                List<String> kept = new ArrayList<>(rawLines.size());
                for (String line : rawLines) {
                    if (shouldPurge(line, deletedNorm)) {
                        purged++;
                    } else {
                        kept.add(line);
                    }
                }
                if (kept.size() != rawLines.size()) {
                    atomicWrite(file, kept);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("日账本归一化清除失败: " + memoryDir, e);
        }
        return purged;
    }

    /** 清除条件：bullet 行 + 归一化后与被删行互为子串 + 行体 ≥12 字符（短行跳过）。 */
    static boolean shouldPurge(String rawLine, String deletedNorm) {
        String norm = normalize(rawLine);
        if (!norm.startsWith("- ") && !norm.startsWith("* ")) {
            return false;   // 只清 bullet 行：节头与叙述行原样保留
        }
        String body = norm.substring(2).strip();
        if (body.length() < MIN_PURGE_BODY_LEN) {
            return false;
        }
        return body.contains(deletedNorm) || deletedNorm.contains(body);
    }

    /** 归一化：strip + 全角→半角（U+FF01..U+FF5E 偏移 0xFEE0；全角空格 U+3000 → 半角）+ 连续空白折叠为单空格。 */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.strip());
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.setCharAt(i, (char) (c - 0xFEE0));
            } else if (c == 0x3000) {
                sb.setCharAt(i, ' ');
            }
        }
        return sb.toString().replaceAll("\\s+", " ").strip();
    }

    /** 匹配用行体：归一化后剥离 bullet 前缀（「- 」/「* 」）。 */
    static String normalizeBody(String rawLine) {
        String norm = normalize(rawLine);
        if (norm.startsWith("- ") || norm.startsWith("* ")) {
            norm = norm.substring(2);
        }
        return norm.strip();
    }

    // ---- 文件 IO ----

    private Path userDir(Long userId) {
        // userId 为 Long（无路径分隔符语义）；Path.resolve 拼接，不做字符串串联
        return Paths.get(harnessConfig.getWorkspace()).resolve(String.valueOf(userId));
    }

    private Path ledgerFile(Long userId, LocalDate date) {
        return userDir(userId).resolve(LEDGER_DIR).resolve(date + ".md");
    }

    private static String fileNameStem(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".md".length());
    }

    /** 读时单次快照：hash 与行内容同源（同一字节），避免 hash 与内容两次读取间的窗口。 */
    private Snapshot read(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return new Snapshot(sha256(bytes), new String(bytes, StandardCharsets.UTF_8).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException("记忆文件读取失败: " + file, e);
        }
    }

    private Snapshot readOrEmpty(Path file) {
        return Files.isRegularFile(file) ? read(file) : new Snapshot(null, List.of());
    }

    /** 原子写：同目录 temp 文件 + ATOMIC_MOVE 覆盖（防与库 consolidation 读交错产出半文件）。 */
    private static void atomicWrite(Path target, List<String> lines) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tmp, lines.isEmpty() ? "" : String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("记忆文件写入失败: " + target, e);
        }
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("记忆文件删除失败: " + file, e);
        }
    }

    private static void stripLeadingAndTrailingBlanks(List<String> lines) {
        stripTrailingBlanks(lines);
        while (!lines.isEmpty() && lines.getFirst().isBlank()) {
            lines.removeFirst();
        }
    }

    private static void stripTrailingBlanks(List<String> lines) {
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺 SHA-256 消息摘要算法", e);
        }
    }

    private record Snapshot(String hash, List<String> lines) {}
}
