package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.application.im.ImReplyPort;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.convention.TestBean;
import reactor.core.publisher.Flux;

/**
 * 飞书对话桥接端到端（feishu-messaging P2）：直调 bridge.onMessage → owner 白名单 →
 * 真实 HarnessAgentFactory + 假 Model → ImReplyPort 记录式 fake 断言回复文本。
 *
 * <p>技术方案说明：
 * <ul>
 *   <li>直调 bridge.onMessage（同步语义）：FeishuWsClient 不在此测试路径——测试进程不配置
 *   app-id/app-secret，FeishuWsClient.start() 按「凭证缺失」早退，全程无 ws 连接等网络副作用；
 *   {@code invest.im.dialogue-enabled=true} 仅为放行 bridge 的防御性早退检查。</li>
 *   <li>假 Model：{@code @TestBean} + 静态工厂把 bean 名 {@code investModel} 整个替换为
 *   {@link FixedReplyModel}（手法同 {@code AguiStreamIntegrationTest}；{@code DEEPSEEK_API_KEY}
 *   只是占位值，用于触发 {@code AgentConfig}/{@code HarnessAgentFactory} 的
 *   {@code @ConditionalOnExpression} 装配路径，不打真实 LLM）。</li>
 *   <li>ImReplyPort：{@code @TestBean} 按字段名 {@code feishuReplyAdapter} 精确替换为记录式
 *   fake（不打真实飞书 API）。bridge 全链路同步（invoker.ask 内 block），onMessage 返回后即可断言，
 *   无需异步等待。</li>
 *   <li>owner 用户：bridge 经 {@code invest.im.owner-username} 解析 userId 供
 *   HarnessAgentFactory 装配；@BeforeEach 幂等 seed（同上下文多用例复用）。</li>
 *   <li>状态隔离：state-root 重定向至 build/feishu-dialogue-test/state（同 AguiStream）；
 *   workspace 维持缺省 .agentscope/workspace（整目录已 gitignore，且假 Model 固定回复不含
 *   ToolUseBlock，不会调用任何写工具）。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "DEEPSEEK_API_KEY=test-dummy-key",
        "invest.mcp.harness.state-root=build/feishu-dialogue-test/state",
        "invest.im.dialogue-enabled=true",
        "invest.im.owner-open-id=ou_feishu_dialogue_owner",
        "invest.im.owner-username=feishu_dialogue_owner"})
class FeishuDialogueIntegrationTest extends PostgresTestSupport {

    private static final String OWNER_OPEN_ID = "ou_feishu_dialogue_owner";
    private static final String OWNER_USERNAME = "feishu_dialogue_owner";

    /** 静态单例：@TestBean 工厂先于用例体执行，录制供用例断言（手法同 AguiInterruptIntegrationTest）。 */
    private static final RecordingReplyPort REPLY_PORT = new RecordingReplyPort();

    @Autowired
    FeishuDialogueBridge bridge;

    @Autowired
    UserRepository userRepository;

    /** bean 名按字段名推断为 investModel，精确替换 AgentConfig#investModel。 */
    @TestBean(methodName = "fixedReplyModel")
    Model investModel;

    /** bean 名按字段名推断为 feishuReplyAdapter，精确替换 FeishuReplyAdapter。 */
    @TestBean(methodName = "recordingReplyPort")
    ImReplyPort feishuReplyAdapter;

    static Model fixedReplyModel() {
        return new FixedReplyModel();
    }

    static ImReplyPort recordingReplyPort() {
        return REPLY_PORT;
    }

    @BeforeEach
    void seedOwnerAndClearRecording() {
        userRepository.findByUsername(OWNER_USERNAME)
                .orElseGet(() -> userRepository.save(
                        User.register(OWNER_USERNAME, "not-a-real-hash").approve()));
        REPLY_PORT.calls.clear();
    }

    @DisplayName("owner 单聊文本经真实 agent 链路收到假 Model 固定回复")
    @Test
    void givenOwnerTextMessage_whenBridgeHandles_thenReplyIsFixedModelOutput() {
        bridge.onMessage(new ImInboundMessage(
                "oc-chat-1", "om_owner_1", OWNER_OPEN_ID, "p2p", "text", "你好"));

        // Model→HarnessAgent→invoker→bridge→ImReplyPort 全链路走通：收到的正是假 Model 的固定回复
        assertThat(REPLY_PORT.calls).hasSize(1);
        assertThat(REPLY_PORT.calls.peek().text()).isEqualTo(FixedReplyModel.REPLY);
        // 装配进上下文的确实是假 Model（而非真实 DeepSeek 客户端）
        assertThat(investModel).isInstanceOf(FixedReplyModel.class);
    }

    @DisplayName("非 owner 单聊文本被白名单静默忽略无回复")
    @Test
    void givenNonOwnerTextMessage_whenBridgeHandles_thenNoReply() {
        bridge.onMessage(new ImInboundMessage(
                "oc-chat-1", "om_stranger_1", "ou_stranger_not_owner", "p2p", "text", "你好"));

        assertThat(REPLY_PORT.calls).isEmpty();
    }

    /** 记录式 ImReplyPort fake：只录不改，reply 恒成功。 */
    static class RecordingReplyPort implements ImReplyPort {

        record ReplyCall(String messageId, String text) {}

        final ConcurrentLinkedQueue<ReplyCall> calls = new ConcurrentLinkedQueue<>();

        @Override
        public boolean reply(String messageId, String text) {
            calls.add(new ReplyCall(messageId, text));
            return true;
        }
    }

    /**
     * 固定回复的假 Model（同 {@code AguiStreamIntegrationTest} 手法）：返回单条预构造文本响应
     * （不含 ToolUseBlock，配合 usage），HarnessAgent 收到无工具调用的响应即结束推理循环，
     * {@code agent.call} 返回该文本。
     */
    static class FixedReplyModel implements Model {

        static final String REPLY = "这是飞书对话测试的固定回复。";

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .id("fake-feishu-reply-1")
                    .content(List.of(TextBlock.builder().text(REPLY).build()))
                    .usage(new ChatUsage(10, 5, 0.01))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "fixed-reply-model";
        }
    }
}
