package com.portfolio.invest.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 情报公告 PDF 抓取白名单（B7 SSRF 纵深防御）：PdfFetcher 仅允许 HTTPS 且主机在白名单
 * 内的地址（初始 URL 与每次重定向目标均校验）。默认可信源与 collector 两源落库主机一致
 * （巨潮静态站 / 东财 PDF CDN）。独立配置类，不动 InvestProperties 主文件。
 */
@ConfigurationProperties(prefix = "invest.intelligence.pdf")
public class IntelligencePdfProperties {

    /** 默认可信源：巨潮静态站 + 东财 PDF CDN。 */
    private List<String> allowedHosts = new ArrayList<>(
            List.of("static.cninfo.com.cn", "pdf.dfcfw.com"));

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }
}
