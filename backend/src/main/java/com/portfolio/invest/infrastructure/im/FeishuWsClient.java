package com.portfolio.invest.infrastructure.im;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.application.im.ImMessageListener;
import com.portfolio.invest.config.InvestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 飞书事件长连接客户端（feishu-messaging P2）：oapi-sdk ws.Client 收 im.message.receive_v1。
 * 关键语义（spike 实证）：ack 在 handler 返回后才发出——本类 handler 只做 解析+去重+转投线程池，
 * 慢工作（agent 调用）全部在 executor 线程；SDK 无重推去重，业务侧按 message_id 去重（5 分钟 TTL）。
 * 进程级单例（close() 不关 SDK 内部线程池，生命周期与进程一致）。
 */
@Component
public class FeishuWsClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FeishuWsClient.class);

    private final InvestProperties props;
    /** 测试构造器直注；生产构造器注入 provider，start() 时解析——无桥接 bean 时显式告警、不破上下文装配。 */
    private volatile ImMessageListener listener;
    private final ObjectProvider<ImMessageListener> listenerProvider; // 生产路径；测试构造器为 null
    private final Executor executor;
    private final Cache<String, Boolean> seenMessages =
            CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(1000).build();
    private volatile com.lark.oapi.ws.Client client;
    private volatile boolean running;

    @org.springframework.beans.factory.annotation.Autowired
    public FeishuWsClient(InvestProperties props, ObjectProvider<ImMessageListener> listenerProvider) {
        this(props, null, listenerProvider, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "feishu-dialogue");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 测试构造器：注入直执行/可控执行器。 */
    FeishuWsClient(InvestProperties props, ImMessageListener listener, Executor executor) {
        this(props, listener, null, executor);
    }

    private FeishuWsClient(InvestProperties props, ImMessageListener listener,
                           ObjectProvider<ImMessageListener> listenerProvider, Executor executor) {
        this.props = props;
        this.listener = listener;
        this.listenerProvider = listenerProvider;
        this.executor = executor;
    }

    @Override
    public void start() {
        var im = props.getIm();
        if (im.getAppId() == null || im.getAppId().isBlank() || im.getAppSecret() == null
                || im.getAppSecret().isBlank() || !im.isDialogueEnabled()) {
            log.info("飞书对话未启用（invest.im.dialogue-enabled=false 或凭证缺失），跳过长连接");
            return;
        }
        if (listener == null) {
            ImMessageListener resolved = listenerProvider.getIfAvailable();
            if (resolved == null) {
                log.warn("ImMessageListener 无实现 bean（对话桥接未装配），飞书长连接不启动");
                return;
            }
            listener = resolved;
        }
        Thread starter = new Thread(() -> {
            try {
                com.lark.oapi.ws.Client ws = buildClient();
                ws.start();
                ws.awaitReady(10_000);
                client = ws;
                running = true;
                log.info("飞书长连接就绪（appId={}）", im.getAppId());
            } catch (Exception e) {
                log.error("飞书长连接启动失败（对话不可用，不影响其他功能）：{}", e.getMessage());
            }
        }, "feishu-ws-starter");
        starter.setDaemon(true);
        starter.start();
    }

    private com.lark.oapi.ws.Client buildClient() {
        var im = props.getIm();
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        onEvent(event);
                    }
                })
                .build();
        return new com.lark.oapi.ws.Client.Builder(im.getAppId(), im.getAppSecret())
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .build();
    }

    void onEvent(P2MessageReceiveV1 event) {
        try {
            var msg = event.getEvent().getMessage();
            String openId = event.getEvent().getSender().getSenderId().getOpenId();
            dispatch(msg.getChatId(), msg.getMessageId(), openId, msg.getChatType(),
                    msg.getMessageType(), msg.getContent());
        } catch (Exception e) {
            log.warn("飞书事件解析失败：{}", e.getMessage());
        }
    }

    /** 解析+去重+异步转投（handler 内同步调用的部分保持轻薄，快速 ack）。 */
    void dispatch(String chatId, String messageId, String openId, String chatType, String msgType,
                  String contentJson) {
        if (messageId == null || seenMessages.getIfPresent(messageId) != null) {
            return;
        }
        seenMessages.put(messageId, Boolean.TRUE);
        String text = extractText(contentJson);
        executor.execute(() -> {
            try {
                listener.onMessage(new ImInboundMessage(chatId, messageId, openId, chatType, msgType, text));
            } catch (Exception e) { // 尽力而为：桥接异常不外溢到 executor 线程
                log.error("飞书消息处理失败（messageId={}）", messageId, e);
            }
        });
    }

    private String extractText(String contentJson) {
        try {
            JsonObject obj = JsonParser.parseString(contentJson).getAsJsonObject();
            return obj.has("text") && !obj.get("text").isJsonNull() ? obj.get("text").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void stop() {
        running = false;
        com.lark.oapi.ws.Client ws = client;
        if (ws != null) {
            ws.close(); // SDK 无 stop()；close 不关其内部线程池——本 bean 进程级单例，可接受
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
