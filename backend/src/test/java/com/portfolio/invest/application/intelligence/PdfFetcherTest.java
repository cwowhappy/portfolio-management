package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 公告 PDF 下载器（JDK HttpServer 本地回环 + 真 JDK HttpClient，零新测试依赖）：
 * 200 正常字节返回、302 重定向跟随、20MB 上限拒绝（Content-Length 头快判 + 无头封顶读
 * 双路）、非 200/超时/非法 URL 抛受检 PdfFetchException（含 URL 与原因）、巨潮相对路径
 * 拼接 static.cninfo.com.cn 前缀（collector 落库为完整 URL，相对路径为防御分支）。
 */
class PdfFetcherTest {

    /** 与被测常量一致的上限（20MB）。 */
    private static final int MAX_BYTES = 20 * 1024 * 1024;

    private HttpServer server;
    private ExecutorService executor;
    private String base;
    private PdfFetcher fetcher;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        fetcher = new PdfFetcher(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofMillis(500)).build(),
                Duration.ofMillis(500));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    @DisplayName("给定200响应，when下载，then返回响应体字节")
    void givenOkResponse_whenDownload_thenBodyBytesReturned() throws Exception {
        byte[] pdf = "%PDF-1.4 公告正文".getBytes(StandardCharsets.UTF_8);
        server.createContext("/final.pdf", ex -> respond(ex, 200, pdf));

        byte[] out = fetcher.download(base + "/final.pdf");

        assertThat(out).isEqualTo(pdf);
    }

    @Test
    @DisplayName("给定302重定向，when下载，then跟随重定向取最终字节")
    void givenRedirect_whenDownload_thenFollowsToFinalBody() throws Exception {
        byte[] pdf = "%PDF-1.4 重定向目标".getBytes(StandardCharsets.UTF_8);
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", base + "/final.pdf");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/final.pdf", ex -> respond(ex, 200, pdf));

        byte[] out = fetcher.download(base + "/redirect");

        assertThat(out).isEqualTo(pdf);
    }

    @Test
    @DisplayName("给定Content-Length超20MB，when下载，then未读正文即拒绝（受检异常含URL）")
    void givenOversizedContentLength_whenDownload_thenRejectedBeforeBodyRead() {
        server.createContext("/huge", ex -> {
            ex.sendResponseHeaders(200, (long) MAX_BYTES + 1);
            ex.close();
        });

        assertThatThrownBy(() -> fetcher.download(base + "/huge"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining(base + "/huge")
                .hasMessageContaining("20");
    }

    @Test
    @DisplayName("给定无Content-Length的超限分块正文，when下载，then封顶读取后拒绝")
    void givenOversizedChunkedBody_whenDownload_thenRejectedByCappedRead() {
        server.createContext("/chunked-huge", ex -> {
            ex.sendResponseHeaders(200, 0); // 0 = chunked（无 Content-Length）
            try (OutputStream os = ex.getResponseBody()) {
                byte[] megabyte = new byte[1024 * 1024];
                for (int i = 0; i <= MAX_BYTES / megabyte.length; i++) { // 共 21MB
                    os.write(megabyte);
                }
            } catch (IOException ignored) {
                // 客户端超限即断开，服务端写中断属预期
            }
        });

        assertThatThrownBy(() -> fetcher.download(base + "/chunked-huge"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining("超");
    }

    @Test
    @DisplayName("给定非200响应，when下载，then抛受检异常且消息含URL与状态码")
    void givenNonOkStatus_whenDownload_thenExceptionWithUrlAndStatus() {
        server.createContext("/gone", ex -> respond(ex, 404, new byte[0]));

        assertThatThrownBy(() -> fetcher.download(base + "/gone"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining(base + "/gone")
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("给定服务端挂起不响应，when下载，then超时抛受检异常且不按默认10s等待")
    void givenHangingServer_whenDownload_thenTimeoutExceptionQuickly() {
        server.createContext("/hang", ex -> {
            try {
                Thread.sleep(5000); // 远超注入的 500ms 超时
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long start = System.nanoTime();

        assertThatThrownBy(() -> fetcher.download(base + "/hang"))
                .isInstanceOf(PdfFetcher.PdfFetchException.class)
                .hasMessageContaining(base + "/hang");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).isLessThan(4000); // 默认 10s 未生效，注入超时真实起作用
    }

    @Test
    @DisplayName("给定巨潮相对路径，when规范化，then拼接static.cninfo.com.cn前缀")
    void givenCninfoRelativePath_whenNormalize_thenPrefixedWithStaticBase() throws Exception {
        assertThat(PdfFetcher.normalize("/finalpage/2026/ann.pdf"))
                .isEqualTo("https://static.cninfo.com.cn/finalpage/2026/ann.pdf");
        assertThat(PdfFetcher.normalize("https://static.cninfo.com.cn/finalpage/x.pdf"))
                .isEqualTo("https://static.cninfo.com.cn/finalpage/x.pdf");
    }

    @Test
    @DisplayName("给定空或非HTTP地址，when下载，then抛受检异常（含原始URL）")
    void givenBlankOrNonHttpUrl_whenDownload_thenRejected() {
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
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, byte[] body)
            throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream os = ex.getResponseBody()) {
            if (body.length > 0) {
                os.write(body);
            }
        }
    }
}
