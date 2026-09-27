package com.portfolio.invest.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从验证码邮件正文提取 6 位数字（集成测试随机码适配）。 */
public final class TestCodes {
    private static final Pattern SIX = Pattern.compile("\\d{6}");

    private TestCodes() {}

    public static String extractSixDigits(String mailText) {
        Matcher m = SIX.matcher(mailText);
        if (!m.find()) {
            throw new AssertionError("邮件正文未找到 6 位验证码: " + mailText);
        }
        return m.group();
    }
}
