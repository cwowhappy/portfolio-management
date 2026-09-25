package com.portfolio.invest.domain.portfolio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CSV 导入 L5 模拟重放器：纯函数，按（groupId, date, rowNumber）稳定排序后在内存中
 * 重放各分组现金与持仓演化，返回行错误清单；组内首错即停该组（后续同组行不再模拟，
 * 其他组继续）。数值文案口径照 {@code PortfolioApplicationService#buy}：
 * stripTrailingZeros().toPlainString()。
 */
public final class ImportSimulator {

    /** 起始状态：各分组现金余额 + 各持仓数量（key = groupId + "|" + stockCode）。 */
    public record StartState(Map<Long, BigDecimal> groupCash, Map<String, BigDecimal> holdingQty) {}

    /** 一条行错误：CSV 文件中的物理行号 + 人读原因。 */
    public record RowError(int row, String reason) {}

    public static List<RowError> simulate(StartState start, List<ImportRow> rows) {
        List<ImportRow> sorted = rows.stream()
                .sorted(Comparator.comparing(ImportRow::groupId)
                        .thenComparing(ImportRow::date)
                        .thenComparing(ImportRow::rowNumber))
                .toList();
        var cash = new HashMap<>(start.groupCash());
        var qty = new HashMap<>(start.holdingQty());
        List<RowError> errors = new ArrayList<>();
        Set<Long> stoppedGroups = new HashSet<>();
        for (ImportRow r : sorted) {
            if (stoppedGroups.contains(r.groupId())) continue;
            Optional<RowError> err = switch (r.type()) {
                case DEPOSIT -> { cash.merge(r.groupId(), r.amount(), BigDecimal::add); yield Optional.empty(); }
                case WITHDRAW -> {
                    BigDecimal available = cash.getOrDefault(r.groupId(), BigDecimal.ZERO);
                    if (available.subtract(r.amount()).compareTo(BigDecimal.ZERO) < 0) {
                        yield Optional.of(new RowError(r.rowNumber(),
                                "转出后分组现金为负（可用 " + fmt(available) + "，本行转出 " + fmt(r.amount()) + "）"));
                    }
                    cash.put(r.groupId(), available.subtract(r.amount()));
                    yield Optional.empty();
                }
                case BUY -> {
                    BigDecimal cost = r.price().multiply(r.quantity()).add(r.fee());
                    BigDecimal available = cash.getOrDefault(r.groupId(), BigDecimal.ZERO);
                    if (available.compareTo(cost) < 0) {
                        yield Optional.of(new RowError(r.rowNumber(),
                                "现金不足：可用 " + fmt(available) + "，本次需 " + fmt(cost) + "（含费）"));
                    }
                    cash.put(r.groupId(), available.subtract(cost));
                    qty.merge(key(r), r.quantity(), BigDecimal::add);
                    yield Optional.empty();
                }
                case SELL -> {
                    BigDecimal held = qty.getOrDefault(key(r), BigDecimal.ZERO);
                    if (r.quantity().compareTo(held) > 0) {
                        yield Optional.of(new RowError(r.rowNumber(),
                                "卖出数量超过持仓（可用 " + fmt(held) + "，本行卖出 " + fmt(r.quantity()) + "）"));
                    }
                    qty.put(key(r), held.subtract(r.quantity()));
                    cash.merge(r.groupId(), r.price().multiply(r.quantity()).subtract(r.fee()), BigDecimal::add);
                    yield Optional.empty();
                }
                case CASH_DIVIDEND -> {
                    BigDecimal held = qty.getOrDefault(key(r), BigDecimal.ZERO);
                    if (held.compareTo(BigDecimal.ZERO) <= 0) {
                        yield Optional.of(new RowError(r.rowNumber(), "分红时该标的持仓为 0"));
                    }
                    cash.merge(r.groupId(), r.price().multiply(held), BigDecimal::add);
                    yield Optional.empty();
                }
                case STOCK_DIVIDEND -> {
                    BigDecimal held = qty.getOrDefault(key(r), BigDecimal.ZERO);
                    if (held.compareTo(BigDecimal.ZERO) <= 0) {
                        yield Optional.of(new RowError(r.rowNumber(), "分红时该标的持仓为 0"));
                    }
                    qty.put(key(r), held.multiply(BigDecimal.ONE.add(r.price())));
                    yield Optional.empty();
                }
            };
            err.ifPresent(e -> { errors.add(e); stoppedGroups.add(r.groupId()); });
        }
        return errors;
    }

    private static String key(ImportRow r) { return r.groupId() + "|" + r.stockCode(); }

    private static String fmt(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }
}
