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
import com.portfolio.invest.application.portfolio.IndustryDistributionView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioOverviewView;
import com.portfolio.invest.application.portfolio.PositionView;
import com.portfolio.invest.application.portfolio.RenameGroupCommand;
import com.portfolio.invest.application.portfolio.SellCommand;
import com.portfolio.invest.application.portfolio.StockDividendCommand;
import com.portfolio.invest.application.portfolio.TradeView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioApplicationService service;

    public PortfolioController(PortfolioApplicationService service) {
        this.service = service;
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
        return ResponseEntity.ok(service.buy(currentUserId(auth), cmd));
    }

    @PostMapping("/positions/sell")
    public ResponseEntity<PositionView> sell(Authentication auth, @Valid @RequestBody SellCommand cmd) {
        return ResponseEntity.ok(service.sell(currentUserId(auth), cmd));
    }

    @PostMapping("/positions/cash-dividend")
    public ResponseEntity<PositionView> cashDividend(Authentication auth, @Valid @RequestBody CashDividendCommand cmd) {
        return ResponseEntity.ok(service.addCashDividend(currentUserId(auth), cmd));
    }

    @PostMapping("/positions/stock-dividend")
    public ResponseEntity<PositionView> stockDividend(Authentication auth, @Valid @RequestBody StockDividendCommand cmd) {
        return ResponseEntity.ok(service.addStockDividend(currentUserId(auth), cmd));
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

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
