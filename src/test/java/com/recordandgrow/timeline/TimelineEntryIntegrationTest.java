package com.recordandgrow.timeline;

import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.response.PageResult;
import com.recordandgrow.timeline.dto.TimelineEntryRequest;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 时间线数据库集成测试：Testcontainers 起真实 PostgreSQL，程序化跑 Flyway 建表后，
 * 用真实 Service + Mapper 验证 CRUD、软删、以及日期区间重叠查询的边界语义。
 *
 * <p>注意：本测试依赖本机 Docker（沙箱无 Docker，需在用户本机运行）。
 */
@Testcontainers
@SpringBootTest
@Transactional
class TimelineEntryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("recordandgrow")
            .withUsername("recordandgrow")
            .withPassword("recordandgrow");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // 应用启动不跑 Flyway（spring.flyway.enabled=false），这里用程序化方式对测试库执行同一份迁移
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Autowired
    private TimelineEntryService service;

    @Test
    void createAndGetShouldRoundTrip() {
        TimelineEntryResponse created = service.create(request("早上跑步 5 公里", dt(14, 7, 0), dt(14, 7, 30)));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();

        TimelineEntryResponse fetched = service.get(created.getId());
        assertThat(fetched.getContent()).isEqualTo("早上跑步 5 公里");
        assertThat(fetched.getStartTime()).isEqualTo(dt(14, 7, 0));
        assertThat(fetched.getEndTime()).isEqualTo(dt(14, 7, 30));
    }

    @Test
    void createWithNullEndTimeShouldPersistOngoingEvent() {
        TimelineEntryResponse created = service.create(request("开始学习 AI", dt(1, 9, 0), null));

        assertThat(created.getEndTime()).isNull();
    }

    @Test
    void updateShouldChangeContentAndBumpUpdatedAt() throws Exception {
        TimelineEntryResponse created = service.create(request("旧内容", dt(14, 7, 0), null));
        OffsetDateTime before = created.getUpdatedAt();

        Thread.sleep(20);

        TimelineEntryResponse updated = service.update(created.getId(), request("新内容", dt(14, 7, 0), null));

        assertThat(updated.getContent()).isEqualTo("新内容");
        assertThat(updated.getUpdatedAt()).isAfter(before);
    }

    @Test
    void deleteShouldSoftDeleteAndExcludeFromQueries() {
        TimelineEntryResponse a = service.create(request("A", dt(14, 8, 0), dt(14, 9, 0)));
        service.create(request("B", dt(14, 10, 0), dt(14, 11, 0)));

        service.delete(a.getId());

        // 软删后列表自动排除
        assertThat(contents(null, null)).containsExactly("B");
        // 软删后按 id 查询视为不存在
        BusinessException ex = catchThrowableOfType(() -> service.get(a.getId()), BusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void listShouldApplyOverlapSemantics() {
        // A 跨天：10-13 22:00 → 10-14 02:00
        service.create(request("A-跨天", dt(13, 22, 0), dt(14, 2, 0)));
        // B 普通：10-14 08:00 → 09:00
        service.create(request("B-普通", dt(14, 8, 0), dt(14, 9, 0)));
        // C 进行中：10-01 起，end 为空
        service.create(request("C-进行中", dt(1, 9, 0), null));
        // D 瞬时：10-14 12:00（start=end）
        service.create(request("D-瞬时", dt(14, 12, 0), dt(14, 12, 0)));
        // E 恰好在 10-14 00:00 结束（窗口下界，不应命中 10-14 的窗口）
        service.create(request("E-边界", dt(13, 20, 0), dt(14, 0, 0)));

        // 窗口 [10-14 00:00, 10-15 00:00)：A、B、C、D 命中，E 不命中
        assertThat(contents(dt(14, 0, 0), dt(15, 0, 0)))
                .containsExactlyInAnyOrder("A-跨天", "B-普通", "C-进行中", "D-瞬时");

        // 窗口 [10-13 00:00, 10-14 00:00)：A（跨天）、C（进行中）、E 命中，B/D 尚未开始不命中
        assertThat(contents(dt(13, 0, 0), dt(14, 0, 0)))
                .containsExactlyInAnyOrder("A-跨天", "C-进行中", "E-边界");

        // 窗口 [10-14 00:00, 10-14 12:00)：A、B、C 命中，D 恰好在窗口上界（半开区间）不命中
        assertThat(contents(dt(14, 0, 0), dt(14, 12, 0)))
                .containsExactlyInAnyOrder("A-跨天", "B-普通", "C-进行中");
    }

    @Test
    void createShouldRejectEndBeforeStart() {
        BusinessException ex = catchThrowableOfType(
                () -> service.create(request("时间倒挂", dt(14, 9, 0), dt(14, 8, 0))),
                BusinessException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void updateShouldRejectEndBeforeStart() {
        TimelineEntryResponse created = service.create(request("合法记录", dt(14, 8, 0), dt(14, 9, 0)));

        BusinessException ex = catchThrowableOfType(
                () -> service.update(created.getId(), request("时间倒挂", dt(14, 9, 0), dt(14, 8, 0))),
                BusinessException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void getUpdateDeleteShouldThrowNotFoundForMissingId() {
        assertThat(catchThrowableOfType(() -> service.get(9999L), BusinessException.class).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(catchThrowableOfType(() -> service.update(9999L, request("x", dt(14, 8, 0), null)),
                BusinessException.class).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(catchThrowableOfType(() -> service.delete(9999L), BusinessException.class).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void searchShouldRespectOverlapAndExcludeSoftDeleted() {
        service.create(request("跑步 5km", dt(14, 7, 0), dt(14, 7, 30)));
        service.create(request("学习 Java", dt(15, 20, 0), null));
        TimelineEntryResponse deleted = service.create(request("Go 语言", dt(16, 9, 0), null));
        service.delete(deleted.getId());

        PageResult<TimelineEntryResponse> result =
                service.search(dt(14, 0, 0), dt(17, 0, 0), List.of(), 100);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getItems()).extracting(TimelineEntryResponse::getContent)
                .containsExactly("学习 Java", "跑步 5km");
    }

    @Test
    void searchShouldMatchChineseKeywordAsSubstring() {
        service.create(request("晚上跑步 5km", dt(14, 20, 0), null));
        service.create(request("学习 Java 集合", dt(14, 21, 0), null));

        PageResult<TimelineEntryResponse> result =
                service.search(dt(14, 0, 0), dt(15, 0, 0), List.of("跑步"), 100);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getItems().get(0).getContent()).isEqualTo("晚上跑步 5km");
    }

    @Test
    void searchShouldUseWordBoundaryForAsciiKeywords() {
        service.create(request("Go 语言", dt(14, 9, 0), null));
        service.create(request("Google Cloud", dt(14, 10, 0), null));
        service.create(request("Java 集合", dt(14, 11, 0), null));
        service.create(request("JavaScript 入门", dt(14, 12, 0), null));

        PageResult<TimelineEntryResponse> go =
                service.search(dt(14, 0, 0), dt(15, 0, 0), List.of("Go"), 100);
        assertThat(go.getItems()).extracting(TimelineEntryResponse::getContent)
                .containsExactly("Go 语言");

        PageResult<TimelineEntryResponse> java =
                service.search(dt(14, 0, 0), dt(15, 0, 0), List.of("Java"), 100);
        assertThat(java.getItems()).extracting(TimelineEntryResponse::getContent)
                .containsExactly("Java 集合");
    }

    @Test
    void searchShouldFallBackToIlikeForSymbolSuffixedAsciiKeywords() {
        service.create(request("C++ 入门", dt(14, 9, 0), null));
        service.create(request("C# 入门", dt(14, 10, 0), null));

        PageResult<TimelineEntryResponse> result =
                service.search(dt(14, 0, 0), dt(15, 0, 0), List.of("C++"), 100);

        assertThat(result.getItems()).extracting(TimelineEntryResponse::getContent)
                .containsExactly("C++ 入门");
    }

    @Test
    void searchShouldAndMultipleKeywords() {
        service.create(request("Spring Boot 学习", dt(14, 9, 0), null));
        service.create(request("Spring 生态", dt(14, 10, 0), null));
        service.create(request("Boot 配置", dt(14, 11, 0), null));

        PageResult<TimelineEntryResponse> result = service.search(
                dt(14, 0, 0), dt(15, 0, 0), List.of("Spring", "Boot"), 100);

        assertThat(result.getItems()).extracting(TimelineEntryResponse::getContent)
                .containsExactly("Spring Boot 学习");
    }

    @Test
    void searchShouldEscapeLikeWildcards() {
        service.create(request("进度 100% 完成", dt(14, 9, 0), null));
        service.create(request("进度 1000 完成", dt(14, 10, 0), null));
        service.create(request("a_b 配置", dt(14, 11, 0), null));
        service.create(request("axb 配置", dt(14, 12, 0), null));

        PageResult<TimelineEntryResponse> percent =
                service.search(dt(14, 0, 0), dt(15, 0, 0), List.of("100%"), 100);
        assertThat(percent.getItems()).extracting(TimelineEntryResponse::getContent)
                .containsExactly("进度 100% 完成");

        PageResult<TimelineEntryResponse> underscore =
                service.search(dt(14, 0, 0), dt(15, 0, 0), List.of("a_b"), 100);
        assertThat(underscore.getItems()).extracting(TimelineEntryResponse::getContent)
                .containsExactly("a_b 配置");
    }

    @Test
    void searchShouldApplyLimitAndReportTotal() {
        service.create(request("第一条", dt(14, 9, 0), null));
        service.create(request("第二条", dt(14, 10, 0), null));
        service.create(request("第三条", dt(14, 11, 0), null));

        PageResult<TimelineEntryResponse> result =
                service.search(dt(14, 0, 0), dt(15, 0, 0), List.of(), 2);

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems()).extracting(TimelineEntryResponse::getContent)
                .containsExactly("第三条", "第二条");
    }

    private List<String> contents(OffsetDateTime start, OffsetDateTime end) {
        PageResult<TimelineEntryResponse> page = service.list(start, end, 1, 100);
        return page.getItems().stream().map(TimelineEntryResponse::getContent).toList();
    }

    private TimelineEntryRequest request(String content, OffsetDateTime start, OffsetDateTime end) {
        TimelineEntryRequest req = new TimelineEntryRequest();
        req.setContent(content);
        req.setStartTime(start);
        req.setEndTime(end);
        return req;
    }

    /** 以 +08:00（Asia/Shanghai 无夏令时）构造固定时刻，便于按「天」表述边界。 */
    private OffsetDateTime dt(int day, int hour, int minute) {
        return OffsetDateTime.of(2026, 10, day, hour, minute, 0, 0, ZoneOffset.ofHours(8));
    }
}
