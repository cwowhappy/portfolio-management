package com.portfolio.invest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 业务配置（invest.*），详见 application.yml。 */
@ConfigurationProperties(prefix = "invest")
public class InvestProperties {

    private Llm llm = new Llm();
    private Market market = new Market();
    private Admin admin = new Admin();
    private Security security = new Security();
    private AppCache appCache = new AppCache();

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
}
