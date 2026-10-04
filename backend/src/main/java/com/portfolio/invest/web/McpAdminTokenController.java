package com.portfolio.invest.web;

import com.portfolio.invest.application.mcp.McpConfigApplicationService;
import com.portfolio.invest.web.dto.SetTokenRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 数据源 token 管理（P1-10，拍板 D1）：admin-only 写入面。
 * 路由挂 /api/admin/mcp 下由 SecurityConfig 的 /api/admin/** ADMIN 守卫保护；
 * 明文只在请求体一次经过、服务端 AES-GCM 加密落库，任何响应不回显（NFR-1）。
 */
@RestController
public class McpAdminTokenController {

    private static final Logger log = LoggerFactory.getLogger(McpAdminTokenController.class);

    private final McpConfigApplicationService service;

    public McpAdminTokenController(McpConfigApplicationService service) {
        this.service = service;
    }

    /** 设置/更换 provider token；token 轮换经 McpTokenRotatedEvent 即时驱逐池内客户端，无需重启（B3）。 */
    @PutMapping("/api/admin/mcp/providers/{code}/token")
    public ResponseEntity<Void> setToken(@PathVariable String code, @Valid @RequestBody SetTokenRequest body,
                                         Authentication auth) {
        // 审计（终审 Important 5）：记录操作者身份，只含 code/用户名，无明文（NFR-1）
        log.info("管理员 {} 设置 MCP provider {} token", auth.getName(), code);
        service.setProviderToken(code, body.token());
        return ResponseEntity.noContent().build();
    }
}
