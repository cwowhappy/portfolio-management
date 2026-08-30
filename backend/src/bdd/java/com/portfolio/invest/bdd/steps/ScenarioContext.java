package com.portfolio.invest.bdd.steps;

import com.portfolio.invest.application.portfolio.PortfolioOverviewView;
import io.cucumber.spring.ScenarioScope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 场景级共享状态：cucumber-spring 的 scenario scope 保证每个场景拿到全新实例，
 * 供各域 Step 类之间传递登录会话、当前用户/分组/持仓 id、最近一次 HTTP 响应等。
 *
 * <p>注意：必须经 getter/setter 访问。scenario scope 以 CGLIB 代理注入 Step 类，
 * 代理只拦截方法调用，直接读写字段会落在代理实例自身（永远为 null）。
 */
@Component
@ScenarioScope
public class ScenarioContext {

    private String username;
    private String password;
    private Long userId;
    private Long groupId;
    private Long positionId;
    private MockHttpSession userSession;
    private MockHttpSession adminSession;
    private MvcResult lastResponse;
    private String aguiStreamBody;
    private String lastQueriedCode;
    private final List<PortfolioOverviewView> overviews = new ArrayList<>();

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Long getPositionId() {
        return positionId;
    }

    public void setPositionId(Long positionId) {
        this.positionId = positionId;
    }

    public MockHttpSession getUserSession() {
        return userSession;
    }

    public void setUserSession(MockHttpSession userSession) {
        this.userSession = userSession;
    }

    public MockHttpSession getAdminSession() {
        return adminSession;
    }

    public void setAdminSession(MockHttpSession adminSession) {
        this.adminSession = adminSession;
    }

    public MvcResult getLastResponse() {
        return lastResponse;
    }

    public void setLastResponse(MvcResult lastResponse) {
        this.lastResponse = lastResponse;
    }

    public String getAguiStreamBody() {
        return aguiStreamBody;
    }

    public void setAguiStreamBody(String aguiStreamBody) {
        this.aguiStreamBody = aguiStreamBody;
    }

    public String getLastQueriedCode() {
        return lastQueriedCode;
    }

    public void setLastQueriedCode(String lastQueriedCode) {
        this.lastQueriedCode = lastQueriedCode;
    }

    public List<PortfolioOverviewView> getOverviews() {
        return overviews;
    }
}
