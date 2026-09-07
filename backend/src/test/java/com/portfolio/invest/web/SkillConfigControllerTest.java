package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.application.skill.SkillView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SkillConfigController.class)
class SkillConfigControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SkillApplicationService service;

    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @DisplayName("GET 目录返回合并后的目录并透传 userId")
    @Test
    void givenGet_whenCatalog_thenReturnsCatalog() throws Exception {
        when(service.catalog(1L)).thenReturn(List.of(
                new SkillView("tushare_data", "desc", "data_source", false, "tushare", true)));

        mvc.perform(get("/api/skills").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillCode").value("tushare_data"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].dependsOnProvider").value("tushare"));
        verify(service).catalog(1L);
    }

    @DisplayName("PUT 保存启停集合")
    @Test
    void givenPut_whenSave_thenPassesEnabledCodes() throws Exception {
        when(service.save(eq(1L), anyList())).thenReturn(List.of());

        mvc.perform(put("/api/skills/config").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":[\"tushare_data\"]}"))
                .andExpect(status().isOk());
        verify(service).save(eq(1L), eq(List.of("tushare_data")));
    }
}
