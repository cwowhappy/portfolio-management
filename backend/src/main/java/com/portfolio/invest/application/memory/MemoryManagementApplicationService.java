package com.portfolio.invest.application.memory;

import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * 记忆管理链编排（F01，设计规格 §2.3）：REST 入口（MemoryController）到工作区记忆端口的薄委托。
 * 文件操作无 JPA 事务，不加 @Transactional（纯编排）；本人隔离由 web 层注入的 userId 与
 * 端口按 {@code <workspace>/<userId>/} 寻址共同保证。wire 参数校验（非空/不含换行）在
 * Controller DTO Bean Validation 层完成，此处只承担语义透传。
 */
@Service
public class MemoryManagementApplicationService {

    private final WorkspaceMemoryPort port;

    public MemoryManagementApplicationService(WorkspaceMemoryPort port) {
        this.port = port;
    }

    /** 策展层 + 当期日账本全视图。 */
    public MemoryFilesView load(Long userId) {
        return port.load(userId);
    }

    /** 删策展层行（含决议 #2 两层同步），返回日账本归一化匹配清除计数。 */
    public CuratedMutationResult deleteCuratedLine(Long userId, int lineIndex, String expectLine, String baseHash) {
        return port.deleteCuratedLine(userId, lineIndex, expectLine, baseHash);
    }

    /** 编策展层行（仅替换该行，不做两层同步——删除独有语义）。 */
    public void editCuratedLine(Long userId, int lineIndex, String expectLine, String newLine, String baseHash) {
        port.editCuratedLine(userId, lineIndex, expectLine, newLine, baseHash);
    }

    /** 删日账本整节。 */
    public void deleteLedgerSection(Long userId, LocalDate date, String sectionHeader, String baseHash) {
        port.deleteLedgerSection(userId, date, sectionHeader, baseHash);
    }
}
