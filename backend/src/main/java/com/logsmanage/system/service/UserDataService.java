package com.logsmanage.system.service;
import cn.hutool.core.date.DateUtil;
import com.logsmanage.system.dto.PageResponse;
import com.logsmanage.system.model.BanedList;
import com.logsmanage.system.model.UserDataUpdateTime;
import com.logsmanage.system.utils.IpCityResolver;
import com.logsmanage.system.utils.SensitiveFields;
import com.logsmanage.system.websocket.UserAndLogExportToMongo;
import org.apache.logging.log4j.util.Base64Util;
import org.bson.Document;
import org.bson.types.Binary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ArrayOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import org.springframework.data.util.CloseableIterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class UserDataService {
    @Autowired
    @Qualifier("datahubTemplate")
    private MongoTemplate datahubTemplate;
    // 139 的 DataHub_SYS 库，只用来读小程序申请表 register_xiaochengxu（审核记录跨库合并用）
    @Autowired
    @Qualifier("datahubSysTemplate")
    private MongoTemplate datahubSysTemplate;
    private static final String COLLECTION_NAME = "User_Data";
    private static final String XUEGUO_COLLECTION = "User_Data_xueguo";
    private static final String NEW_LOG_COLLECTION = "NewLog";
    private static final String REGISTER_COLLECTION = "register";
    // 小程序申请表，在 139 的 DataHub_SYS（走 datahubSysTemplate），字段与 register 高度一致
    private static final String REGISTER_XCX_COLLECTION = "register_xiaochengxu";
    // 注册入口派生列的两个值（API 层用英文，前端展示转中文「网页」/「小程序」）
    private static final String REGISTER_ENTRY_WEB = "web";
    private static final String REGISTER_ENTRY_XCX = "miniprogram";
    private static final String BANED_LIST_COLLECTION = "BanedList";
    // 创建时间列以 UserAuditRecord(139/DataHub) 联查的 CreateTime 为准（真实审核开通时间），
    // User_Data 原始 CreateDate 已弃用，getPage 查询侧 exclude 掉它、不让旧值进入返回。
    private static final String USER_AUDIT_COLLECTION = "UserAuditRecord";
    private static final String FIELD_AUDIT_CREATE_TIME = "CreateTime";
    private static final String FIELD_IS_UNBANNED = "IsUnbanned";
    private static final List<String> REGISTER_HIDDEN_FIELDS =
            Arrays.asList("_id", "id", "okstatus", "status", "random",
                    "emailstatus", "department", "isdoctor",
                    "bodaoname", "other", "college", "isdaikaitong", "daikaitongtime",
                    "activecount", "activeemailsend", "otheruseridentity", "pass");
    private static final Pattern SENSITIVE_FIELD_NAME =
            Pattern.compile("(password|passwd|pwd|secret|token)", Pattern.CASE_INSENSITIVE);
    private static final List<Object> UNBANNED_TRUE_VALUES =
            Arrays.asList(true, "true", "True", "TRUE", "1", 1, 1L);
    // User_Data_UpdateTime 里区分同步来源的 type 值，与 websocket 端 @ServerEndpoint 的 type 一致
    private static final String SYNC_TYPE_USER_DATA = "User_Data";
    // 登录日志集合与同步断点集合，都由 websocket 同步程序写入，这里只做只读诊断
    private static final String LOG_LOGIN_COLLECTION = "Log_Login";
    // 中英文版项目（datahub 后端 + DataHub_NEW_WEB 前端）的操作日志：源在 213/DataHub.En_Log，
    // 由同步程序搬到 181/DataHub_Log.En_Log。字段是 NewLog 与 Log_Login 的并集，
    // 读取侧把它当第三个数据源合并进来。集合首次同步写入才建，查询前要判存在。
    private static final String EN_LOG_COLLECTION = "En_Log";
    // 智能体同步的目标集合（源是 139/DataHub_SYS.gptReplyHistory，写入端在 websocket 里）。
    // 名字必须与 UserAndLogExportToMongo.AGENT_TARGET_COLLECTION 保持一致。
    private static final String AGENT_TARGET_COLLECTION = "GptReplyHistory";
    private static final String SYNC_STATE_COLLECTION = "_sync_state";
    // 只问「存不存在」时用它：命中第一条就停，别在 2258 万行上做全量 count
    private static final com.mongodb.client.model.CountOptions COUNT_ONE =
            new com.mongodb.client.model.CountOptions().limit(1);
    // 日志表带筛选时，总数最多数到该上限，避免对海量命中做全量 count
    private static final int NEW_LOG_COUNT_CAP = 10000;
    // 匿名访问的昵称，机构日志按业务口径要排除（不算该校真实使用）
    private static final String ANONYMOUS_NICKNAME = "anonymous";
    // ===== 正式 / 试用账号判定规则（账号类型筛选与登录排行共用一套） =====
    // 正式用户的 UserType 取值
    private static final int FORMAL_USER_TYPE = 1;
    // 公司内部测试账号
    private static final List<String> INTERNAL_TEST_USERS =
            Arrays.asList("schooluser", "anonymous", "zhengshi");
    // 试用特征：用户名以 -gj 结尾（库里 -gj / -GJ 两种写法都有，故忽略大小写）
    private static final Pattern TRIAL_GJ_SUFFIX =
            Pattern.compile("-gj\\s*$", Pattern.CASE_INSENSITIVE);
    // 试用特征：姓名含「试用」，如「负责人C-高级试用」
    private static final Pattern TRIAL_NAME = Pattern.compile("试用");
    // 试用特征：机构以「上海示例科技」开头（本公司，含「示例科技有限公司」）
    private static final Pattern OWN_COMPANY = Pattern.compile("^\\s*上海示例科技");
    // 账号类型筛选的取值
    private static final String ACCOUNT_TYPE_FORMAL = "formal";
    private static final String ACCOUNT_TYPE_TRIAL = "trial";
    // ===== 申请通道（派生字段，库里无对应列） =====
    // 用来区分正常申请开通的账号，和疑似脚本/其他途径批量刷取的账号
    private static final String CHANNEL_APPLY = "apply";
    private static final String CHANNEL_BULK = "bulk";
    // 教育邮箱特征，只要含 edu 就算（edu.cn / edu.pk / mails.tsinghua.edu.cn 都覆盖）
    private static final Pattern EDU_MAIL = Pattern.compile("edu", Pattern.CASE_INSENSITIVE);
    // 人工审核标记：业务方在前端发现不合规数据后，给这些账号手工加的 piliang 字段。
    // 命中即「必定批量」，优先级最高、盖过其余所有规则。该字段不是每条都有。
    // 库里取值类型不确定，按全兼容匹配（布尔 true / 字符串 "true" / "1" / 数字 1）。
    private static final String FIELD_PILIANG = "piliang";
    private static final List<Object> PILIANG_TRUE_VALUES =
            Arrays.asList(true, "true", "True", "TRUE", "1", 1, 1L);
    // piliang 明确为 false（人工审核确认「不是批量、是申请」）→ 必定「申请」，
    // 优先级仅次于 piliang=true。全兼容匹配布尔/字符串/数字。
    private static final List<Object> PILIANG_FALSE_VALUES =
            Arrays.asList(false, "false", "False", "FALSE", "0", 0, 0L);
    // 邮箱形态校验，与 RegisterFileService.normalizeMail 同一套（挡掉 "2522782769@qq. com" 这类）
    private static final Pattern LOOKS_LIKE_MAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
    // ===== 账号状态（派生字段，库里无对应列） =====
    private static final String ACCOUNT_STATUS_NORMAL = "normal";
    private static final String ACCOUNT_STATUS_EXPIRED = "expired";
    private static final String ACCOUNT_STATUS_DISABLED = "disabled";
    // 毕业+在职教师：不是「到期」，而是「升级」（从学生身份转教师）
    private static final String ACCOUNT_STATUS_UPGRADED = "upgraded";
    // 毕业时点按入学年+学制那年的 9 月 1 日
    private static final int GRADUATION_MONTH = 9;
    // 入学年份的合理区间，超出视为脏数据、不参与毕业判定
    private static final int MIN_RUXUE_YEAR = 1900;
    private static final int MAX_RUXUE_YEAR = 2200;
    // 取字符串前导数字，兼容 "3年" 这类写法
    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\d+");
    // 日志数据页统一返回旧 NewLog 的字段结构。Log_Login 多出来的
    // O_queryParameters/O_level/O_Language 不返回给前端；缺的 NickName/Institution/Mail 补 null。
    // 这样表格列名和账号详情抽屉里的「用户日志」都保持旧口径，不随数据来源变形。
    private static final List<String> LOG_RESULT_FIELDS = Arrays.asList(
            "_id", "ID", "UserName", "NickName", "Institution", "Mail",
            "O_srcIP", "O_city", "O_system", "O_database", "O_table",
            // 日志表字段数(数据同步的「日志表字段数」加工补的字典值)。之前漏在白名单外，
            // 文档里有但读取/导出(Map 化)时被丢掉，学校日志导出的 count 列因此恒空。
            "tableFieldCount",
            "O_Operation_type", "O_date", "O_SessionId",
            // 语言版本：只有中英文版项目（En_Log）的行有真实版本标记（中文 / 英文），
            // 其余日志统一显示「旧版」。取值规则收敛在 logRecord(document, collection)。
            "O_language");
    // 语言版本列的兜底值：NewLog / Log_Login 都来自旧版项目，没有版本标记，
    // 统一显示「旧版」（而不是留空、也不是拿 Log_Login 那个从没填过的遗留列去猜）。
    private static final String LEGACY_LANGUAGE = "旧版";
    // 排行可取的最大名次数
    private static final int RANKING_MAX_LIMIT = 500;
    // 「全部」档位的硬上限，兜底防止响应体过大（单条约 130 字节）
    private static final int RANKING_ALL_LIMIT = 200000;
    // 去重取值缓存的有效期
    private static final long DISTINCT_CACHE_TTL_MS = 10 * 60 * 1000L;
    // 排行结果缓存的有效期。
    // 排行聚合必须全量扫 NewLog 再按用户分组，「历史全部」档位实测约 50s
    // （一年内 19s、一个月内 1.7s —— 耗时随时间窗口线性下降）。
    // 而历史操作日志几乎不变，没必要每次请求都重算；用户点「刷新」可强制重取。
    private static final long RANKING_CACHE_TTL_MS = 30 * 60 * 1000L;
    private final MongoTemplate mongoTemplate;
    // 账号数据的两个文件链接来自 register 集合，按邮箱关联，逻辑单独放
    private final RegisterFileService registerFileService;
    // 学校日志的「学院」列来自 User_Data，按 UserName 关联，映射带缓存，逻辑单独放
    private final UserCollegeService userCollegeService;
    // 字段名 -> 去重取值，供账号状态筛选复用
    private final Map<String, CachedValues> distinctCache = new ConcurrentHashMap<>();
    // 筛选条件组合 -> 排行结果
    private final Map<String, CachedRanking> rankingCache = new ConcurrentHashMap<>();
    // 同一筛选组合的并发请求只算一遍，避免多人同时打页面时叠加多次全量扫描
    private final Map<String, Object> rankingLocks = new ConcurrentHashMap<>();
    public UserDataService(MongoTemplate mongoTemplate,
                           RegisterFileService registerFileService,
                           UserCollegeService userCollegeService) {
        this.mongoTemplate = mongoTemplate;
        this.registerFileService = registerFileService;
        this.userCollegeService = userCollegeService;
    }
    // 账号数据 User_Data 高级搜索分页
    public Map<String, Object> getPage(int pageNum, int pageSize, Map<String, String> filters) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        // 全部文本字段走精确匹配
        List<Criteria> criteriaList = buildAccountCriteria(filters);
        boolean hasFilter = !criteriaList.isEmpty();
        Query countQuery = new Query();
        applyCriteria(countQuery, criteriaList);
        long total = mongoTemplate.count(countQuery, COLLECTION_NAME);
        Query pageQuery = new Query();
        applyCriteria(pageQuery, criteriaList);
        pageQuery.fields().exclude("Password").exclude("password").exclude("CreateDate"); // 创建时间改联查 UserAuditRecord，原始值不取
        // 按 _id 倒序（新录入的在前）。原先按 CreateDate 倒序，但开通时间会随用户续约
        // 重置，排出来的其实是「最近续约」而非「最近开通」，没有参考意义，已改回 _id
        pageQuery.with(Sort.by(Sort.Direction.DESC, "_id"));
        pageQuery.skip((long) (safePageNum - 1) * safePageSize);
        pageQuery.limit(safePageSize);
        List<Map<String, Object>> records = accountRecords(
                mongoTemplate.find(pageQuery, Document.class, COLLECTION_NAME));
        // 账号状态是算出来的派生字段，库里没有对应列
        records.forEach(r -> r.put("AccountStatus", resolveAccountStatus(r)));
        // 账号类型：正式 / 试用，同样派生自判定规则
        records.forEach(r -> r.put("AccountType", resolveAccountType(r)));
        // 使用协议 / 注册资料两个链接来自 register，按邮箱关联（整页一次聚合）
        registerFileService.attachFiles(records);
        // 申请通道依赖上面补进来的两个文件字段，必须在 attachFiles 之后算
        records.forEach(r -> r.put("ApplyChannel", resolveApplyChannel(r)));
        // ============ 新增：从 User_Data_xueguo 获取 xueguo 并加密 ============
        if (!records.isEmpty()) {
            // 1. 收集所有 _id
            List<String> ids = records.stream()
                    .map(r -> (String) r.get("_id"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (!ids.isEmpty()) {
                // 2. 批量查询 xueguo 表
                Query xueguoQuery = new Query(Criteria.where("_id").in(ids));
                xueguoQuery.fields().include("_id").include("xueguo"); // 只取需要的字段
                List<Document> xueguoDocs = mongoTemplate.find(xueguoQuery, Document.class, XUEGUO_COLLECTION);

                // 3. 构建 _id -> xueguo 映射
                Map<String, String> xueguoMap = xueguoDocs.stream()
                        .collect(Collectors.toMap(
                                doc -> doc.getString("_id"),
                                doc -> doc.getString("xueguo"),
                                (v1, v2) -> v1  // 若重复取第一个
                        ));

                // 4. 为每条记录填充加密后的 xueguo
                records.forEach(r -> {
                    String id = (String) r.get("_id");
                    String rawXueguo = xueguoMap.get(id);
                    if (rawXueguo != null) {
                        // 调用 Base64Util 加密
                        String encrypted = Base64Util.encode(rawXueguo);
                        r.put("xueguo_Url", encrypted);  // 键名可自定义，此处用 "xueguoEncrypted"
                    } else {
                        r.put("xueguo_Url", null);       // 或空字符串 ""
                    }
                });
            }
        }
        // 账号创建时间：User_Data.CreateDate 弃用（查询侧已 exclude），改为联查
        // UserAuditRecord(139/DataHub) 的真实审核开通时间，命中填值、查不到置空（前端显示 -）
        Map<String, String> auditCreateTime = auditCreateTimeByUser(records);
        records.forEach(r -> {
            Object uname = r.get("UserName");
            r.put("CreateDate", uname == null ? null : auditCreateTime.get(uname.toString()));
        });
        return wrap(records, total, safePageNum, safePageSize, hasFilter ? "搜索成功" : "查询成功");
    }
    /** 本页 UserName 在 UserAuditRecord(139/DataHub) 里的真实创建时间。一次批量查，不逐行打公网。 */
    private Map<String, String> auditCreateTimeByUser(List<Map<String, Object>> records) {
        Map<String, String> byUser = new HashMap<>();
        List<String> names = records.stream()
                .map(r -> r.get("UserName"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .distinct()
                .collect(Collectors.toList());
        if (names.isEmpty()) {
            return byUser;
        }
        Query query = new Query(Criteria.where("UserName").in(names));
        query.fields().include("UserName").include(FIELD_AUDIT_CREATE_TIME);
        for (Document doc : datahubTemplate.find(query, Document.class, USER_AUDIT_COLLECTION)) {
            String name = doc.getString("UserName");
            String time = doc.getString(FIELD_AUDIT_CREATE_TIME);
            if (name != null && time != null) {
                byUser.put(name, time); // 同名多条 CreateTime 恒定，覆盖无歧义
            }
        }
        return byUser;
    }

    /**
     * 审核记录：合并两张申请表 —— 网页申请在 181 的 {@code register}，小程序申请在
     * 139 DataHub_SYS 的 {@code register_xiaochengxu}。派生「注册入口」列区分来源
     * （{@code web} / {@code miniprogram}，前端转中文）。
     *
     * <p>两表在不同库无法一次 join，沿用 {@code NewLog + Log_Login} 的合并模式：
     * 各取 {@code pageNum*pageSize} 条，按 {@code applicationTime} 倒序合并再切页
     * （两表该字段类型不统一，统一折算成毫秒再比，见 {@link #registerTimeMillis}）。register 有 7.9 万、
     * 小程序表极小，浏览通常只看前几页，深翻页的重复拉取可接受。
     *
     * @param registerEntry 注册入口筛选：{@code web} 只查网页、{@code miniprogram} 只查小程序、
     *                      空则两边都查
     */
    public Map<String, Object> getRegisterPage(int pageNum, int pageSize, String keyword, String registerEntry) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        List<Criteria> criteriaList = buildRegisterCriteria(keyword);
        String entry = registerEntry == null ? "" : registerEntry.trim();
        boolean wantWeb = entry.isEmpty() || REGISTER_ENTRY_WEB.equalsIgnoreCase(entry);
        boolean wantXcx = entry.isEmpty() || REGISTER_ENTRY_XCX.equalsIgnoreCase(entry);
        boolean hasFilter = !criteriaList.isEmpty() || !entry.isEmpty();

        long total = 0;
        int fetchSize = safePageNum * safePageSize;
        List<Map<String, Object>> merged = new ArrayList<>();
        if (wantWeb) {
            Query countQuery = new Query();
            applyCriteria(countQuery, criteriaList);
            total += mongoTemplate.count(countQuery, REGISTER_COLLECTION);
            for (Map<String, Object> row : registerRecords(mongoTemplate.find(
                    buildRegisterPageQuery(criteriaList, fetchSize), Document.class, REGISTER_COLLECTION))) {
                row.put("registerEntry", REGISTER_ENTRY_WEB);
                merged.add(row);
            }
        }
        if (wantXcx) {
            Query countQuery = new Query();
            applyCriteria(countQuery, criteriaList);
            total += datahubSysTemplate.count(countQuery, REGISTER_XCX_COLLECTION);
            for (Map<String, Object> row : registerRecords(datahubSysTemplate.find(
                    buildRegisterPageQuery(criteriaList, fetchSize), Document.class, REGISTER_XCX_COLLECTION))) {
                row.put("registerEntry", REGISTER_ENTRY_XCX);
                merged.add(row);
            }
        }
        // 按提交时间倒序合并（跨库唯一可比的时序字段，见 registerTimeMillis）
        merged.sort((a, b) -> Long.compare(registerTimeKey(b), registerTimeKey(a)));
        int from = Math.min((safePageNum - 1) * safePageSize, merged.size());
        int to = Math.min(from + safePageSize, merged.size());
        List<Map<String, Object>> records = new ArrayList<>(merged.subList(from, to));
        return wrap(records, total, safePageNum, safePageSize, hasFilter ? "搜索成功" : "查询成功");
    }

    // 审核记录分页查询：两库共用同一套筛选、排序、字段裁剪，各取 fetchSize 条
    private Query buildRegisterPageQuery(List<Criteria> criteriaList, int fetchSize) {
        Query pageQuery = new Query();
        applyCriteria(pageQuery, criteriaList);
        pageQuery.fields()
                .exclude("ActiveCount")
                .exclude("ActiveEmailSend")
                .exclude("OtherUserIdentity")
                .exclude("Pass");
        pageQuery.with(Sort.by(Sort.Direction.DESC, "applicationTime")
                .and(Sort.by(Sort.Direction.DESC, "_id")));
        pageQuery.limit(fetchSize);
        return pageQuery;
    }

    /**
     * 合并排序键：提交时间（毫秒时间戳），缺失或解析不出来的垫底。
     *
     * <p><b>不能直接比较 {@code applicationTime.toString()}（2026-09-11 修）：</b>
     * 两表的 {@code applicationTime} 在库里是 {@link java.util.Date}，不是字符串，
     * {@code toString()} 得到的是 {@code "Sun Sep 06 15:32:00 CST 2026"} 这种英文串，
     * 按字典序比等于先比星期缩写 —— 合并结果整表乱序（9-06 会排在 9-07 前面）。
     * 这里统一折算成毫秒再比，Date / 数字 / 字符串（ISO-8601 或 {@code yyyy-MM-dd HH:mm:ss}）
     * 都兼容，免得下次库里换类型又静默排错。
     */
    private long registerTimeKey(Map<String, Object> row) {
        return registerTimeMillis(row.get("applicationTime"));
    }

    private long registerTimeMillis(Object value) {
        if (value == null) {
            return Long.MIN_VALUE;
        }
        if (value instanceof Date) {
            return ((Date) value).getTime();
        }
        if (value instanceof Number) {
            long n = ((Number) value).longValue();
            // 10 位按秒、13 位按毫秒
            return n < 100000000000L ? n * 1000L : n;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return Long.MIN_VALUE;
        }
        if (text.matches("\\d{10,13}")) {
            long n = Long.parseLong(text);
            return n < 100000000000L ? n * 1000L : n;
        }
        try {
            // 带偏移或 Z 的 ISO-8601
            return java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 落到下面几种格式再试
        }
        try {
            return java.time.LocalDateTime.parse(text.replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 落到只认日期的格式
        }
        try {
            return LocalDate.parse(text.substring(0, Math.min(10, text.length())))
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }
    // 账号数据的筛选条件，getPage 与流式导出共用同一套，保证导出与页面口径一致
    private List<Criteria> buildAccountCriteria(Map<String, String> filters) {
        List<Criteria> criteriaList = new ArrayList<>();
        addExact(criteriaList, "UserName", filters.get("userName"));
        addExact(criteriaList, "NickName", filters.get("nickName"));
        addExact(criteriaList, "Mail", filters.get("mail"));
        addInstitution(criteriaList, filters.get("institution"));
        addExact(criteriaList, "College", filters.get("college"));
        addExact(criteriaList, "xuezhi", filters.get("xuezhi"));
        addExact(criteriaList, "ruxueYear", filters.get("ruxueYear"));
        addExact(criteriaList, "Position", filters.get("position"));
        addAccountType(criteriaList, filters.get("accountType"));
        addAccountStatus(criteriaList, filters.get("accountStatus"));
        addApplyChannel(criteriaList, filters.get("applyChannel"));
        // 兼容旧的统一关键字（同时匹配用户名/昵称）
        addKeyword(criteriaList, filters.get("keyword"), "UserName", "NickName");
        return criteriaList;
    }
    /**
     * 流式导出账号数据：Mongo 游标逐行读、逐行补派生字段与文件列，交给 sink 写出，
     * 不把整个结果集堆进内存。配合调用方的 SXSSF 流式写，可导到几十万行不 OOM。
     *
     * <p>与 {@link #getPage} 同源：筛选走 {@link #buildAccountCriteria}、排序同为
     * {@code _id DESC}、派生字段（状态/类型/申请通道）用同一批方法算，
     * 保证导出值与页面一致。区别只在文件列 —— 这里用预载的整表映射
     * （{@link RegisterFileService#latestFilesByMail}）逐行 O(1) 查，绕开 attachFiles
     * 的 200 邮箱上限，否则大批量导出材料列会被静默截断。
     *
     * @param limit 最多导出多少行（上限由调用方控制）
     * @param sink  每行回调（已含派生字段与文件列）
     * @return 实际导出的行数
     */
    public long streamAccountRows(Map<String, String> filters, int limit,
                                  java.util.function.Consumer<Map<String, Object>> sink) {
        List<Criteria> criteriaList = buildAccountCriteria(filters);
        Query query = new Query();
        applyCriteria(query, criteriaList);
        query.fields().exclude("Password").exclude("password");
        query.with(Sort.by(Sort.Direction.DESC, "_id"));
        query.limit(limit);
        // 游标分批拉取，避免驱动一次性把全部文档抓到本地
        query.cursorBatchSize(1000);
        // 文件列映射预载一次（约 6.6 万邮箱，缓存 30 分钟），逐行 O(1) 查
        Map<String, Document> filesByMail = registerFileService.latestFilesByMail();
        long count = 0;
        // MongoTemplate.stream 返回 CloseableIterator（服务端游标），try-with 确保关闭
        try (CloseableIterator<Document> it =
                     mongoTemplate.stream(query, Document.class, COLLECTION_NAME)) {
            while (it.hasNext()) {
                Map<String, Object> r = new LinkedHashMap<>(it.next());
                r.put("AccountStatus", resolveAccountStatus(r));
                r.put("AccountType", resolveAccountType(r));
                registerFileService.attachFilesFromMap(r, filesByMail);
                // 申请通道依赖上面补进来的文件字段，须在其后算
                r.put("ApplyChannel", resolveApplyChannel(r));
                sink.accept(r);
                count++;
            }
        }
        return count;
    }
    // ============ 申请通道（申请 / 批量） ============
    /**
     * 判定账号是正常申请开通，还是疑似脚本批量刷取。
     *
     * <p>判定优先级（自上而下，命中即定）：
     * <ol>
     *   <li>{@code piliang == true}（人工审核标记）→ 必定「批量」。</li>
     *   <li>{@code piliang == false}（人工审核标记）→ 必定「申请」。</li>
     *   <li>否则满足以下**任一**即「申请」，其余「批量」：
     *     有材料 / 非正式账号 / 邮箱含 edu 且身份为教师。</li>
     * </ol>
     *
     * <p><b>为什么「有材料」「非正式」是「任一」而不是「同时」：</b>二者在数据上互斥 ——
     * 试用账号多是机构号（如 {@code bupt.edu.cn-gj}），{@code Mail} 是「无」，
     * 申请材料靠邮箱关联，没邮箱就永远匹配不到材料。按「同时满足」算「申请」会是 0 条。
     *
     * <p><b>edu 收紧（2026-08-07）：</b>原来「邮箱含 edu 即申请」放得太宽，把非教师、
     * 无材料的教育邮箱号也算成申请。现改成 edu 只在「教师」时才顶申请（有材料的已由条件命中）。
     */
    private String resolveApplyChannel(Map<String, Object> record) {
        // 账号数据页：文件字段已由 attachFiles 补进记录，直接判空即可
        boolean hasMaterial = record.get(RegisterFileService.FIELD_AGREE) != null
                || record.get(RegisterFileService.FIELD_WORK) != null;
        return applyChannelOf(piliangState(record.get(FIELD_PILIANG)), hasMaterial,
                isFormalAccount(record), isEduMail(record.get("Mail")),
                isTeacher(record.get("Position")));
    }
    /**
     * 异常操作排行的申请通道判定，与账号数据页同一口径。
     *
     * <p>排行结果最多 20 万行，不能像分页那样走 {@code attachFiles}（会打爆
     * 单页邮箱上限、也没必要真取 URL），所以「是否有材料」改成查缓存的邮箱集合，
     * 判定规则本身仍复用 {@link #applyChannelOf}。
     *
     * <p>正式账号判定用 userDoc 投影出来的字段重建一遍，而不是直接写 {@code true}：
     * 排行的 postMatch 目前已保证每行都是正式账号，但若将来放宽了筛选，
     * 写死会让这一列悄悄失准。
     */
    private String rankingApplyChannel(Document document) {
        Object mail = document.get("udMail");
        Map<String, Object> forFormalCheck = new LinkedHashMap<>();
        forFormalCheck.put("UserType", document.get("udUserType"));
        // group 的 _id 就是 UserName
        forFormalCheck.put("UserName", document.get("_id"));
        forFormalCheck.put("NickName", document.get("udNickName"));
        forFormalCheck.put("Institution", document.get("udInstitution"));
        return applyChannelOf(
                piliangState(document.get("udPiliang")),
                registerFileService.hasMaterial(mail),
                isFormalAccount(forFormalCheck),
                isEduMail(mail),
                isTeacher(document.get("Position")));
    }
    /**
     * 申请通道的唯一判定入口。账号数据页和异常操作排行都走这里 —— 两边取
     * 「是否有材料」的方式不同（前者用整页 attachFiles 补进来的字段，后者用缓存的
     * 邮箱集合逐行查），但结论口径必须一致，所以把判定收敛到这一个方法。
     *
     * <p>优先级（自上而下命中即定）：
     * <ol>
     *   <li>piliang 明确为 true（人工审核）→ 必定「批量」。</li>
     *   <li>piliang 明确为 false（人工审核）→ 必定「申请」。</li>
     *   <li>piliang 缺失/无法判定：「有材料 / 非正式 / (edu 且教师)」任一 → 「申请」，
     *       其余「批量」。</li>
     * </ol>
     *
     * <p>{@code piliang} 是三态：{@link Boolean#TRUE}/{@link Boolean#FALSE}/{@code null}
     * （缺失或无法判定）。与 {@link #addApplyChannel} 的下推条件必须同构。
     */
    private String applyChannelOf(Boolean piliang, boolean hasMaterial,
                                  boolean formal, boolean eduMail, boolean teacher) {
        if (Boolean.TRUE.equals(piliang)) {
            return CHANNEL_BULK;
        }
        if (Boolean.FALSE.equals(piliang)) {
            return CHANNEL_APPLY;
        }
        return (hasMaterial || !formal || (eduMail && teacher)) ? CHANNEL_APPLY : CHANNEL_BULK;
    }
    /**
     * piliang 人工审核标记的三态解析：全兼容匹配（布尔/字符串/数字）。
     * @return {@link Boolean#TRUE} 明确批量 / {@link Boolean#FALSE} 明确申请 /
     *         {@code null} 字段缺失或无法判定（走后续规则）
     */
    private Boolean piliangState(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            int n = ((Number) value).intValue();
            if (n == 1) return Boolean.TRUE;
            if (n == 0) return Boolean.FALSE;
            return null;
        }
        String s = value.toString().trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return Boolean.FALSE;
        return null;
    }
    /**
     * 申请通道筛选下推到 Mongo，与 {@link #resolveApplyChannel} 保持同一套规则。
     *
     * <p>「有材料」是跨集合条件（材料在 register 里、按邮箱关联），没法直接写成
     * User_Data 上的条件。做法与账号状态筛选一致：把它枚举成实际取值再 {@code $in} ——
     * 先取 register 侧「有材料的邮箱」集合（约 6 万，带 30 分钟缓存），
     * 再与 User_Data 的 Mail 去重取值求交，得到本库里真正存在的拼写。
     *
     * <p><b>为什么要绕这一圈：</b>register 侧邮箱是小写化后的，而 User_Data.Mail
     * 有 2152 条含大写、65 条带首尾空格。直接拿小写集合做 {@code $in} 会静默漏掉这些行，
     * 筛选结果与列里显示的值不一致。用 {@code $expr + $toLower} 虽然对，但聚合的
     * {@code $in} 是逐条线性比对（6 万 × 12.9 万次），跑不动。
     */
    private void addApplyChannel(List<Criteria> list, String applyChannel) {
        if (applyChannel == null || applyChannel.trim().isEmpty()) {
            return;
        }
        String value = applyChannel.trim();
        boolean wantApply = CHANNEL_APPLY.equalsIgnoreCase(value);
        if (!wantApply && !CHANNEL_BULK.equalsIgnoreCase(value)) {
            return;
        }
        List<String> mailsWithMaterial = mailsWithMaterial();
        Criteria hasMaterial = Criteria.where("Mail").in(mailsWithMaterial);
        // $nin 同时命中 Mail 缺失/为 null 的记录，与「无邮箱即无材料」一致
        Criteria noMaterial = Criteria.where("Mail").nin(mailsWithMaterial);
        Criteria eduMail = Criteria.where("Mail").regex(EDU_MAIL);
        Criteria notEduMail = Criteria.where("Mail").not().regex(EDU_MAIL);
        // edu 收紧后「edu 顶申请」只在教师时成立
        Criteria eduAndTeacher = new Criteria().andOperator(
                eduMail, Criteria.where("Position").is("教师"));
        // 人工审核标记（三态）：true→批量，false→申请，缺失/其他→走后续规则
        Criteria piliangTrue = Criteria.where(FIELD_PILIANG).in(PILIANG_TRUE_VALUES);
        Criteria piliangFalse = Criteria.where(FIELD_PILIANG).in(PILIANG_FALSE_VALUES);
        // 既非明确 true 也非明确 false（含字段缺失，$nin 会命中缺失记录）
        List<Object> piliangExplicit = new ArrayList<>(PILIANG_TRUE_VALUES);
        piliangExplicit.addAll(PILIANG_FALSE_VALUES);
        Criteria piliangNeither = Criteria.where(FIELD_PILIANG).nin(piliangExplicit);
        if (wantApply) {
            // 申请 = piliang 明确 false，或（piliang 非明确 且（有材料 / 非正式 / edu且教师 任一））
            list.add(new Criteria().orOperator(
                    piliangFalse,
                    new Criteria().andOperator(
                            piliangNeither,
                            new Criteria().orOperator(
                                    hasMaterial, trialCriteria(), eduAndTeacher))));
        } else {
            // 批量 = piliang 明确 true，或（piliang 非明确 且 无材料 且 正式 且 非(edu且教师)）
            // ——对申请整体严格取反，三态分区 + 分区内规则互补，保证申请+批量=总数
            Criteria notEduTeacher = new Criteria().orOperator(
                    notEduMail, Criteria.where("Position").ne("教师"));
            list.add(new Criteria().orOperator(
                    piliangTrue,
                    new Criteria().andOperator(
                            piliangNeither, noMaterial, formalCriteria(), notEduTeacher)));
        }
    }
    /**
     * User_Data 里确实存在、且有申请材料的 Mail 取值（保留库里的原始拼写）。
     * 归一化口径与 RegisterFileService.normalizeMail 对齐：脏值一律视为无材料。
     */
    private List<String> mailsWithMaterial() {
        Set<String> materialMails = registerFileService.materialMails();
        List<String> out = new ArrayList<>();
        for (String mail : distinctStrings("Mail")) {
            if (mail == null) {
                continue;
            }
            String trimmed = mail.trim();
            if (trimmed.isEmpty() || "无".equals(trimmed)) {
                continue;
            }
            if (!LOOKS_LIKE_MAIL.matcher(trimmed).matches()) {
                continue;
            }
            if (materialMails.contains(trimmed.toLowerCase())) {
                out.add(mail);
            }
        }
        return out;
    }
    /**
     * 单条记录的正式账号判定，与 {@link #formalCriteria()} 保持同一套规则
     * （那边是下推到 Mongo 的 Criteria 版，这边是内存版，改一处必须同步改另一处）。
     *
     * <p>空值语义也对齐 Mongo：{@code UserName} 为 null 时不触发内部测试号/后缀规则，
     * {@code UserType} 缺失即不算正式。
     */
    private boolean isFormalAccount(Map<String, Object> record) {
        Object userType = record.get("UserType");
        if (!(userType instanceof Number)
                || ((Number) userType).intValue() != FORMAL_USER_TYPE) {
            return false;
        }
        String userName = asString(record.get("UserName"));
        if (userName != null) {
            if (INTERNAL_TEST_USERS.contains(userName)) {
                return false;
            }
            if (TRIAL_GJ_SUFFIX.matcher(userName).find()) {
                return false;
            }
        }
        String nickName = asString(record.get("NickName"));
        if (nickName != null && TRIAL_NAME.matcher(nickName).find()) {
            return false;
        }
        String institution = asString(record.get("Institution"));
        return institution == null || !OWN_COMPANY.matcher(institution).find();
    }
    private boolean isEduMail(Object mail) {
        String s = asString(mail);
        return s != null && EDU_MAIL.matcher(s).find();
    }
    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
    // ============ 账号状态（正常 / 到期 / 禁用） ============
    // 优先级：禁用 > 升级 > 到期 > 正常。
    // 升级：教师身份且已毕业（本该是到期，但已从学生转为教师身份）
    private String resolveAccountStatus(Map<String, Object> record) {
        if (isDisabled(record.get("IsDisabled"))) {
            return ACCOUNT_STATUS_DISABLED;
        }
        if (isGraduated(record.get("ruxueYear"), record.get("xuezhi"))) {
            // 已毕业 + 教师身份 → 升级，其余真到期
            return isTeacher(record.get("Position")) ? ACCOUNT_STATUS_UPGRADED : ACCOUNT_STATUS_EXPIRED;
        }
        return ACCOUNT_STATUS_NORMAL;
    }
    private boolean isTeacher(Object position) {
        if (!(position instanceof String)) {
            return false;
        }
        return "教师".equals(((String) position).trim());
    }
    // 账号类型：正式 / 试用，复用与高级搜索完全一致的正式账号判定规则
    private String resolveAccountType(Map<String, Object> record) {
        return isFormalAccount(record) ? ACCOUNT_TYPE_FORMAL : ACCOUNT_TYPE_TRIAL;
    }
    // IsDisabled 可能是数字 1 / 字符串 "1" / 布尔 true
    private boolean isDisabled(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() == 1;
        }
        return value != null && "1".equals(value.toString().trim());
    }
    // 毕业即到期：入学年 + 学制 = 毕业年，按当年 9/1 为毕业时点。
    // 两个字段任一算不出（null/空/"无"/学制超出合理范围）就不判到期，返回 false
    private boolean isGraduated(Object ruxueYear, Object xuezhi) {
        Integer year = parseLeadingInt(ruxueYear);
        Integer years = parseLeadingInt(xuezhi);
        if (year == null || years == null) {
            return false;
        }
        // 入学年份和学制的合理区间，超出视为脏数据（库里存在 xuezhi="99999"）
        if (year < 1900 || year > 2200 || years < 1 || years > 10) {
            return false;
        }
        return !LocalDate.now().isBefore(LocalDate.of(year + years, GRADUATION_MONTH, 1));
    }
    // 取前导数字，兼容 "3年" 这类写法；"无"/""/null 返回 null
    private Integer parseLeadingInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        Matcher matcher = LEADING_DIGITS.matcher(value.toString().trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    // 日志数据 NewLog 高级搜索分页，排除 O_SessionId
    /**
     * 日志数据分页查询。
     *
     * 同时查 NewLog、Log_Login 和已存在的 En_Log 三个集合：
     * NewLog 有 2021-01 到现在的查看/下载/预览 + 2022-10 之前的登录/登出；
     * Log_Login 有 2022-11 至今的登录/登出；En_Log 是中英文版项目同步来的独立操作日志。
     * 只查一个集合会丢数据。
     *
     * 合并走应用层：分别查各集合、合并列表、按排序字段重排、再取分页窗口。
     * $unionWith agg 的前提是集合文档形状一致，但 Log_Login 缺少
     * NickName/Institution/Mail，En_Log 的字段又可能随同步版本变化，所以走应用层。
     */
    public Map<String, Object> getNewLogPage(int pageNum, int pageSize, Map<String, String> filters) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        // 构建查询条件，两边集合共用同一套筛选
        List<Criteria> criteriaList = new ArrayList<>();
        addExact(criteriaList, "UserName", filters.get("userName"));
        addExact(criteriaList, "NickName", filters.get("nickName"));
        addInstitution(criteriaList, filters.get("institution"));
        addExact(criteriaList, "Mail", filters.get("mail"));
        addExact(criteriaList, "O_srcIP", filters.get("srcIP"));
        addExact(criteriaList, "O_city", filters.get("city"));
        addExact(criteriaList, "O_system", filters.get("system"));
        addExact(criteriaList, "O_database", filters.get("database"));
        addExact(criteriaList, "O_table", filters.get("table"));
        addExact(criteriaList, "O_Operation_type", filters.get("operationType"));
        boolean hasDate = hasValue(filters.get("dateStart")) || hasValue(filters.get("dateEnd"));
        addDateRange(criteriaList, "O_date", filters.get("dateStart"), filters.get("dateEnd"));
        boolean hasFilter = !criteriaList.isEmpty();
        // 有筛选时也要查所有存在的辅助集合 —— 日期/操作类型/城市/语言等条件可能命中 En_Log。
        // NickName/Institution/Mail 在 Log_Login 原始数据中没有，在 En_Log 会由同步时补齐，
        // 所以不能因为旧表缺字段就把辅助集合整体排除。
        List<String> auxiliary = auxiliaryLogCollections();
        boolean queryBoth = !auxiliary.isEmpty();
        long total;
        boolean totalCapped = false;
        // 只有按用户名精确筛选时才做精确 count：管理员查某个用户的全部登录记录，
        // 这类查询必须给出真实总数，不能只显示 20000+。
        // 其它大范围筛选（如只选「登录」）命中可能上百万，仍用封顶计数保护接口响应。
        boolean exactCount = hasValue(filters.get("userName"));
        if (hasFilter) {
            if (exactCount) {
                total = countOn(criteriaList, NEW_LOG_COLLECTION);
            } else {
                total = cappedCount(criteriaList);
                totalCapped = total >= NEW_LOG_COUNT_CAP;
            }
        } else {
            total = mongoTemplate.estimatedCount(NEW_LOG_COLLECTION);
        }
        // 合并查时总数为各表之和
        for (String collection : auxiliary) {
            if (hasFilter) {
                if (exactCount) {
                    total += countOn(criteriaList, collection);
                } else {
                    long more = cappedCountOn(criteriaList, collection);
                    total += more;
                    totalCapped = totalCapped || more >= NEW_LOG_COUNT_CAP;
                }
            } else {
                total += mongoTemplate.estimatedCount(collection);
            }
        }
        // 有辅助集合时始终走合并路径
        List<Map<String, Object>> records;
        if (queryBoth) {
            records = queryBothCollections(criteriaList, auxiliary, pageNum, pageSize, hasDate);
        } else {
            Query pageQuery = new Query();
            applyCriteria(pageQuery, criteriaList);
            pageQuery.fields().exclude("O_SessionId");
            pageQuery.with(Sort.by(Sort.Direction.DESC, hasDate ? "O_date" : "_id"));
            pageQuery.skip((long) (safePageNum - 1) * safePageSize);
            pageQuery.limit(safePageSize);
            records = toRecords(mongoTemplate.find(pageQuery, Document.class, NEW_LOG_COLLECTION),
                    NEW_LOG_COLLECTION);
        }
        Map<String, Object> result = wrap(records, total, safePageNum, safePageSize,
                hasFilter ? "搜索成功" : "查询成功");
        result.put("totalCapped", totalCapped);
        return result;
    }
    /**
     * 按机构查操作日志（实用工具 → 学校日志导出）。
     *
     * <p>不复用 {@link #getNewLogPage}，因为那边按机构筛时走的是
     * {@link #cappedCount}（封顶 {@link #NEW_LOG_COUNT_CAP} = 10000），
     * 只有按用户名筛才给精确总数。学校报表要真实条数、导出也要按它对账，
     * 显示「10000+」不行，所以这里单独做精确 count。
     *
     * <p>NewLog 与 En_Log 都支持按机构查询：En_Log 的 Institution 是同步时从 181 User_Data 补上的；
     * Log_Login 仍不参与，因为源表 dbo.Log_Login 没有 Institution 列，无法按机构筛选。
     * 因此本接口会包含 NewLog 的历史操作和已同步的中英文版日志，但不包含 Log_Login 的登录/登出。
     *
     * <p>口径对齐业务方手工跑的那份 SQL：机构 + 时间范围 +
     * {@code NickName != 'anonymous'}（匿名访问不算该校真实使用）。
     * 机构这一项不是等值，而是容忍首尾空白的匹配 —— 日志里的机构名有不少带尾填充空格，
     * 等值会把「学校账号」那批整片漏掉，详见 {@link #buildSchoolLogCriteria}。
     *
     * @param excludeAnonymous 是否排除匿名访问，导出与预览必须传同一个值，
     *                         否则页面显示 N 条、导出 M 条，看着像丢数据
     */
    public Map<String, Object> getSchoolLogPage(String institution, int pageNum, int pageSize,
                                                String dateStart, String dateEnd,
                                                boolean excludeAnonymous) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        List<Criteria> criteriaList = buildSchoolLogCriteria(institution, dateStart, dateEnd, excludeAnonymous);

        List<String> auxiliary = institutionLogCollections();

        long total = countOn(criteriaList, NEW_LOG_COLLECTION);
        for (String collection : auxiliary) {
            total += countOn(criteriaList, collection);
        }

        List<Map<String, Object>> records;
        if (auxiliary.isEmpty()) {
            // 没有别的来源时走单表 skip/limit，省掉一次合并
            Query pageQuery = new Query();
            applyCriteria(pageQuery, criteriaList);
            pageQuery.fields().exclude("O_SessionId");
            pageQuery.with(Sort.by(Sort.Direction.DESC, "O_date"));
            pageQuery.skip((long) (safePageNum - 1) * safePageSize);
            pageQuery.limit(safePageSize);
            records = toRecords(mongoTemplate.find(pageQuery, Document.class, NEW_LOG_COLLECTION),
                    NEW_LOG_COLLECTION);
        } else {
            // 跨集合分页不能靠 skip/limit（各集合 _id 不可比），必须先合并再切页
            records = queryBothCollections(criteriaList, auxiliary, safePageNum, safePageSize, false);
        }

        Map<String, Object> result = wrap(records, total, safePageNum, safePageSize, "搜索成功");
        // 精确 count，永不封顶；前端据此不显示「+」
        result.put("totalCapped", false);
        // 「学院」列：页面与导出同一套取值口径（学校共用账号留空）
        attachCollege(records);
        return result;
    }

    /**
     * 给学校日志的一页记录补「学院」列。
     *
     * <p>页面走这里，导出走 {@link #streamSchoolLogRows}，两边都收敛到
     * {@link UserCollegeService#fillCollege}，保证页面上看到的学院与 Excel 里的一致。
     * 整页只在有数据时预载一次映射（约 13 万条，30 分钟缓存），不逐行打库。
     */
    private void attachCollege(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<String, String> collegeByUserName = userCollegeService.collegeByUserName();
        for (Map<String, Object> record : records) {
            UserCollegeService.fillCollege(record, collegeByUserName);
        }
    }

    /**
     * 机构日志的筛选条件，分页、计数与导出共用一份，保证三处口径一致。
     *
     * <p><b>机构名不能用等值匹配（2026-09-15 修）。</b>日志文档里的 {@code Institution}
     * 有一部分是从 SQL Server 定长列原样搬进来的，**带一大串尾部填充空格**
     * （实测「学校账号」{@code schooluser} 那批是补齐到 100 字符，如
     * {@code '四川大学                    …'}）。等值匹配对这些行恒不命中，
     * 表现就是按学校查出来的结果里整片少了学校账号的日志。
     * 而 {@code User_Data}（账号表）走的是 {@code trimValue}，所以账号页看不出这个问题。
     *
     * <p>与 {@link RegisterFileService} 的邮箱匹配同一个坑、同一套解法：
     * 用 {@code ^\s*值\s*$} 锚定正则，容忍首尾空白。
     *
     * <p>匿名访问（{@code NickName = anonymous}）仍按业务口径排除：那不算该校的真实使用。
     */
    private List<Criteria> buildSchoolLogCriteria(String institution, String dateStart, String dateEnd,
                                                  boolean excludeAnonymous) {
        String safeInstitution = institution == null ? "" : institution.trim();
        if (safeInstitution.isEmpty()) {
            throw new IllegalArgumentException("机构名称不能为空");
        }
        List<Criteria> criteriaList = new ArrayList<>();
        addInstitution(criteriaList, safeInstitution);
        addDateRange(criteriaList, "O_date", dateStart, dateEnd);
        if (excludeAnonymous) {
            criteriaList.add(Criteria.where("NickName").ne(ANONYMOUS_NICKNAME));
        }
        return criteriaList;
    }

    /**
     * 机构名的匹配统一走这里，**不能退化成等值**。
     *
     * <p>库里有一批 {@code Institution} 是 SQL Server 定长列原样搬进来的，带一大串尾部填充空格
     * （「学校账号」{@code schooluser} 那批是补齐到 100 字符，如 {@code '四川大学        …'}）。
     * 等值匹配对它们恒不命中 —— 学校日志页 2026-09-15 就是这么丢了整片学校账号日志，
     * 日志数据页与账号数据页是同一个坑，一并按同一套解法改。
     *
     * <p>日志侧的 En_Log 机构值由同步时补写，本来就是去空格的，正则照样命中。
     */
    private void addInstitution(List<Criteria> list, String institution) {
        if (institution != null && !institution.trim().isEmpty()) {
            list.add(Criteria.where("Institution").regex(trimTolerantPattern(institution.trim())));
        }
    }

    /**
     * 容忍首尾空白的等值匹配正则：{@code ^\s*值\s*$}。
     *
     * <p>值走 {@link Pattern#quote} 转义 —— 机构名里有 {@code .}、{@code (} 之类是常态
     * （如 {@code zzu.edu.cn}），不转义会变成通配。
     */
    private static String trimTolerantPattern(String value) {
        return "^\\s*" + Pattern.quote(value) + "\\s*$";
    }

    /**
     * 按机构流式导出日志（实用工具 → 学校日志导出）。
     *
     * <p>与 {@link #getSchoolLogPage} 共用 {@link #buildSchoolLogCriteria}，口径一致：
     * 同一机构 + 日期 + 排 anonymous，页面显示 N 条、导出就导出 N 条。
     * NewLog 与已同步的 En_Log 都参与；Log_Login 因没有 Institution 列不参与。
     * 每行还会补上「学院」列（与页面同一套取值，学校共用账号留空）。
     * limit 只是防御性兜底；超过 50 万行的场景由导出侧
     * 滚动分 Sheet（同一 Excel 多张表），这里不要把行数截死在单表上限内。
     */
    public long streamSchoolLogRows(String institution, String dateStart, String dateEnd,
                                    boolean excludeAnonymous, int limit,
                                    Consumer<Map<String, Object>> sink) {
        List<Criteria> criteriaList = buildSchoolLogCriteria(institution, dateStart, dateEnd, excludeAnonymous);
        Query query = new Query(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        query.fields().exclude("O_SessionId");
        query.with(Sort.by(Sort.Direction.DESC, "O_date"));
        query.cursorBatchSize(1000);
        // 游标要带上来源集合名 —— 语言版本列按集合判定（En_Log 才有真实版本标记）
        List<LogCursor> cursors = new ArrayList<>();
        // 「学院」列按 UserName 查 User_Data：整批预载一次映射（约 13 万条，30 分钟缓存），
        // 逐行 O(1) 查。加载失败就让导出报错，不静默导出一列空学院。
        Map<String, String> collegeByUserName = userCollegeService.collegeByUserName();
        try {
            cursors.add(new LogCursor(NEW_LOG_COLLECTION,
                    mongoTemplate.stream(query, Document.class, NEW_LOG_COLLECTION)));
            for (String collection : institutionLogCollections()) {
                cursors.add(new LogCursor(collection,
                        mongoTemplate.stream(query, Document.class, collection)));
            }
            return mergeLogStreams(cursors, limit, record -> {
                UserCollegeService.fillCollege(record, collegeByUserName);
                sink.accept(record);
            });
        } finally {
            for (LogCursor cursor : cursors) {
                try {
                    cursor.iterator.close();
                } catch (Exception ignored) {
                    // 关闭游标失败不该盖住导出本身的结果
                }
            }
        }
    }

    /** 机构日志精确计数，供导出任务启动时算总数（与页面/导出同一套 criteria）。 */
    public long countSchoolLogRows(String institution, String dateStart, String dateEnd,
                                   boolean excludeAnonymous) {
        List<Criteria> criteriaList = buildSchoolLogCriteria(institution, dateStart, dateEnd, excludeAnonymous);
        long total = countOn(criteriaList, NEW_LOG_COLLECTION);
        for (String collection : institutionLogCollections()) {
            total += countOn(criteriaList, collection);
        }
        return total;
    }

    /** 集合实际存在才能查。estimatedCount 对不存在的集合也返回 0，分不出来。 */
    /**
     * 从 NewLog、Log_Login 和 En_Log 各取一页再合并取前 N。
     *
     * 各取 pageNum * pageSize 条，合并后排序，手动切第 N 页。
     * 翻页越深越慢，但浏览通常只看前几页，实际够用。
     *
     * **排序必须用 O_date，不能用 _id。** 三个集合的 _id 都是各自独立生成的，
     * 跨集合完全不可比：NewLog / Log_Login 是旧源的独立 ID，En_Log 是 ObjectId。
     */
    private List<Map<String, Object>> queryBothCollections(List<Criteria> criteria,
            List<String> auxiliary, int pageNum, int pageSize, boolean sortByDate) {
        int fetchSize = pageNum * pageSize;
        // 合并各日志集合时排序键恒为 O_date（跨集合唯一可比的时序字段），忽略 sortByDate。
        // 每个集合都应有 O_date 索引；_id 只在同一时间值时作为稳定性兜底。
        String sortField = "O_date";
        Query q = new Query();
        applyCriteria(q, criteria);
        q.fields().exclude("O_SessionId");
        q.with(Sort.by(Sort.Direction.DESC, sortField));
        q.limit(fetchSize);
        List<Map<String, Object>> fromNewLog =
                toRecords(mongoTemplate.find(q, Document.class, NEW_LOG_COLLECTION), NEW_LOG_COLLECTION);
        // Log_Login / En_Log 走同一套取数（底层 API + BSON filter），有几张查几张
        List<Map<String, Object>> merged = new ArrayList<>(fromNewLog);
        for (String collection : auxiliary) {
            merged.addAll(fetchLogPage(collection, criteria, fetchSize));
        }
        // 合并并按 O_date 倒序，再切窗口
        merged.sort((a, b) -> {
            int c = compareDesc(a.get(sortField), b.get(sortField));
            // O_date 相同（同秒的登录不少）时用 _id 兜底，保证跨页顺序稳定。
            // 注意三张表的 _id 都不可跨集合比较；这里只为消除同一 O_date 下的抖动，
            // 不影响按真实时间排序。
            if (c != 0) return c;
            return compareDesc(a.get("_id"), b.get("_id"));
        });
        int start = (pageNum - 1) * pageSize;
        if (start >= merged.size()) return new ArrayList<>();
        int end = Math.min(start + pageSize, merged.size());
        return merged.subList(start, end);
    }
    /** 倒序比较，null 永远排最后。 */
    @SuppressWarnings("unchecked")
    private int compareDesc(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return ((Comparable<Object>) b).compareTo(a);
    }
    /** 把前端的 where 条件转成 MongoDB 的 BSON filter（给 MongoCollection.find 用）。 */
    private Document bsonToFilter(List<Criteria> criteriaList) {
        if (criteriaList.isEmpty()) return new Document();
        List<Document> filters = new ArrayList<>();
        for (Criteria c : criteriaList) {
            Document obj = c.getCriteriaObject();
            if (obj != null && !obj.isEmpty()) filters.add(obj);
        }
        if (filters.isEmpty()) return new Document();
        return new Document("$and", filters);
    }

    /**
     * 除 NewLog 之外还要一起查的日志集合（集合存在才列进来）。
     *
     * <p>Log_Login：2022-11 之后的登录/登出；
     * En_Log：中英文版项目（datahub + DataHub_NEW_WEB）同步过来的全部操作日志。
     * 两个集合都是「首次同步写入才创建」，没同步过就不存在，此时不参与合并。
     */
    private List<String> auxiliaryLogCollections() {
        List<String> collections = new ArrayList<>(2);
        if (mongoTemplate.collectionExists(LOG_LOGIN_COLLECTION)) {
            collections.add(LOG_LOGIN_COLLECTION);
        }
        if (mongoTemplate.collectionExists(EN_LOG_COLLECTION)) {
            collections.add(EN_LOG_COLLECTION);
        }
        return collections;
    }

    /**
     * 带 Institution 列、可以按机构筛日志的辅助集合（存在才列）。
     *
     * <p>只有 En_Log 符合条件：Log_Login 的源表 dbo.Log_Login 压根没有 Institution 列，
     * 同步搬不出来，按机构筛它恒为空 —— 这也是学校日志历来「只查 NewLog」的原因。
     * En_Log 的 Institution 是同步时用 181 现成的 User_Data 就地补上的。
     */
    private List<String> institutionLogCollections() {
        List<String> collections = new ArrayList<>(1);
        if (mongoTemplate.collectionExists(EN_LOG_COLLECTION)) {
            collections.add(EN_LOG_COLLECTION);
        }
        return collections;
    }

    /**
     * 用底层 API 从单个日志集合取前 limit 条（O_date 倒序、排除 O_SessionId），
     * 转成统一的返回结构。多个辅助日志集合共用，避免每处各写一遍同样的取数。
     */
    private List<Map<String, Object>> fetchLogPage(String collection,
            List<Criteria> criteria, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Document doc : mongoTemplate.getCollection(collection)
                .find(bsonToFilter(criteria))
                .projection(new Document("O_SessionId", 0))
                .sort(new Document("O_date", -1))
                .limit(limit)) {
            rows.add(logRecord(doc, collection));
        }
        return rows;
    }

    // 封顶计数：$match 后 $limit 到上限再 $count，命中过多时不必数全量
    private long cappedCount(List<Criteria> criteriaList) {
        return cappedCountOn(criteriaList, NEW_LOG_COLLECTION);
    }
    private long cappedCountOn(List<Criteria> criteriaList, String collection) {
        Criteria criteria = new Criteria().andOperator(criteriaList.toArray(new Criteria[0]));
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(criteria),
                Aggregation.limit(NEW_LOG_COUNT_CAP),
                Aggregation.count().as("n"));
        AggregationResults<Document> res =
                mongoTemplate.aggregate(agg, collection, Document.class);
        Document doc = res.getUniqueMappedResult();
        return doc == null ? 0L : ((Number) doc.get("n")).longValue();
    }
    /** 精确计数，仅用于有 UserName 精确筛选这种可控范围。 */
    private long countOn(List<Criteria> criteriaList, String collection) {
        Query query = new Query();
        applyCriteria(query, criteriaList);
        return mongoTemplate.count(query, collection);
    }
    // 按用户名查该用户的日志，隐藏邮箱/用户名/来源IP/SessionId
    // operationType 非空时只统计该操作类型（如只看「登录」）
    //
    // 同样要查三个集合：NewLog + Log_Login + 已存在的 En_Log。
    public Map<String, Object> getNewLogByUser(
            String userName, int pageNum, int pageSize, String operationType) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        String safeUser = userName == null ? "" : userName.trim();
        Criteria criteria = Criteria.where("UserName").is(safeUser);
        if (operationType != null && !operationType.trim().isEmpty()) {
            criteria = criteria.and("O_Operation_type").is(operationType.trim());
        }
        List<String> auxiliary = auxiliaryLogCollections();
        long total = mongoTemplate.count(new Query(criteria), NEW_LOG_COLLECTION);
        for (String collection : auxiliary) {
            total += mongoTemplate.count(new Query(criteria), collection);
        }
        // 各取一页再按时间合并。不能按 _id：各集合 _id 独立生成，跨集合不可比。
        int fetchSize = safePageNum * safePageSize;
        Query pageQuery = new Query(criteria);
        pageQuery.fields().exclude("O_SessionId");
        pageQuery.with(Sort.by(Sort.Direction.DESC, "O_date"));
        pageQuery.limit(fetchSize);
        List<Map<String, Object>> records = new ArrayList<>(
                toRecords(mongoTemplate.find(pageQuery, Document.class, NEW_LOG_COLLECTION),
                        NEW_LOG_COLLECTION));
        for (String collection : auxiliary) {
            records.addAll(fetchLogPage(collection, Collections.singletonList(criteria), fetchSize));
        }
        records.sort((a, b) -> {
            int c = compareDesc(a.get("O_date"), b.get("O_date"));
            if (c != 0) return c;
            return compareDesc(a.get("_id"), b.get("_id"));
        });
        int start = (safePageNum - 1) * safePageSize;
        if (start >= records.size()) records.clear();
        else records = records.subList(start, Math.min(start + safePageSize, records.size()));
        return wrap(new ArrayList<>(records), total, safePageNum, safePageSize, "查询成功");
    }
    /**
     * 按用户名导出全部操作日志。对 NewLog、Log_Login、En_Log 分别按时间打开游标，做 k 路归并，
     * 不把用户全部日志一次性读进内存；登录/登出 2022 年后的记录在 Log_Login，
     * 中英文版项目记录在 En_Log，必须一起导出。
     */
    public long streamUserLogRows(String userName, int limit, Consumer<Map<String, Object>> sink) {
        String safeUser = userName == null ? "" : userName.trim();
        if (safeUser.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        Criteria criteria = Criteria.where("UserName").is(safeUser);
        Query query = new Query(criteria);
        query.fields().exclude("O_SessionId");
        query.with(Sort.by(Sort.Direction.DESC, "O_date"));
        query.cursorBatchSize(1000);
        List<LogCursor> cursors = new ArrayList<>();
        try {
            cursors.add(new LogCursor(NEW_LOG_COLLECTION,
                    mongoTemplate.stream(query, Document.class, NEW_LOG_COLLECTION)));
            for (String collection : auxiliaryLogCollections()) {
                cursors.add(new LogCursor(collection,
                        mongoTemplate.stream(query, Document.class, collection)));
            }
            return mergeLogStreams(cursors, limit, sink);
        } finally {
            for (LogCursor cursor : cursors) {
                try {
                    cursor.iterator.close();
                } catch (Exception ignored) {
                    // 关闭游标失败不该盖住导出本身的结果
                }
            }
        }
    }

    /**
     * 一个日志集合的游标：集合名和游标绑在一起传。
     *
     * <p>归并本身只需要游标，但每行都要按来源集合判定语言版本（只有 En_Log 的行有真实
     * 版本标记，其余是「旧版」），而 {@link Document} 里分不出来源集合，所以游标必须
     * 自带集合名。两个平铺的 List 容易错位，故包一层。
     */
    private static final class LogCursor {
        final String collection;
        final CloseableIterator<Document> iterator;

        LogCursor(String collection, CloseableIterator<Document> iterator) {
            this.collection = collection;
            this.iterator = iterator;
        }
    }

    /**
     * 多游标按 O_date 倒序归并，逐行交给 sink，最多 limit 行。
     *
     * <p>每个游标维护一个队首，每轮取 O_date 最新的那个。k 路归并的 k 最多 3
     * （NewLog / Log_Login / En_Log），线性挑最小值足够，不值得上堆。
     */
    private long mergeLogStreams(List<LogCursor> cursors, int limit,
                                 Consumer<Map<String, Object>> sink) {
        // 各集合 _id 独立生成、跨集合不可比，先后只能由 O_date 决定（见 compareLogDocs）
        List<Document> heads = new ArrayList<>(cursors.size());
        for (LogCursor cursor : cursors) {
            heads.add(nextOrNull(cursor.iterator));
        }
        long count = 0;
        while (count < limit) {
            int picked = -1;
            for (int i = 0; i < heads.size(); i++) {
                if (heads.get(i) == null) {
                    continue;
                }
                if (picked < 0 || compareLogDocs(heads.get(i), heads.get(picked)) < 0) {
                    picked = i;
                }
            }
            if (picked < 0) {
                break;
            }
            sink.accept(logRecord(heads.get(picked), cursors.get(picked).collection));
            heads.set(picked, nextOrNull(cursors.get(picked).iterator));
            count++;
        }
        return count;
    }
    private Document nextOrNull(CloseableIterator<Document> it) {
        return it != null && it.hasNext() ? it.next() : null;
    }
    private int compareLogDocs(Document a, Document b) {
        int c = compareDesc(a.get("O_date"), b.get("O_date"));
        if (c != 0) return c;
        return compareDesc(a.get("_id"), b.get("_id"));
    }
    /** Log_Login 集合条数估算，给同步面板「现有」栏用。 */
    public long getLogLoginTotal() {
        if (mongoTemplate.collectionExists(LOG_LOGIN_COLLECTION)) {
            return mongoTemplate.estimatedCount(LOG_LOGIN_COLLECTION);
        }
        return 0L;
    }

    /**
     * 智能体目标集合 GptReplyHistory（181）的现有条数，同步页 Agent 卡片的「现有」用它。
     * 和 Log_Login 一样，集合是首次同步写入才建的 —— 光看条数分不出「空集合」和「压根没同步过」，
     * 所以把 exists 一并回传，前端据此决定显示 0 还是 —。
     * 这里走精确 count 而不是 estimatedCount：集合只有千条量级，代价可忽略，
     * 而这个数字是要给人拿去和别的记录对账的，不接受元数据估算的偏差。
     */
    public Map<String, Object> getAgentSyncTotal() {
        Map<String, Object> body = new HashMap<>();
        boolean exists = mongoTemplate.collectionExists(AGENT_TARGET_COLLECTION);
        body.put("exists", exists);
        body.put("total", exists ? mongoTemplate.count(new Query(), AGENT_TARGET_COLLECTION) : 0L);
        return body;
    }

    /**
     * 中英文版日志目标集合 En_Log（181）的现有条数，同步页卡片的「现有」用它。
     * 集合是首次同步写入才建的，光看条数分不出「空集合」和「压根没同步过」，
     * 所以把 exists 一并回传，前端据此决定显示 0 还是 —。
     */
    public Map<String, Object> getEnLogTotal() {
        Map<String, Object> body = new HashMap<>();
        boolean exists = mongoTemplate.collectionExists(EN_LOG_COLLECTION);
        body.put("exists", exists);
        body.put("total", exists ? mongoTemplate.count(new Query(), EN_LOG_COLLECTION) : 0L);
        return body;
    }
    // 异常情况：User_Data 中 IsDisabled=1，且 BanedList 未标记解禁的账号。
    public Map<String, Object> getDisabledUsers(int pageNum, int pageSize) {
        return getDisabledUsers(pageNum, pageSize, null);
    }

    public Map<String, Object> getDisabledUsers(int pageNum, int pageSize, String userName) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        // IsDisabled 可能存成数字 1 / 字符串 "1" / 布尔 true，一并兼容
        Criteria criteria = Criteria.where("IsDisabled").in(1, 1L, "1", true);
        String exactUserName = userName == null ? "" : userName.trim();
        List<String> unbannedUsers = loadUnbannedUsers();
        if (!exactUserName.isEmpty() && !unbannedUsers.isEmpty()) {
            criteria = new Criteria().andOperator(
                    Criteria.where("IsDisabled").in(1, 1L, "1", true),
                    Criteria.where("UserName").is(exactUserName),
                    Criteria.where("UserName").nin(unbannedUsers));
        } else if (!exactUserName.isEmpty()) {
            criteria.and("UserName").is(exactUserName);
        } else if (!unbannedUsers.isEmpty()) {
            criteria.and("UserName").nin(unbannedUsers);
        }
        long total = mongoTemplate.count(new Query(criteria), COLLECTION_NAME);
        Query pageQuery = new Query(criteria);
        pageQuery.fields().include("UserName").include("NickName").include("Institution");
        pageQuery.with(Sort.by(Sort.Direction.DESC, "_id"));
        pageQuery.skip((long) (safePageNum - 1) * safePageSize);
        pageQuery.limit(safePageSize);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Document document : mongoTemplate.find(pageQuery, Document.class, COLLECTION_NAME)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("UserName", trimValue(document.get("UserName")));
            row.put("NickName", trimValue(document.get("NickName")));
            row.put("Institution", trimValue(document.get("Institution")));
            BanedList banedList = datahubTemplate.findOne(new Query(Criteria.where("UserName").is(trimValue(document.get("UserName")))), BanedList.class);
            if (banedList != null) {
                row.put("DisabledTime", DateUtil.format(banedList.getBanTime(), "yyyy-MM-dd"));
                row.put("DisabledReason", banedList.getBanReason() == null ? "倒卖数据" : banedList.getBanReason());
            } else {
                row.put("DisabledTime", null);
                row.put("DisabledReason", "倒卖数据");
            }
            records.add(row);
        }
        return wrap(records, total, safePageNum, safePageSize, "查询成功");
    }
    private List<String> loadUnbannedUsers() {
        Query query = new Query(Criteria.where(FIELD_IS_UNBANNED).in(UNBANNED_TRUE_VALUES));
        query.fields().include("UserName");
        List<String> users = new ArrayList<>();
        for (Document document : datahubTemplate.find(
                query, Document.class, BANED_LIST_COLLECTION)) {
            Object value = trimValue(document.get("UserName"));
            if (value != null) {
                users.add(String.valueOf(value));
            }
        }
        return users;
    }
    /**
     * 异常操作排行：NewLog 中按 UserName 分组统计操作次数，倒序取前 N 名。
     *
     * <p>统计的是全部操作类型（下载/查看/登出/登录/预览），
     * 不再只统计「登录」—— 登录操作 2022 年后已无数据。
     *
     * <p>只统计 User_Data 中 UserType=1 的正式用户，并排除内部测试账号。
     *
     * <p>结果带 TTL 缓存（见 {@link #RANKING_CACHE_TTL_MS}）：聚合开销大且历史数据稳定，
     * 命中缓存时响应里 {@code cached=true}，{@code updatedAt} 仍是真正跑聚合的时刻。
     *
     * @param limit    取前 N 名，null 或 &lt;=0 表示不限（全部）
     * @param withinDays 只统计最近 N 天的操作，null 表示历史全部
     * @param position 身份筛选，空表示不限
     * @param accountStatus 账号状态筛选（normal/expired/disabled），空表示不限
     * @param forceRefresh 跳过缓存强制重算（前端「刷新」按钮）
     */
    public Map<String, Object> getOperationRanking(
            Integer limit, Integer withinDays, String position, String accountStatus,
            boolean forceRefresh) {
        return getOperationRanking(limit, withinDays, position, accountStatus, null, forceRefresh);
    }
    public Map<String, Object> getOperationRanking(
            Integer limit, Integer withinDays, String position, String accountStatus,
            String applyChannel, boolean forceRefresh) {
        String key = rankingCacheKey(limit, withinDays, position, accountStatus, applyChannel);
        if (forceRefresh) {
            rankingCache.remove(key);
        } else {
            CachedRanking hit = rankingCache.get(key);
            if (hit != null && !hit.isExpired(System.currentTimeMillis())) {
                return hit.payload(true);
            }
        }
        // 同一 key 只让一个线程跑聚合，其余等它算完直接吃结果
        Object lock = rankingLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // 双检：等锁期间可能已经被别的线程算好了
            CachedRanking again = rankingCache.get(key);
            if (again != null && !again.isExpired(System.currentTimeMillis())) {
                return again.payload(true);
            }
            Map<String, Object> computed =
                    computeOperationRanking(limit, withinDays, position, accountStatus, applyChannel);
            rankingCache.put(key, new CachedRanking(computed, System.currentTimeMillis()));
            return copyWithCacheFlag(computed, false);
        }
    }
    // 缓存键：所有影响聚合结果的入参都要参与，否则会串味
    private String rankingCacheKey(
            Integer limit, Integer withinDays, String position, String accountStatus,
            String applyChannel) {
        boolean unlimited = limit == null || limit <= 0;
        int effectiveLimit = unlimited ? 0 : Math.min(limit, RANKING_MAX_LIMIT);
        return effectiveLimit
                + "|" + (withinDays == null ? "" : withinDays)
                + "|" + (position == null ? "" : position.trim())
                + "|" + (accountStatus == null ? "" : accountStatus.trim())
                + "|" + (applyChannel == null ? "" : applyChannel.trim());
    }
    // 在原结果上叠一个 cached 标记返回，不改动缓存里的那份
    private Map<String, Object> copyWithCacheFlag(Map<String, Object> source, boolean cached) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put("cached", cached);
        return copy;
    }
    private Map<String, Object> computeOperationRanking(
            Integer limit, Integer withinDays, String position, String accountStatus,
            String applyChannel) {
        // limit 为空或非正表示「全部」，仍设一个硬上限兜底防止响应过大
        boolean unlimited = limit == null || limit <= 0;
        int safeLimit = unlimited ? RANKING_ALL_LIMIT
                : Math.min(limit, RANKING_MAX_LIMIT);
        Map<String, Document> merged = new LinkedHashMap<>();
        mergeRankingSource(merged, NEW_LOG_COLLECTION, withinDays, position, accountStatus);
        // 各辅助日志集合（Log_Login / En_Log）按同一聚合口径并入，存在才查
        for (String collection : auxiliaryLogCollections()) {
            mergeRankingSource(merged, collection, withinDays, position, accountStatus);
        }
        List<Map<String, Object>> records = finalizeRankingRecords(merged, applyChannel, safeLimit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "统计成功");
        result.put("limit", unlimited ? null : safeLimit);
        result.put("unlimited", unlimited);
        result.put("withinDays", withinDays);
        result.put("updatedAt", new Date());
        result.put("records", records);
        return result;
    }
    private void mergeRankingSource(Map<String, Document> merged, String collection,
                                    Integer withinDays, String position, String accountStatus) {
        AggregationResults<Document> res = mongoTemplate.aggregate(
                rankingSourceAggregation(withinDays, position, accountStatus),
                collection, Document.class);
        for (Document document : res.getMappedResults()) {
            mergeRankingDocument(merged, document);
        }
    }
    private Aggregation rankingSourceAggregation(
            Integer withinDays, String position, String accountStatus) {
        List<Criteria> preMatch = new ArrayList<>();
        preMatch.add(Criteria.where("UserName").ne(null));
        preMatch.add(Criteria.where("UserName").ne(""));
        preMatch.add(Criteria.where("UserName").nin(INTERNAL_TEST_USERS));
        preMatch.add(Criteria.where("UserName").not().regex(TRIAL_GJ_SUFFIX));
        // 时间筛选放在分组前，能大幅减少进入 $group 的文档量；两张日志表必须同口径。
        if (withinDays != null && withinDays > 0) {
            Date since = Date.from(LocalDate.now()
                    .minusDays(withinDays)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant());
            preMatch.add(Criteria.where("O_date").gte(since));
        }
        List<AggregationOperation> stages = new ArrayList<>();
        // 先收窄，并在分组前剔掉能靠用户名判定的账号。
        stages.add(Aggregation.match(new Criteria().andOperator(
                preMatch.toArray(new Criteria[0]))));
        stages.add(Aggregation.group("UserName")
                .count().as("operationCount")
                .first("NickName").as("NickName")
                .first("Institution").as("Institution"));
        // 分组后行数已收敛到去重用户数，此时回查 User_Data 取当前权威账号信息。
        // 外键必须用主键 _id（User_Data 里 _id 恒等于 UserName），避免无索引 lookup。
        stages.add(Aggregation.lookup(COLLECTION_NAME, "_id", "_id", "userDoc"));
        List<Criteria> postMatch = new ArrayList<>();
        postMatch.add(Criteria.where("userDoc.UserType").is(FORMAL_USER_TYPE));
        postMatch.add(Criteria.where("userDoc.NickName").not().regex(TRIAL_NAME));
        postMatch.add(Criteria.where("userDoc.Institution").not().regex(OWN_COMPANY));
        if (position != null && !position.trim().isEmpty()) {
            postMatch.add(Criteria.where("userDoc.Position").is(position.trim()));
        }
        addAccountStatus(postMatch, accountStatus, "userDoc.");
        stages.add(Aggregation.match(new Criteria().andOperator(
                postMatch.toArray(new Criteria[0]))));
        // 这里不 sort/limit：NewLog 与 Log_Login 的次数要先合并，最终总次数才是排行依据。
        stages.add(Aggregation.project("operationCount", "NickName", "Institution")
                .and(ArrayOperators.ArrayElemAt.arrayOf("userDoc.Position").elementAt(0))
                .as("Position")
                .and(ArrayOperators.ArrayElemAt.arrayOf("userDoc.IsDisabled").elementAt(0))
                .as("IsDisabled")
                .and(ArrayOperators.ArrayElemAt.arrayOf("userDoc.ruxueYear").elementAt(0))
                .as("ruxueYear")
                .and(ArrayOperators.ArrayElemAt.arrayOf("userDoc.xuezhi").elementAt(0))
                .as("xuezhi")
                .and(ArrayOperators.ArrayElemAt.arrayOf("userDoc.Mail").elementAt(0))
                .as("udMail")
                .and(ArrayOperators.ArrayElemAt.arrayOf("userDoc.UserType").elementAt(0))
                .as("udUserType")
                .and(ArrayOperators.ArrayElemAt.arrayOf("userDoc.NickName").elementAt(0))
                .as("udNickName")
                .and(ArrayOperators.ArrayElemAt.arrayOf("userDoc.Institution").elementAt(0))
                .as("udInstitution")
                .and(ArrayOperators.ArrayElemAt.arrayOf("userDoc.piliang").elementAt(0))
                .as("udPiliang"));
        return Aggregation.newAggregation(stages)
                .withOptions(Aggregation.newAggregationOptions().allowDiskUse(true).build());
    }
    private void mergeRankingDocument(Map<String, Document> merged, Document source) {
        Object id = source.get("_id");
        if (id == null) return;
        String userName = String.valueOf(id);
        Document target = merged.get(userName);
        if (target == null) {
            target = new Document(source);
            target.put("operationCount", toLong(source.get("operationCount")));
            merged.put(userName, target);
            return;
        }
        target.put("operationCount",
                toLong(target.get("operationCount")) + toLong(source.get("operationCount")));
        copyIfAbsent(target, source, "NickName");
        copyIfAbsent(target, source, "Institution");
        copyIfAbsent(target, source, "Position");
        copyIfAbsent(target, source, "IsDisabled");
        copyIfAbsent(target, source, "ruxueYear");
        copyIfAbsent(target, source, "xuezhi");
        copyIfAbsent(target, source, "udMail");
        copyIfAbsent(target, source, "udUserType");
        copyIfAbsent(target, source, "udNickName");
        copyIfAbsent(target, source, "udInstitution");
        copyIfAbsent(target, source, "udPiliang");
    }
    private void copyIfAbsent(Document target, Document source, String field) {
        if (target.get(field) == null && source.get(field) != null) {
            target.put(field, source.get(field));
        }
    }
    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }
    List<Map<String, Object>> finalizeRankingRecords(
            Map<String, Document> merged, String applyChannel, int safeLimit) {
        List<Map<String, Object>> records = new ArrayList<>();
        String chan = applyChannel == null ? "" : applyChannel.trim();
        for (Document document : merged.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("UserName", trimValue(document.get("_id")));
            row.put("NickName", trimValue(document.get("NickName")));
            row.put("Institution", trimValue(document.get("Institution")));
            row.put("Position", trimValue(document.get("Position")));
            row.put("AccountStatus", resolveAccountStatus(document));
            row.put("ApplyChannel", rankingApplyChannel(document));
            row.put("operationCount", toLong(document.get("operationCount")));
            if (!chan.isEmpty()
                    && !chan.equalsIgnoreCase(String.valueOf(row.get("ApplyChannel")))) {
                continue;
            }
            records.add(row);
        }
        Collections.sort(records, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int countCompare = Long.compare(
                        ((Number) b.get("operationCount")).longValue(),
                        ((Number) a.get("operationCount")).longValue());
                if (countCompare != 0) return countCompare;
                return String.valueOf(a.get("UserName"))
                        .compareTo(String.valueOf(b.get("UserName")));
            }
        });
        int end = Math.min(Math.max(safeLimit, 0), records.size());
        List<Map<String, Object>> limited = new ArrayList<>(records.subList(0, end));
        int rank = 1;
        for (Map<String, Object> row : limited) {
            row.put("rank", rank++);
        }
        return limited;
    }
    // 高级搜索下拉可选值
    public Map<String, Object> getOptions() {
        List<String> positions = cleanDistinct(
                mongoTemplate.findDistinct(new Query(), "Position", COLLECTION_NAME, String.class));
        List<String> operationTypes = cleanDistinct(
                mongoTemplate.findDistinct(new Query(), "O_Operation_type", NEW_LOG_COLLECTION, String.class));
        // 各集合的操作类型可能有差异，取并集
        for (String collection : auxiliaryLogCollections()) {
            for (String v : mongoTemplate.findDistinct(
                    new Query(), "O_Operation_type", collection, String.class)) {
                if (v != null && !operationTypes.contains(v)) {
                    operationTypes.add(v);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("positions", positions);
        result.put("operationTypes", operationTypes);
        return result;
    }
    // ============ 账号类型（正式 / 试用）判定 ============
    // 试用账号：满足任一特征即算。与 formalCriteria() 严格互补，两者命中数之和 = 总数
    private Criteria trialCriteria() {
        return new Criteria().orOperator(
                Criteria.where("UserType").ne(FORMAL_USER_TYPE),
                Criteria.where("UserName").in(INTERNAL_TEST_USERS),
                Criteria.where("UserName").regex(TRIAL_GJ_SUFFIX),
                Criteria.where("NickName").regex(TRIAL_NAME),
                Criteria.where("Institution").regex(OWN_COMPANY));
    }
    // 正式账号：UserType=1 且不带任何试用特征（对 trialCriteria 取反，德摩根展开）
    private Criteria formalCriteria() {
        return new Criteria().andOperator(
                Criteria.where("UserType").is(FORMAL_USER_TYPE),
                Criteria.where("UserName").nin(INTERNAL_TEST_USERS),
                Criteria.where("UserName").not().regex(TRIAL_GJ_SUFFIX),
                Criteria.where("NickName").not().regex(TRIAL_NAME),
                Criteria.where("Institution").not().regex(OWN_COMPANY));
    }
    // ============ 账号状态筛选（下推到 Mongo，与 resolveAccountStatus 保持同一套规则） ============
    // 毕业年阈值：毕业年 < 该值即视为已到期。
    // 今天若已过 9/1，当年毕业的也算到期，故阈值取次年
    private int graduationCutoffYear() {
        LocalDate today = LocalDate.now();
        boolean passedGraduation = !today.isBefore(LocalDate.of(today.getYear(), GRADUATION_MONTH, 1));
        return passedGraduation ? today.getYear() + 1 : today.getYear();
    }
    // 被封禁：IsDisabled 命中 1 / "1" / true
    private Criteria disabledCriteria() {
        return disabledCriteria("");
    }
    private Criteria disabledCriteria(String fieldPrefix) {
        return Criteria.where(fieldPrefix + "IsDisabled").in(1, 1L, "1", true);
    }
    private Criteria notDisabledCriteria() {
        return notDisabledCriteria("");
    }
    private Criteria notDisabledCriteria(String fieldPrefix) {
        return new Criteria().norOperator(disabledCriteria(fieldPrefix));
    }
    /**
     * 按「是否已毕业」把 (xuezhi, ruxueYear) 的实际取值组合枚举成可索引条件。
     *
     * <p>两个方向（到期 / 正常）都由这里生成，判定全部走 Java 侧 parseLeadingInt，
     * 与状态列的 isGraduated() 同源，因此不会出现口径不一致。
     *
     * <p>做法：取两字段的去重取值（各十几到二十几个），两两判定，
     * 按 xuezhi 分组成「xuezhi 等值 + ruxueYear $in [...]」分支再 $or。
     * 等值 + $in 可命中 {xuezhi, ruxueYear} 复合索引。
     *
     * <p>相比字符串范围比较，枚举实际取值能天然吃掉脏值（"4年"、"无"、"99999"），
     * 不需要正则或长度约束。
     *
     * @param graduated true 生成「已毕业」条件，false 生成「未毕业」条件
     */
    private Criteria graduationCriteria(boolean graduated) {
        return graduationCriteria(graduated, "");
    }
    /**
     * @param fieldPrefix 字段前缀，聚合管道里 lookup 出来的子文档需传 "userDoc."
     */
    private Criteria graduationCriteria(boolean graduated, String fieldPrefix) {
        int cutoff = graduationCutoffYear();
        List<String> xuezhiValues = distinctStrings("xuezhi");
        List<String> ruxueValues = distinctStrings("ruxueYear");
        String xuezhiField = fieldPrefix + "xuezhi";
        String ruxueField = fieldPrefix + "ruxueYear";
        List<Criteria> branches = new ArrayList<>();
        for (String xz : xuezhiValues) {
            Integer years = parseLeadingInt(xz);
            boolean xuezhiUsable = years != null && years >= 1 && years <= 10;
            if (!xuezhiUsable) {
                // 学制不可用 -> 该 xuezhi 下所有记录都算未毕业，与 ruxueYear 无关
                if (!graduated) {
                    branches.add(Criteria.where(xuezhiField).is(xz));
                }
                continue;
            }
            List<String> matched = new ArrayList<>();
            for (String ry : ruxueValues) {
                Integer year = parseLeadingInt(ry);
                boolean yearUsable = year != null && year >= MIN_RUXUE_YEAR && year <= MAX_RUXUE_YEAR;
                boolean isGraduated = yearUsable && (year + years) < cutoff;
                if (isGraduated == graduated) {
                    matched.add(ry);
                }
            }
            if (!matched.isEmpty()) {
                branches.add(new Criteria().andOperator(
                        Criteria.where(xuezhiField).is(xz),
                        Criteria.where(ruxueField).in(matched)));
            }
        }
        if (branches.isEmpty()) {
            // 无任何匹配组合，用永假条件
            return Criteria.where("_id").exists(false);
        }
        return new Criteria().orOperator(branches.toArray(new Criteria[0]));
    }
    // 取某字段的去重取值。null 会被 findDistinct 跳过，单独补一个 null 代表缺失/为空，
    // 使「未毕业」侧能覆盖到这些记录。
    //
    // 带 TTL 缓存：这两个字段的取值集合几乎不变，但每次状态筛选都要用，
    // 不缓存的话每请求多两次 distinct 查询
    private List<String> distinctStrings(String field) {
        CachedValues cached = distinctCache.get(field);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < DISTINCT_CACHE_TTL_MS) {
            return cached.values;
        }
        List<String> values = new ArrayList<>(
                mongoTemplate.findDistinct(new Query(), field, COLLECTION_NAME, String.class));
        values.add(null);
        distinctCache.put(field, new CachedValues(values, now));
        return values;
    }
    private static final class CachedValues {
        private final List<String> values;
        private final long loadedAt;
        private CachedValues(List<String> values, long loadedAt) {
            this.values = values;
            this.loadedAt = loadedAt;
        }
    }
    private static final class CachedRanking {
        private final Map<String, Object> result;
        private final long loadedAt;
        private CachedRanking(Map<String, Object> result, long loadedAt) {
            this.result = result;
            this.loadedAt = loadedAt;
        }
        private boolean isExpired(long now) {
            return now - loadedAt >= RANKING_CACHE_TTL_MS;
        }
        // records 列表在缓存后不再被改动，浅拷贝即可，避免复制 7 万行
        private Map<String, Object> payload(boolean cached) {
            Map<String, Object> copy = new LinkedHashMap<>(result);
            copy.put("cached", cached);
            return copy;
        }
    }
    private void addAccountStatus(List<Criteria> list, String accountStatus) {
        addAccountStatus(list, accountStatus, "");
    }
    // fieldPrefix：聚合管道里 lookup 出来的子文档需传 "userDoc."
    private void addAccountStatus(List<Criteria> list, String accountStatus, String fieldPrefix) {
        if (accountStatus == null || accountStatus.trim().isEmpty()) {
            return;
        }
        String value = accountStatus.trim();
        if (ACCOUNT_STATUS_DISABLED.equalsIgnoreCase(value)) {
            list.add(disabledCriteria(fieldPrefix));
        } else if (ACCOUNT_STATUS_UPGRADED.equalsIgnoreCase(value)) {
            // 升级：未被封禁 + 已毕业 + 身份为教师。
            // Position 在聚合管道里需带前缀，查询层直接用 Position 字段。
            list.add(new Criteria().andOperator(
                    notDisabledCriteria(fieldPrefix),
                    graduationCriteria(true, fieldPrefix),
                    Criteria.where(fieldPrefix + "Position").is("教师")));
        } else if (ACCOUNT_STATUS_EXPIRED.equalsIgnoreCase(value)) {
            // 到期：未被封禁 且 已毕业（禁用优先级更高）。
            // 正向匹配用可索引的多分支写法，约 194ms
            list.add(new Criteria().andOperator(
                    notDisabledCriteria(fieldPrefix),
                    graduationCriteria(true, fieldPrefix)));
        } else if (ACCOUNT_STATUS_NORMAL.equalsIgnoreCase(value)) {
            // 正常：未被封禁 且 未毕业（含算不出毕业年的）。
            // 用正向枚举而非取反 —— 取反走不了索引。
            // 与「到期」同源于 graduationCriteria，口径必然一致
            list.add(new Criteria().andOperator(
                    notDisabledCriteria(fieldPrefix),
                    graduationCriteria(false, fieldPrefix)));
        }
    }
    private void addAccountType(List<Criteria> list, String accountType) {
        if (accountType == null || accountType.trim().isEmpty()) {
            return;
        }
        String value = accountType.trim();
        if (ACCOUNT_TYPE_TRIAL.equalsIgnoreCase(value)) {
            list.add(trialCriteria());
        } else if (ACCOUNT_TYPE_FORMAL.equalsIgnoreCase(value)) {
            list.add(formalCriteria());
        }
    }
    // ============ 私有辅助 ============
    // 所有文本条件统一等值匹配（不再有模糊/前缀匹配）
    private void addExact(List<Criteria> list, String field, String value) {
        if (value != null && !value.trim().isEmpty()) {
            list.add(Criteria.where(field).is(value.trim()));
        }
    }
    // 源库部分字段是定长类型，值带大量尾部空格，展示前去掉首尾空白
    private Object trimValue(Object value) {
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return value;
    }
    private boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty();
    }
    // 兼容旧的统一关键字入口（页面已不用），同样改为等值匹配
    private void addKeyword(List<Criteria> list, String value, String... fields) {
        if (value != null && !value.trim().isEmpty()) {
            String keyword = value.trim();
            Criteria[] ors = new Criteria[fields.length];
            for (int i = 0; i < fields.length; i++) {
                ors[i] = Criteria.where(fields[i]).is(keyword);
            }
            list.add(new Criteria().orOperator(ors));
        }
    }
    private List<Criteria> buildRegisterCriteria(String keyword) {
        List<Criteria> criteriaList = new ArrayList<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            Pattern pattern = Pattern.compile(Pattern.quote(keyword.trim()), Pattern.CASE_INSENSITIVE);
            criteriaList.add(new Criteria().orOperator(
                    Criteria.where("Email").regex(pattern),
                    Criteria.where("name").regex(pattern),
                    Criteria.where("school").regex(pattern),
                    Criteria.where("college").regex(pattern)));
        }
        return criteriaList;
    }
    private void addDateRange(List<Criteria> list, String field, String start, String end) {
        Date startDate = parseDate(start, false);
        Date endDate = parseDate(end, true);
        if (startDate == null && endDate == null) {
            return;
        }
        Criteria criteria = Criteria.where(field);
        if (startDate != null) {
            criteria = criteria.gte(startDate);
        }
        if (endDate != null) {
            criteria = criteria.lt(endDate);
        }
        list.add(criteria);
    }
    // 把 yyyy-MM-dd 转为当天起点；结束日期转为次日起点（用于 lt，含当天）
    private Date parseDate(String value, boolean isEnd) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value.trim());
            if (isEnd) {
                date = date.plusDays(1);
            }
            return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            return null;
        }
    }
    private void applyCriteria(Query query, List<Criteria> criteriaList) {
        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }
    }
    private List<Map<String, Object>> accountRecords(List<Document> documents) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Document document : documents) {
            records.add(accountRecord(document));
        }
        return records;
    }
    /** 账号数据要保留 User_Data 的原字段；派生状态/类型依赖 UserType、Position、xuezhi 等字段。 */
    private Map<String, Object> accountRecord(Document document) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            row.put(entry.getKey(), trimValue(entry.getValue()));
        }
        return row;
    }
    private List<Map<String, Object>> registerRecords(List<Document> documents) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Document document : documents) {
            records.add(registerRecord(document));
        }
        return records;
    }
    private Map<String, Object> registerRecord(Document document) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String field = entry.getKey();
            if (isHiddenRegisterField(field)) {
                continue;
            }
            row.put(field, registerValue(field, entry.getValue()));
        }
        // 驳回是派生列：Okstatus==true 且 status==false 才算驳回，其余都不是。
        // 两个原始字段已被隐藏，这里只回传算好的布尔结论。
        row.put("isReject", isReject(document));
        return row;
    }

    private boolean isReject(Document document) {
        return isTruthy(registerField(document, "Okstatus"))
                && !isTruthy(registerField(document, "status"));
    }

    // register 字段大小写不统一（Email/AgreeFile 大写，name/school 小写），按忽略大小写取原始值
    private Object registerField(Document document, String field) {
        Object value = document.get(field);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            if (field.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    // 兼容布尔/数字/字符串各种「真」的写法（register 迁自 SQL Server，类型可能不一）
    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String s = value.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "是".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private boolean isHiddenRegisterField(String field) {
        return REGISTER_HIDDEN_FIELDS.contains(field.toLowerCase())
                || SENSITIVE_FIELD_NAME.matcher(field).find()
                // 手机号走统一口径（小程序申请表 register_xiaochengxu 带 phone），
                // 与数据交付记录那边共用同一份判定，免得两处各写一套慢慢走偏
                || SensitiveFields.isPhoneField(field);
    }
    private Object registerValue(String field, Object value) {
        if (value instanceof byte[] || value instanceof Binary) {
            return null;
        }
        return trimValue(value);
    }
    private List<Map<String, Object>> toRecords(List<Document> documents, String collection) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Document document : documents) {
            records.add(logRecord(document, collection));
        }
        return records;
    }

    /**
     * 日志行统一成旧 NewLog 的字段结构。
     *
     * @param collection 该行来自哪个日志集合 —— 语言版本列只看这个，不看文档内容
     */
    private Map<String, Object> logRecord(Document document, String collection) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String field : LOG_RESULT_FIELDS) {
            row.put(field, document.get(field));
        }
        row.put("O_language", resolveLanguage(document, collection));
        return row;
    }

    /**
     * 语言版本列的取值。
     *
     * <p>只有 En_Log（中英文版项目）的行带真实版本标记，取写入方写的中文/英文 ——
     * 同一个用户用英文站操作就是「英文」、用中文站就是「中文」，是逐行的值。
     *
     * <p>NewLog / Log_Login 一律返回 {@link #LEGACY_LANGUAGE}「旧版」：它们都是旧版项目的
     * 操作记录，库里的 {@code O_Language} 是遗留列、从未被填过（实测 193 万条全是字符串
     * {@code "None"}），既不能当版本用，也不能拿来推断历史版本。
     *
     * <p>En_Log 里版本标记缺失或为脏值（空串 / {@code "None"} / {@code "null"}）时归一成
     * null，前端统一显示「—」。
     */
    private Object resolveLanguage(Document document, String collection) {
        if (!EN_LOG_COLLECTION.equals(collection)) {
            return LEGACY_LANGUAGE;
        }
        // 写入方给的是 O_language，但同步是整文档搬运，大小写取决于源端；
        // 先精确取、再忽略大小写兜底，避免源端改了大小写就静默变空。
        Object language = document.get("O_language");
        if (language == null) {
            for (Map.Entry<String, Object> entry : document.entrySet()) {
                if ("O_language".equalsIgnoreCase(entry.getKey())) {
                    language = entry.getValue();
                    break;
                }
            }
        }
        if (language == null) {
            return null;
        }
        String text = String.valueOf(language).trim();
        if (text.isEmpty()
                || "None".equalsIgnoreCase(text)
                || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return language;
    }
    private List<String> cleanDistinct(List<String> raw) {
        List<String> cleaned = new ArrayList<>();
        for (String v : raw) {
            if (v != null && !v.trim().isEmpty()) {
                cleaned.add(v);
            }
        }
        cleaned.sort(String::compareTo);
        return cleaned;
    }
    private Map<String, Object> wrap(List<Map<String, Object>> records, long total,
                                     int pageNum, int pageSize, String message) {
        long totalPages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        PageResponse pageResponse = new PageResponse(
                records, total, pageNum, pageSize, totalPages,
                pageNum < totalPages, pageNum > 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", message);
        result.put("data", pageResponse);
        return result;
    }
    /**
     * 同步断点诊断（只读）。日志同步靠 _sync_state 里的 lastId 做增量，
     * 书签与集合实际内容不一致时会重拉巨量数据，上手前需要先看清真实数字。
     */
    public Map<String, Object> getSyncDiagnostics() {
        Map<String, Object> result = new HashMap<>();
        result.put("newLog", collectionStats(NEW_LOG_COLLECTION));
        result.put("logLogin", collectionStats(LOG_LOGIN_COLLECTION));
        result.put("enLog", collectionStats(EN_LOG_COLLECTION));
        result.put("register", collectionStats(REGISTER_COLLECTION));
        Map<String, Object> bookmarks = new HashMap<>();
        for (String id : new String[]{NEW_LOG_COLLECTION, LOG_LOGIN_COLLECTION,
                EN_LOG_COLLECTION, "register_oss"}) {
            Document state = mongoTemplate.getCollection(SYNC_STATE_COLLECTION)
                    .find(new Document("_id", id)).first();
            bookmarks.put(id, state == null ? null : new HashMap<>(state));
        }
        result.put("bookmarks", bookmarks);
        // 目标库里实际有哪些集合。MongoDB 的集合是首次写入时才创建的，
        // Log_Login 同步从没真跑过 → 这个集合此刻很可能压根不存在
        // （estimatedCount 对不存在的集合也返回 0，光看条数分不出「空」和「没有」）。
        List<String> collections = new ArrayList<>();
        for (String name : mongoTemplate.getDb().listCollectionNames()) {
            collections.add(name);
        }
        java.util.Collections.sort(collections);
        Map<String, Object> dbInfo = new HashMap<>();
        dbInfo.put("database", mongoTemplate.getDb().getName());
        dbInfo.put("collections", collections);
        dbInfo.put("logLoginExists", collections.contains(LOG_LOGIN_COLLECTION));
        dbInfo.put("enLogExists", collections.contains(EN_LOG_COLLECTION));
        result.put("targetDb", dbInfo);
        // O_city 被写成运营商名的行有多少、落在哪个 _id 区间。
        // 2026-08-10：IpCityResolver 升 xdb 后一度按老格式取下标 3（实际是 ISP 段），
        // 把「电信/联通」写进了城市字段。用来量清受影响范围、修完复核归零。
        Document ispCity = new Document("O_city",
                new Document("$in", Arrays.asList("电信", "联通", "移动", "铁通", "长城宽带")));
        // Log_Login 同步进来的文档实际长什么样。它没有读取接口，
        // 字段映射（U_Username→UserName、id→ID）只能这样核实。
        Map<String, Object> logLoginShape = new HashMap<>();
        Document logLoginSample = mongoTemplate.getCollection(LOG_LOGIN_COLLECTION)
                .find().limit(1).first();
        logLoginShape.put("sampleKeys", logLoginSample == null
                ? new ArrayList<String>() : new ArrayList<>(logLoginSample.keySet()));
        logLoginShape.put("hasUserName", logLoginSample != null && logLoginSample.containsKey("UserName"));
        logLoginShape.put("hasID", logLoginSample != null && logLoginSample.containsKey("ID"));
        logLoginShape.put("sampleCity", logLoginSample == null ? null : logLoginSample.getString("O_city"));
        result.put("logLoginShape", logLoginShape);
        Map<String, Object> ispRows = new HashMap<>();
        ispRows.put("count", mongoTemplate.getCollection(NEW_LOG_COLLECTION).countDocuments(ispCity));
        ispRows.put("minId", boundaryIdMatching(NEW_LOG_COLLECTION, ispCity, 1));
        ispRows.put("maxId", boundaryIdMatching(NEW_LOG_COLLECTION, ispCity, -1));
        result.put("ispValuedCity", ispRows);
        // 书签以上的行到底是什么形状？r13342 之前两张源表都写进 NewLog，
        // 如果那批 Log_Login 行还在，就不能拿全局 max(_id) 当断点
        // —— 会把真正没同步的 NewLog 行永久跳过。
        Object mark = bookmarks.get(NEW_LOG_COLLECTION) instanceof Map
                ? ((Map<?, ?>) bookmarks.get(NEW_LOG_COLLECTION)).get("lastId") : null;
        if (mark instanceof Number) {
            int lastId = ((Number) mark).intValue();
            Map<String, Object> above = new HashMap<>();
            Document gt = new Document("_id", new Document("$gt", lastId));
            above.put("count", mongoTemplate.getCollection(NEW_LOG_COLLECTION).countDocuments(gt));
            // NewLog 源表独有字段；Log_Login 经字段映射后不会有 O_system
            Document gtNoSystem = new Document("_id", new Document("$gt", lastId))
                    .append("O_system", new Document("$exists", false));
            above.put("missingNewLogFieldCount",
                    mongoTemplate.getCollection(NEW_LOG_COLLECTION).countDocuments(gtNoSystem));
            above.put("sampleKeys", sampleKeys(NEW_LOG_COLLECTION, gt));
            result.put("newLogAboveBookmark", above);
        }
        return result;
    }
    /**
     * 探源库还剩多少行要拉（只读，连公网 SQL Server）。
     * 起点用与同步一致的「书签与集合实际进度取大者」，所以这里报的数字
     * 就是真正会传输的行数。
     */
    public Map<String, Object> probeSyncSource() throws Exception {
        int newLogFrom = resumePointOf(NEW_LOG_COLLECTION);
        int logLoginFrom = resumePointOf(LOG_LOGIN_COLLECTION);
        return UserAndLogExportToMongo.probeSource(newLogFrom, logLoginFrom);
    }
    /** 源表列名与类型（只读，不读行）。 */
    public Map<String, Object> probeSyncSchema() throws Exception {
        return UserAndLogExportToMongo.probeSchema();
    }
    /** register 源库探测（只读）：总数、最大id、书签、待同步条数。 */
    public Map<String, Object> probeRegisterSource() throws Exception {
        int bookmarkLastId = readRegisterBookmark();
        return UserAndLogExportToMongo.probeRegisterSource(bookmarkLastId);
    }

    /** 读取 register 同步书签 */
    private int readRegisterBookmark() {
        Document state = mongoTemplate.getCollection(SYNC_STATE_COLLECTION)
                .find(new Document("_id", "register_oss")).first();
        if (state == null || !(state.get("lastId") instanceof Number)) {
            return 0;
        }
        return ((Number) state.get("lastId")).intValue();
    }

    /** 登录记录的时间覆盖范围（只读），判断 Log_Login 是重复还是补缺。 */
    public Map<String, Object> probeLoginCoverage() throws Exception {
        return UserAndLogExportToMongo.probeLoginCoverage();
    }
    /**
     * 用户名格式一致性（只读）：拿 Log_Login 的用户名样本，
     * 去 User_Data 和 NewLog 里查能否命中。方案 A 读取时要按用户合并两个集合，
     * 命中率低说明两边用户名不是同一种写法，合并会对不上。
     */
    public Map<String, Object> probeUsernameConsistency(int limit) throws Exception {
        List<String> sample = UserAndLogExportToMongo.sampleLog_LoginUsernames(limit);
        int inUserData = 0;
        int inNewLog = 0;
        List<String> misses = new ArrayList<>();
        for (String name : sample) {
            boolean hitUserData = mongoTemplate.getCollection(COLLECTION_NAME)
                    .countDocuments(new Document("UserName", name), COUNT_ONE) > 0;
            boolean hitNewLog = mongoTemplate.getCollection(NEW_LOG_COLLECTION)
                    .countDocuments(new Document("UserName", name), COUNT_ONE) > 0;
            if (hitUserData) inUserData++;
            if (hitNewLog) inNewLog++;
            if (!hitUserData && !hitNewLog && misses.size() < 10) {
                misses.add(name);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("sampleSize", sample.size());
        result.put("hitInUserData", inUserData);
        result.put("hitInNewLog", inNewLog);
        result.put("sampleValues", sample.size() > 10 ? sample.subList(0, 10) : sample);
        result.put("missedExamples", misses);
        return result;
    }
    /** 与同步端 alignWithTarget 同口径：书签与集合实际 max(_id) 取大者。 */
    private int resumePointOf(String collection) {
        Document state = mongoTemplate.getCollection(SYNC_STATE_COLLECTION)
                .find(new Document("_id", collection)).first();
        int bookmark = state != null && state.get("lastId") instanceof Number
                ? ((Number) state.get("lastId")).intValue() : 0;
        Object maxId = boundaryId(collection, -1);
        int maxExisting = maxId instanceof Number ? ((Number) maxId).intValue() : 0;
        return Math.max(bookmark, maxExisting);
    }
    /** 取一条样本文档的字段名，用来判断集合里混进了哪种形状的行。 */
    private List<String> sampleKeys(String collection, Document filter) {
        Document doc = mongoTemplate.getCollection(collection).find(filter).limit(1).first();
        return doc == null ? new ArrayList<>() : new ArrayList<>(doc.keySet());
    }
    /** 单个集合的条数与 _id 边界；_id 存的是源表自增 id（整数）。 */
    private Map<String, Object> collectionStats(String collection) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("estimatedCount", mongoTemplate.estimatedCount(collection));
        stats.put("maxId", boundaryId(collection, -1));
        stats.put("minId", boundaryId(collection, 1));
        return stats;
    }
    /**
     * 重算指定 _id 区间内的 O_city（只改这一个字段）。
     *
     * 不回源库：O_srcIP 已经在库里，城市是纯本地计算，所以修这批数据不需要
     * 再拉一遍源表、也不用动 _sync_state 书签。
     *
     * @param dryRun true 时只统计会改多少行，不写库
     */
    public Map<String, Object> recomputeCityRange(int fromId, int toId, boolean dryRun) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("fromId", fromId);
        result.put("toId", toId);
        result.put("dryRun", dryRun);
        long scanned = 0;
        long changed = 0;
        List<com.mongodb.client.model.WriteModel<Document>> writes = new ArrayList<>();
        Document range = new Document("_id",
                new Document("$gte", fromId).append("$lte", toId));
        try (IpCityResolver resolver = new IpCityResolver();
             com.mongodb.client.MongoCursor<Document> cursor = mongoTemplate
                     .getCollection(NEW_LOG_COLLECTION)
                     .find(range)
                     .projection(new Document("_id", 1).append("O_srcIP", 1).append("O_city", 1))
                     .iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                scanned++;
                String correct = resolver.resolve(doc.getString("O_srcIP"));
                String current = doc.getString("O_city");
                if (java.util.Objects.equals(correct, current)) {
                    continue;
                }
                changed++;
                if (!dryRun) {
                    writes.add(new com.mongodb.client.model.UpdateOneModel<Document>(
                            new Document("_id", doc.get("_id")),
                            new Document("$set", new Document("O_city", correct))));
                    if (writes.size() >= 1000) {
                        mongoTemplate.getCollection(NEW_LOG_COLLECTION).bulkWrite(writes,
                                new com.mongodb.client.model.BulkWriteOptions().ordered(false));
                        writes.clear();
                    }
                }
            }
            if (!dryRun && !writes.isEmpty()) {
                mongoTemplate.getCollection(NEW_LOG_COLLECTION).bulkWrite(writes,
                        new com.mongodb.client.model.BulkWriteOptions().ordered(false));
            }
        }
        result.put("scanned", scanned);
        result.put("changed", changed);
        return result;
    }
    /** 满足给定条件的行里 _id 的边界值，用来定位受影响区间。 */
    private Object boundaryIdMatching(String collection, Document filter, int direction) {
        Document doc = mongoTemplate.getCollection(collection)
                .find(filter)
                .sort(new Document("_id", direction))
                .projection(new Document("_id", 1))
                .limit(1)
                .first();
        return doc == null ? null : doc.get("_id");
    }
    private Object boundaryId(String collection, int direction) {
        Document doc = mongoTemplate.getCollection(collection)
                .find()
                .sort(new Document("_id", direction))
                .projection(new Document("_id", 1))
                .limit(1)
                .first();
        return doc == null ? null : doc.get("_id");
    }
    /**
     * 账号表最近同步完成时间。
     *
     * 三种同步（User_Data / Log / Log_Login）都往 User_Data_UpdateTime 插完成时间，
     * 所以不能无条件取最新一条 —— 否则同步过日志之后，这里会返回日志的时间。
     */
    public UserDataUpdateTime getLastUpdateTime() {
        return getLastUpdateTime(SYNC_TYPE_USER_DATA);
    }
    /** 按同步类型取最近完成时间，type 传 null 表示不过滤（保留「任意同步」的语义）。 */
    public UserDataUpdateTime getLastUpdateTime(String type) {
        Query query = new Query();
        if (type != null) {
            if (SYNC_TYPE_USER_DATA.equals(type)) {
                // type 字段是 r13342 才加的，此前写入的历史记录没有这个字段，
                // 而那时只有账号表会写这个集合 —— 所以「字段缺失」等同于 User_Data。
                // 严格按 type 相等过滤会一条都查不到、界面退回「未同步过」。
                query.addCriteria(new Criteria().orOperator(
                        Criteria.where("type").is(SYNC_TYPE_USER_DATA),
                        Criteria.where("type").exists(false)));
            } else {
                query.addCriteria(Criteria.where("type").is(type));
            }
        }
        query.with(Sort.by(Sort.Direction.DESC, "updateTime"));
        query.limit(1);
        return datahubTemplate.findOne(query, UserDataUpdateTime.class);
    }
}
