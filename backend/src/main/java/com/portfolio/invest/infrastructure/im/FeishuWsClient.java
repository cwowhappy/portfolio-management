package com.portfolio.invest.infrastructure.im;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.portfolio.invest.application.im.ImCommandRouter;
import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.application.im.ImMessageListener;
import com.portfolio.invest.application.intelligence.BindingCommandHandler;
import com.portfolio.invest.config.InvestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 飞书事件长连接客户端（feishu-messaging P2）：oapi-sdk ws.Client 收 im.message.receive_v1。
 * 关键语义（spike 实证）：ack 在 handler 返回后才发出——本类 handler 只做 解析+去重+转投线程池，
 * 慢工作（agent 调用）全部在 executor 线程；SDK 无重推去重，业务侧按 message_id 去重（5 分钟 TTL）。
 * 进程级单例（close() 不关 SDK 内部线程池，生命周期与进程一致）。
 *
 * <p>P4 Task 5：转投 listener 前先过 {@link ImCommandRouter} 命令前置路由（如飞书绑定码），
 * 命中即 sendReply 回话术后返回——不进对话桥；未命中/无路由 bean 照旧透传。
 *
 * <p>P2-B10 入站三重加固：① 单线程有界队列（满即丢弃留痕，不阻塞 WS 事件/ack 路径）；
 * ② 非 owner 纯对话消息入队前丢弃（防刷占队；命令格式文本仍放行走路由）；
 * ③ 绑定命令冷却见 {@link BindingCommandHandler}。
 */
@Component
public class FeishuWsClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FeishuWsClient.class);

    /** B10：入站队列容量（单线程消费；满即丢弃计数+告警，绝不阻塞 ack 路径）。 */
    static final int QUEUE_CAPACITY = 100;

    private final InvestProperties props;
    /** 测试构造器直注；生产构造器注入 provider，start() 时解析——无桥接 bean 时显式告警、不破上下文装配。 */
    private volatile ImMessageListener listener;
    private final ObjectProvider<ImMessageListener> listenerProvider; // 生产路径；测试构造器为 null
    /** 测试构造器直注；生产构造器注入 provider，dispatch 懒解析——无路由 bean 时透传对话桥。 */
    private volatile ImCommandRouter router;
    private final ObjectProvider<ImCommandRouter> routerProvider; // 生产路径；测试构造器为 null
    private final FeishuClient feishuClient; // 命令命中的回复通道（同包直用，不经 ImReplyPort 门面）
    private final Executor executor;
    /** B10：队列满丢弃计数（可观测/测试断言）。 */
    private final AtomicInteger dropped = new AtomicInteger();
    private final Cache<String, Boolean> seenMessages =
            CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(1000).build();
    private volatile com.lark.oapi.ws.Client client;
    private volatile boolean running;

    @org.springframework.beans.factory.annotation.Autowired
    public FeishuWsClient(InvestProperties props, ObjectProvider<ImMessageListener> listenerProvider,
                          ObjectProvider<ImCommandRouter> routerProvider, FeishuClient feishuClient) {
        // 不走 this(...) 委托：有界执行器需引用实例字段 dropped（字段初始化先于构造器体，但后于委托调用）
        this.props = props;
        this.listener = null;
        this.listenerProvider = listenerProvider;
        this.router = null;
        this.routerProvider = routerProvider;
        this.feishuClient = feishuClient;
        this.executor = boundedExecutor(QUEUE_CAPACITY, dropped);
    }

    /** 既有测试构造器（无命令路由/回复通道：命令不拦截，照旧透传 listener）。 */
    FeishuWsClient(InvestProperties props, ImMessageListener listener, Executor executor) {
        this(props, listener, null, null, null, null, executor);
    }

    /** 测试构造器：注入直执行/可控执行器 + 命令路由与回复通道（命令拦截路径）。 */
    FeishuWsClient(InvestProperties props, ImMessageListener listener, ImCommandRouter router,
                   FeishuClient feishuClient, Executor executor) {
        this(props, listener, null, router, null, feishuClient, executor);
    }

    /** B10 测试构造器：小容量有界队列驱动「队列满丢弃留痕」路径（真实 ThreadPoolExecutor）。 */
    FeishuWsClient(InvestProperties props, ImMessageListener listener, int queueCapacity) {
        // 同上：不经委托以引用实例字段 dropped
        this.props = props;
        this.listener = listener;
        this.listenerProvider = null;
        this.router = null;
        this.routerProvider = null;
        this.feishuClient = null;
        this.executor = boundedExecutor(queueCapacity, dropped);
    }

    private FeishuWsClient(InvestProperties props, ImMessageListener listener,
                           ObjectProvider<ImMessageListener> listenerProvider,
                           ImCommandRouter router, ObjectProvider<ImCommandRouter> routerProvider,
                           FeishuClient feishuClient, Executor executor) {
        this.props = props;
        this.listener = listener;
        this.listenerProvider = listenerProvider;
        this.router = router;
        this.routerProvider = routerProvider;
        this.feishuClient = feishuClient;
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
                client = ws; // 立即赋值：握手失败/超时时 SDK autoReconnect 仍持有实例，stop() 需能 close（置 userClosed 阻断重连）
                ws.start();
                ws.awaitReady(10_000);
                running = true;
                log.info("飞书长连接就绪（appId={}）", im.getAppId());
            } catch (Exception e) {
                log.error("飞书长连接启动失败（对话不可用，不影响其他功能）", e);
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
            log.warn("飞书事件解析失败", e);
        }
    }

    /** 解析+去重+异步转投（handler 内同步调用的部分保持轻薄，快速 ack）。
     * 命令前置路由在 executor 线程内进行（bindByCode 落库属慢工作，不占 ack 路径）。
     * B10：非 owner 纯对话消息在入队前丢弃（防刷占队），命令格式文本仍放行走路由。 */
    void dispatch(String chatId, String messageId, String openId, String chatType, String msgType,
                  String contentJson) {
        if (messageId == null || seenMessages.getIfPresent(messageId) != null) {
            return;
        }
        seenMessages.put(messageId, Boolean.TRUE);
        String text = extractText(contentJson);
        if (blockedByOwnerGate(openId, text)) {
            log.debug("飞书消息来自非 owner（open_id={}，messageId={}），入队前丢弃", openId, messageId);
            return;
        }
        executor.execute(() -> {
            try {
                ImInboundMessage message =
                        new ImInboundMessage(chatId, messageId, openId, chatType, msgType, text);
                ImCommandRouter commandRouter = resolveRouter();
                if (commandRouter != null) {
                    Optional<String> reply = commandRouter.tryRoute(message);
                    if (reply.isPresent()) {
                        feishuClient.sendReply(messageId, reply.get());
                        return; // 命令已处理：不进对话桥
                    }
                }
                listener.onMessage(message);
            } catch (Exception e) { // 尽力而为：桥接/路由异常不外溢到 executor 线程
                log.error("飞书消息处理失败（messageId={}）", messageId, e);
            }
        });
    }

    /** 命令路由懒解析（生产 provider 单例查询，首次解析后记忆）；测试直注或无 bean 时按原值。 */
    private ImCommandRouter resolveRouter() {
        ImCommandRouter resolved = router;
        if (resolved != null || routerProvider == null) {
            return resolved;
        }
        resolved = routerProvider.getIfAvailable();
        if (resolved != null) {
            router = resolved;
        }
        return resolved;
    }

    /**
     * B10 非 owner 防刷门：owner 未配置（null/空白）全放行（向后兼容）；配置后仅 owner 消息
     * 与命令格式文本（绑定码，仍需走路由核销，口径复用 {@link BindingCommandHandler#extractCode}
     * 保持与真实命令形态同步）可入队。owner 判定就地复刻 agent 层 FeishuDialogueBridge 的口径
     * （infrastructure 禁 import agent 包），均以 invest.im.owner-open-id 为准。
     */
    private boolean blockedByOwnerGate(String openId, String text) {
        String owner = props.getIm().getOwnerOpenId();
        if (owner == null || owner.isBlank()) {
            return false;
        }
        return !owner.equals(openId) && BindingCommandHandler.extractCode(text) == null;
    }

    /** B10：单线程有界执行器——队列满不阻塞 WS 事件线程（拒绝仅在调用方计数+告警后丢弃）。 */
    private static ThreadPoolExecutor boundedExecutor(int queueCapacity, AtomicInteger dropped) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity 必须为正数: " + queueCapacity);
        }
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "feishu-dialogue");
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> {
                    dropped.incrementAndGet();
                    log.warn("飞书入站队列已满（capacity={}），丢弃消息", queueCapacity);
                });
    }

    /** B10：队列满丢弃累计数（测试断言/运维观测）。 */
    int droppedCount() {
        return dropped.get();
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
