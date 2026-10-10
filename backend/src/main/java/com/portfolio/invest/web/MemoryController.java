package com.portfolio.invest.web;

import com.portfolio.invest.application.memory.CuratedMutationResult;
import com.portfolio.invest.application.memory.MemoryFilesView;
import com.portfolio.invest.application.memory.MemoryManagementApplicationService;
import com.portfolio.invest.application.memory.ProfileConfigApplicationService;
import com.portfolio.invest.application.memory.ProfileConfigView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.web.dto.DeleteCuratedLineRequest;
import com.portfolio.invest.web.dto.DeleteLedgerSectionRequest;
import com.portfolio.invest.web.dto.EditCuratedLineRequest;
import com.portfolio.invest.web.dto.ProfileConfigResponse;
import com.portfolio.invest.web.dto.SaveProfileConfigRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆管理 REST 接口（F01，设计规格 §2.3；F02 画像授权 §3.5 挂同一 /api/memory 前缀）：
 * 全视图 / 删·编策展层行 / 删日账本节 / 画像授权配置读写。本人隔离——全部操作以认证解析出的
 * userId 寻址，无跨用户对象 id。乐观并发三层异常（hash 冲突/漂移/只读标题）由
 * GlobalExceptionHandler 统一映射 409/410/400。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryManagementApplicationService service;
    private final ProfileConfigApplicationService profileConfigService;

    public MemoryController(MemoryManagementApplicationService service,
            ProfileConfigApplicationService profileConfigService) {
        this.service = service;
        this.profileConfigService = profileConfigService;
    }

    @GetMapping
    public MemoryFilesView view(Authentication auth) {
        return service.load(currentUserId(auth));
    }

    @DeleteMapping("/curated")
    public CuratedMutationResult deleteCuratedLine(Authentication auth,
            @Valid @RequestBody DeleteCuratedLineRequest req) {
        return service.deleteCuratedLine(currentUserId(auth), req.lineIndex(), req.expectLine(), req.baseHash());
    }

    /** 204（无响应体）：行编辑仅替换该行，无两层同步计数可返。 */
    @PutMapping("/curated")
    public ResponseEntity<Void> editCuratedLine(Authentication auth,
            @Valid @RequestBody EditCuratedLineRequest req) {
        service.editCuratedLine(currentUserId(auth), req.lineIndex(),
                req.expectLine(), req.newLine(), req.baseHash());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/ledger/{date}")
    public ResponseEntity<Void> deleteLedgerSection(Authentication auth,
            @PathVariable LocalDate date, @Valid @RequestBody DeleteLedgerSectionRequest req) {
        service.deleteLedgerSection(currentUserId(auth), date, req.sectionHeader(), req.baseHash());
        return ResponseEntity.noContent().build();
    }

    /** 画像授权配置 + 四段预览同刻一次拉取（§3.5：所见即所注入，preview 与注入同一渲染路径）。 */
    @GetMapping("/profile-config")
    public ProfileConfigResponse profileConfig(Authentication auth) {
        Long userId = currentUserId(auth);
        return compose(userId, profileConfigService.config(userId));
    }

    /** 四开关全量保存，回执返回保存后开关+预览最新态（保存回执驱动前端刷新）。 */
    @PutMapping("/profile-config")
    public ProfileConfigResponse saveProfileConfig(Authentication auth,
            @RequestBody SaveProfileConfigRequest req) {
        Long userId = currentUserId(auth);
        profileConfigService.save(userId, req.style(), req.industry(), req.watchlist(), req.holdings());
        return compose(userId, profileConfigService.config(userId));
    }

    private ProfileConfigResponse compose(Long userId, ProfileConfigView config) {
        Map<String, String> preview = profileConfigService.preview(userId);
        return new ProfileConfigResponse(
                section(config.style(), preview, ProfileConfigApplicationService.KEY_STYLE),
                section(config.industry(), preview, ProfileConfigApplicationService.KEY_INDUSTRY),
                section(config.watchlist(), preview, ProfileConfigApplicationService.KEY_WATCHLIST),
                section(config.holdings(), preview, ProfileConfigApplicationService.KEY_HOLDINGS));
    }

    private static ProfileConfigResponse.Section section(boolean enabled,
            Map<String, String> preview, String key) {
        return new ProfileConfigResponse.Section(enabled, preview.get(key));
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
