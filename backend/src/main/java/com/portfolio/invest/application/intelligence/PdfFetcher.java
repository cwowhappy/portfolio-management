package com.portfolio.invest.application.intelligence;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 公告 PDF 下载器（MS-21 D18 前半）：{@code intelligence_announcement.pdf_url} → 原始字节，
 * 供 {@link AnnouncementPdfTextPort} 内存解析（全程零落盘）。JDK HttpClient 形态照
 * FeishuClient 先例：HTTP/1.1、连接与请求双 10s 超时、重定向跟随（{@code Redirect.NORMAL}，
 * 巨潮/东财 CDN 常见 302）。
 *
 * <p>防御：入口字节上限 {@link #MAX_BYTES}（20MB，与 PDF 解析端口同限）双路拒收——
 * Content-Length 头快判（超限不读正文即拒绝）+ 无头/短头封顶读（读至 MAX+1 字节即拒绝），
 * 防超大文件 OOM。失败一律抛受检 {@link PdfFetchException}（含 URL 与原因），调用方
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

    /** 巨潮静态站前缀（相对路径防御分支的拼接基）。 */
    private static final String CNINFO_STATIC_BASE = "https://static.cninfo.com.cn";

    /** 采集源同款 UA（部分 CDN 拒绝默认 Java UA）。 */
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)";

    private final HttpClient http;
    private final Duration requestTimeout;

    @Autowired
    public PdfFetcher() {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10)).build(),
                Duration.ofSeconds(10));
    }

    /** 测试构造器：注入 HttpClient 与请求超时（本地回环服务可缩短超时加速用例）。 */
    PdfFetcher(HttpClient http, Duration requestTimeout) {
        this.http = http;
        this.requestTimeout = requestTimeout;
    }

    /**
     * 下载公告 PDF 原始字节。
     *
     * @param url 完整 HTTP(S) 地址（或巨潮 {@code /} 开头相对路径）
     * @return PDF 原始字节（≤ {@link #MAX_BYTES}）
     * @throws PdfFetchException URL 非法 / 非 200 / 超时 / 网络失败 / 超过字节上限（消息含 URL 与原因）
     */
    public byte[] download(String url) throws PdfFetchException {
        try {
            URI target = URI.create(normalize(url));
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(requestTimeout)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
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
     * URL 规范化：完整 http(s) 地址原样；{@code /} 开头的巨潮相对路径补 static.cninfo.com.cn
     * 前缀；空值与非 HTTP(S) 地址拒绝。
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
