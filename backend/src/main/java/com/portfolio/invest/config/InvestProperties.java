package com.portfolio.invest.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 业务配置（invest.*），详见 application.yml。 */
@ConfigurationProperties(prefix = "invest")
public class InvestProperties {

    private Llm llm = new Llm();
    private Market market = new Market();
    private Admin admin = new Admin();
    private Security security = new Security();
    private AppCache appCache = new AppCache();
    private Mcp mcp = new Mcp();
    private Im im = new Im();
    private Mail mail = new Mail();
    private Intelligence intelligence = new Intelligence();
    private Trust trust = new Trust();

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Market getMarket() {
        return market;
    }

    public void setMarket(Market market) {
        this.market = market;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public AppCache getAppCache() {
        return appCache;
    }

    public void setAppCache(AppCache appCache) {
        this.appCache = appCache;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public void setMcp(Mcp mcp) {
        this.mcp = mcp;
    }

    public Im getIm() {
        return im;
    }

    public void setIm(Im im) {
        this.im = im;
    }

    public Mail getMail() {
        return mail;
    }

    public void setMail(Mail mail) {
        this.mail = mail;
    }

    public Intelligence getIntelligence() {
        return intelligence;
    }

    public void setIntelligence(Intelligence intelligence) {
        this.intelligence = intelligence;
    }

    public Trust getTrust() {
        return trust;
    }

    public void setTrust(Trust trust) {
        this.trust = trust;
    }

    public static class Llm {
        private String provider = "deepseek";
        private String model = "deepseek-v4-flash";
        private String baseUrl = "https://api.deepseek.com";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Market {
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(5);
        private int rateLimitPerSecond = 5;
        private int maxAttempts = 3;
        private long retryBackoffMillis = 300;
        private long acquireTimeoutMillis = 2000;
        private Cache cache = new Cache();

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public int getRateLimitPerSecond() {
            return rateLimitPerSecond;
        }

        public void setRateLimitPerSecond(int rateLimitPerSecond) {
            this.rateLimitPerSecond = rateLimitPerSecond;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getRetryBackoffMillis() {
            return retryBackoffMillis;
        }

        public void setRetryBackoffMillis(long retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
        }

        public long getAcquireTimeoutMillis() {
            return acquireTimeoutMillis;
        }

        public void setAcquireTimeoutMillis(long acquireTimeoutMillis) {
            this.acquireTimeoutMillis = acquireTimeoutMillis;
        }

        public Cache getCache() {
            return cache;
        }

        public void setCache(Cache cache) {
            this.cache = cache;
        }
    }

    public static class Cache {
        private int maxEntries = 10000;
        private Duration quoteTtl = Duration.ofSeconds(15);
        private Duration klineTtl = Duration.ofMinutes(5);
        private Duration searchTtl = Duration.ofMinutes(10);
        private Duration financialsTtl = Duration.ofHours(1);
        private Duration newsTtl = Duration.ofMinutes(5);
        private Duration overviewTtl = Duration.ofSeconds(15);

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public Duration getQuoteTtl() {
            return quoteTtl;
        }

        public void setQuoteTtl(Duration quoteTtl) {
            this.quoteTtl = quoteTtl;
        }

        public Duration getKlineTtl() {
            return klineTtl;
        }

        public void setKlineTtl(Duration klineTtl) {
            this.klineTtl = klineTtl;
        }

        public Duration getSearchTtl() {
            return searchTtl;
        }

        public void setSearchTtl(Duration searchTtl) {
            this.searchTtl = searchTtl;
        }

        public Duration getFinancialsTtl() {
            return financialsTtl;
        }

        public void setFinancialsTtl(Duration financialsTtl) {
            this.financialsTtl = financialsTtl;
        }

        public Duration getNewsTtl() {
            return newsTtl;
        }

        public void setNewsTtl(Duration newsTtl) {
            this.newsTtl = newsTtl;
        }

        public Duration getOverviewTtl() {
            return overviewTtl;
        }

        public void setOverviewTtl(Duration overviewTtl) {
            this.overviewTtl = overviewTtl;
        }
    }

    public static class Admin {
        private String username = "";
        private String password = "";
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /** 飞书自建应用（feishu-messaging 通道二）。空值 = 未启用，相关功能静默跳过。 */
    public static class Im {
        private String appId = "";
        private String appSecret = "";
        private String chatId = "";
        private String ownerUsername = "";
        private String apiBase = "https://open.feishu.cn";
        private String ownerOpenId = "";
        private boolean dialogueEnabled = false;

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getChatId() { return chatId; }
        public void setChatId(String chatId) { this.chatId = chatId; }
        public String getOwnerUsername() { return ownerUsername; }
        public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }

        public String getOwnerOpenId() { return ownerOpenId; }
        public void setOwnerOpenId(String ownerOpenId) { this.ownerOpenId = ownerOpenId; }
        public boolean isDialogueEnabled() { return dialogueEnabled; }
        public void setDialogueEnabled(boolean dialogueEnabled) { this.dialogueEnabled = dialogueEnabled; }
    }

    public static class Security {
        /** remember-me 签名 key：必须经配置提供，缺失/空白时拒绝启动（去公开兜底 key）。 */
        private String rememberMeKey = "";
        public String getRememberMeKey() { return rememberMeKey; }
        public void setRememberMeKey(String rememberMeKey) { this.rememberMeKey = rememberMeKey; }
    }

    /** 应用级共享缓存（ApplicationCache）配置：估值/筛选/探活等结果缓存。 */
    public static class AppCache {
        private int maxEntries = 1000;
        private Duration ttl = Duration.ofMinutes(5);
        private Duration healthProbeTtl = Duration.ofSeconds(30);

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getHealthProbeTtl() {
            return healthProbeTtl;
        }

        public void setHealthProbeTtl(Duration healthProbeTtl) {
            this.healthProbeTtl = healthProbeTtl;
        }
    }

    public static class Mcp {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration toolTimeout = Duration.ofSeconds(30);
        /** token 加密主密钥（MCP_SECRET_KEY，base64 32B）。空白 = 未启用：不阻断启动，加解密调用时报错（P1-10）。 */
        private String secretKey = "";
        private Harness harness = new Harness();
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getToolTimeout() { return toolTimeout; }
        public void setToolTimeout(Duration toolTimeout) { this.toolTimeout = toolTimeout; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public Harness getHarness() { return harness; }
        public void setHarness(Harness harness) { this.harness = harness; }

        public static class Harness {
            private String workspace = ".agentscope/workspace";
            private String stateRoot = ".agentscope/state";
            private Compaction compaction = new Compaction();
            private Memory memory = new Memory();
            public String getWorkspace() { return workspace; }
            public void setWorkspace(String workspace) { this.workspace = workspace; }
            public String getStateRoot() { return stateRoot; }
            public void setStateRoot(String stateRoot) { this.stateRoot = stateRoot; }
            public Compaction getCompaction() { return compaction; }
            public void setCompaction(Compaction compaction) { this.compaction = compaction; }
            public Memory getMemory() { return memory; }
            public void setMemory(Memory memory) { this.memory = memory; }
            public static class Compaction {
                private int triggerMessages = 30;
                private int keepMessages = 10;
                private boolean flushBeforeCompact = true;
                public int getTriggerMessages() { return triggerMessages; }
                public void setTriggerMessages(int triggerMessages) { this.triggerMessages = triggerMessages; }
                public int getKeepMessages() { return keepMessages; }
                public void setKeepMessages(int keepMessages) { this.keepMessages = keepMessages; }
                public boolean isFlushBeforeCompact() { return flushBeforeCompact; }
                public void setFlushBeforeCompact(boolean flushBeforeCompact) { this.flushBeforeCompact = flushBeforeCompact; }
            }
            public static class Memory {
                private Duration flushMinGap = Duration.ofMinutes(30);
                public Duration getFlushMinGap() { return flushMinGap; }
                public void setFlushMinGap(Duration flushMinGap) { this.flushMinGap = flushMinGap; }
            }
        }
    }

    /** 情报域（M15）：新闻/公告/政策 LLM 抽取批与重要度分档（详见设计规格 D6/D16 与 §4.5）。 */
    public static class Intelligence {
        private int majorThreshold = 80;
        private int watchThreshold = 50;
        private int extractBatchSize = 15;
        private long dailyTokenGuardrail = 2_000_000L;
        private int briefMaxItems = 25;
        private int briefMinItems = 15;
        /** 绑定码 TTL 分钟数（D8：设置页生成 6 位数字码，10 分钟内飞书发码核销）。 */
        private int bindingCodeTtlMinutes = 10;

        public int getMajorThreshold() { return majorThreshold; }
        public void setMajorThreshold(int majorThreshold) { this.majorThreshold = majorThreshold; }
        public int getWatchThreshold() { return watchThreshold; }
        public void setWatchThreshold(int watchThreshold) { this.watchThreshold = watchThreshold; }
        public int getExtractBatchSize() { return extractBatchSize; }
        public void setExtractBatchSize(int extractBatchSize) { this.extractBatchSize = extractBatchSize; }
        public long getDailyTokenGuardrail() { return dailyTokenGuardrail; }
        public void setDailyTokenGuardrail(long dailyTokenGuardrail) { this.dailyTokenGuardrail = dailyTokenGuardrail; }
        public int getBriefMaxItems() { return briefMaxItems; }
        public void setBriefMaxItems(int briefMaxItems) { this.briefMaxItems = briefMaxItems; }
        public int getBriefMinItems() { return briefMinItems; }
        public void setBriefMinItems(int briefMinItems) { this.briefMinItems = briefMinItems; }
        public int getBindingCodeTtlMinutes() { return bindingCodeTtlMinutes; }
        public void setBindingCodeTtlMinutes(int bindingCodeTtlMinutes) { this.bindingCodeTtlMinutes = bindingCodeTtlMinutes; }
    }

    /** 可信溯源（MS-29）：容差参数组① + 修正上限参数组⑥（设计规格 §4.6，ConsistencyValidator 消费）。 */
    public static class Trust {
        private ToleranceSettings tolerance =
                new ToleranceSettings(new BigDecimal("0.02"), new BigDecimal("0.05"),
                        new BigDecimal("0.01"), new BigDecimal("0.10"));
        private CorrectionSettings correction = new CorrectionSettings(2);
        /** 词表参数组④（MS-29 B6，决策 #4 双层井集的兜底层；空表 = 仅自声明标记生效）。 */
        private List<String> adviceLexicon = new ArrayList<>(List.of(
                "买入", "卖出", "加仓", "减仓", "清仓", "建仓", "补仓", "止损", "止盈",
                "目标价", "抄底", "逃顶", "满仓", "空仓"));
        /** 文案参数组③（MS-29 B6）：advice.flag=true 时随 trust.anchors payload 透传（F3 前端 DisclaimerNote 消费）。 */
        private String disclaimerText = "以上内容由 AI 生成，仅供参考，不构成任何投资建议；"
                + "市场有风险，投资决策请独立判断或咨询持牌专业机构。";

        public ToleranceSettings getTolerance() { return tolerance; }
        public void setTolerance(ToleranceSettings tolerance) { this.tolerance = tolerance; }
        public CorrectionSettings getCorrection() { return correction; }
        public void setCorrection(CorrectionSettings correction) { this.correction = correction; }
        public List<String> getAdviceLexicon() { return adviceLexicon; }
        public void setAdviceLexicon(List<String> adviceLexicon) { this.adviceLexicon = adviceLexicon; }
        public String getDisclaimerText() { return disclaimerText; }
        public void setDisclaimerText(String disclaimerText) { this.disclaimerText = disclaimerText; }

        /**
         * 容差参数组①（用户拍板默认）：relative=相对 2%；absolute=无量纲绝对 0.05（%、倍）；
         * price=价格语义绝对 0.01 元；deviation=大偏差线 10%（超出容差即拦截改写——中间带按大偏差处理，决策 #1）。
         * 嵌套 record 走构造器绑定：部分配置时分量为 null，紧凑构造器回退默认（不产生空值）。
         */
        public record ToleranceSettings(BigDecimal relative, BigDecimal absolute,
                BigDecimal price, BigDecimal deviation) {
            public ToleranceSettings {
                if (relative == null) relative = new BigDecimal("0.02");
                if (absolute == null) absolute = new BigDecimal("0.05");
                if (price == null) price = new BigDecimal("0.01");
                if (deviation == null) deviation = new BigDecimal("0.10");
            }
        }

        /** 修正参数组⑥：确定性替换重试上限（替换后重校验，触达降级：原文保留 + 显式标注 + 修正失败信号）。 */
        public record CorrectionSettings(Integer maxRetries) {
            public CorrectionSettings {
                if (maxRetries == null) maxRetries = 2;
            }
        }
    }

    /** SMTP 发信（M01-F06，阿里云企业邮箱）。空值 = 未启用，发信入口返回「系统未配置邮件服务」。 */
    public static class Mail {
        private String smtpHost = "";
        private int smtpPort = 465;
        private String smtpUsername = "";
        private String smtpPassword = "";
        private String from = "";
        /** 仅 e2e/联调：非空时验证码恒为该值（生产必须留空，且须同时开 test-mode）。 */
        private String testFixedCode = "";
        /** 固定码开关（MAIL_TEST_MODE）：test-fixed-code 非空但本开关未开时启动即失败——防固定码泄入生产 env。 */
        private boolean testMode = false;
        /** 告警降级收件人（ALERT_MAIL_TO，逗号分隔，B13）：飞书调度告警失败时的邮件兜底；空 = 不降级。 */
        private List<String> alertMailTo = new ArrayList<>();

        public boolean configured() {
            return !smtpHost.isBlank() && !smtpUsername.isBlank()
                    && !smtpPassword.isBlank() && !from.isBlank();
        }

        public String getSmtpHost() { return smtpHost; }
        public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }
        public int getSmtpPort() { return smtpPort; }
        public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }
        public String getSmtpUsername() { return smtpUsername; }
        public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }
        public String getSmtpPassword() { return smtpPassword; }
        public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTestFixedCode() { return testFixedCode; }
        public void setTestFixedCode(String testFixedCode) { this.testFixedCode = testFixedCode; }
        public boolean isTestMode() { return testMode; }
        public void setTestMode(boolean testMode) { this.testMode = testMode; }
        public List<String> getAlertMailTo() { return alertMailTo; }
        public void setAlertMailTo(List<String> alertMailTo) { this.alertMailTo = alertMailTo; }
    }
}
