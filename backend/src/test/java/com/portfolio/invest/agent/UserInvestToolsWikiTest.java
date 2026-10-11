package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.wiki.WikiApplicationService;
import com.portfolio.invest.application.wiki.WikiEntryView;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * search_wiki 用户知识库检索工具（MS-32 B5，研报五源收口）：keyword title/content
 * 大小写不敏感 contains 过滤、上限 20 条、单条截 200 字符摘要、type 枚举解析
 * （非法值沿 run() 兜底返回友好错误文本，绝不抛）、空结果话术、userId 构造传导。
 * WikiApplicationService 以手写 fake（覆写 entries 记录入参回放固定清单）钉行为，不起 Spring。
 */
class UserInvestToolsWikiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EMPTY_MESSAGE = "知识库中无相关笔记（可直接基于工具数据分析）";

    /** 手写 fake：覆写 entries 记录 (userId, type) 入参并回放固定清单（构造依赖不参与行为）。 */
    private static final class FakeWikiService extends WikiApplicationService {

        private final List<WikiEntryView> entries;
        Long seenUserId;
        WikiEntryType seenType;

        FakeWikiService(List<WikiEntryView> entries) {
            super(null, null, null);
            this.entries = entries;
        }

        @Override
        public List<WikiEntryView> entries(Long userId, WikiEntryType type) {
            this.seenUserId = userId;
            this.seenType = type;
            return entries;
        }
    }

    private static WikiEntryView entry(WikiEntryType type, String title, String content) {
        return new WikiEntryView(1L, type, title, content, null, null,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));
    }

    @DisplayName("search_wiki 命中过滤：title/content 大小写不敏感 contains，上限 20 条、单条截 200 字符，userId 传导")
    @Test
    void givenEntries_whenSearchWikiByKeyword_thenFilteredCappedTruncatedWithUserId() {
        FakeWikiService wiki = new FakeWikiService(List.of(
                entry(WikiEntryType.BOOK_NOTE, "安全边际读书笔记", "格雷厄姆：价格与价值的关系"),
                entry(WikiEntryType.RESEARCH_NOTE, "白酒行业研究", "核心原则是安全边际优先，回调分批"),
                entry(WikiEntryType.CONCEPT, "Margin of Safety 概念", "护城河与估值缓冲"),
                entry(WikiEntryType.CONCEPT, "护城河速查", "品牌与转换成本")));
        UserInvestTools tools = new UserInvestTools(7L, null, null, null, wiki, MAPPER);

        String out = tools.searchWiki("安全边际", null);

        // 标题命中 + 内容命中都保留，双不命中剔除
        assertThat(out)
                .contains("【BOOK_NOTE】安全边际读书笔记")
                .contains("【RESEARCH_NOTE】白酒行业研究")
                .doesNotContain("护城河速查");
        assertThat(wiki.seenUserId).isEqualTo(7L); // userId 构造传导，不经 LLM 入参
        assertThat(wiki.seenType).isNull(); // type 空=全部

        // 大小写不敏感：小写关键词命中英文标题
        String english = tools.searchWiki("margin", null);
        assertThat(english).contains("Margin of Safety 概念");

        // 上限 20 条 + 单条截 200 字符：25 条全命中只显示前 20，超长内容截断加省略号
        List<WikiEntryView> bulk = new java.util.ArrayList<>();
        bulk.add(entry(WikiEntryType.BOOK_NOTE, "安全边际笔记1",
                "甲".repeat(200) + "TRUNCATION_MARKER_XYZ"));
        for (int i = 2; i <= 25; i++) {
            bulk.add(entry(WikiEntryType.BOOK_NOTE, "安全边际笔记" + i, "短内容"));
        }
        String capped = new UserInvestTools(7L, null, null, null,
                new FakeWikiService(List.copyOf(bulk)), MAPPER).searchWiki("安全边际", null);

        assertThat(capped).contains("知识库命中 25 条（显示前 20 条）");
        assertThat(capped.split("【", -1).length - 1).isEqualTo(20); // 恰 20 行条目
        assertThat(capped).contains("安全边际笔记20").doesNotContain("安全边际笔记21");
        assertThat(capped).contains("甲".repeat(200)).doesNotContain("TRUNCATION_MARKER_XYZ");
    }

    @DisplayName("search_wiki 类型解析：合法枚举透传（trim）、空=全部；非法值返回友好错误文本不抛、不打服务")
    @Test
    void givenTypeVariants_whenSearchWiki_thenParsedPassedAndIllegalReturnsFriendlyError() {
        FakeWikiService wiki = new FakeWikiService(List.of(
                entry(WikiEntryType.BOOK_NOTE, "安全边际读书笔记", "格雷厄姆"),
                entry(WikiEntryType.CONCEPT, "护城河速查", "品牌与转换成本")));
        UserInvestTools tools = new UserInvestTools(7L, null, null, null, wiki, MAPPER);

        String all = tools.searchWiki(null, "BOOK_NOTE");
        assertThat(wiki.seenType).isEqualTo(WikiEntryType.BOOK_NOTE);
        assertThat(all).contains("【BOOK_NOTE】安全边际读书笔记"); // keyword 空=不过滤，服务回什么出什么

        tools.searchWiki(null, "  CONCEPT  ");
        assertThat(wiki.seenType).isEqualTo(WikiEntryType.CONCEPT); // 首尾空白 trim 后解析

        tools.searchWiki(null, null);
        tools.searchWiki(null, " ");
        assertThat(wiki.seenType).isNull(); // 空白串归一 null=全部

        // 非法类型：友好错误 JSON（列合法取值，LLM 自纠），绝不抛、不到服务层
        FakeWikiService untouched = new FakeWikiService(List.of());
        String bad = new UserInvestTools(7L, null, null, null, untouched, MAPPER)
                .searchWiki(null, "WEEKLY");

        assertThat(bad)
                .contains("\"error\"")
                .contains("未知知识库类型：WEEKLY")
                .contains("BOOK_NOTE").contains("CONCEPT").contains("RESEARCH_NOTE")
                .contains("请修正 type 后重试");
        assertThat(untouched.seenUserId).isNull(); // 未打到服务层
    }

    @DisplayName("search_wiki 空结果话术：无笔记与关键词不过滤两路都引导「可直接基于工具数据分析」")
    @Test
    void givenNoMatch_whenSearchWiki_thenEmptyGuidanceMessage() {
        String noEntries = new UserInvestTools(7L, null, null, null,
                new FakeWikiService(List.of()), MAPPER).searchWiki(null, null);
        assertThat(noEntries).contains(EMPTY_MESSAGE);

        FakeWikiService wiki = new FakeWikiService(List.of(
                entry(WikiEntryType.CONCEPT, "护城河速查", "品牌与转换成本")));
        String keywordNoMatch = new UserInvestTools(7L, null, null, null, wiki, MAPPER)
                .searchWiki("不存在关键词", null);
        assertThat(keywordNoMatch).contains(EMPTY_MESSAGE);
    }
}
