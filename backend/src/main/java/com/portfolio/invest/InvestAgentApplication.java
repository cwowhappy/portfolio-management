package com.portfolio.invest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InvestAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvestAgentApplication.class, args);
    }
}
