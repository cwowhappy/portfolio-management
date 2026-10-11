package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 研报确认状态机（设计规格 §3.10：PENDING_CONFIRM→CONFIRMED/REJECTED，终态再操作=empty 由调用方转 409）。 */
class ResearchReportTest {

    private static final Instant CREATED = Instant.parse("2026-10-11T08:00:00Z");
    private static final Instant LATER = CREATED.plusSeconds(300);

    // ---- create ----

    @DisplayName("create：默认 PENDING_CONFIRM、无确认/拒绝痕迹、时间取入参")
    @Test
    void givenTaskAndContent_whenCreate_thenPendingConfirm() {
        var report = newReport();
        assertThat(report.id()).isNull();
        assertThat(report.taskId()).isEqualTo(9L);
        assertThat(report.projectId()).isEqualTo(3L);
        assertThat(report.userId()).isEqualTo(7L);
        assertThat(report.status()).isEqualTo(ResearchReport.Status.PENDING_CONFIRM);
        assertThat(report.title()).isEqualTo("贵州茅台深度研究");
        assertThat(report.contentMd()).isEqualTo("# 贵州茅台深度研究\n股价 1520.33 元");
        assertThat(report.confirmedAt()).isNull();
        assertThat(report.rejectedReason()).isNull();
        assertThat(report.createdAt()).isEqualTo(CREATED);
        assertThat(report.updatedAt()).isEqualTo(CREATED);
    }

    @DisplayName("create：taskId/projectId/userId 缺失抛 TASK_REQUIRED/PROJECT_REQUIRED/USER_REQUIRED")
    @Test
    void givenMissingIds_whenCreate_thenThrowRequired() {
        assertThatThrownBy(() -> ResearchReport.create(null, 3L, 7L, "t", "c", null, CREATED))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TASK_REQUIRED));
        assertThatThrownBy(() -> ResearchReport.create(9L, null, 7L, "t", "c", null, CREATED))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.PROJECT_REQUIRED));
        assertThatThrownBy(() -> ResearchReport.create(9L, 3L, null, "t", "c", null, CREATED))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.USER_REQUIRED));
    }

    @DisplayName("create：标题/正文空白抛 TITLE_BLANK/REPORT_CONTENT_REQUIRED；limitations 可空")
    @Test
    void givenBlankTitleOrContent_whenCreate_thenThrow() {
        assertThatThrownBy(() -> ResearchReport.create(9L, 3L, 7L, " ", "c", null, CREATED))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TITLE_BLANK));
        assertThatThrownBy(() -> ResearchReport.create(9L, 3L, 7L, "t", null, null, CREATED))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REPORT_CONTENT_REQUIRED));
        // limitations 是数据局限注记，允许为空
        assertThat(newReport().limitations()).isNull();
    }

    // ---- confirm ----

    @DisplayName("confirm：PENDING_CONFIRM→CONFIRMED 落 confirmedAt、清拒绝痕迹")
    @Test
    void givenPendingConfirm_whenConfirm_thenConfirmed() {
        var confirmed = newReport().confirm(LATER);
        assertThat(confirmed).isPresent();
        assertThat(confirmed.get().status()).isEqualTo(ResearchReport.Status.CONFIRMED);
        assertThat(confirmed.get().confirmedAt()).isEqualTo(LATER);
        assertThat(confirmed.get().updatedAt()).isEqualTo(LATER);
        assertThat(confirmed.get().rejectedReason()).isNull();
        // 其余字段原样贯通
        assertThat(confirmed.get().title()).isEqualTo("贵州茅台深度研究");
        assertThat(confirmed.get().contentMd()).isEqualTo("# 贵州茅台深度研究\n股价 1520.33 元");
    }

    @DisplayName("confirm：终态（CONFIRMED/REJECTED）再确认返回 empty（调用方转 409）")
    @Test
    void givenTerminalState_whenConfirm_thenEmpty() {
        var confirmed = newReport().confirm(LATER).orElseThrow();
        assertThat(confirmed.confirm(LATER.plusSeconds(1))).isEmpty();
        var rejected = newReport().reject("口径不符", LATER).orElseThrow();
        assertThat(rejected.confirm(LATER.plusSeconds(1))).isEmpty();
    }

    // ---- reject ----

    @DisplayName("reject：PENDING_CONFIRM→REJECTED 留 rejectedReason、无确认痕迹")
    @Test
    void givenPendingConfirm_whenReject_thenRejectedWithReason() {
        var rejected = newReport().reject("估值口径与最新财报不符", LATER);
        assertThat(rejected).isPresent();
        assertThat(rejected.get().status()).isEqualTo(ResearchReport.Status.REJECTED);
        assertThat(rejected.get().rejectedReason()).isEqualTo("估值口径与最新财报不符");
        assertThat(rejected.get().updatedAt()).isEqualTo(LATER);
        assertThat(rejected.get().confirmedAt()).isNull();
    }

    @DisplayName("reject：理由 null/空白抛 REJECT_REASON_REQUIRED（§3.10 REJECT 必填）")
    @Test
    void givenBlankReason_whenReject_thenThrow() {
        assertThatThrownBy(() -> newReport().reject(null, LATER))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REJECT_REASON_REQUIRED));
        assertThatThrownBy(() -> newReport().reject("   ", LATER))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REJECT_REASON_REQUIRED));
    }

    @DisplayName("reject：终态再拒绝返回 empty")
    @Test
    void givenTerminalState_whenReject_thenEmpty() {
        var confirmed = newReport().confirm(LATER).orElseThrow();
        assertThat(confirmed.reject("迟到的拒绝", LATER.plusSeconds(1))).isEmpty();
        var rejected = newReport().reject("口径不符", LATER).orElseThrow();
        assertThat(rejected.reject("再拒", LATER.plusSeconds(1))).isEmpty();
    }

    // ---- reconstitute 与不可变 ----

    @DisplayName("reconstitute：全参还原持久化状态（不做校验）")
    @Test
    void givenAllFields_whenReconstitute_thenCarriedThrough() {
        var report = ResearchReport.reconstitute(31L, 9L, 3L, 7L, ResearchReport.Status.REJECTED,
                "贵州茅台深度研究", "# 内容", "部分源失败", null, "口径不符", CREATED, LATER);
        assertThat(report.id()).isEqualTo(31L);
        assertThat(report.taskId()).isEqualTo(9L);
        assertThat(report.projectId()).isEqualTo(3L);
        assertThat(report.userId()).isEqualTo(7L);
        assertThat(report.status()).isEqualTo(ResearchReport.Status.REJECTED);
        assertThat(report.confirmedAt()).isNull();
        assertThat(report.rejectedReason()).isEqualTo("口径不符");
        assertThat(report.createdAt()).isEqualTo(CREATED);
        assertThat(report.updatedAt()).isEqualTo(LATER);
    }

    @DisplayName("状态迁移不可变：confirm/reject 返回新实例，原实例仍 PENDING_CONFIRM")
    @Test
    void givenTransition_whenApplied_thenOriginalUnchanged() {
        var pending = newReport();
        pending.confirm(LATER);
        assertThat(pending.status()).isEqualTo(ResearchReport.Status.PENDING_CONFIRM);
        assertThat(pending.updatedAt()).isEqualTo(CREATED);
    }

    private static ResearchReport newReport() {
        return ResearchReport.create(9L, 3L, 7L, "贵州茅台深度研究",
                "# 贵州茅台深度研究\n股价 1520.33 元", null, CREATED);
    }
}
