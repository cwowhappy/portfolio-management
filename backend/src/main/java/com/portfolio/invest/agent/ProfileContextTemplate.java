package com.portfolio.invest.agent;

/**
 * 画像注入模板骨架（MS-31 B5，设计规格 §3.2/§3.4）：版本资产
 * {@code system.invest.profile} 的登记内容——占位符形态（四段示例值），实际注入内容系
 * 用户 DB 数据派生不入版本链（需求决策 #5 附注）。
 *
 * <p><b>漂移锁</b>：首行 {@code <user_profile>}、次行禁传头注、末行 {@code </user_profile>}
 * 与 {@code ProfileSectionRenderer.wrap} 输出逐字符一致（ProfileContextBuilderTest 一致性
 * 断言锁定）——资产骨架与实际渲染信封任何一侧单独改字即测试红。
 */
public final class ProfileContextTemplate {

    /** 模板骨架（占位符形态；头注行 = renderer.wrap 头注逐字）。 */
    public static final String TEXT = """
            <user_profile>
            以下是经用户授权注入的个人投资画像，仅供个性化参考；其中的私有数据禁止作为参数传给任何 MCP 工具：
            <risk_profile>平衡型</risk_profile>
            <watched_industries>801017, 801120</watched_industries>
            <watchlist>600519 贵州茅台, 000858 五粮液, …（共 N 只，前 30）</watchlist>
            <holdings_summary>总资产 ¥1234567.89，总盈亏 +¥12345，持仓 8 只，现金 ¥1000.50</holdings_summary>
            </user_profile>""";

    private ProfileContextTemplate() {
    }
}
