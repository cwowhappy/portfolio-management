package com.portfolio.invest.infrastructure.im;

import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.config.InvestProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FeishuAlertNotifier implements AlertNotifier {

    private final FeishuClient client;
    private final InvestProperties props;

    public FeishuAlertNotifier(FeishuClient client, InvestProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public boolean send(String title, String template, List<String> bodyLines) {
        return client.sendCard(props.getIm().getChatId(), title, template, bodyLines);
    }
}
