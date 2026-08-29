package com.portfolio.invest.web;

import com.portfolio.invest.application.portfolio.GroupView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortfolioControllerTest {

    private final PortfolioApplicationService service = mock(PortfolioApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PortfolioController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void groups返回200() throws Exception {
        // 构造已认证主体：控制器 currentUserId(auth) 会 cast auth.getPrincipal() 为 AuthenticatedUser
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                java.time.Instant.now(), java.time.Instant.now());
        var principal = new AuthenticatedUser(user);
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        when(service.groups(1L)).thenReturn(List.of(
                new GroupView(1L, "华泰", GroupType.ACCOUNT, 0, java.math.BigDecimal.ZERO)));

        mvc.perform(get("/api/portfolio/groups").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("华泰"));
    }
}
