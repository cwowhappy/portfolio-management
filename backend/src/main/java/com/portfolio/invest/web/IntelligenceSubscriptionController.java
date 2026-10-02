package com.portfolio.invest.web;

import com.portfolio.invest.application.intelligence.SubscriptionService;
import com.portfolio.invest.application.intelligence.SubscriptionService.SubscriptionView;
import com.portfolio.invest.application.intelligence.SubscriptionService.UpdateSubscriptionCommand;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订阅双端点（/api/intelligence/subscription，F16 数据面最小版——UI 随 P4）：
 * GET 取订阅（无行返回缺省视图）、PUT 全量替换（幂等，整体提交语义）。校验失败走
 * 既有 MethodArgumentNotValidException → 400 INVALID_REQUEST 分支，无新增异常分支。
 */
@RestController
@RequestMapping("/api/intelligence/subscription")
public class IntelligenceSubscriptionController {

    private final SubscriptionService service;

    public IntelligenceSubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    /** 取当前用户订阅视图（无落库行 = pushEnabled=true 空集）。 */
    @GetMapping
    public SubscriptionView get(Authentication auth) {
        return service.get(currentUserId(auth));
    }

    /** 全量替换保存（幂等）：返回保存后视图。 */
    @PutMapping
    public SubscriptionView update(Authentication auth, @Valid @RequestBody UpdateSubscriptionCommand cmd) {
        return service.update(currentUserId(auth), cmd);
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
