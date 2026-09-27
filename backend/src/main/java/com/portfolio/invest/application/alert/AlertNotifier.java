package com.portfolio.invest.application.alert;

import java.util.List;

/** 告警推送端口（infrastructure.im 实现）。尽力而为：失败返回 false，内部已记日志。 */
public interface AlertNotifier {

    boolean send(String title, String template, List<String> bodyLines);
}
