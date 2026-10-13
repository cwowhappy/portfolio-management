package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.FundingRound;
import org.apache.commons.csv.CSVRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 融资事件 CSV 解析层（设计规格 §五 L1/L2，照 CsvImportParser 结构）：UTF-8 字符串 +
 * RFC 4180（容忍 BOM，字符解码由调用方完成）。L1 文件级：表头九列精确匹配、数据行 ≤2000、
 * 存在数据行；L2 行级：必填列（event_date/company_name/round/industry_code/source_title）
 * 非空、round ∈ FundingRound 枚举（下划线大写）、event_date 严格 yyyy-MM-dd 且 ≤ 今日、
 * amount_yi NUMERIC(14,2)（空=未披露，整数位 ≥10^12 拒绝）、文本列按 V19 列长上限校验
 * （超长报行错误，不待落库约束炸）。所有行错误一次聚齐返回（rows 与 errors 互斥）；
 * 文件级门禁与行级校验助手共享自 {@link CurationCsvParser}。
 * 行号 = CSV 记录序号（表头为 1；被忽略的空行不计）。
 * L3 引用（行业码白名单）与 L5 幂等键重复在编排层 {@link IndustryCurationImportService}。
 */
public final class FundingEventCsvParser extends CurationCsvParser {

    /** 九列固定顺序表头（设计规格 §五模板即权威口径）。 */
    private static final List<String> HEADER = List.of(
            "event_date", "company_name", "round", "amount_yi", "investors",
            "industry_code", "segment", "source_title", "source_url");

    private static final int COL_EVENT_DATE = 0;
    private static final int COL_COMPANY_NAME = 1;
    private static final int COL_ROUND = 2;
    private static final int COL_AMOUNT_YI = 3;
    private static final int COL_INVESTORS = 4;
    private static final int COL_INDUSTRY_CODE = 5;
    private static final int COL_SEGMENT = 6;
    private static final int COL_SOURCE_TITLE = 7;
    private static final int COL_SOURCE_URL = 8;

    /** 本模板特有的文本列长上限（V19 DDL；共用的企业名称/行业代码/细分赛道上限见骨架）。 */
    private static final int MAX_INVESTORS_LENGTH = 256;
    private static final int MAX_SOURCE_TITLE_LENGTH = 256;
    private static final int MAX_SOURCE_URL_LENGTH = 512;

    /** 单行解析产物（行号 + 九列；可选列空白落 null）。 */
    public record ParsedFundingEvent(int rowNumber, LocalDate eventDate, String companyName,
                                     FundingRound round, BigDecimal amountYi, String investors,
                                     String industryCode, String segment, String sourceTitle,
                                     String sourceUrl) {}

    @Override
    protected List<String> header() {
        return HEADER;
    }

    /** L1/L2 公共入口：文件级门禁见骨架，行级校验委托 {@link #parseRow}。 */
    public ParseOutcome<ParsedFundingEvent> parse(String content, LocalDate today) {
        return parse(content, today, this::parseRow);
    }

    /** L2 行级校验与产物构建：problems 非空即整行拒绝（话术由骨架聚齐成行错误）。 */
    protected RowOutcome<ParsedFundingEvent> parseRow(int rowNumber, CSVRecord record, LocalDate today) {
        List<String> problems = new ArrayList<>();
        requireNonBlank(record.get(COL_EVENT_DATE), "事件日期不能为空", problems);
        LocalDate eventDate = parseDate(record.get(COL_EVENT_DATE), problems);
        if (eventDate != null && eventDate.isAfter(today)) {
            problems.add("事件日期不能晚于今日");
        }
        requireNonBlank(record.get(COL_COMPANY_NAME), "企业名称不能为空", problems);
        FundingRound round = null;
        String roundRaw = record.get(COL_ROUND);
        if (!isBlank(roundRaw)) {
            round = parseRound(roundRaw, problems);
        } else {
            problems.add("轮次不能为空");
        }
        BigDecimal amountYi = optionalDecimal(record.get(COL_AMOUNT_YI), "金额", problems);
        requireNonBlank(record.get(COL_INDUSTRY_CODE), "行业代码不能为空", problems);
        requireNonBlank(record.get(COL_SOURCE_TITLE), "来源标题不能为空", problems);
        requireMaxLength(record.get(COL_COMPANY_NAME), "企业名称", MAX_COMPANY_NAME_LENGTH, problems);
        requireMaxLength(record.get(COL_INVESTORS), "投资方", MAX_INVESTORS_LENGTH, problems);
        requireMaxLength(record.get(COL_INDUSTRY_CODE), "行业代码", MAX_INDUSTRY_CODE_LENGTH, problems);
        requireMaxLength(record.get(COL_SEGMENT), "细分赛道", MAX_SEGMENT_LENGTH, problems);
        requireMaxLength(record.get(COL_SOURCE_TITLE), "来源标题", MAX_SOURCE_TITLE_LENGTH, problems);
        requireMaxLength(record.get(COL_SOURCE_URL), "来源URL", MAX_SOURCE_URL_LENGTH, problems);
        if (!problems.isEmpty()) {
            return new RowOutcome<>(null, problems);
        }
        return new RowOutcome<>(new ParsedFundingEvent(rowNumber, eventDate,
                record.get(COL_COMPANY_NAME).strip(), round, amountYi,
                blankToNull(record.get(COL_INVESTORS)), record.get(COL_INDUSTRY_CODE).strip(),
                blankToNull(record.get(COL_SEGMENT)), record.get(COL_SOURCE_TITLE).strip(),
                blankToNull(record.get(COL_SOURCE_URL))), List.of());
    }
}
