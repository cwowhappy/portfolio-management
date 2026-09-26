package com.portfolio.invest.web;

import com.portfolio.invest.application.industry.CurationImportResult;
import com.portfolio.invest.application.industry.IndustryCurationApplicationService;
import com.portfolio.invest.application.industry.IndustryCurationImportService;
import com.portfolio.invest.application.industry.SaveUnlistedCompanyCommand;
import com.portfolio.invest.application.industry.UnlistedCompanyView;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 策展写侧 REST 接口（MS-10，设计规格 §四）。路径挂 /api/industry-curation 而非
 * /api/industry/curation：/api/industry/** 是公开前缀（PublicEndpointPaths），策展写入
 * 需要登录——独立前缀天然落在认证范围（SecurityConfig anyRequest().authenticated()），
 * 零安全配置改动（照 IndustryWatchController 先例）。
 * 策展为全局研究数据无 user 归属（设计规格 §九#1）：方法不收 Authentication 参数、
 * 不按人过滤——路由级鉴权已由前缀达成，无用户语义可取。
 * 错误双层语义（照 MS-14）：文件级（空/超 1MB/表头不匹配）→ 400 ApiError；行级 → 200 +
 * CurationImportResult 双计数与行错误清单。
 */
@RestController
@RequestMapping("/api/industry-curation")
public class IndustryCurationController {

    private static final long IMPORT_MAX_BYTES = 1024 * 1024;

    private final IndustryCurationApplicationService curationService;
    private final IndustryCurationImportService importService;

    public IndustryCurationController(IndustryCurationApplicationService curationService,
                                      IndustryCurationImportService importService) {
        this.curationService = curationService;
        this.importService = importService;
    }

    @PostMapping("/companies")
    public UnlistedCompanyView createCompany(@Valid @RequestBody SaveUnlistedCompanyCommand cmd) {
        return UnlistedCompanyView.from(curationService.save(null, cmd));
    }

    @PutMapping("/companies/{id}")
    public UnlistedCompanyView updateCompany(@PathVariable Long id,
                                             @Valid @RequestBody SaveUnlistedCompanyCommand cmd) {
        return UnlistedCompanyView.from(curationService.save(id, cmd));
    }

    @DeleteMapping("/companies/{id}")
    public ResponseEntity<Void> deleteCompany(@PathVariable Long id) {
        curationService.deleteCompany(id); // 服务幂等：删不存在亦成功
        return ResponseEntity.noContent().build();
    }

    /** CSV 模板下载：BOM + 八列表头 + 示例行（与解析器测试样例逐行同源）。 */
    @GetMapping("/companies/import/template")
    public ResponseEntity<String> companiesTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=unlisted-companies-template.csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(IndustryCurationTemplates.COMPANIES_CSV);
    }

    /**
     * 策展企业 CSV 批量导入（multipart 字段 file）。文件级只校空文件与 1MB 上限；行级校验
     * （2000 行上限、表头、类型/日期约束、白名单、键内重复）全在解析层 L1/L2 + 编排层 L3/L5。
     * 字节按 UTF-8 宽容解码，不抛编码异常（与 MS-14 同口径）。
     */
    @PostMapping(value = "/companies/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CurationImportResult importCompanies(
            @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        requireImportable(file);
        return importService.importCompanies(new String(file.getBytes(), StandardCharsets.UTF_8));
    }

    /** CSV 模板下载：BOM + 九列表头 + 示例行（与解析器测试样例逐行同源）。 */
    @GetMapping("/funding-events/import/template")
    public ResponseEntity<String> fundingEventsTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=industry-funding-events-template.csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(IndustryCurationTemplates.FUNDING_EVENTS_CSV);
    }

    /** 融资事件 CSV 导入（同 {@link #importCompanies} 双层错误语义）。 */
    @PostMapping(value = "/funding-events/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CurationImportResult importFundingEvents(
            @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        requireImportable(file);
        return importService.importFundingEvents(new String(file.getBytes(), StandardCharsets.UTF_8));
    }

    @DeleteMapping("/funding-events/{id}")
    public ResponseEntity<Void> deleteFundingEvent(@PathVariable Long id) {
        curationService.deleteFundingEvent(id); // 服务幂等
        return ResponseEntity.noContent().build();
    }

    /** 文件级校验：空文件/超 1MB → 400（INVALID_CSV 经 GlobalExceptionHandler 映射）。 */
    private static void requireImportable(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IndustryException(IndustryErrorCode.INVALID_CSV, "文件为空");
        }
        if (file.getSize() > IMPORT_MAX_BYTES) {
            throw new IndustryException(IndustryErrorCode.INVALID_CSV, "文件过大（上限 1MB）");
        }
    }
}
