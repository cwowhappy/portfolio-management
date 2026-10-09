package com.portfolio.invest.infrastructure.eval;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 进程环境快照供给（MS-30 B4）：调度器（application 层）构造子进程 env 白名单需要父进程
 * 环境全集，而 A3/E4 禁止 application 层直调 {@code System.getenv}——本适配器落在
 * infrastructure 层承担该调用（依赖方向 infrastructure→application 反向注入，调度器只见
 * {@code Supplier<Map<String,String>>} 信号，测试注入固定 map 即可，零真环境）。
 */
@Component
public class SystemParentEnvironment implements Supplier<Map<String, String>> {

    @Override
    public Map<String, String> get() {
        return System.getenv();
    }
}
