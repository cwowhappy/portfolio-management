package com.portfolio.invest.web;

import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.application.skill.SkillView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.web.dto.SaveSkillConfigRequest;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Skill 接入层：目录只读，个人配置以当前登录用户为归属。 */
@RestController
@RequestMapping("/api/skills")
public class SkillConfigController {

    private final SkillApplicationService service;

    public SkillConfigController(SkillApplicationService service) {
        this.service = service;
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }

    @GetMapping
    public List<SkillView> catalog(Authentication auth) {
        return service.catalog(currentUserId(auth));
    }

    @PutMapping("/config")
    public List<SkillView> save(Authentication auth, @RequestBody SaveSkillConfigRequest body) {
        return service.save(currentUserId(auth), body.enabled());
    }
}
