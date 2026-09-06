package com.portfolio.invest.web;

import com.portfolio.invest.application.mcp.McpConfigApplicationService;
import com.portfolio.invest.application.mcp.McpConfigView;
import com.portfolio.invest.application.mcp.McpProviderView;
import com.portfolio.invest.application.mcp.TestResult;
import com.portfolio.invest.application.mcp.ToolView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.web.dto.SaveConfigRequest;
import com.portfolio.invest.web.dto.TestConnectionRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MCP 数据源接入层：目录只读，个人配置以当前登录用户为归属（非本人 404）。 */
@RestController
@RequestMapping("/api/mcp")
public class McpConfigController {

    private final McpConfigApplicationService service;

    public McpConfigController(McpConfigApplicationService service) {
        this.service = service;
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }

    @GetMapping("/providers")
    public List<McpProviderView> providers() { return service.providers(); }

    @GetMapping("/configs")
    public List<McpConfigView> configs(Authentication auth) { return service.myConfigs(currentUserId(auth)); }

    @PutMapping("/configs/{providerId}")
    public McpConfigView save(Authentication auth, @PathVariable Long providerId, @RequestBody SaveConfigRequest body) {
        return service.save(currentUserId(auth), providerId, body.enabled(), body.disabledTools());
    }

    @DeleteMapping("/configs/{providerId}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long providerId) {
        service.delete(currentUserId(auth), providerId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/providers/test")
    public TestResult test(@RequestBody TestConnectionRequest body) { return service.test(body.providerId()); }

    @GetMapping("/configs/{providerId}/tools")
    public List<ToolView> tools(Authentication auth, @PathVariable Long providerId) {
        return service.tools(currentUserId(auth), providerId);
    }
}
