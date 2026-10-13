package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.FundingRound;
import org.apache.commons.csv.CSVRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 策展企业 CSV 解析层（设计规格 §五 L1/L2，照 CsvImportParser 结构）：UTF-8 字符串 +
 * RFC 4180（容忍 BOM，字符解码由调用方完成）。L1 文件级：表头八列精确匹配、数据行 ≤2000、
 * 存在数据行；L2 行级：必填列（industry_code/company_name/latest_round）非空、
 * latest_round ∈ FundingRound 枚举、last_funding_date 严格 yyyy-MM-dd 且 ≤ 今日、
 * total_funding_yi NUMERIC(14,2)（整数位 ≥10^12 拒绝）、文本列按 V19 列长上限校验
 * （超长报行错误，不待落库约束炸）。所有行错误一次聚齐返回（rows 与 errors 互斥——有错误即全量
 * 拒绝，与编排层 all-or-nothing 口径一致，不做首错即停）；文件级门禁与行级校验助手共享自
 * {@link CurationCsvParser}。
 * 行号 = CSV 记录序号（表头为 1、首个数据行为 2；被忽略的空行不计）。
 * L3 引用（行业码白名单）与 L5 幂等键重复在编排层 {@link IndustryCurationImportService}。
 */
public final class UnlistedCompanyCsvParser extends CurationCsvParser {

    /** 八列固定顺序表头（设计规格 §五模板即权威口径）。 */
    private static final List<String> HEADER = List.of(
            "industry_code", "company_name", "segment", "latest_round",
            "last_funding_date", "total_funding_yi", "summary", "source_note");

    private static final int COL_INDUSTRY_CODE = 0;
    private static final int COL_COMPANY_NAME = 1;
    private static final int COL_SEGMENT = 2;
    private static final int COL_LATEST_ROUND = 3;
    private static final int COL_LAST_FUNDING_DATE = 4;
    private static final int COL_TOTAL_FUNDING_YI = 5;
    private static final int COL_SUMMARY = 6;
    private static final int COL_SOURCE_NOTE = 7;

    /** 本模板特有的文本列长上限（V19 DDL；共用的企业名称/行业代码/细分赛道上限见骨架）。 */
    private static final int MAX_SUMMARY_LENGTH = 256;
    private static final int MAX_SOURCE_NOTE_LENGTH = 128;

    /** 单行解析产物（行号 + 八列；可选列空白落 null）。 */
    public record ParsedCompany(int rowNumber, String industryCode, String companyName, String segment,
                                FundingRound latestRound, LocalDate lastFundingDate,
                                BigDecimal totalFundingYi, String summary, String sourceNote) {}

    @Override
    protected List<String> header() {
        return HEADER;
    }

    /** L1/L2 公共入口：文件级门禁见骨架，行级校验委托 {@link #parseRow}。 */
    public ParseOutcome<ParsedCompany> parse(String content, LocalDate today) {
        return parse(content, today, this::parseRow);
    }

    /** L2 行级校验与产物构建：problems 非空即整行拒绝（话术由骨架聚齐成行错误）。 */
    protected RowOutcome<ParsedCompany> parseRow(int rowNumber, CSVRecord record, LocalDate today) {
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
            return new RowOutcome<>(null, problems);
        }
        return new RowOutcome<>(new ParsedCompany(rowNumber,
                industryCode.strip(), companyName.strip(), blankToNull(record.get(COL_SEGMENT)),
                latestRound, lastFundingDate, totalFundingYi,
                blankToNull(record.get(COL_SUMMARY)), blankToNull(record.get(COL_SOURCE_NOTE))), List.of());
    }
}
