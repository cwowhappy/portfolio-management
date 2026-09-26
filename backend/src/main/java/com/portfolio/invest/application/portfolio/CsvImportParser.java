package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.ImportRow;
import com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType;
import com.portfolio.invest.domain.portfolio.ImportSimulator;
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
 * CSV 导入解析层（设计规格 §1.2 的 L1/L2/L4）：UTF-8 字符串 + RFC 4180（容忍 BOM，
 * 字符解码由调用方完成）。L1 文件级：表头九列精确匹配、数据行 ≤2000、存在数据行；
 * L2 行级：类型/日期/数值列按 §1.1 类型×列约束矩阵校验（含上游评审补充的卖出费用
 * 上限）；L4：行日期不得晚于 today。所有行错误一次聚齐返回（rows 与 errors 互斥——
 * 有错误即全量拒绝，与 L5 模拟器聚齐口径一致，不做首错即停）。
 * 产出行的 groupName 取 CSV 分组名称列、groupId 恒为 null（名→id 解析是 L3 编排层职责）。
 * 行号 = CSV 记录序号（表头为 1、首个数据行为 2；被忽略的空行不计）。
 */
public final class CsvImportParser {

    /** 文件级错误行号（无具体物理行可指）。 */
    static final int FILE_LEVEL_ROW = 0;

    /** 数据行上限（§1.2 L1）。 */
    static final int MAX_DATA_ROWS = 2000;

    /** 备注上限（§1.1：落 cash_transaction.note，VARCHAR(255)）。 */
    private static final int MAX_NOTE_LENGTH = 255;

    /** 九列固定顺序表头（§1.1 模板即权威口径）。 */
    static final List<String> HEADER = List.of(
            "日期", "类型", "证券代码", "证券名称", "分组名称", "价格", "数量", "费用/金额", "备注");

    private static final String ALLOWED_TYPES = "BUY/SELL/CASH_DIVIDEND/STOCK_DIVIDEND/DEPOSIT/WITHDRAW";

    private static final int COL_DATE = 0;
    private static final int COL_TYPE = 1;
    private static final int COL_STOCK_CODE = 2;
    private static final int COL_STOCK_NAME = 3;
    private static final int COL_GROUP_NAME = 4;
    private static final int COL_PRICE = 5;
    private static final int COL_QUANTITY = 6;
    private static final int COL_FEE_OR_AMOUNT = 7;
    private static final int COL_NOTE = 8;

    /** 解析产物：全部合法行，或聚齐的错误清单（互斥——有错误时 rows 为空 List）。 */
    public record ParseOutcome(List<ImportRow> rows, List<ImportSimulator.RowError> errors) {

        static ParseOutcome ok(List<ImportRow> rows) {
            return new ParseOutcome(rows, List.of());
        }

        static ParseOutcome failed(List<ImportSimulator.RowError> errors) {
            return new ParseOutcome(List.of(), errors);
        }
    }

    public ParseOutcome parse(String content, LocalDate today) {
        List<CSVRecord> records = readRecords(Objects.requireNonNullElse(content, ""));
        // L1 文件级：零记录（空内容/全空行）或唯一记录即表头（仅表头）都无数据行
        if (records.size() <= 1) {
            return ParseOutcome.failed(List.of(rowError(FILE_LEVEL_ROW, "无数据行")));
        }
        // L1 文件级：表头九列精确匹配（BOM 已在读入前剥离）
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
        // L2/L4 行级：逐行校验，错误聚齐
        List<ImportRow> rows = new ArrayList<>();
        List<ImportSimulator.RowError> errors = new ArrayList<>();
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
    private record RowOutcome(ImportRow row, List<ImportSimulator.RowError> errors) {}

    private RowOutcome parseRow(int rowNumber, CSVRecord record, LocalDate today) {
        List<String> problems = new ArrayList<>();
        ImportRowType type = parseType(record.get(COL_TYPE), problems);
        LocalDate date = parseDate(record.get(COL_DATE), problems);
        BigDecimal price = null;
        BigDecimal quantity = null;
        BigDecimal fee = null;
        BigDecimal amount = null;
        if (type != null) {
            String stockCode = record.get(COL_STOCK_CODE);
            String groupName = record.get(COL_GROUP_NAME);
            String priceRaw = record.get(COL_PRICE);
            String quantityRaw = record.get(COL_QUANTITY);
            String feeOrAmountRaw = record.get(COL_FEE_OR_AMOUNT);
            // 列约束矩阵（§1.1 权威口径）：BUY/SELL 需 price>0/qty>0/fee≥0（可空=0）、代码+分组必填；
            // 分红需 price>0、代码+分组必填、数量/费用金额必空；DEPOSIT/WITHDRAW 需 amount>0、
            // 分组必填、代码/价格/数量必空
            switch (type) {
                case BUY, SELL -> {
                    requireNonBlank(stockCode, "证券代码不能为空", problems);
                    requireNonBlank(groupName, "分组名称不能为空", problems);
                    price = requirePositive(priceRaw, "价格", problems);
                    quantity = requirePositive(quantityRaw, "数量", problems);
                    fee = optionalFee(feeOrAmountRaw, problems);
                    // 上游评审补充校验：卖出费用不低于卖出金额即拒绝（≥，含恰好相等）
                    if (type == ImportRowType.SELL && price != null && quantity != null && fee != null
                            && fee.compareTo(price.multiply(quantity)) >= 0) {
                        problems.add("卖出费用不能超过卖出金额");
                    }
                }
                case CASH_DIVIDEND, STOCK_DIVIDEND -> {
                    requireNonBlank(stockCode, "证券代码不能为空", problems);
                    requireNonBlank(groupName, "分组名称不能为空", problems);
                    price = requirePositive(priceRaw, "价格", problems);
                    requireBlank(quantityRaw, "数量列必须为空", problems);
                    requireBlank(feeOrAmountRaw, "费用/金额列必须为空", problems);
                }
                case DEPOSIT, WITHDRAW -> {
                    requireNonBlank(groupName, "分组名称不能为空", problems);
                    requireBlank(stockCode, "证券代码列必须为空", problems);
                    requireBlank(priceRaw, "价格列必须为空", problems);
                    requireBlank(quantityRaw, "数量列必须为空", problems);
                    amount = requirePositive(feeOrAmountRaw, "金额", problems);
                }
            }
        }
        String note = record.get(COL_NOTE);
        if (note.length() > MAX_NOTE_LENGTH) {
            problems.add("备注不能超过 " + MAX_NOTE_LENGTH + " 字符");
        }
        if (date != null && date.isAfter(today)) {
            problems.add("日期不能晚于今日");
        }
        if (!problems.isEmpty()) {
            List<ImportSimulator.RowError> errors = problems.stream()
                    .map(problem -> rowError(rowNumber, problem))
                    .toList();
            return new RowOutcome(null, errors);
        }
        return new RowOutcome(new ImportRow(rowNumber, type, date,
                blankToNull(record.get(COL_STOCK_CODE)), blankToNull(record.get(COL_STOCK_NAME)),
                blankToNull(record.get(COL_GROUP_NAME)), null,
                price, quantity, fee, amount, blankToNull(note)), List.of());
    }

    /** L2 类型白名单：全大写、大小写敏感（§1.1）。 */
    private static ImportRowType parseType(String raw, List<String> problems) {
        try {
            return ImportRowType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            problems.add("类型无效 " + raw + "（允许：" + ALLOWED_TYPES + "）");
            return null;
        }
    }

    /** L2 日期：严格 yyyy-MM-dd（ISO LOCAL_DATE）。 */
    private static LocalDate parseDate(String raw, List<String> problems) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            problems.add("日期不可解析「" + raw + "」（格式须为 yyyy-MM-dd）");
            return null;
        }
    }

    /** 必填正数列：空与不可解析/≤0 同记「X 必须大于 0」之外的解析错误，空值并入同一文案。 */
    private static BigDecimal requirePositive(String raw, String column, List<String> problems) {
        if (isBlank(raw)) {
            problems.add(column + "必须大于 0");
            return null;
        }
        BigDecimal value = decimal(raw, column, problems);
        if (value != null && value.signum() <= 0) {
            problems.add(column + "必须大于 0");
        }
        return value;
    }

    /** BUY/SELL 费用列：可空=0，负数记错误（模拟器契约要求 fee 非空，空值必须落 0）。 */
    private static BigDecimal optionalFee(String raw, List<String> problems) {
        if (isBlank(raw)) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = decimal(raw, "费用/金额", problems);
        if (value != null && value.signum() < 0) {
            problems.add("费用不能为负数");
        }
        return value;
    }

    private static BigDecimal decimal(String raw, String column, List<String> problems) {
        try {
            return new BigDecimal(raw.strip());
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

    private static void requireBlank(String raw, String message, List<String> problems) {
        if (!isBlank(raw)) {
            problems.add(message);
        }
    }

    private static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    private static String blankToNull(String raw) {
        return isBlank(raw) ? null : raw;
    }

    private static ImportSimulator.RowError rowError(int row, String reason) {
        return new ImportSimulator.RowError(row, reason);
    }

    private static List<CSVRecord> readRecords(String content) {
        // 预设 HEADER 名映射但【不】跳过首记录——L1 要求表头九列精确匹配，须读到首记录比对
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

    /** 剥离 UTF-8 BOM（写模板带 BOM、读侧容忍，§1.1 口径）。 */
    private static String stripBom(String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }
}
