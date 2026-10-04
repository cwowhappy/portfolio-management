package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.config.IntelligencePdfProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 公告 PDF 下载器（MS-21 D18 前半）：{@code intelligence_announcement.pdf_url} → 原始字节，
 * 供 {@link AnnouncementPdfTextPort} 内存解析（全程零落盘）。JDK HttpClient 形态照
 * FeishuClient 先例：HTTP/1.1、连接与请求双 10s 超时；重定向手动逐跳跟随（B7：每跳目标
 * 均须通过 {@link #validateUrl} 白名单校验，内嵌 Tomcat/JDK NORMAL 跟随无法逐跳拦截）。
 *
 * <p>防御（B7 SSRF 纵深）：{@code pdf_url} 虽来自 collector 落库，但一旦解析链路被污染，
 * 后端即成内网跳板——故初始 URL 与每次重定向目标都校验「HTTPS 且主机在白名单」
 * （{@code invest.intelligence.pdf.allowed-hosts}，默认巨潮/东财两源），非法地址在发起
 * 请求前即抛 {@link PdfFetchException}（消息只含待抓 URL 本身，不携带内网细节）。
 *
 * <p>入口字节上限 {@link #MAX_BYTES}（20MB，与 PDF 解析端口同限）双路拒收——
 * Content-Length 头快判（超限不读正文即拒绝）+ 无头/短头封顶读（读至 MAX+1 字节即拒绝），
 * 防超大文件 OOM。失败一律抛受检 {@link PdfFetchException}，调用方
 * （AnnouncementExtractionService）按单条 FAILED 终态处理。
 *
 * <p>URL 形态：collector 两源落库均为完整 URL（巨潮 = static.cninfo.com.cn/ + adjunctUrl
 * 已在采集侧拼接、东财 = pdf.dfcfw.com 直链，announcements.py 实况核实）；以 {@code /}
 * 开头的巨潮相对路径为防御分支，规范化时补 static.cninfo.com.cn 前缀。
 */
@Component
public class PdfFetcher {

    /** 下载字节上限（20MB）：与 PdfboxAnnouncementPdfTextPort 的解析上限同限。 */
    static final int MAX_BYTES = 20 * 1024 * 1024;

    /** 重定向跟随上限（手动逐跳校验，防重定向环）。 */
    static final int MAX_REDIRECTS = 5;

    /** 巨潮静态站前缀（相对路径防御分支的拼接基）。 */
    private static final String CNINFO_STATIC_BASE = "https://static.cninfo.com.cn";

    /** 采集源同款 UA（部分 CDN 拒绝默认 Java UA）。 */
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)";

    private final HttpClient http;
    private final Duration requestTimeout;
    private final List<String> allowedHosts;

    @Autowired
    public PdfFetcher(IntelligencePdfProperties pdfProps) {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NEVER) // 重定向由本类逐跳校验后手动跟随
                        .connectTimeout(Duration.ofSeconds(10)).build(),
                Duration.ofSeconds(10), pdfProps);
    }

    /** 测试构造器：注入 HttpClient 与请求超时（本地回环/桩定服务可缩短超时加速用例）。 */
    PdfFetcher(HttpClient http, Duration requestTimeout, IntelligencePdfProperties pdfProps) {
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.allowedHosts = List.copyOf(pdfProps.getAllowedHosts());
    }

    /**
     * 下载公告 PDF 原始字节。
     *
     * @param url 完整 HTTPS 白名单地址（或巨潮 {@code /} 开头相对路径）
     * @return PDF 原始字节（≤ {@link #MAX_BYTES}）
     * @throws PdfFetchException URL 非法 / 主机不在白名单 / 非 200 / 重定向越界 / 超时 /
     *                           网络失败 / 超过字节上限（消息含 URL 与原因）
     */
    public byte[] download(String url) throws PdfFetchException {
        try {
            URI target = validateUrl(URI.create(normalize(url)));
            for (int hop = 0; ; hop++) {
                HttpRequest request = HttpRequest.newBuilder(target)
                        .timeout(requestTimeout)
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (isRedirect(response.statusCode())) {
                    if (hop >= MAX_REDIRECTS) {
                        throw new PdfFetchException("公告 PDF 重定向次数超限（" + url + "）");
                    }
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new PdfFetchException("公告 PDF 重定向缺少 Location（" + url + "）"));
                    response.body().close(); // 弃用响应体即关流，释放连接
                    // 逐跳校验：相对 Location 先对当前 URI 解析，防 302 跳板到内网/白名单外
                    target = validateUrl(target.resolve(URI.create(location.trim())));
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new PdfFetchException("公告 PDF 下载失败: HTTP " + response.statusCode()
                            + "（" + url + "）");
                }
                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (contentLength > MAX_BYTES) { // 头快判：超限不读正文即拒绝
                    throw new PdfFetchException("公告 PDF 超过字节上限 " + MAX_BYTES
                            + "（Content-Length " + contentLength + "，" + url + "）");
                }
                try (InputStream body = response.body()) {
                    byte[] bytes = body.readNBytes(MAX_BYTES + 1); // 封顶读：无头/短头时读至 MAX+1 即止
                    if (bytes.length > MAX_BYTES) {
                        throw new PdfFetchException("公告 PDF 超过字节上限 " + MAX_BYTES
                                + "（实际已读 " + bytes.length + "+，" + url + "）");
                    }
                    return bytes;
                }
            }
        } catch (PdfFetchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断位再包装
            throw new PdfFetchException("公告 PDF 下载被中断（" + url + "）", e);
        } catch (Exception e) { // IOException/IllegalArgumentException 等统一包装（含 URL 与原因）
            throw new PdfFetchException("公告 PDF 下载失败（" + url + "）: " + e.getMessage(), e);
        }
    }

    /**
     * URL 白名单校验（B7 纯函数）：scheme 必须 https、host 必须在白名单内（均小写比较）。
     * 初始 URL 与每次重定向目标都经此校验，非法即在发起请求前拒绝。
     */
    static void validateUrl(URI uri, List<String> allowedHosts) throws PdfFetchException {
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new PdfFetchException("公告 PDF URL 仅允许 HTTPS: " + uri);
        }
        String host = uri.getHost();
        if (host == null || allowedHosts.stream().noneMatch(host::equalsIgnoreCase)) {
            throw new PdfFetchException("公告 PDF URL 主机不在白名单: " + uri);
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    /** 实例级校验入口：注入配置的白名单（纯函数 {@link #validateUrl(URI, List)} 的柯里化）。 */
    private URI validateUrl(URI uri) throws PdfFetchException {
        validateUrl(uri, allowedHosts);
        return uri;
    }

    /**
     * URL 规范化：完整 http(s) 地址原样（http 与否由 {@link #validateUrl} 拦截）；
     * {@code /} 开头的巨潮相对路径补 static.cninfo.com.cn 前缀；空值拒绝。
     */
    static String normalize(String url) throws PdfFetchException {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) {
            throw new PdfFetchException("公告 PDF URL 为空");
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/")) {
            return CNINFO_STATIC_BASE + trimmed;
        }
        throw new PdfFetchException("公告 PDF URL 非 HTTP(S) 地址: " + trimmed);
    }

    /** 下载失败受检异常（调用方标单条 FAILED 终态；消息含 URL 与原因）。 */
    public static class PdfFetchException extends Exception {

        public PdfFetchException(String message) {
            super(message);
        }

        public PdfFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
