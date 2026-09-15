package com.portfolio.invest.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 评估进程的环境变量支持：进程环境变量优先，缺失时回退仓库根 {@code .env}（scripts/smoke.sh 同款约定）。 */
public final class EnvSupport {

    private EnvSupport() {}

    /**
     * 仓库根定位：从工作目录（Gradle JavaExec 固定为 backend/）向上找同时含 {@code .env}
     * 与 {@code backend/} 的目录；找不到则返回工作目录本身（.env 缺失场景由调用方给指引）。
     */
    public static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve(".env")) && Files.isDirectory(dir.resolve("backend"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    /** 解析 {@code .env}（存在时）：{@code key=value} 行，忽略空行/注释，去可选引号。 */
    public static Map<String, String> loadDotEnv(Path root) {
        Map<String, String> values = new LinkedHashMap<>();
        Path file = root.resolve(".env");
        if (!Files.isRegularFile(file)) return values;
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim().replaceAll("^[\"']|[\"']$", "");
                values.put(key, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 .env 失败: " + file, e);
        }
        return values;
    }

    /** 取值：进程环境变量优先，其次 .env；两者皆无返回 empty。 */
    public static Optional<String> resolve(String key, Map<String, String> dotEnv) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) return Optional.of(fromEnv);
        String fromFile = dotEnv.get(key);
        if (fromFile != null && !fromFile.isBlank()) return Optional.of(fromFile);
        return Optional.empty();
    }
}
