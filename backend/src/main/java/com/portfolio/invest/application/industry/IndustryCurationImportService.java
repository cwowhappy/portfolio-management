package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.FundingEvent;
import com.portfolio.invest.domain.industry.FundingEventRepository;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.UnlistedCompany;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 策展 CSV 导入编排服务（设计规格 §五管线 + §九#2 upsert 语义，照 PortfolioImportService
 * 五层编排先例，<b>无模拟器</b>——策展/融资数据无跨行资金语义）：
 * L1/L2 由两解析器完成；本层补 L3（industry_code ∈ 申万一级白名单，按文件去重集合一次查全）
 * 与 L5（文件内幂等键重复，第二行起报错）。全量预检通过才逐条 upsert——任何行错误即
 * all-or-nothing 双计数归零（返回 {@link CurationImportResult} 行错误清单，不允许静默跳过）。
 * 策展/融资为全局研究数据无 user 归属（设计规格 §九#1），入口不带用户参数。
 */
@Service
public class IndustryCurationImportService {

    private final UnlistedCompanyRepository companyRepository;
    private final FundingEventRepository eventRepository;
    private final IndustryRepository industryRepository;

    /** 解析器无状态且非 Spring bean（照 PortfolioImportService 先例），直接持有实例即可。 */
    private final UnlistedCompanyCsvParser companyParser = new UnlistedCompanyCsvParser();
    private final FundingEventCsvParser eventParser = new FundingEventCsvParser();

    public IndustryCurationImportService(UnlistedCompanyRepository companyRepository,
                                         FundingEventRepository eventRepository,
                                         IndustryRepository industryRepository) {
        this.companyRepository = companyRepository;
        this.eventRepository = eventRepository;
        this.industryRepository = industryRepository;
    }

    @Transactional
    public CurationImportResult importCompanies(String csvContent) {
        // L1/L2：解析层错误（含未来日期/表头不符）直接透传，不触达仓储
        var parsed = companyParser.parse(csvContent, LocalDate.now());
        if (!parsed.errors().isEmpty()) {
            return new CurationImportResult(0, 0, parsed.errors());
        }

        // L3：行业码白名单——按文件去重集合一次查全（非逐行查库）
        Set<String> invalidCodes = invalidIndustryCodes(
                parsed.rows().stream().map(UnlistedCompanyCsvParser.ParsedCompany::industryCode).toList());
        List<CurationImportResult.RowError> errors = new ArrayList<>();
        for (var row : parsed.rows()) {
            if (invalidCodes.contains(row.industryCode())) {
                errors.add(new CurationImportResult.RowError(row.rowNumber(),
                        "行业代码不存在: " + row.industryCode()));
            }
        }

        // L5：文件内幂等键重复（industry_code+company_name），第二行起报错
        Set<String> seenKeys = new HashSet<>();
        for (var row : parsed.rows()) {
            String key = row.industryCode() + "|" + row.companyName();
            if (!seenKeys.add(key)) {
                errors.add(new CurationImportResult.RowError(row.rowNumber(),
                        "文件内幂等键重复（industry_code+company_name）：" + key.replace('|', '，')));
            }
        }
        if (!errors.isEmpty()) {
            return new CurationImportResult(0, 0, errors); // all-or-nothing：预检不通过零执行
        }

        // 执行：按文件序逐条 upsert，累计 inserted/updated（§九#2 双计数）
        int inserted = 0;
        int updated = 0;
        for (var row : parsed.rows()) {
            var outcome = companyRepository.upsert(new UnlistedCompany(null, row.industryCode(),
                    row.companyName(), row.segment(), row.latestRound(), row.lastFundingDate(),
                    row.totalFundingYi(), row.summary(), row.sourceNote(), Instant.now()));
            if (outcome.inserted()) {
                inserted++;
            } else {
                updated++;
            }
        }
        return new CurationImportResult(inserted, updated, List.of());
    }

    @Transactional
    public CurationImportResult importFundingEvents(String csvContent) {
        // L1/L2：解析层错误直接透传，不触达仓储
        var parsed = eventParser.parse(csvContent, LocalDate.now());
        if (!parsed.errors().isEmpty()) {
            return new CurationImportResult(0, 0, parsed.errors());
        }

        // L3：行业码白名单——按文件去重集合一次查全
        Set<String> invalidCodes = invalidIndustryCodes(
                parsed.rows().stream().map(FundingEventCsvParser.ParsedFundingEvent::industryCode).toList());
        List<CurationImportResult.RowError> errors = new ArrayList<>();
        for (var row : parsed.rows()) {
            if (invalidCodes.contains(row.industryCode())) {
                errors.add(new CurationImportResult.RowError(row.rowNumber(),
                        "行业代码不存在: " + row.industryCode()));
            }
        }

        // L5：文件内幂等键重复（event_date+company_name+round，同日同企同轮），第二行起报错
        Set<String> seenKeys = new HashSet<>();
        for (var row : parsed.rows()) {
            String key = row.eventDate() + "|" + row.companyName() + "|" + row.round().name();
            if (!seenKeys.add(key)) {
                errors.add(new CurationImportResult.RowError(row.rowNumber(),
                        "文件内幂等键重复（event_date+company_name+round）：" + key.replace('|', '，')));
            }
        }
        if (!errors.isEmpty()) {
            return new CurationImportResult(0, 0, errors); // all-or-nothing：预检不通过零执行
        }

        // 执行：按文件序逐条 upsert，累计 inserted/updated
        int inserted = 0;
        int updated = 0;
        for (var row : parsed.rows()) {
            var outcome = eventRepository.upsert(new FundingEvent(null, row.eventDate(),
                    row.companyName(), row.round(), row.amountYi(), row.investors(),
                    row.industryCode(), row.segment(), row.sourceTitle(), row.sourceUrl(), Instant.now()));
            if (outcome.inserted()) {
                inserted++;
            } else {
                updated++;
            }
        }
        return new CurationImportResult(inserted, updated, List.of());
    }

    /** L3 批量预检：文件内去重后的行业码集合中不在白名单的部分。 */
    private Set<String> invalidIndustryCodes(List<String> codesInFile) {
        Set<String> distinct = new LinkedHashSet<>(codesInFile);
        Set<String> invalid = new HashSet<>();
        for (String code : distinct) {
            if (!industryRepository.existsIndustry(code)) {
                invalid.add(code);
            }
        }
        return invalid;
    }
}
