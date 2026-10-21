package com.logsmanage.system.service;

import com.logsmanage.system.dto.PageResponse;
import org.bson.BsonRegularExpression;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDataServiceTest {

    private MongoTemplate primaryTemplate;
    private MongoTemplate datahubTemplate;
    private MongoTemplate datahubSysTemplate;
    private UserDataService service;

    @BeforeEach
    void setUp() {
        primaryTemplate = mock(MongoTemplate.class);
        datahubTemplate = mock(MongoTemplate.class);
        datahubSysTemplate = mock(MongoTemplate.class);
        service = new UserDataService(primaryTemplate, mock(RegisterFileService.class),
                mock(UserCollegeService.class));
        ReflectionTestUtils.setField(service, "datahubTemplate", datahubTemplate);
        ReflectionTestUtils.setField(service, "datahubSysTemplate", datahubSysTemplate);
    }

    @Test
    void excludesUnbannedUsersBeforeCountingAndPagingDisabledList() {
        when(datahubTemplate.find(any(Query.class), eq(Document.class), eq("BanedList")))
                .thenReturn(Collections.singletonList(
                        new Document("UserName", "released-user")));
        when(primaryTemplate.count(any(Query.class), eq("User_Data"))).thenReturn(0L);
        when(primaryTemplate.find(any(Query.class), eq(Document.class), eq("User_Data")))
                .thenReturn(Collections.emptyList());

        service.getDisabledUsers(1, 10);

        ArgumentCaptor<Query> countQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(primaryTemplate).count(countQueryCaptor.capture(), eq("User_Data"));
        Document userNameCondition = countQueryCaptor.getValue().getQueryObject()
                .get("UserName", Document.class);
        assertThat(userNameCondition.getList("$nin", String.class))
                .containsExactly("released-user");
    }

    @Test
    void finalizesRankingAfterMergedCountsBeforeLimit() {
        Map<String, Document> merged = new LinkedHashMap<>();
        merged.put("lower-newlog-count", rankingDoc("lower-newlog-count", 10));
        merged.put("higher-final-count", rankingDoc("higher-final-count", 20));
        merged.get("lower-newlog-count").put("operationCount", 110L);

        List<Map<String, Object>> records = service.finalizeRankingRecords(merged, null, 1);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("UserName")).isEqualTo("lower-newlog-count");
        assertThat(records.get(0).get("operationCount")).isEqualTo(110L);
        assertThat(records.get(0).get("rank")).isEqualTo(1);
    }

    @Test
    void filtersApplyChannelBeforeRankingLimit() {
        Map<String, Document> merged = new LinkedHashMap<>();
        merged.put("bulk-user", rankingDoc("bulk-user", 200));
        merged.put("apply-user", rankingDoc("apply-user", 100));
        merged.get("bulk-user").put("udPiliang", true);
        merged.get("apply-user").put("udPiliang", false);

        List<Map<String, Object>> records = service.finalizeRankingRecords(merged, "apply", 1);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("UserName")).isEqualTo("apply-user");
        assertThat(records.get(0).get("rank")).isEqualTo(1);
    }

    @Test
    void reportsEnLogTotalWhenTargetCollectionExists() {
        when(primaryTemplate.collectionExists("En_Log")).thenReturn(true);
        when(primaryTemplate.count(any(Query.class), eq("En_Log"))).thenReturn(33L);

        Map<String, Object> body = service.getEnLogTotal();

        assertThat(body.get("exists")).isEqualTo(true);
        assertThat(body.get("total")).isEqualTo(33L);
    }

    @Test
    void reportsEnLogCollectionMissingInsteadOfZeroRows() {
        when(primaryTemplate.collectionExists("En_Log")).thenReturn(false);

        Map<String, Object> body = service.getEnLogTotal();

        assertThat(body.get("exists")).isEqualTo(false);
        assertThat(body.get("total")).isEqualTo(0L);
        verify(primaryTemplate, never()).count(any(Query.class), eq("En_Log"));
    }

    @Test
    void showsLegacyLanguageForNewLogAndLogLoginRows() {
        // 旧版项目（NewLog / Log_Login）的日志没有版本标记，一律显示「旧版」。
        // Log_Login 那个遗留列的值（193 万条全是字符串 "None"）不能被当成版本透出去。
        Map<String, Object> newLog = ReflectionTestUtils.invokeMethod(service, "logRecord",
                new Document("UserName", "u"), "NewLog");
        Map<String, Object> logLogin = ReflectionTestUtils.invokeMethod(service, "logRecord",
                new Document("O_Language", "None"), "Log_Login");

        assertThat(newLog.get("O_language")).isEqualTo("旧版");
        assertThat(logLogin.get("O_language")).isEqualTo("旧版");
    }

    @Test
    void keepsEnLogLanguageAndNormalizesDirtyValue() {
        // En_Log 才有逐行的真实版本标记；大小写不同的字段名要能取到（同步是整文档搬运）。
        Map<String, Object> chinese = ReflectionTestUtils.invokeMethod(service, "logRecord",
                new Document("O_language", "中文"), "En_Log");
        Map<String, Object> english = ReflectionTestUtils.invokeMethod(service, "logRecord",
                new Document("O_Language", "英文"), "En_Log");
        Map<String, Object> dirty = ReflectionTestUtils.invokeMethod(service, "logRecord",
                new Document("O_language", "None"), "En_Log");
        Map<String, Object> missing = ReflectionTestUtils.invokeMethod(service, "logRecord",
                new Document("UserName", "u"), "En_Log");

        assertThat(chinese.get("O_language")).isEqualTo("中文");
        assertThat(english.get("O_language")).isEqualTo("英文");
        assertThat(dirty.get("O_language")).isNull();
        assertThat(missing.get("O_language")).isNull();
    }

    @Test
    void reportsAgentTotalWhenTargetCollectionExists() {
        when(primaryTemplate.collectionExists("GptReplyHistory")).thenReturn(true);
        when(primaryTemplate.count(any(Query.class), eq("GptReplyHistory"))).thenReturn(901L);

        Map<String, Object> body = service.getAgentSyncTotal();

        assertThat(body.get("exists")).isEqualTo(true);
        assertThat(body.get("total")).isEqualTo(901L);
    }

    @Test
    void reportsAgentCollectionMissingInsteadOfZeroRows() {
        // 集合是首次同步写入才建的。不存在时不能只回 total=0 ——
        // 前端要靠 exists 区分「还没同步过」和「同步过但一条都没有」，前者要显示「—」。
        when(primaryTemplate.collectionExists("GptReplyHistory")).thenReturn(false);

        Map<String, Object> body = service.getAgentSyncTotal();

        assertThat(body.get("exists")).isEqualTo(false);
        assertThat(body.get("total")).isEqualTo(0L);
        verify(primaryTemplate, never()).count(any(Query.class), eq("GptReplyHistory"));
    }

    @Test
    void mergesRegisterPagesBySubmitTimeDescending() {
        // 两表的 applicationTime 在库里都是 Date（不是字符串）。早先直接 toString() 比较，
        // 拿到的是 "Sun Sep 06 15:32:00 CST 2026" 这种英文串 —— 按星期缩写字典序排，
        // 9-06 会排在 9-07 前面，整个合并列表乱序。这里钉住「按真实时间倒序」。
        when(primaryTemplate.count(any(Query.class), eq("register"))).thenReturn(2L);
        when(primaryTemplate.find(any(Query.class), eq(Document.class), eq("register")))
                .thenReturn(Arrays.asList(
                        new Document("applicationTime", registerTime("2026-09-06 15:32:00")),
                        new Document("applicationTime", registerTime("2026-09-07 02:01:00"))));
        when(datahubSysTemplate.count(any(Query.class), eq("register_xiaochengxu"))).thenReturn(1L);
        when(datahubSysTemplate.find(any(Query.class), eq(Document.class), eq("register_xiaochengxu")))
                .thenReturn(Collections.singletonList(
                        new Document("applicationTime", registerTime("2026-09-11 09:51:43"))));

        Map<String, Object> body = service.getRegisterPage(1, 10, "", null);

        PageResponse page = (PageResponse) body.get("data");
        assertThat(page.getRecords()).extracting(row -> row.get("applicationTime"))
                .containsExactly(
                        registerTime("2026-09-11 09:51:43"),
                        registerTime("2026-09-07 02:01:00"),
                        registerTime("2026-09-06 15:32:00"));
        assertThat(page.getRecords()).extracting(row -> row.get("registerEntry"))
                .containsExactly("miniprogram", "web", "web");
        assertThat(page.getTotal()).isEqualTo(3L);
    }

    @Test
    void schoolLogMatchesInstitutionToleratingSurroundingSpaces() {
        // 日志里的 Institution 有一部分带一大串尾填充空格（SQL Server 定长列原样搬入，
        // 「学校账号」schooluser 那批是补齐到 100 字符）。等值匹配对这些行恒不命中，
        // 表现就是按学校查出来的结果里整片少了学校账号的日志。这里钉住：
        // 机构条件必须是容忍首尾空白的锚定正则，不能退回等值。
        stubEmptySchoolLogQuery();

        service.getSchoolLogPage("示例师范大学", 1, 10, "2025-01-01", "2025-03-01", true);

        List<Document> conditions = capturedSchoolLogConditions();
        Document institution = conditionByKey(conditions, "Institution");
        assertThat(institution).isNotNull();
        // 机构名走 Pattern.quote 转义：叫 zzu.edu.cn 的机构不能被当成通配
        assertThat(regexPattern(institution.get("Institution")))
                .isEqualTo("^\\s*\\Q示例师范大学\\E\\s*$");
    }

    @Test
    void schoolLogKeepsOnlyInstitutionDateAndAnonymousConditions() {
        // 现役口径就这三条：机构（容忍空格）+ 操作时间 + 排除匿名访问。
        // 不得再夹带账号类型、正式性、操作类型等筛选。
        stubEmptySchoolLogQuery();

        service.getSchoolLogPage("示例师范大学", 1, 10, "2025-01-01", "2025-03-01", true);

        List<Document> conditions = capturedSchoolLogConditions();
        assertThat(conditions).hasSize(3);
        assertThat(conditionByKey(conditions, "Institution")).isNotNull();
        assertThat(conditionByKey(conditions, "NickName")
                .get("NickName", Document.class).getString("$ne")).isEqualTo("anonymous");
        // 结束日期按「含当天」处理：下推到次日 0 点的 $lt
        Document range = conditionByKey(conditions, "O_date").get("O_date", Document.class);
        assertThat(range.getDate("$gte")).isEqualTo(
                Date.from(LocalDate.of(2025, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant()));
        assertThat(range.getDate("$lt")).isEqualTo(
                Date.from(LocalDate.of(2025, 3, 2).atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    @Test
    void schoolLogCountCoversNewLogAndEnLog() {
        // 学校日志的数据来源是 NewLog + 已同步的 En_Log（Log_Login 没有 Institution 列，
        // 按机构筛恒为空，不参与）。计数与导出共用同一套条件，这里钉住集合范围与合计。
        when(primaryTemplate.collectionExists("En_Log")).thenReturn(true);
        when(primaryTemplate.count(any(Query.class), eq("NewLog"))).thenReturn(3L);
        when(primaryTemplate.count(any(Query.class), eq("En_Log"))).thenReturn(4L);

        long total = service.countSchoolLogRows("示例师范大学", "2025-01-01", "2025-03-01", true);

        assertThat(total).isEqualTo(7L);
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(primaryTemplate, times(2)).count(captor.capture(), any(String.class));
        // 两张表必须用同一套条件，否则页面与导出的条数会对不上
        for (Query query : captor.getAllValues()) {
            Document institution = conditionByKey(
                    query.getQueryObject().getList("$and", Document.class), "Institution");
            assertThat(regexPattern(institution.get("Institution")))
                    .isEqualTo("^\\s*\\Q示例师范大学\\E\\s*$");
        }
    }

    @Test
    void accountPageInstitutionFilterToleratesPaddingSpaces() {
        // 账号数据页的机构筛选以前是等值匹配，和学校日志页同一个坑：
        // User_Data 也是从源库原样搬入的，机构名可能带尾部填充空格。
        when(primaryTemplate.count(any(Query.class), eq("User_Data"))).thenReturn(0L);
        when(primaryTemplate.find(any(Query.class), eq(Document.class), eq("User_Data")))
                .thenReturn(Collections.emptyList());

        service.getPage(1, 10, Collections.singletonMap("institution", "示例师范大学"));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(primaryTemplate).count(captor.capture(), eq("User_Data"));
        assertThat(institutionPatternOf(captor.getValue()))
                .isEqualTo("^\\s*\\Q示例师范大学\\E\\s*$");
    }

    @Test
    void logPageInstitutionFilterToleratesPaddingSpaces() {
        // 日志数据页的机构筛选同理：日志文档里的 Institution 有带尾填充空格的
        // （「学校账号」schooluser 那批）。这里钉住它不再是等值。
        when(primaryTemplate.aggregate(any(Aggregation.class), eq("NewLog"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(Collections.emptyList(), new Document()));
        when(primaryTemplate.find(any(Query.class), eq(Document.class), eq("NewLog")))
                .thenReturn(Collections.emptyList());

        service.getNewLogPage(1, 10, Collections.singletonMap("institution", "示例师范大学"));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(primaryTemplate).find(captor.capture(), eq(Document.class), eq("NewLog"));
        assertThat(institutionPatternOf(captor.getValue()))
                .isEqualTo("^\\s*\\Q示例师范大学\\E\\s*$");
    }

    // 学校日志分页在「没有辅助集合」时走单表路径，这几条用例都用它
    private void stubEmptySchoolLogQuery() {
        when(primaryTemplate.collectionExists("En_Log")).thenReturn(false);
        when(primaryTemplate.count(any(Query.class), eq("NewLog"))).thenReturn(0L);
        when(primaryTemplate.find(any(Query.class), eq(Document.class), eq("NewLog")))
                .thenReturn(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private List<Document> capturedSchoolLogConditions() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(primaryTemplate).count(captor.capture(), eq("NewLog"));
        List<Document> conditions = captor.getValue().getQueryObject().getList("$and", Document.class);
        assertThat(conditions).isNotNull();
        return conditions;
    }

    private Document conditionByKey(List<Document> conditions, String key) {
        for (Document condition : conditions) {
            if (condition.containsKey(key)) {
                return condition;
            }
        }
        return null;
    }

    // 从一条 Query 里取出机构条件用的正则 pattern
    private String institutionPatternOf(Query query) {
        return regexPattern(conditionByKey(
                query.getQueryObject().getList("$and", Document.class), "Institution")
                .get("Institution"));
    }

    // 机构条件在 criteria 里是正则对象。Spring Data 的包法随版本变
    // （BsonRegularExpression / java.util.regex.Pattern / {"$regex": ...}），
    // 这里统一取出 pattern 再断言，避免把测试绑死在某个版本的内部结构上。
    private String regexPattern(Object value) {
        if (value instanceof BsonRegularExpression) {
            return ((BsonRegularExpression) value).getPattern();
        }
        if (value instanceof Pattern) {
            return ((Pattern) value).pattern();
        }
        if (value instanceof Document) {
            return regexPattern(((Document) value).get("$regex"));
        }
        throw new AssertionError("机构条件不是可识别的正则：" + value);
    }

    // 造一个 Date 型的提交时间。时区无所谓：比较只用到毫秒时刻。
    private Date registerTime(String text) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text);
        } catch (ParseException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private Document rankingDoc(String userName, long count) {
        return new Document("_id", userName)
                .append("operationCount", count)
                .append("NickName", "张三")
                .append("Institution", "某大学")
                .append("Position", "教师")
                .append("IsDisabled", 0)
                .append("ruxueYear", "2024")
                .append("xuezhi", "4")
                .append("udMail", "teacher@example.edu.cn")
                .append("udUserType", 1)
                .append("udNickName", "张三")
                .append("udInstitution", "某大学");
    }
}
