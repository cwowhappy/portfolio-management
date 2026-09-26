package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.FundingRound;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 策展企业 CSV 解析层（设计规格 §五 L1/L2，照 CsvImportParser 结构）：UTF-8 字符串 +
 * RFC 4180（容忍 BOM，字符解码由调用方完成）。L1 文件级：表头八列精确匹配、数据行 ≤2000、
 * 存在数据行；L2 行级：必填列（industry_code/company_name/latest_round）非空、
 * latest_round ∈ FundingRound 枚举、last_funding_date 严格 yyyy-MM-dd 且 ≤ 今日、
 * total_funding_yi NUMERIC(14,2)（整数位 ≥10^12 拒绝）、文本列按 V19 列长上限校验
 * （超长报行错误，不待落库约束炸）。所有行错误一次聚齐返回（rows 与 errors 互斥——有错误即全量
 * 拒绝，与编排层 all-or-nothing 口径一致，不做首错即停）。
 * 行号 = CSV 记录序号（表头为 1、首个数据行为 2；被忽略的空行不计）。
 * L3 引用（行业码白名单）与 L5 幂等键重复在编排层 {@link IndustryCurationImportService}。
 */
public final class UnlistedCompanyCsvParser {

    /** 文件级错误行号（无具体物理行可指）。 */
    static final int FILE_LEVEL_ROW = 0;

    /** 数据行上限（设计规格 §五 L1）。 */
    static final int MAX_DATA_ROWS = 2000;

    /** 八列固定顺序表头（设计规格 §五模板即权威口径）。 */
    static final List<String> HEADER = List.of(
            "industry_code", "company_name", "segment", "latest_round",
            "last_funding_date", "total_funding_yi", "summary", "source_note");

    private static final String ALLOWED_ROUNDS =
            "SEED/ANGEL/PRE_A/A/A_PLUS/B/B_PLUS/C/C_PLUS/D/STRATEGIC/PRE_IPO/IPO/ACQUIRED/UNKNOWN";

    private static final int COL_INDUSTRY_CODE = 0;
    private static final int COL_COMPANY_NAME = 1;
    private static final int COL_SEGMENT = 2;
    private static final int COL_LATEST_ROUND = 3;
    private static final int COL_LAST_FUNDING_DATE = 4;
    private static final int COL_TOTAL_FUNDING_YI = 5;
    private static final int COL_SUMMARY = 6;
    private static final int COL_SOURCE_NOTE = 7;

    /** 文本列长上限（V19 DDL，照 CsvImportParser MAX_NOTE_LENGTH 先例：L2 前置拦截超长，给人话行错误）。 */
    private static final int MAX_INDUSTRY_CODE_LENGTH = 16;
    private static final int MAX_COMPANY_NAME_LENGTH = 128;
    private static final int MAX_SEGMENT_LENGTH = 64;
    private static final int MAX_SUMMARY_LENGTH = 256;
    private static final int MAX_SOURCE_NOTE_LENGTH = 128;

    /** total_funding_yi NUMERIC(14,2) 整数位溢出阈值：|值| ≥ 10^12（12 位整数满额后再进位即溢出）。 */
    private static final BigDecimal NUMERIC_OVERFLOW_THRESHOLD = new BigDecimal("1000000000000");

    /** 单行解析产物（行号 + 八列；可选列空白落 null）。 */
    public record ParsedCompany(int rowNumber, String industryCode, String companyName, String segment,
                                FundingRound latestRound, LocalDate lastFundingDate,
                                BigDecimal totalFundingYi, String summary, String sourceNote) {}

    /** 解析产物：全部合法行，或聚齐的错误清单（互斥——有错误时 rows 为空 List）。 */
    public record ParseOutcome(List<ParsedCompany> rows, List<CurationImportResult.RowError> errors) {

        static ParseOutcome ok(List<ParsedCompany> rows) {
            return new ParseOutcome(rows, List.of());
        }

        static ParseOutcome failed(List<CurationImportResult.RowError> errors) {
            return new ParseOutcome(List.of(), errors);
        }
    }

    public ParseOutcome parse(String content, LocalDate today) {
        List<CSVRecord> records = readRecords(Objects.requireNonNullElse(content, ""));
        // L1 文件级：零记录（空内容/全空行）或唯一记录即表头（仅表头）都无数据行
        if (records.size() <= 1) {
            return ParseOutcome.failed(List.of(rowError(FILE_LEVEL_ROW, "无数据行")));
        }
        // L1 文件级：表头八列精确匹配（BOM 已在读入前剥离）
        if (!records.get(0).toList().equals(HEADER)) {
            return ParseOutcome.failed(List.of(rowError(FILE_LEVEL_ROW,
                    "表头与模板不符（期望：" + String.join(",", HEADER) + "）")));
        }
        // L1 文件级：数据行超上限
        int dataRowCount = records.size() - 1;
        if (dataRowCount > MAX_DATA_ROWS) {
            return ParseOutcome.failed(List.of(rowError(FILE_LEVEL_ROW,
                    "数据行超过上限（最多 " + MAX_DATA_ROWS + " 行，实际 " + dataRowCount + " 行）")));
        }
        // L2 行级：逐行校验，错误聚齐
        List<ParsedCompany> rows = new ArrayList<>();
        List<CurationImportResult.RowError> errors = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            CSVRecord record = records.get(i);
            int rowNumber = (int) record.getRecordNumber();
            if (record.size() != HEADER.size()) {
                errors.add(rowError(rowNumber, "列数不符（期望 " + HEADER.size() + " 列，实际 "
                        + record.size() + " 列）"));
                continue;
            }
            RowOutcome outcome = parseRow(rowNumber, record, today);
            errors.addAll(outcome.errors());
            if (outcome.row() != null) {
                rows.add(outcome.row());
            }
        }
        return errors.isEmpty() ? ParseOutcome.ok(List.copyOf(rows)) : ParseOutcome.failed(errors);
    }

    /** 单行校验产物：合法行或该行的问题清单（二者互斥）。 */
    private record RowOutcome(ParsedCompany row, List<CurationImportResult.RowError> errors) {}

    private RowOutcome parseRow(int rowNumber, CSVRecord record, LocalDate today) {
        List<String> problems = new ArrayList<>();
        String industryCode = record.get(COL_INDUSTRY_CODE);
        String companyName = record.get(COL_COMPANY_NAME);
        String latestRoundRaw = record.get(COL_LATEST_ROUND);
        requireNonBlank(industryCode, "行业代码不能为空", problems);
        requireNonBlank(companyName, "企业名称不能为空", problems);
        FundingRound latestRound = null;
        if (!isBlank(latestRoundRaw)) {
            latestRound = parseRound(latestRoundRaw, problems);
        } else {
            problems.add("最新轮次不能为空");
        }
        LocalDate lastFundingDate = parseDate(record.get(COL_LAST_FUNDING_DATE), problems);
        if (lastFundingDate != null && lastFundingDate.isAfter(today)) {
            problems.add("最近融资日期不能晚于今日");
        }
        BigDecimal totalFundingYi = optionalDecimal(record.get(COL_TOTAL_FUNDING_YI), "累计融资额", problems);
        requireMaxLength(industryCode, "行业代码", MAX_INDUSTRY_CODE_LENGTH, problems);
        requireMaxLength(companyName, "企业名称", MAX_COMPANY_NAME_LENGTH, problems);
        requireMaxLength(record.get(COL_SEGMENT), "细分赛道", MAX_SEGMENT_LENGTH, problems);
        requireMaxLength(record.get(COL_SUMMARY), "简介", MAX_SUMMARY_LENGTH, problems);
        requireMaxLength(record.get(COL_SOURCE_NOTE), "来源标注", MAX_SOURCE_NOTE_LENGTH, problems);
        if (!problems.isEmpty()) {
            List<CurationImportResult.RowError> errors = problems.stream()
                    .map(problem -> rowError(rowNumber, problem))
                    .toList();
            return new RowOutcome(null, errors);
        }
        return new RowOutcome(new ParsedCompany(rowNumber,
                industryCode.strip(), companyName.strip(), blankToNull(record.get(COL_SEGMENT)),
                latestRound, lastFundingDate, totalFundingYi,
                blankToNull(record.get(COL_SUMMARY)), blankToNull(record.get(COL_SOURCE_NOTE))), List.of());
    }

    /** L2 轮次白名单：枚举名（下划线大写、大小写敏感，设计规格 §三）。 */
    private static FundingRound parseRound(String raw, List<String> problems) {
        try {
            return FundingRound.parse(raw);
        } catch (IllegalArgumentException e) {
            problems.add("轮次无效 " + raw + "（允许：" + ALLOWED_ROUNDS + "）");
            return null;
        }
    }

    /** L2 日期：严格 yyyy-MM-dd（ISO LOCAL_DATE），空=未知放行。 */
    private static LocalDate parseDate(String raw, List<String> problems) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            problems.add("日期不可解析「" + raw + "」（格式须为 yyyy-MM-dd）");
            return null;
        }
    }

    /** L2 金额：可选 NUMERIC(14,2)，空=未披露；整数位 ≥10^12 落库必溢出，前置报行错误。 */
    private static BigDecimal optionalDecimal(String raw, String column, List<String> problems) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.strip());
            if (value.abs().compareTo(NUMERIC_OVERFLOW_THRESHOLD) >= 0) {
                problems.add(column + "超出 NUMERIC(14,2) 范围「" + raw + "」");
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            problems.add(column + "列数值无效「" + raw + "」");
            return null;
        }
    }

    private static void requireNonBlank(String raw, String message, List<String> problems) {
        if (isBlank(raw)) {
            problems.add(message);
        }
    }

    /** L2 列长上限（V19 DDL）：超长在解析层报人话错误，避免落库才炸。 */
    private static void requireMaxLength(String raw, String column, int maxLength, List<String> problems) {
        if (raw != null && raw.length() > maxLength) {
            problems.add(column + "超长（≤" + maxLength + " 字符）");
        }
    }

    private static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    private static String blankToNull(String raw) {
        return isBlank(raw) ? null : raw;
    }

    private static CurationImportResult.RowError rowError(int row, String reason) {
        return new CurationImportResult.RowError(row, reason);
    }

    private static List<CSVRecord> readRecords(String content) {
        // 预设 HEADER 名映射但【不】跳过首记录——L1 要求表头八列精确匹配，须读到首记录比对
        //（skipHeaderRecord=true 会让首记录从 getRecords() 消失，表头校验无从做起）
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(HEADER.toArray(new String[0]))
                .setIgnoreEmptyLines(true)
                .build();
        try (CSVParser csvParser = new CSVParser(new StringReader(stripBom(content)), format)) {
            return csvParser.getRecords();
        } catch (IOException e) {
            // StringReader 无 I/O 源，不会抛出；防御性转为运行时异常
            throw new IllegalStateException("CSV 读取失败", e);
        }
    }

    /** 剥离 UTF-8 BOM（写模板带 BOM、读侧容忍，设计规格 §五口径）。 */
    private static String stripBom(String content) {
        return content.startsWith("﻿") ? content.substring(1) : content;
    }
}
