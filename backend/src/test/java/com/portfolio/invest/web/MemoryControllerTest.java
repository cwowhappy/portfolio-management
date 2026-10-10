package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.memory.CuratedMutationResult;
import com.portfolio.invest.application.memory.LedgerFileView;
import com.portfolio.invest.application.memory.LedgerSection;
import com.portfolio.invest.application.memory.MemoryConflictException;
import com.portfolio.invest.application.memory.MemoryFileView;
import com.portfolio.invest.application.memory.MemoryFilesView;
import com.portfolio.invest.application.memory.MemoryManagementApplicationService;
import com.portfolio.invest.application.memory.MemoryReadOnlyException;
import com.portfolio.invest.application.memory.MemoryStaleException;
import com.portfolio.invest.application.memory.ProfileConfigApplicationService;
import com.portfolio.invest.application.memory.ProfileConfigView;
import com.portfolio.invest.application.memory.SectionKind;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 记忆管理四端点切片（MS-31 B3）：GET 全视图（视图 record 字段名逐字透传）、删/编策展层行、
 * 删日账本节。业务编排打桩；乐观并发三层异常经 GlobalExceptionHandler 映射 409/410/400；
 * wire 参数校验（B2 评审 Minor-2：newLine 非空白且不含换行、expectLine/baseHash/sectionHeader
 * 非空）→ 400 INVALID_REQUEST 且不触达服务。安全用 Boot 默认链（需认证 + CSRF 开启），与
 * {@link IndustryWatchControllerTest} 同构——/api/memory 不在公开前缀，天然需登录、本人隔离。
 */
@WebMvcTest(MemoryController.class)
class MemoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MemoryManagementApplicationService service;

    @MockitoBean
    private ProfileConfigApplicationService profileConfigService;

    /** 构造已认证主体：控制器 currentUserId(auth) 会 cast auth.getPrincipal() 为 AuthenticatedUser。 */
    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    /** 预览 Map 允许 null 段值（失败/为空段），Map.of 不收 null 故手工构建。 */
    private static Map<String, String> preview(String style, String industry, String watchlist, String holdings) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(ProfileConfigApplicationService.KEY_STYLE, style);
        map.put(ProfileConfigApplicationService.KEY_INDUSTRY, industry);
        map.put(ProfileConfigApplicationService.KEY_WATCHLIST, watchlist);
        map.put(ProfileConfigApplicationService.KEY_HOLDINGS, holdings);
        return map;
    }

    @Test
    @DisplayName("GET 全视图返回 200，视图 record 字段名逐字透传（curated/ledgers/sections/kind）")
    void givenMemoryFiles_whenGet_thenReturnFullView() throws Exception {
        when(service.load(1L)).thenReturn(new MemoryFilesView(
                new MemoryFileView("hash-curated",
                        List.of("# MEMORY.md — Curated Long-Term Memory", "## 偏好", "- 偏好白酒股")),
                List.of(new LedgerFileView(LocalDate.of(2026, 10, 9), "hash-ledger", List.of(
                        new LedgerSection("## Memory Flush — 2026-10-09T13:58:45Z", SectionKind.FLUSH,
                                List.of("这是要点")),
                        new LedgerSection("## 外部事件摘要 — 2026-10-10（a ~ b）", SectionKind.EXTERNAL_EVENT,
                                List.of("- 买入 600519")))))));

        mvc.perform(get("/api/memory").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.curated.hash").value("hash-curated"))
                .andExpect(jsonPath("$.curated.lines[2]").value("- 偏好白酒股"))
                .andExpect(jsonPath("$.ledgers[0].date").value("2026-10-09"))
                .andExpect(jsonPath("$.ledgers[0].hash").value("hash-ledger"))
                .andExpect(jsonPath("$.ledgers[0].sections[0].header").value("## Memory Flush — 2026-10-09T13:58:45Z"))
                .andExpect(jsonPath("$.ledgers[0].sections[0].kind").value("FLUSH"))
                .andExpect(jsonPath("$.ledgers[0].sections[1].kind").value("EXTERNAL_EVENT"));
    }

    @Test
    @DisplayName("GET 记忆文件缺失返回 200 空视图（hash=null、lines=[]）")
    void givenNoFiles_whenGet_thenReturnEmptyView() throws Exception {
        when(service.load(1L)).thenReturn(new MemoryFilesView(new MemoryFileView(null, List.of()), List.of()));

        mvc.perform(get("/api/memory").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.curated.hash").value((Object) null))
                .andExpect(jsonPath("$.curated.lines").isEmpty())
                .andExpect(jsonPath("$.ledgers").isEmpty());
    }

    @Test
    @DisplayName("删策展层行返回 200 与 purgedLedgerLines 计数，本人 userId 注入透传")
    void givenCuratedLine_whenDelete_thenReturnPurgedCount() throws Exception {
        when(service.deleteCuratedLine(1L, 2, "- 偏好白酒股", "hash-curated"))
                .thenReturn(new CuratedMutationResult(1));

        mvc.perform(delete("/api/memory/curated").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":2,\"expectLine\":\"- 偏好白酒股\",\"baseHash\":\"hash-curated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedLedgerLines").value(1));

        verify(service).deleteCuratedLine(1L, 2, "- 偏好白酒股", "hash-curated");
    }

    @Test
    @DisplayName("编策展层行返回 204 无响应体，本人 userId 注入透传")
    void givenCuratedLine_whenEdit_then204AndDelegate() throws Exception {
        mvc.perform(put("/api/memory/curated").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":2,\"expectLine\":\"- 偏好白酒股\","
                                + "\"newLine\":\"- 偏好红酒股\",\"baseHash\":\"hash-curated\"}"))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$").doesNotExist());

        verify(service).editCuratedLine(1L, 2, "- 偏好白酒股", "- 偏好红酒股", "hash-curated");
    }

    @Test
    @DisplayName("删日账本节返回 204，date 路径变量解析为 LocalDate 透传")
    void givenLedgerSection_whenDelete_then204AndDelegate() throws Exception {
        mvc.perform(delete("/api/memory/ledger/2026-10-09").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sectionHeader\":\"## Memory Flush — 2026-10-09T13:58:45Z\","
                                + "\"baseHash\":\"hash-ledger\"}"))
                .andExpect(status().isNoContent());

        verify(service).deleteLedgerSection(1L, LocalDate.of(2026, 10, 9),
                "## Memory Flush — 2026-10-09T13:58:45Z", "hash-ledger");
    }

    @Test
    @DisplayName("GET profile-config 返回 200：开关+预览同刻同源（§3.5 四段 enabled/preview）")
    void givenConfigAndPreview_whenGetProfileConfig_thenReturnEnabledAndPreviewPerSection() throws Exception {
        when(profileConfigService.config(1L))
                .thenReturn(new ProfileConfigView(true, false, true, false));
        when(profileConfigService.preview(1L)).thenReturn(preview(
                "<risk_profile>平衡型</risk_profile>",
                "<watched_industries>801017, 801120</watched_industries>", null, null));

        mvc.perform(get("/api/memory/profile-config").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.style.enabled").value(true))
                .andExpect(jsonPath("$.style.preview").value("<risk_profile>平衡型</risk_profile>"))
                .andExpect(jsonPath("$.industry.enabled").value(false))
                .andExpect(jsonPath("$.industry.preview")
                        .value("<watched_industries>801017, 801120</watched_industries>"))
                .andExpect(jsonPath("$.watchlist.enabled").value(true))
                .andExpect(jsonPath("$.watchlist.preview").value((Object) null))
                .andExpect(jsonPath("$.holdings.enabled").value(false))
                .andExpect(jsonPath("$.holdings.preview").value((Object) null));
    }

    @Test
    @DisplayName("PUT profile-config 返回 200 保存回执（保存后开关+预览最新态）")
    void givenSaveRequest_whenPutProfileConfig_thenSaveAndReturnLatestState() throws Exception {
        when(profileConfigService.config(1L))
                .thenReturn(new ProfileConfigView(true, false, true, false));
        when(profileConfigService.preview(1L)).thenReturn(preview(
                "<risk_profile>平衡型</risk_profile>", null, null, null));

        mvc.perform(put("/api/memory/profile-config").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"style\":true,\"industry\":false,\"watchlist\":true,\"holdings\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.style.enabled").value(true))
                .andExpect(jsonPath("$.style.preview").value("<risk_profile>平衡型</risk_profile>"))
                .andExpect(jsonPath("$.holdings.enabled").value(false));

        verify(profileConfigService).save(1L, true, false, true, false);
    }

    @Test
    @DisplayName("未登录访问 profile-config 两端点均 401")
    void givenAnonymous_whenAccessProfileConfig_then401() throws Exception {
        mvc.perform(get("/api/memory/profile-config"))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/memory/profile-config").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"style\":true,\"industry\":true,\"watchlist\":true,\"holdings\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("读后写前文件被改动（baseHash 不符）映射 409 MEMORY_CONFLICT")
    void givenHashConflict_whenDeleteCurated_then409() throws Exception {
        when(service.deleteCuratedLine(eq(1L), anyInt(), any(), any()))
                .thenThrow(new MemoryConflictException("记忆文件已被修改，请刷新后重试"));

        mvc.perform(delete("/api/memory/curated").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":2,\"expectLine\":\"- 偏好白酒股\",\"baseHash\":\"stale\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMORY_CONFLICT"));
    }

    @Test
    @DisplayName("行寻址漂移（lineIndex/expectLine 失配）映射 410 MEMORY_STALE")
    void givenLineDrift_whenEditCurated_then410() throws Exception {
        doThrow(new MemoryStaleException("目标行已漂移"))
                .when(service).editCuratedLine(eq(1L), anyInt(), any(), any(), any());

        mvc.perform(put("/api/memory/curated").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":2,\"expectLine\":\"- 已变\",\"newLine\":\"- 新\",\"baseHash\":\"h\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("MEMORY_STALE"));
    }

    @Test
    @DisplayName("命中只读标题行映射 400 MEMORY_READ_ONLY")
    void givenTitleLine_whenDeleteCurated_then400() throws Exception {
        when(service.deleteCuratedLine(eq(1L), anyInt(), any(), any()))
                .thenThrow(new MemoryReadOnlyException("标题行只读"));

        mvc.perform(delete("/api/memory/curated").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":0,\"expectLine\":\"# MEMORY.md\",\"baseHash\":\"h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_READ_ONLY"));
    }

    @Test
    @DisplayName("编辑请求 newLine 含换行返回 400 且不触达服务（B2 评审 Minor-2）")
    void givenNewLineWithLineBreak_whenEdit_then400NoServiceCall() throws Exception {
        mvc.perform(put("/api/memory/curated").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":2,\"expectLine\":\"- a\","
                                + "\"newLine\":\"- b\\n- c\",\"baseHash\":\"h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("编辑请求 newLine 空白返回 400 且不触达服务")
    void givenBlankNewLine_whenEdit_then400NoServiceCall() throws Exception {
        mvc.perform(put("/api/memory/curated").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":2,\"expectLine\":\"- a\",\"newLine\":\"   \",\"baseHash\":\"h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("删策展层行 expectLine/baseHash 缺失返回 400 且不触达服务")
    void givenMissingExpectLineOrHash_whenDeleteCurated_then400NoServiceCall() throws Exception {
        mvc.perform(delete("/api/memory/curated").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("删日账本节 sectionHeader 空白返回 400 且不触达服务")
    void givenBlankSectionHeader_whenDeleteLedger_then400NoServiceCall() throws Exception {
        mvc.perform(delete("/api/memory/ledger/2026-10-09").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sectionHeader\":\"\",\"baseHash\":\"h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("未登录访问四端点均 401（/api/memory 不在公开前缀）")
    void givenAnonymous_whenAccessFourEndpoints_then401() throws Exception {
        mvc.perform(get("/api/memory"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/memory/curated").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":2,\"expectLine\":\"- a\",\"baseHash\":\"h\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/memory/curated").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIndex\":2,\"expectLine\":\"- a\",\"newLine\":\"- b\",\"baseHash\":\"h\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/memory/ledger/2026-10-09").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sectionHeader\":\"## s\",\"baseHash\":\"h\"}"))
                .andExpect(status().isUnauthorized());
    }
}
