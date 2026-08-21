package com.portfolio.invest.web;

import com.portfolio.invest.application.useradmin.UserAdminApplicationService;
import com.portfolio.invest.application.useradmin.UserAdminView;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员接入层（路径 /api/admin/** 由 Security 限 ADMIN 角色）。 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final UserAdminApplicationService service;

    public UserAdminController(UserAdminApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserAdminView> list() {
        return service.list();
    }

    @PostMapping("/{id}/approve")
    public UserAdminView approve(@PathVariable Long id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/reject")
    public UserAdminView reject(@PathVariable Long id) {
        return service.reject(id);
    }

    @PostMapping("/{id}/enable")
    public UserAdminView enable(@PathVariable Long id) {
        return service.enable(id);
    }

    @PostMapping("/{id}/disable")
    public UserAdminView disable(@PathVariable Long id) {
        return service.disable(id);
    }

    @PostMapping("/{id}/reset-password")
    public UserAdminView resetPassword(@PathVariable Long id,
                                       @RequestBody Map<String, String> body) {
        return service.resetPassword(id, body.get("newPassword"));
    }
}
