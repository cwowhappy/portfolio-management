package com.portfolio.invest.web;

import com.portfolio.invest.application.intelligence.SubscriptionService;
import com.portfolio.invest.application.intelligence.SubscriptionService.BindingCodeView;
import com.portfolio.invest.application.intelligence.SubscriptionService.BindingStatusView;
import com.portfolio.invest.application.intelligence.SubscriptionService.SubscriptionView;
import com.portfolio.invest.application.intelligence.SubscriptionService.UpdateSubscriptionCommand;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订阅与绑定端点（/api/intelligence/subscription）：GET 取订阅（无行返回缺省视图）、
 * PUT 全量替换（幂等，整体提交语义）；P4 绑定数据面（D8）——POST 生成绑定码
 * （201 + 码与失效时刻，用户持码到飞书发码核销，核销闭环属 Task 5）、GET /binding
 * 绑定状态（P4 Task 6 设置页绑定态分支）、DELETE 解绑（幂等 204）。校验失败走既有
 * MethodArgumentNotValidException → 400 分支。
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

    /** 生成绑定码（D8：6 位数字、TTL 10 分钟、冲突重生成）→ 201 + 码与失效时刻。 */
    @PostMapping("/binding-code")
    public ResponseEntity<BindingCodeView> createBindingCode(Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.generateCode(currentUserId(auth)));
    }

    /** 绑定状态（P4 Task 6 设置页绑定态）：未绑定 bound=false + boundAt=null。 */
    @GetMapping("/binding")
    public BindingStatusView getBindingStatus(Authentication auth) {
        return service.getBindingStatus(currentUserId(auth));
    }

    /** 解绑当前用户飞书（幂等：未绑定同样 204）。 */
    @DeleteMapping("/binding")
    public ResponseEntity<Void> unbind(Authentication auth) {
        service.unbind(currentUserId(auth));
        return ResponseEntity.noContent().build();
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
