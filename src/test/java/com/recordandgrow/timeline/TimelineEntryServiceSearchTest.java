package com.recordandgrow.timeline;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * 只验证 search() 生成的 SQL 片段与参数，不连数据库。
 *
 * <p>真正执行 `~*` / `ILIKE` 的语义仍由 Testcontainers 集成测试覆盖；
 * 这里保证 Java 侧没有写错占位符、转义或 AND/OR。
 */
@ExtendWith(MockitoExtension.class)
class TimelineEntryServiceSearchTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-14T00:00:00+08:00");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-10-15T00:00:00+08:00");

    @Mock
    private TimelineEntryMapper mapper;

    @InjectMocks
    private TimelineEntryService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 解析列名依赖 MyBatis-Plus 的 lambda 缓存；纯单测里手动初始化一次
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TimelineEntry.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asciiWordUsesRegexWordBoundary() {
        Wrapper<TimelineEntry> wrapper = captureWrapper(List.of("Go"), 10);

        assertThat(wrapper.getSqlSegment()).contains("content ~*");
        assertThat(params(wrapper).values()).contains("\\mGo\\M");
    }

    @Test
    @SuppressWarnings("unchecked")
    void chineseKeywordUsesIlikeWithPercentWildcards() {
        Wrapper<TimelineEntry> wrapper = captureWrapper(List.of("跑步"), 10);

        assertThat(wrapper.getSqlSegment()).contains("content ILIKE");
        assertThat(params(wrapper).values()).contains("%跑步%");
    }

    @Test
    @SuppressWarnings("unchecked")
    void symbolSuffixedAsciiKeywordFallsBackToIlike() {
        Wrapper<TimelineEntry> wrapper = captureWrapper(List.of("C++"), 10);

        assertThat(wrapper.getSqlSegment()).contains("content ILIKE");
        assertThat(params(wrapper).values()).contains("%C++%");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multipleKeywordsAreAnded() {
        Wrapper<TimelineEntry> wrapper = captureWrapper(List.of("Spring", "Boot"), 10);

        assertThat(wrapper.getSqlSegment()).contains("content ~*").contains("AND");
        assertThat(params(wrapper).values())
                .contains("\\mSpring\\M", "\\mBoot\\M");
    }

    @Test
    @SuppressWarnings("unchecked")
    void likeWildcardsAreEscaped() {
        Wrapper<TimelineEntry> percent = captureWrapper(List.of("100%"), 10);
        String percentPattern = onlyPattern(percent);
        assertThat(percentPattern).hasSize(7);
        assertThat(percentPattern.charAt(4)).isEqualTo('\\');

        // 含中文 → 走 ILIKE，才能覆盖 LIKE 的 _ 转义
        Wrapper<TimelineEntry> underscore = captureWrapper(List.of("进度_a"), 10);
        String underscorePattern = onlyPattern(underscore);
        assertThat(underscorePattern).hasSize(7);
        assertThat(underscorePattern.charAt(3)).isEqualTo('\\');
    }

    @Test
    @SuppressWarnings("unchecked")
    void noKeywordsKeepsOnlyOverlapPredicate() {
        Wrapper<TimelineEntry> wrapper = captureWrapper(List.of(), 10);

        assertThat(wrapper.getSqlSegment())
                .contains("end_time IS NULL")
                .contains("end_time >")
                .doesNotContain("content");
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitIsClampedToMaxPageSize() {
        doReturn(new Page<TimelineEntry>())
                .when(mapper).selectPage(any(), any());
        service.search(START, END, List.of(), 500);

        ArgumentCaptor<Page<TimelineEntry>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(100);
    }

    @SuppressWarnings("unchecked")
    private Wrapper<TimelineEntry> captureWrapper(List<String> keywords, int limit) {
        clearInvocations(mapper);
        doReturn(new Page<TimelineEntry>())
                .when(mapper).selectPage(any(), any());
        service.search(START, END, keywords, limit);

        ArgumentCaptor<Wrapper<TimelineEntry>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        // 参数表在生成 SQL 片段时才填充
        captor.getValue().getSqlSegment();
        assertThat(params(captor.getValue())).isNotNull();
        return captor.getValue();
    }

    private static Map<String, Object> params(Wrapper<TimelineEntry> wrapper) {
        return ((AbstractWrapper<TimelineEntry, ?, ?>) wrapper).getParamNameValuePairs();
    }

    private static String onlyPattern(Wrapper<TimelineEntry> wrapper) {
        // 参数表里还有时间窗边界的两个 OffsetDateTime，这里只取关键词的 String
        return params(wrapper).values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
