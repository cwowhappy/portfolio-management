package com.portfolio.invest.domain.research;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 证伪条件规则式求值唯一计算点（D10/D21，纯函数）：启用条件集 + 行情/估值快照 → 求值结果列表，
 * 无状态无副作用——实时命中（D21 页面判定）与日终扫描共用同一口径。
 *
 * <p>语义：PREDICATE 按方向严格比较（等于阈值不命中），对应值缺失（快照字段 null）→
 * skipped 条目（basis「无最近价/估值」），不自动命中不 pending——与
 * {@link DisciplineCheckService} 缺数据跳过同口径；EVENT 事件类 → pending「待人工勾选」，
 * 不自动判命中（D10 人工确认）；enabled=false 条件跳过不产出。命中 basis 含数字与口径
 * （取自快照 priceNote），如「收盘价 12.34 &lt; 下限 13.00（东财收盘 2026-09-26）」。
 */
public final class FalsifierEvaluator {

    /** 缺最近值跳过条目的 basis（D10 不自动命中）。 */
    static final String BASIS_NO_DATA = "无最近价/估值";
    /** EVENT 待人工勾选条目的 basis（D10 事件类不自动判命中）。 */
    static final String BASIS_PENDING_EVENT = "待人工勾选";

    private FalsifierEvaluator() {}

    /**
     * 求值启用中的证伪条件列表。falsifiers 为 null 或空 → 空结果；快照为 null 视同
     * 各口径全部缺失（全部谓词 skipped，容错日终扫描），EVENT 不依赖快照仍 pending。
     *
     * @return 与入参启用条件同序的结果列表（不可修改）
     */
    public static List<FalsifierHitResult> evaluate(List<Falsifier> falsifiers, MarketSnapshot snapshot) {
        if (falsifiers == null || falsifiers.isEmpty()) {
            return List.of();
        }
        List<FalsifierHitResult> results = new ArrayList<>(falsifiers.size());
        for (Falsifier falsifier : falsifiers) {
            if (!falsifier.enabled()) {
                continue;
            }
            if (falsifier.kind() == FalsifierKind.EVENT) {
                results.add(new FalsifierHitResult(falsifier, false, true, false, BASIS_PENDING_EVENT));
                continue;
            }
            results.add(evaluatePredicate(falsifier, snapshot));
        }
        return List.copyOf(results);
    }

    private static FalsifierHitResult evaluatePredicate(Falsifier falsifier, MarketSnapshot snapshot) {
        BigDecimal value = valueOf(falsifier.predicate(), snapshot);
        if (value == null) {
            return new FalsifierHitResult(falsifier, false, false, true, BASIS_NO_DATA);
        }
        int cmp = value.compareTo(falsifier.threshold());
        boolean hit = falsifier.predicate() == FalsifierPredicate.PRICE_BELOW ? cmp < 0 : cmp > 0;
        String basis = basis(falsifier.predicate(), value, falsifier.threshold(), hit,
                snapshot == null ? null : snapshot.priceNote());
        return new FalsifierHitResult(falsifier, hit, false, false, basis);
    }

    /** 谓词 → 快照对应值：价格谓词读收盘价，估值谓词读 PE/PB；快照缺失一律 null。 */
    private static BigDecimal valueOf(FalsifierPredicate predicate, MarketSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return switch (predicate) {
            case PRICE_BELOW, PRICE_ABOVE -> snapshot.close();
            case PE_ABOVE -> snapshot.pe();
            case PB_ABOVE -> snapshot.pb();
        };
    }

    /**
     * 可解释 basis（D10）：值 + 方向算符（命中 &lt;/&gt;，未命中镜像 ≥/≤）+ 阈值 + 口径尾注。
     * 数字 toPlainString 保留 scale（13.00 不折叠为 13），priceNote 缺失则不带尾注。
     */
    private static String basis(FalsifierPredicate predicate, BigDecimal value, BigDecimal threshold,
                                boolean hit, String priceNote) {
        String label = switch (predicate) {
            case PRICE_BELOW -> "收盘价 " + value.toPlainString() + (hit ? " < 下限 " : " ≥ 下限 ")
                    + threshold.toPlainString();
            case PRICE_ABOVE -> "收盘价 " + value.toPlainString() + (hit ? " > 上限 " : " ≤ 上限 ")
                    + threshold.toPlainString();
            case PE_ABOVE -> "PE " + value.toPlainString() + (hit ? " > 上限 " : " ≤ 上限 ")
                    + threshold.toPlainString();
            case PB_ABOVE -> "PB " + value.toPlainString() + (hit ? " > 上限 " : " ≤ 上限 ")
                    + threshold.toPlainString();
        };
        if (priceNote == null || priceNote.isBlank()) {
            return label;
        }
        return label + "（" + priceNote + "）";
    }
}
