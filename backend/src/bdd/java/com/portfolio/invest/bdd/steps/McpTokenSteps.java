package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.bdd.CucumberSpringConfig;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpSecretCodec;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MCP token 设置步骤（P1-10）：admin HTTP → 库内 v1 密文 → 主密钥解密还原。
 * 密钥经 CucumberSpringConfig 的 invest.mcp.secret-key 测试属性提供。
 */
public class McpTokenSteps {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ScenarioContext ctx;

    @Autowired
    McpConfigRepository mcpRepository;

    @Autowired
    McpSecretCodec codec;

    @当("管理员为数据源 {string} 设置 token {string}")
    public void 管理员设置token(String code, String token) throws Exception {
        mockMvc.perform(put("/api/admin/mcp/providers/{code}/token", code)
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isNoContent());
    }

    @那么("数据源 {string} 的库内 token 应为 v1 密文且不含明文 {string}")
    public void 库内为密文(String code, String plaintext) {
        String enc = mcpRepository.findProviderByCode(code).orElseThrow().authSecretEnc();
        assertThat(enc).startsWith("v1:").doesNotContain(plaintext);
    }

    @那么("数据源 {string} 的 token 解密后应还原为 {string}")
    public void 解密还原(String code, String plaintext) {
        String enc = mcpRepository.findProviderByCode(code).orElseThrow().authSecretEnc();
        assertThat(codec.decrypt(enc)).isEqualTo(plaintext);
    }

    /** 内置管理员（AdminSeedRunner 种子）登录后台，会话在场景内复用（同 AdminResetPasswordSteps）。 */
    private MockHttpSession adminSession() throws Exception {
        if (ctx.getAdminSession() == null) {
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + CucumberSpringConfig.ADMIN_USERNAME
                                    + "\",\"password\":\"" + CucumberSpringConfig.ADMIN_PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            ctx.setAdminSession((MockHttpSession) result.getRequest().getSession(false));
        }
        return ctx.getAdminSession();
    }
}
