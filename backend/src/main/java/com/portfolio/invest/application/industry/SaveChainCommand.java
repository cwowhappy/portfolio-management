package com.portfolio.invest.application.industry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 产业链全文档保存命令（wire DTO，设计规格 §四写侧）：stages+members 嵌套整体替换——
 * id 不在命令内（POST 传 null、PUT 走路径参数，照 SaveUnlistedCompanyCommand 先例）。
 * tier/memberType 为字符串形态：枚举与 CHECK 镜像校验在应用服务（非法 → INVALID_TIER /
 * INVALID_MEMBER），Bean Validation 只做结构性非空。
 */
public record SaveChainCommand(@NotBlank String name, String description,
                               @Valid @NotEmpty List<StageCommand> stages) {

    public record StageCommand(@NotBlank String tier, @NotBlank String name, int sortOrder,
                               @Valid @NotEmpty List<MemberCommand> members) {}

    public record MemberCommand(@NotBlank String memberType, String stockCode,
                                Long unlistedCompanyId, @NotBlank String displayName) {}
}
