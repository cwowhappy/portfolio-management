package com.portfolio.invest.web;

import com.portfolio.invest.application.memory.CuratedMutationResult;
import com.portfolio.invest.application.memory.MemoryFilesView;
import com.portfolio.invest.application.memory.MemoryManagementApplicationService;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.web.dto.DeleteCuratedLineRequest;
import com.portfolio.invest.web.dto.DeleteLedgerSectionRequest;
import com.portfolio.invest.web.dto.EditCuratedLineRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
 * 记忆管理 REST 接口（F01，设计规格 §2.3）：全视图 / 删·编策展层行 / 删日账本节。本人隔离——
 * 全部操作以认证解析出的 userId 寻址 {@code <workspace>/<userId>/}，无跨用户对象 id。
 * 乐观并发三层异常（hash 冲突/漂移/只读标题）由 GlobalExceptionHandler 统一映射 409/410/400。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryManagementApplicationService service;

    public MemoryController(MemoryManagementApplicationService service) {
        this.service = service;
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

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
