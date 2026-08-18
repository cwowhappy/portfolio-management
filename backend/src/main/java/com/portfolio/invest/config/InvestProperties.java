package com.portfolio.invest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 业务配置（invest.*），详见 application.yml。 */
@ConfigurationProperties(prefix = "invest")
public class InvestProperties {

    private Llm llm = new Llm();
    private Market market = new Market();

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

    public static class Llm {
        private String provider = "deepseek";
        private String model = "deepseek-chat";
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

        public Cache getCache() {
            return cache;
        }

        public void setCache(Cache cache) {
            this.cache = cache;
        }
    }

    public static class Cache {
        private Duration quoteTtl = Duration.ofSeconds(15);
        private Duration klineTtl = Duration.ofMinutes(5);
        private Duration searchTtl = Duration.ofMinutes(10);
        private Duration financialsTtl = Duration.ofHours(1);
        private Duration newsTtl = Duration.ofMinutes(5);
        private Duration overviewTtl = Duration.ofSeconds(15);

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
}
