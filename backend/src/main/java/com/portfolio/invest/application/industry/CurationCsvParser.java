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
 * 策展 CSV 解析骨架（2026-10-12 审查 §三中1 复制粘贴簇归一）：融资事件/策展企业两解析器
 * 照同一设计规格 §五 L1/L2 誊写，共同部分收敛于此——L1 文件级门禁（无数据行/表头精确匹配/
 * 数据行 ≤2000）、RFC 4180 读入（容忍 BOM、忽略空行，行号 = CSV 记录序号）、L2 行级校验助手
 * （必填/列长/日期/金额 NUMERIC(14,2) 溢出阈值/轮次枚举白名单）与「错误聚齐、rows 与 errors
 * 互斥」收口。差异点（表头列清单、列索引、本模板特有的列长上限、单行字段校验与产物类型）
 * 由子类提供——子类实现 {@link #header()} 与 {@link RowParser} 行校验（通常即自身的
 * {@code parseRow} 方法），并以 {@code parse(content, today, this::parseRow)} 调本骨架的
 * 公共入口。L3 引用（行业码白名单）与 L5 幂等键重复在编排层 {@link IndustryCurationImportService}。
 */
public abstract class CurationCsvParser {

    /** 文件级错误行号（无具体物理行可指）。 */
    static final int FILE_LEVEL_ROW = 0;

    /** 数据行上限（设计规格 §五 L1）。 */
    static final int MAX_DATA_ROWS = 2000;

    /** 两模板共用的文本列长上限（V19 DDL，照 CsvImportParser MAX_NOTE_LENGTH 先例：L2 前置拦截超长，给人话行错误）。 */
    protected static final int MAX_COMPANY_NAME_LENGTH = 128;
    protected static final int MAX_INDUSTRY_CODE_LENGTH = 16;
    protected static final int MAX_SEGMENT_LENGTH = 64;

    /** L2 轮次白名单：枚举名（下划线大写、大小写敏感，设计规格 §三）。 */
    private static final String ALLOWED_ROUNDS =
            "SEED/ANGEL/PRE_A/A/A_PLUS/B/B_PLUS/C/C_PLUS/D/STRATEGIC/PRE_IPO/IPO/ACQUIRED/UNKNOWN";

    /** 金额列 NUMERIC(14,2) 整数位溢出阈值：|值| ≥ 10^12（12 位整数满额后再进位即溢出）。 */
    private static final BigDecimal NUMERIC_OVERFLOW_THRESHOLD = new BigDecimal("1000000000000");

    /** 解析产物：全部合法行，或聚齐的错误清单（互斥——有错误时 rows 为空 List）。 */
    public record ParseOutcome<R>(List<R> rows, List<CurationImportResult.RowError> errors) {

        static <R> ParseOutcome<R> ok(List<R> rows) {
            return new ParseOutcome<>(rows, List.of());
        }

        static <R> ParseOutcome<R> failed(List<CurationImportResult.RowError> errors) {
            return new ParseOutcome<>(List.of(), errors);
        }
    }

    /** 单行校验产物：合法行（problems 须为空 List）或该行的问题话术清单（由骨架聚齐成行错误）。 */
    protected record RowOutcome<R>(R row, List<String> problems) {}

    /** 行级校验器：由 {@link #parse(String, LocalDate, RowParser)} 逐行回调，产出合法行或问题话术。 */
    @FunctionalInterface
    protected interface RowParser<R> {

        RowOutcome<R> parse(int rowNumber, CSVRecord record, LocalDate today);
    }

    /**
     * L1/L2 解析主流程：L1 文件级门禁（无数据行/表头精确匹配/数据行超上限）→ L2 逐行校验、
     * 错误聚齐（rows 与 errors 互斥——有错误即全量拒绝，与编排层 all-or-nothing 口径一致，
     * 不做首错即停）。行号 = CSV 记录序号（表头为 1、首个数据行为 2；被忽略的空行不计）。
     */
    public final <R> ParseOutcome<R> parse(String content, LocalDate today, RowParser<R> rowParser) {
        List<CSVRecord> records = readRecords(Objects.requireNonNullElse(content, ""));
        // L1 文件级：零记录（空内容/全空行）或唯一记录即表头（仅表头）都无数据行
        if (records.size() <= 1) {
            return ParseOutcome.failed(List.of(rowError(FILE_LEVEL_ROW, "无数据行")));
        }
        // L1 文件级：表头精确匹配（BOM 已在读入前剥离）
        List<String> header = header();
        if (!records.get(0).toList().equals(header)) {
            return ParseOutcome.failed(List.of(rowError(FILE_LEVEL_ROW,
                    "表头与模板不符（期望：" + String.join(",", header) + "）")));
        }
        // L1 文件级：数据行超上限
        int dataRowCount = records.size() - 1;
        if (dataRowCount > MAX_DATA_ROWS) {
            return ParseOutcome.failed(List.of(rowError(FILE_LEVEL_ROW,
                    "数据行超过上限（最多 " + MAX_DATA_ROWS + " 行，实际 " + dataRowCount + " 行）")));
        }
        // L2 行级：逐行校验，错误聚齐
        List<R> rows = new ArrayList<>();
        List<CurationImportResult.RowError> errors = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            CSVRecord record = records.get(i);
            int rowNumber = (int) record.getRecordNumber();
            if (record.size() != header.size()) {
                errors.add(rowError(rowNumber, "列数不符（期望 " + header.size() + " 列，实际 "
                        + record.size() + " 列）"));
                continue;
            }
            RowOutcome<R> outcome = rowParser.parse(rowNumber, record, today);
            errors.addAll(outcome.problems().stream()
                    .map(problem -> rowError(rowNumber, problem))
                    .toList());
            if (outcome.row() != null) {
                rows.add(outcome.row());
            }
        }
        return errors.isEmpty() ? ParseOutcome.ok(List.copyOf(rows)) : ParseOutcome.failed(errors);
    }

    /** 表头列清单（固定顺序，设计规格 §五模板即权威口径）。 */
    protected abstract List<String> header();

    /** L2 轮次白名单：枚举名（下划线大写、大小写敏感，设计规格 §三）。 */
    protected static FundingRound parseRound(String raw, List<String> problems) {
        try {
            return FundingRound.parse(raw);
        } catch (IllegalArgumentException e) {
            problems.add("轮次无效 " + raw + "（允许：" + ALLOWED_ROUNDS + "）");
            return null;
        }
    }

    /** L2 日期：严格 yyyy-MM-dd（ISO LOCAL_DATE），空返回 null——必填与否由调用方语义决定。 */
    protected static LocalDate parseDate(String raw, List<String> problems) {
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
    protected static BigDecimal optionalDecimal(String raw, String column, List<String> problems) {
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

    protected static void requireNonBlank(String raw, String message, List<String> problems) {
        if (isBlank(raw)) {
            problems.add(message);
        }
    }

    /** L2 列长上限（V19 DDL）：超长在解析层报人话错误，避免落库才炸。 */
    protected static void requireMaxLength(String raw, String column, int maxLength, List<String> problems) {
        if (raw != null && raw.length() > maxLength) {
            problems.add(column + "超长（≤" + maxLength + " 字符）");
        }
    }

    protected static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    protected static String blankToNull(String raw) {
        return isBlank(raw) ? null : raw;
    }

    protected static CurationImportResult.RowError rowError(int row, String reason) {
        return new CurationImportResult.RowError(row, reason);
    }

    private List<CSVRecord> readRecords(String content) {
        // 预设表头名映射但【不】跳过首记录——L1 要求表头精确匹配，须读到首记录比对
        //（skipHeaderRecord=true 会让首记录从 getRecords() 消失，表头校验无从做起）
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(header().toArray(new String[0]))
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
