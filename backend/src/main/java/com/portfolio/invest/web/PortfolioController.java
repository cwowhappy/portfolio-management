package com.portfolio.invest.web;

import com.portfolio.invest.application.portfolio.AssetAllocationView;
import com.portfolio.invest.application.portfolio.BuyCommand;
import com.portfolio.invest.application.portfolio.CashDividendCommand;
import com.portfolio.invest.application.portfolio.CashTransactionCommand;
import com.portfolio.invest.application.portfolio.CashTransactionView;
import com.portfolio.invest.application.portfolio.ConcentrationView;
import com.portfolio.invest.application.portfolio.CreateGroupCommand;
import com.portfolio.invest.application.portfolio.DividendView;
import com.portfolio.invest.application.portfolio.EditTradeCommand;
import com.portfolio.invest.application.portfolio.GroupView;
import com.portfolio.invest.application.portfolio.ImportResult;
import com.portfolio.invest.application.portfolio.IndustryDistributionView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioImportService;
import com.portfolio.invest.application.portfolio.PortfolioOverviewView;
import com.portfolio.invest.application.portfolio.PositionView;
import com.portfolio.invest.application.portfolio.RenameGroupCommand;
import com.portfolio.invest.application.portfolio.SellCommand;
import com.portfolio.invest.application.portfolio.StockDividendCommand;
import com.portfolio.invest.application.portfolio.TradeView;
import com.portfolio.invest.domain.portfolio.PortfolioErrorCode;
import com.portfolio.invest.domain.portfolio.PortfolioException;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    /** 导入文件大小上限 1MB；multipart 解析器上限放宽到 2MB（application.yml），边界文件不被容器先拒。 */
    private static final long IMPORT_MAX_BYTES = 1024 * 1024;

    private final PortfolioApplicationService service;
    private final PortfolioImportService importService;

    public PortfolioController(PortfolioApplicationService service, PortfolioImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @GetMapping("/overview")
    public PortfolioOverviewView overview(Authentication auth) {
        return service.overview(currentUserId(auth));
    }

    @GetMapping("/positions")
    public List<PositionView> positions(Authentication auth, @RequestParam(required = false) Long groupId) {
        return service.positions(currentUserId(auth), groupId);
    }

    @GetMapping("/groups")
    public List<GroupView> groups(Authentication auth) {
        return service.groups(currentUserId(auth));
    }

    @PostMapping("/groups")
    public ResponseEntity<GroupView> createGroup(Authentication auth, @Valid @RequestBody CreateGroupCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGroup(currentUserId(auth), cmd));
    }

    @DeleteMapping("/groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(Authentication auth, @PathVariable Long groupId) {
        service.deleteGroup(currentUserId(auth), groupId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/groups/{groupId}")
    public GroupView renameGroup(Authentication auth, @PathVariable Long groupId,
                                 @Valid @RequestBody RenameGroupCommand cmd) {
        return service.renameGroup(currentUserId(auth), groupId, cmd);
    }

    @PostMapping("/positions/buy")
    public ResponseEntity<PositionView> buy(Authentication auth, @Valid @RequestBody BuyCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.buy(currentUserId(auth), cmd));
    }

    @PostMapping("/positions/sell")
    public ResponseEntity<PositionView> sell(Authentication auth, @Valid @RequestBody SellCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.sell(currentUserId(auth), cmd));
    }

    @PostMapping("/positions/cash-dividend")
    public ResponseEntity<PositionView> cashDividend(Authentication auth, @Valid @RequestBody CashDividendCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addCashDividend(currentUserId(auth), cmd));
    }

    @PostMapping("/positions/stock-dividend")
    public ResponseEntity<PositionView> stockDividend(Authentication auth, @Valid @RequestBody StockDividendCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addStockDividend(currentUserId(auth), cmd));
    }

    @DeleteMapping("/positions/{positionId}")
    public ResponseEntity<Void> deletePosition(Authentication auth, @PathVariable Long positionId) {
        service.deletePosition(currentUserId(auth), positionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/positions/{positionId}/trades")
    public List<TradeView> trades(Authentication auth, @PathVariable Long positionId) {
        return service.trades(currentUserId(auth), positionId);
    }

    @PutMapping("/positions/{positionId}/trades/{tradeId}")
    public PositionView editTrade(Authentication auth, @PathVariable Long positionId,
                                  @PathVariable Long tradeId, @Valid @RequestBody EditTradeCommand cmd) {
        return service.editTrade(currentUserId(auth), positionId, tradeId, cmd);
    }

    @GetMapping("/positions/{positionId}/dividends")
    public List<DividendView> dividends(Authentication auth, @PathVariable Long positionId) {
        return service.dividends(currentUserId(auth), positionId);
    }

    @PostMapping("/cash-transactions")
    public ResponseEntity<CashTransactionView> addCashTransaction(Authentication auth, @Valid @RequestBody CashTransactionCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addCashTransaction(currentUserId(auth), cmd));
    }

    @GetMapping("/cash-transactions")
    public List<CashTransactionView> cashTransactions(Authentication auth, @RequestParam Long groupId) {
        return service.cashTransactions(currentUserId(auth), groupId);
    }

    @GetMapping("/allocation")
    public AssetAllocationView allocation(Authentication auth) {
        return service.allocation(currentUserId(auth));
    }

    @GetMapping("/industry-distribution")
    public IndustryDistributionView industryDistribution(Authentication auth) {
        return service.industryDistribution(currentUserId(auth));
    }

    @GetMapping("/concentration")
    public ConcentrationView concentration(Authentication auth) {
        return service.concentration(currentUserId(auth));
    }

    /** CSV 导入模板下载：BOM + 九列表头 + 六类型示例行（与解析器测试样例逐行同源）。 */
    @GetMapping("/import/template")
    public ResponseEntity<String> importTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=import-template.csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(PortfolioImportTemplate.CSV);
    }

    /**
     * CSV 批量导入（multipart 字段 file）。文件级只校空文件与 1MB 上限；行级校验（含
     * 2000 行上限、九列结构、类型/数值/日期约束）全在解析层 L1/L2/L4，此处不重复实现。
     * 字节按 UTF-8 宽容解码（非法字节替换 U+FFFD，与解析层容忍 BOM 同口径），不抛编码异常。
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importCsv(Authentication auth,
                                  @RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new PortfolioException(PortfolioErrorCode.INVALID_INPUT, "文件为空");
        }
        if (file.getSize() > IMPORT_MAX_BYTES) {
            throw new PortfolioException(PortfolioErrorCode.INVALID_INPUT, "文件过大（上限 1MB）");
        }
        String csvContent = new String(file.getBytes(), StandardCharsets.UTF_8);
        return importService.importCsv(currentUserId(auth), csvContent);
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
