package com.portfolio.invest.web;

import com.portfolio.invest.application.wiki.CreatePrincipleRuleCommand;
import com.portfolio.invest.application.wiki.CreateWikiEntryCommand;
import com.portfolio.invest.application.wiki.PrincipleRuleApplicationService;
import com.portfolio.invest.application.wiki.PrincipleRuleView;
import com.portfolio.invest.application.wiki.WikiApplicationService;
import com.portfolio.invest.application.wiki.WikiEntryView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WikiControllerTest {

    private final WikiApplicationService wikiService = mock(WikiApplicationService.class);
    private final PrincipleRuleApplicationService ruleService = mock(PrincipleRuleApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WikiController(wikiService, ruleService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private org.springframework.security.core.Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    @DisplayName("创建条目返回201")
    @Test
    void givenValidCommand_whenCreateEntry_thenReturn201() throws Exception {
        when(wikiService.createEntry(eq(1L), any(CreateWikiEntryCommand.class)))
                .thenReturn(new WikiEntryView(5L, WikiEntryType.BOOK_NOTE, "笔记", "内容",
                        null, null, Instant.now(), Instant.now()));
        mvc.perform(post("/api/wiki/entries").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BOOK_NOTE\",\"title\":\"笔记\",\"content\":\"内容\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("笔记"));
    }

    @DisplayName("列表按类型过滤返回200")
    @Test
    void givenTypeFilter_whenListEntries_thenReturn200() throws Exception {
        when(wikiService.entries(1L, WikiEntryType.CONCEPT)).thenReturn(List.of(
                new WikiEntryView(5L, WikiEntryType.CONCEPT, "护城河", "解释", "质量", null,
                        Instant.now(), Instant.now())));
        mvc.perform(get("/api/wiki/entries").principal(auth()).param("type", "CONCEPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CONCEPT"))
                .andExpect(jsonPath("$[0].category").value("质量"));
    }

    @DisplayName("他人条目映射404")
    @Test
    void givenOthersEntry_whenGetEntry_thenReturn404() throws Exception {
        when(wikiService.getEntry(1L, 99L)).thenThrow(new WikiException(WikiErrorCode.NOT_FOUND, "条目不存在"));
        mvc.perform(get("/api/wiki/entries/99").principal(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @DisplayName("创建规则返回201")
    @Test
    void givenValidRule_whenCreateRule_thenReturn201() throws Exception {
        when(ruleService.createRule(eq(1L), any(CreatePrincipleRuleCommand.class)))
                .thenReturn(new PrincipleRuleView(9L, PrincipleMetric.SINGLE_POSITION_RATIO,
                        new BigDecimal("0.20"), true, null, Instant.now(), Instant.now()));
        mvc.perform(post("/api/wiki/rules").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metric\":\"SINGLE_POSITION_RATIO\",\"threshold\":0.20,\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metric").value("SINGLE_POSITION_RATIO"));
    }

    @DisplayName("同指标重复规则映射409 DUPLICATE_METRIC")
    @Test
    void givenDuplicateMetric_whenCreateRule_thenReturn409() throws Exception {
        when(ruleService.createRule(eq(1L), any(CreatePrincipleRuleCommand.class)))
                .thenThrow(new WikiException(WikiErrorCode.DUPLICATE_METRIC, "该指标已有规则，请直接编辑既有规则"));
        mvc.perform(post("/api/wiki/rules").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metric\":\"SINGLE_POSITION_RATIO\",\"threshold\":0.20,\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_METRIC"));
    }

    @DisplayName("规则列表返回200")
    @Test
    void whenListRules_thenReturn200() throws Exception {
        when(ruleService.rules(1L)).thenReturn(List.of(
                new PrincipleRuleView(9L, PrincipleMetric.STOCK_PE_MAX, new BigDecimal("40"),
                        true, "高估值不买", Instant.now(), Instant.now())));
        mvc.perform(get("/api/wiki/rules").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].metric").value("STOCK_PE_MAX"));
    }
}
