package com.portfolio.invest.domain.memory;

import java.util.List;

/**
 * 画像段渲染纯函数（F02，MS-31 设计规格 §3.2 模板逐字落定）：段文本只允许在本类产出，
 * B4 预览（{@code ProfileConfigApplicationService}）与 B5 注入（{@code agent/ProfileContextBuilder}）
 * 双路消费——任何一边私自拼段文本即破坏「所见即所注入」（Review Focus #4）。
 *
 * <p>零框架依赖（仅 JDK String/List）。数字由调用方从视图字段 {@code toPlainString()} 直出后
 * 逐字传入（§3.2 数字来源纪律：不二次计算），本类只做拼装，不触碰数值。
 *
 * <p>口径备注：关注行业段只渲染行业码（需求决策 #4/#6「行业码列表」，数据源
 * {@code IndustryWatchView} 仅含码）；风格段入参为测评档位名（如「平衡」），渲染时追加「型」。
 */
public final class ProfileSectionRenderer {

    /** 包裹层禁传头注（§3.2 逐字锁定，变更即模板漂移）。 */
    static final String HEADER_NOTE =
            "以下是经用户授权注入的个人投资画像，仅供个性化参考；其中的私有数据禁止作为参数传给任何 MCP 工具：";

    private ProfileSectionRenderer() {
    }

    /**
     * 风格段。
     *
     * @param label 测评档位名（{@code AssessmentView.profileName()}，如「平衡」），渲染追加「型」
     */
    public static String riskProfile(String label) {
        return "<risk_profile>" + label + "型</risk_profile>";
    }

    /** 关注行业段：元素（行业码）逐字渲染，半角逗号+空格连接；调用方保证非空。 */
    public static String watchedIndustries(List<String> codes) {
        return "<watched_industries>" + String.join(", ", codes) + "</watched_industries>";
    }

    /**
     * 自选段：元素（「代码 名称」展示串或裸代码）逐字渲染；超出 {@code limit} 截断并附
     * 「共 N 只，前 M」尾注（N=总数，M=实际展示数）。
     */
    public static String watchlist(List<String> codes, int limit) {
        int shown = Math.min(Math.max(limit, 0), codes.size());
        StringBuilder body = new StringBuilder(String.join(", ", codes.subList(0, shown)));
        if (codes.size() > shown) {
            body.append(", …（共 ").append(codes.size()).append(" 只，前 ").append(shown).append("）");
        }
        return "<watchlist>" + body + "</watchlist>";
    }

    /**
     * 持仓汇总段：数字字符串逐字嵌入（toPlainString 直出）；总盈亏正值补「+」、负值保留「-」；
     * {@code pnlRatio} 为 null/空白时省略全角括注（当前 overview 无盈亏比字段，双路均传 null）。
     */
    public static String holdings(String totalAssets, String totalPnl, String pnlRatio,
            int positions, String cash) {
        String pnl = totalPnl.startsWith("-") ? "-¥" + totalPnl.substring(1) : "+¥" + totalPnl;
        String ratio = pnlRatio == null || pnlRatio.isBlank() ? "" : "（" + pnlRatio + "）";
        return "<holdings_summary>总资产 ¥" + totalAssets + "，总盈亏 " + pnl + ratio
                + "，持仓 " + positions + " 只，现金 ¥" + cash + "</holdings_summary>";
    }

    /** 整块包裹：user_profile 标签 + 禁传头注 + 各段换行连接；调用方保证 sections 非空。 */
    public static String wrap(List<String> sections) {
        return "<user_profile>\n" + HEADER_NOTE + "\n" + String.join("\n", sections) + "\n</user_profile>";
    }
}
