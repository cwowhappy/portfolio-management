package com.portfolio.invest.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 题库装载：classpath {@code questions/*.yaml}（eval 源集资源目录），YAML 数组 → {@link EvalQuestion}。
 *
 * <p>schema 校验（装载即失败，fail-fast）：id 非空且全库唯一、category/mode 取值合法、turns
 * 非空、judge 引用的 rubric 资源存在、mode=stub 时至少一个 expect 维度；dataFidelity 锚点须与
 * 桩数据自洽（锚点串必须出现在桩数据 JSON 序列化里——防"期望 1700.5、桩里 1700.50"这类写错题）。
 */
public final class QuestionLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private QuestionLoader() {}

    public static List<EvalQuestion> loadFromClasspath() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:questions/*.yaml");
            List<EvalQuestion> all = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Resource resource : resources) {
                List<EvalQuestion> inFile;
                try (InputStream in = resource.getInputStream()) {
                    inFile = YAML.readValue(in,
                            YAML.getTypeFactory().constructCollectionType(List.class, EvalQuestion.class));
                }
                for (EvalQuestion q : inFile) {
                    validate(q, resource.getFilename());
                    if (!ids.add(q.id())) {
                        throw new IllegalStateException("题库 id 重复: " + q.id()
                                + "（文件 " + resource.getFilename() + "）");
                    }
                    all.add(q);
                }
            }
            if (all.isEmpty()) {
                throw new IllegalStateException("题库为空：classpath:questions/*.yaml 未装载到任何题目");
            }
            return List.copyOf(all);
        } catch (IOException e) {
            throw new UncheckedIOException("题库装载失败", e);
        }
    }

    private static void validate(EvalQuestion q, String file) {
        require(q.id() != null && !q.id().isBlank(), file, "id 缺失");
        require(q.category() != null && EvalQuestion.CATEGORIES.contains(q.category()),
                file, q.id() + ": category 须为 " + EvalQuestion.CATEGORIES + " 之一");
        require(q.mode() != null && EvalQuestion.MODES.contains(q.mode()),
                file, q.id() + ": mode 须为 " + EvalQuestion.MODES + " 之一");
        require(q.turns() != null && !q.turns().isEmpty()
                        && q.turns().stream().noneMatch(t -> t == null || t.isBlank()),
                file, q.id() + ": turns 须为非空字符串数组");
        require(q.judge() != null && !q.judge().isBlank(), file, q.id() + ": judge 引用缺失");
        require(QuestionLoader.class.getClassLoader().getResource("rubric/" + q.judge() + ".md") != null,
                file, q.id() + ": judge 引用的 rubric 不存在: rubric/" + q.judge() + ".md");
        require(!q.isStubMode() || q.expect() != null, file,
                q.id() + ": mode=stub 的题必须声明 expect（至少一个维度）");
        validateFidelityAnchors(q, file);
    }

    /** dataFidelity 锚点须能在桩数据 JSON 里找到（仅 stub 模式且有锚点时校验）。 */
    private static void validateFidelityAnchors(EvalQuestion q, String file) {
        if (!q.isStubMode() || q.expect() == null || q.expect().dataFidelity() == null) return;
        List<String> anchors = q.expect().dataFidelity().answerContains();
        if (anchors == null || anchors.isEmpty()) return;
        String stubJson;
        try {
            stubJson = JSON.writeValueAsString(q.stubData() == null ? EvalStubData.empty() : q.stubData());
        } catch (IOException e) {
            throw new IllegalStateException(file + " " + q.id() + ": 桩数据序列化失败", e);
        }
        for (String anchor : anchors) {
            require(anchor != null && !anchor.isBlank() && stubJson.contains(anchor), file,
                    q.id() + ": dataFidelity 锚点 \"" + anchor + "\" 与桩数据不自洽（桩 JSON 中找不到）");
        }
    }

    private static void require(boolean condition, String file, String message) {
        if (!condition) {
            throw new IllegalStateException("题库校验失败（" + file + "）: " + message);
        }
    }
}
