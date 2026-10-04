package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.IntelligencePdfProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.OngoingStubbing;

/**
 * 公告 PDF 下载器（B7 SSRF 纵深防御，mock HttpClient 层）：初始 URL 与每次重定向目标
 * 均须 HTTPS 且主机在白名单内（默认 static.cninfo.com.cn / pdf.dfcfw.com），非法地址在
 * 发起请求前即抛 {@link PdfFetcher.PdfFetchException}（不泄漏内网细节）；字节上限双路拒收、
 * 非 200、请求超时接线、巨潮相对路径拼接等既有语义回归。
 */
class PdfFetcherTest {

    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(500);

    private HttpClient http;
    private PdfFetcher fetcher;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        fetcher = new PdfFetcher(http, REQUEST_TIMEOUT, new IntelligencePdfProperties());
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, HttpHeaders headers, InputStream body) {
        HttpResponse<InputStream> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.headers()).thenReturn(headers);
        when(r.body()).thenReturn(body);
        return r;
    }

    private static HttpHeaders headers(String... kvs) {
        Map<String, List<String>> map = new HashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            map.put(kvs[i], List.of(kvs[i + 1]));
        }
        return HttpHeaders.of(map, (k, v) -> true);
    }

    private static InputStream bytes(byte[] body) {
        return new ByteArrayInputStream(body);
    }

    /** 预置 send 桩（顺序响应；send 的受检异常仅为编译期声明，打桩本身不触发）。 */
    private void respond(HttpResponse<InputStream> first, HttpResponse<InputStream>... rest) {
        stubSequentially(first, rest);
    }

    @SuppressWarnings("unchecked")
    private void stubSequentially(HttpResponse<InputStream> first, HttpResponse<InputStream>... rest) {
        try {
            OngoingStubbing<HttpResponse<InputStream>> stubbing =
                    when(http.<InputStream>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
            stubbing = stubbing.thenReturn(first);
            for (HttpResponse<InputStream> r : rest) {
                stubbing = stubbing.thenReturn(r);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e); // 打桩路径不可达
        }
    }

    // ── 白名单/协议校验（SSRF 纵深） ────────────────────────────────

    @Test
    @DisplayName("白名单主机 https 200：正常返回字节（回归）")
    void givenWhitelistedHttpsUrl_whenDownload_thenBodyBytesReturned() throws Exception {
        byte[] pdf = "%PDF-1.4 公告正文".getBytes(StandardCharsets.UTF_8);
        respond(response(200, headers("Content-Length", String.valueOf(pdf.length)), bytes(pdf)));

        byte[] out = fetcher.download("https://static.cninfo.com.cn/finalpage/2026/ann.pdf");

        assertThat(out).isEqualTo(pdf);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        assertThat(captor.getValue().uri()).isEqualTo(URI.create("https://static.cninfo.com.cn/finalpage/2026/ann.pdf"));
        // 请求超时接线（既有 10s/注入值不得回归为无限）
        assertThat(captor.getValue().timeout()).hasValue(REQUEST_TIMEOUT);
    }

    @Test
    @DisplayName("非白名单主机（公网域名）：拒绝且不发起任何请求")
    void givenNonWhitelistedHost_whenDownload_thenRejectedBeforeRequest() {
        assertThatThrownBy(() -> fetcher.download("https://evil.example.com/x.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("白名单");
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("非白名单主机（内网回环 IP）：拒绝且不发起任何请求")
    void givenLoopbackIp_whenDownload_thenRejectedBeforeRequest() {
        assertThatThrownBy(() -> fetcher.download("https://127.0.0.1:8080/internal.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class);
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("非白名单主机（内网域名）：拒绝且不发起任何请求")
    void givenIntranetHost_whenDownload_thenRejectedBeforeRequest() {
        assertThatThrownBy(() -> fetcher.download("https://nas.intra.local/share/report.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class);
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("http（非 https）：拒绝且不发起任何请求")
    void givenPlainHttp_whenDownload_thenRejectedBeforeRequest() {
        assertThatThrownBy(() -> fetcher.download("http://static.cninfo.com.cn/x.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("HTTPS");
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("巨潮相对路径：补 static.cninfo.com.cn 前缀后通过校验并发起请求")
    void givenCninfoRelativePath_whenDownload_thenPrefixedAndFetched() throws Exception {
        byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        respond(response(200, headers("Content-Length", String.valueOf(pdf.length)), bytes(pdf)));

        fetcher.download("/finalpage/2026/ann.pdf");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        assertThat(captor.getValue().uri())
                .isEqualTo(URI.create("https://static.cninfo.com.cn/finalpage/2026/ann.pdf"));
    }

    @Test
    @DisplayName("scheme/host 大小写变体：HTTPS 大写与白名单主机大写均放行（小写比较）")
    void givenUppercaseSchemeAndHost_whenValidateUrl_thenAccepted() {
        assertThatCode(() -> PdfFetcher.validateUrl(
                URI.create("HTTPS://STATIC.CNINFO.COM.CN/x.pdf"),
                List.of("static.cninfo.com.cn")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("无主机 URI（https:///path）：host 为空拒绝")
    void givenMissingHost_whenValidateUrl_thenRejected() {
        assertThatThrownBy(() -> PdfFetcher.validateUrl(URI.create("https:///path/x.pdf"),
                List.of("static.cninfo.com.cn")))
                .isInstanceOf(PdfFetcher.PdfFetchException.class);
    }

    // ── 重定向逐跳校验 ─────────────────────────────────────────────

    @Test
    @DisplayName("302 到白名单内主机：跟随并重取最终字节")
    void givenRedirectToWhitelistedHost_whenDownload_thenFollowsToFinalBody() throws Exception {
        byte[] pdf = "%PDF-1.4 重定向目标".getBytes(StandardCharsets.UTF_8);
        respond(
                response(302, headers("Location", "https://pdf.dfcfw.com/final.pdf"),
                        InputStream.nullInputStream()),
                response(200, headers("Content-Length", String.valueOf(pdf.length)), bytes(pdf)));

        byte[] out = fetcher.download("https://static.cninfo.com.cn/redirect.pdf");

        assertThat(out).isEqualTo(pdf);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(captor.capture(), any());
        assertThat(captor.getAllValues().get(1).uri())
                .isEqualTo(URI.create("https://pdf.dfcfw.com/final.pdf"));
    }

    @Test
    @DisplayName("302 到白名单外主机（内网跳板）：拒绝，第二个请求不得发出")
    void givenRedirectToNonWhitelistedHost_whenDownload_thenRejectedAndSecondRequestNeverSent() throws Exception {
        respond(response(302, headers("Location", "https://127.0.0.1:8443/internal.pdf"),
                InputStream.nullInputStream()));

        assertThatThrownBy(() -> fetcher.download("https://static.cninfo.com.cn/redirect.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("白名单");
        verify(http, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("相对 Location：对当前 URI 解析后再校验")
    void givenRelativeRedirectLocation_whenDownload_thenResolvedAgainstCurrentUri() throws Exception {
        byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        respond(
                response(302, headers("Location", "/next/ann.pdf"), InputStream.nullInputStream()),
                response(200, headers("Content-Length", String.valueOf(pdf.length)), bytes(pdf)));

        byte[] out = fetcher.download("https://static.cninfo.com.cn/dir/start.pdf");

        assertThat(out).isEqualTo(pdf);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(captor.capture(), any());
        assertThat(captor.getAllValues().get(1).uri())
                .isEqualTo(URI.create("https://static.cninfo.com.cn/next/ann.pdf"));
    }

    @Test
    @DisplayName("重定向次数超限（>5 跳）：拒绝，防重定向环")
    void givenRedirectLoop_whenDownload_thenRejectedAfterCap() throws Exception {
        HttpResponse<InputStream> hop = response(302,
                headers("Location", "https://static.cninfo.com.cn/loop.pdf"), InputStream.nullInputStream());
        HttpResponse<InputStream>[] hops = new HttpResponse[8];
        Arrays.fill(hops, hop);
        respond(hops[0], Arrays.copyOfRange(hops, 1, hops.length));

        assertThatThrownBy(() -> fetcher.download("https://static.cninfo.com.cn/loop.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("重定向");
        verify(http, times(6)).send(any(HttpRequest.class), any()); // 上限 5 跳：第 6 次响应即拒绝
    }

    // ── 既有语义回归 ───────────────────────────────────────────────

    @Test
    @DisplayName("给定Content-Length超20MB：未读正文即拒绝（受检异常含URL）")
    void givenOversizedContentLength_whenDownload_thenRejectedBeforeBodyRead() throws Exception {
        respond(response(200, headers("Content-Length", String.valueOf(MAX_BYTES + 1)),
                InputStream.nullInputStream()));

        assertThatThrownBy(() -> fetcher.download("https://static.cninfo.com.cn/huge.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("huge.pdf")
                .hasMessageContaining("20");
    }

    @Test
    @DisplayName("给定无Content-Length的超限正文：封顶读取后拒绝")
    void givenOversizedBodyWithoutContentLength_whenDownload_thenRejectedByCappedRead() throws Exception {
        InputStream big = new InputStream() {
            private int remaining = MAX_BYTES + 1;

            @Override
            public int read() {
                return remaining-- > 0 ? 0x41 : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                int n = Math.min(len, remaining);
                remaining -= n;
                Arrays.fill(b, off, off + n, (byte) 0x41);
                return n;
            }
        };
        respond(response(200, headers(), big));

        assertThatThrownBy(() -> fetcher.download("https://pdf.dfcfw.com/chunked-huge.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("超");
    }

    @Test
    @DisplayName("给定非200响应：抛受检异常且消息含URL与状态码")
    void givenNonOkStatus_whenDownload_thenExceptionWithUrlAndStatus() throws Exception {
        respond(response(404, headers(), InputStream.nullInputStream()));

        assertThatThrownBy(() -> fetcher.download("https://static.cninfo.com.cn/gone.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("gone.pdf")
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("给定空/非法URL：抛受检异常（含原始URL）")
    void givenBlankOrIllegalUrl_whenDownload_thenRejected() {
        assertThatThrownBy(() -> fetcher.download(null))
                .isInstanceOf(PdfFetcher.PdfFetchException.class);
        assertThatThrownBy(() -> fetcher.download("  "))
                .isInstanceOf(PdfFetcher.PdfFetchException.class);
        assertThatThrownBy(() -> fetcher.download("ftp://static.cninfo.com.cn/x.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("ftp://static.cninfo.com.cn/x.pdf");
        // 非法字符的 http 地址（URI 不可解析）同样走受检包装，不外抛运行时异常
        assertThatThrownBy(() -> fetcher.download("http://a b.com/x.pdf"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("http://a b.com/x.pdf");
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("normalize：相对路径补前缀 / 完整 https 原样 / 非 HTTP(S) 拒绝（纯函数回归）")
    void givenVariousUrls_whenNormalize_thenExpected() throws Exception {
        assertThat(PdfFetcher.normalize("/finalpage/2026/ann.pdf"))
                .isEqualTo("https://static.cninfo.com.cn/finalpage/2026/ann.pdf");
        assertThat(PdfFetcher.normalize("https://pdf.dfcfw.com/x.pdf"))
                .isEqualTo("https://pdf.dfcfw.com/x.pdf");
        assertThatThrownBy(() -> PdfFetcher.normalize("not-a-url"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class);
    }
}
