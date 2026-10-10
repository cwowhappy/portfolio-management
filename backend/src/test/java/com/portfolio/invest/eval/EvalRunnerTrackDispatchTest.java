package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * evalBootJar 单 Start-Class 的轨道分派参数解析（MS-30 跟进②）：argv 含
 * {@code --track=extraction} 时 EvalRunner.main 转发 ExtractionEvalRunner.main（两轨独立
 * main 语义不变、本地 make eval-extraction 仍直调）；轨别参数须从剩余 argv 剥离——
 * 两轨 Options 均拒未知参数，透传即炸。纯函数直测（main 自身 System.exit 不可直调）。
 */
class EvalRunnerTrackDispatchTest {

    @Test
    @DisplayName("给定 --track=extraction 与其余参数混排，when解析，then轨别识别且剥离出剩余参数")
    void givenExtractionTrackMixedArgs_whenParse_thenTrackRecognizedAndStripped() {
        EvalRunner.TrackDispatch dispatch = EvalRunner.TrackDispatch.parse(new String[]{
                "--invest.eval.data-root=/data/eval", "--track=extraction", "--list"});

        assertThat(dispatch.track()).isEqualTo("extraction");
        assertThat(dispatch.remaining()).containsExactly(
                "--invest.eval.data-root=/data/eval", "--list");
    }

    @Test
    @DisplayName("给定无轨别参数，when解析，then缺省对话轨（agent）且 argv 原样透传")
    void givenNoTrackArg_whenParse_thenDefaultsToAgentAndArgsUntouched() {
        EvalRunner.TrackDispatch dispatch = EvalRunner.TrackDispatch.parse(
                new String[]{"--list", "--timeout-ms=5000"});

        assertThat(dispatch.track()).isEqualTo("agent");
        assertThat(dispatch.remaining()).containsExactly("--list", "--timeout-ms=5000");
    }

    @Test
    @DisplayName("给定 --track=agent 显式声明，when解析，then等同缺省（对话轨）且剥离轨别参数")
    void givenExplicitAgentTrack_whenParse_thenTreatedAsDefaultAndStripped() {
        EvalRunner.TrackDispatch dispatch = EvalRunner.TrackDispatch.parse(
                new String[]{"--track=agent", "--real"});

        assertThat(dispatch.track()).isEqualTo("agent");
        assertThat(dispatch.remaining()).containsExactly("--real");
    }

    @Test
    @DisplayName("给定非法轨别，when解析，then抛引导异常（仅支持 agent/extraction）")
    void givenUnknownTrackValue_whenParse_thenGuidedFailure() {
        assertThatThrownBy(() -> EvalRunner.TrackDispatch.parse(new String[]{"--track=bogus"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--track=bogus")
                .hasMessageContaining("agent")
                .hasMessageContaining("extraction");
    }
}
