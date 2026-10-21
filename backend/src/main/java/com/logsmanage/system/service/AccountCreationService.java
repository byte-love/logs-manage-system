package com.logsmanage.system.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;
import com.logsmanage.system.dto.CreateAccountRequest;
import com.logsmanage.system.utils.SqlUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AccountCreationService {

    private static final Logger log = LoggerFactory.getLogger(AccountCreationService.class);

    private static final String USER_SQL_URL = setting(
            "datahub.userSql.url", "DataHub_USER_SQL_URL",
            "jdbc:sqlserver://login.api.example.com:1433;databaseName=cas;responseBuffering=adaptive");
    private static final String USER_SQL_USER = setting(
            "datahub.userSql.user", "DataHub_USER_SQL_USER", "jhadmin");
    private static final String USER_SQL_PASSWORD = setting(
            "datahub.userSql.password", "DataHub_USER_SQL_PASSWORD", "byteloveadmin");

    private static final String SYS_SQL_URL = setting(
            "datahub.sysSql.url", "DataHub_SYS_SQL_URL",
            "jdbc:sqlserver://203.0.113.14:1433;databaseName=DataHub_SYS;responseBuffering=adaptive");
    private static final String SYS_SQL_USER = setting(
            "datahub.sysSql.user", "DataHub_SYS_SQL_USER", "sa");
    private static final String SYS_SQL_PASSWORD = setting(
            "datahub.sysSql.password", "DataHub_SYS_SQL_PASSWORD", "ByteLove0713");

    private static final String USER_COLLECTION = "User_Data";
    private static final String BANED_LIST_COLLECTION = "BanedList";
    private static final String CREATE_USER_LOG_COLLECTION = "CreateUserLog";
    private static final String SYSTEM_LIST_SQL =
            "SELECT ID, Name, Type, Partition FROM dbo.SystemList " +
                    "WHERE IsPublish = 1 ORDER BY Orders";
    private static final List<String> LEGACY_SYSTEM_PARTITIONS = Arrays.asList(
            "上市公司经营研究",
            "上市公司文本信息",
            "上市公司新闻舆情",
            "资本市场人物特征",
            "银行及金融研究",
            "公司债券信息研究",
            "社会经济组织研究",
            "宏观经济研究系列",
            "区域经济研究系列",
            "财政金融研究系列",
            "对外经济研究系列",
            "产业经济研究系列",
            "人文社科研究系列",
            "上市公司股票基础数据",
            "上市公司财务基础数据",
            "经济研究基础数据",
            "上市公司治理基础数据");
    // dbo.Colleges 的真实列名以 U+FEFF 开头，旧 EF 模型将它映射成了 C_college。
    private static final String COLLEGE_COLUMN =
            "[" + Character.toString((char) 0xFEFF) + "college]";

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\d{11}$");
    private static final Pattern MAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_.+\\-]+@[a-zA-Z0-9\\-]+\\.[a-zA-Z0-9.\\-]+$");
    private static final Pattern IPV4_PART_PATTERN = Pattern.compile("^\\d{1,3}$");
    private static final DateTimeFormatter SQL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String INSERT_SQL =
            "INSERT INTO dbo.User_Data (" +
                    "UserName, Password, NickName, Info, IsDisabled, Mail, Institution, Phone, " +
                    "IpRange, Extra, InfoIndex, CreateDate, UserType, College, CollegeDept, Position, " +
                    "DataNames, DataCode, ReceiveMail, ContactPhone, Contactors, ContactMail, Addresses, " +
                    "ContactPositon, ExpireDateTime, SendEmail, SendMessage, LoginTimes, AcceptAggrement, " +
                    "xuezhi, ruxueYear, endDate, xueEnddate" +
                    ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final MongoTemplate primaryMongoTemplate;
    private final MongoTemplate backupMongoTemplate;
    private final ObjectMapper objectMapper;

    public AccountCreationService(
            @Qualifier("MongoTemplate") MongoTemplate primaryMongoTemplate,
            @Qualifier("datahubTemplate") MongoTemplate backupMongoTemplate,
            ObjectMapper objectMapper) {
        this.primaryMongoTemplate = primaryMongoTemplate;
        this.backupMongoTemplate = backupMongoTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getCreateOptions() throws SQLException {
        List<SystemOption> systems;
        List<String> schools;
        try (Connection connection = userConnection()) {
            systems = loadSystems(connection);
            schools = loadSchools(connection);
        }

        List<String> colleges;
        try (Connection connection = sysConnection()) {
            colleges = loadColleges(connection);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("systems", toSystemMaps(systems));
        result.put("schools", schools);
        result.put("colleges", colleges);
        result.put("positions", Arrays.asList("教师", "博士", "硕士", "本科", "其他"));
        result.put("userTypes", Arrays.asList(
                option(0, "图书馆院系"),
                option(1, "个人账号"),
                option(2, "机构账号"),
                option(3, "班级账号")));
        return result;
    }

    public Map<String, Object> getSchoolPermissionTemplate(String school) throws SQLException {
        String schoolName = required(school, "学校", 200);
        String jurisdiction = "{}";
        String endDate = LocalDate.now().plusYears(1).toString();
        boolean found = false;
        List<SystemOption> systems;

        try (Connection connection = userConnection()) {
            systems = loadSystems(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT jurisdiction, endDate FROM dbo.Ip_Adr WHERE Adress = ?")) {
                statement.setString(1, schoolName);
                try (ResultSet rs = statement.executeQuery()) {
                    // 旧工具在存在重复学校时使用查询结果中的最后一条。
                    while (rs.next()) {
                        found = true;
                        jurisdiction = defaultValue(trim(rs.getString("jurisdiction")), "{}");
                        endDate = defaultValue(trim(rs.getString("endDate")), endDate);
                    }
                }
            }
        }

        if (!found) {
            throw new IllegalArgumentException("未找到该学校的权限配置");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(jurisdiction);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("该学校的权限配置不是有效 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("该学校的权限配置格式不正确");
        }

        List<Map<String, Object>> permissions = new ArrayList<>();
        Set<String> publishedCodes = new HashSet<>();
        for (SystemOption system : systems) {
            publishedCodes.add(system.code);
            JsonNode detail = root.get(system.code);
            if (detail == null || !detail.isObject()) {
                continue;
            }
            JsonNode expiryNode = detail.get("expirydate");
            if (expiryNode == null || trim(expiryNode.asText()) == null) {
                throw new IllegalArgumentException("学校权限缺少过期时间：" + system.code);
            }
            Map<String, Object> permission = new LinkedHashMap<>();
            permission.put("code", system.code);
            permission.put("expiryDate", normalizeDateTime(expiryNode.asText(),
                    "学校权限过期时间（" + system.code + "）"));
            permissions.add(permission);
        }

        int ignoredCodes = 0;
        java.util.Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            if (!publishedCodes.contains(fieldNames.next())) {
                ignoredCodes++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("school", schoolName);
        result.put("endDate", endDate);
        result.put("permissions", permissions);
        result.put("ignoredCodes", ignoredCodes);
        return result;
    }

    public Map<String, Object> addCollege(String value) throws SQLException {
        String college = required(value, "学院名称", 255);
        String insertSql = "INSERT INTO dbo.Colleges (" + COLLEGE_COLUMN + ") " +
                "SELECT ? WHERE NOT EXISTS (SELECT 1 FROM dbo.Colleges WHERE " +
                COLLEGE_COLUMN + " = ?)";

        List<String> colleges;
        try (Connection connection = sysConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                statement.setString(1, college);
                statement.setString(2, college);
                if (statement.executeUpdate() == 0) {
                    rollbackQuietly(connection);
                    throw new CollegeAlreadyExistsException(college);
                }
                connection.commit();
                colleges = loadColleges(connection);
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "学院添加成功");
        result.put("college", college);
        result.put("colleges", colleges);
        return result;
    }

    public Map<String, Object> create(CreateAccountRequest request) throws SQLException {
        ValidatedAccount account = validate(request);

        Integer primaryId = null;
        Integer secondaryId = null;
        boolean primaryCommitted = false;
        boolean secondaryCommitted = false;
        try (Connection primary = userConnection();
             Connection secondary = sysConnection()) {
            primary.setAutoCommit(false);
            secondary.setAutoCommit(false);
            try {
                if (exists(primary, account.userName)) {
                    throw new AccountAlreadyExistsException(account.userName);
                }

                List<SystemOption> systems = loadSystems(primary);
                account.info = buildInfo(request.getPermissions(), systems, account.institution);

                validatePhoneBinding(primary, account.userName, account.phone);
                insertPhoneBinding(primary, account.userName, account.phone, account.createdAt);
                primaryId = insert(primary, account);

                // 旧工具会覆盖 DataHub_SYS 中可能遗留的同名数据，但 CAS 中重名时拒绝创建。
                deleteUser(secondary, account.userName);
                secondaryId = insert(secondary, account);

                // 先提交主库；若副库提交失败，补偿删除主库账号和手机号绑定。
                primary.commit();
                primaryCommitted = true;
                secondary.commit();
                secondaryCommitted = true;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(primary);
                rollbackQuietly(secondary);
                throw e;
            }
        } catch (AccountCreationConflictException e) {
            throw e;
        } catch (SQLException e) {
            if (primaryCommitted != secondaryCommitted) {
                compensateSql(account.userName, primaryCommitted, secondaryCommitted);
            }
            throw e;
        }

        Document commonDocument = toDocument(account);
        List<String> syncWarnings = new ArrayList<>();
        try {
            // 对齐旧工具 BackupUserData：远程 DataHub.User_Data 使用 DataHub_SYS 生成的 ID。
            backupMongoTemplate.save(buildBackupDocument(commonDocument, secondaryId), USER_COLLECTION);
        } catch (RuntimeException e) {
            log.warn("账号 {} 已写入 SQL，但远程 MongoDB 备份失败", account.userName, e);
            syncWarnings.add("远程备份库同步失败");
        }
        try {
            // 页面 Mongo 与 cas 主库保持同一个 ID，并沿用旧同步逻辑不写 Phone。
            primaryMongoTemplate.save(buildDisplayDocument(commonDocument, primaryId), USER_COLLECTION);
        } catch (RuntimeException e) {
            log.warn("账号 {} 已写入 SQL，但页面 MongoDB 同步失败", account.userName, e);
            syncWarnings.add("页面数据同步失败，请执行账号表同步");
        }
        try {
            primaryMongoTemplate.insert(
                    buildCreateUserLogDocument(
                            commonDocument,
                            primaryId,
                            secondaryId,
                            account.marketInitiator,
                            account.otherDescription,
                            Date.from(account.createdAt.atZone(ZoneId.systemDefault()).toInstant())),
                    CREATE_USER_LOG_COLLECTION);
        } catch (RuntimeException e) {
            log.warn("账号 {} 已创建，但 CreateUserLog 审计记录写入失败", account.userName, e);
            syncWarnings.add("创建账号审计记录写入失败");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", syncWarnings.isEmpty() ? "账号添加成功" : "账号已添加，部分同步任务失败");
        result.put("userName", account.userName);
        result.put("warnings", syncWarnings);
        return result;
    }

    public Map<String, Object> unbanUser(String userName) throws SQLException {
        String normalizedUserName = required(userName, "用户名", 100);
        Query mongoUserQuery = new Query(Criteria.where("UserName").is(normalizedUserName));
        List<Document> mongoUsers = primaryMongoTemplate.find(
                mongoUserQuery, Document.class, USER_COLLECTION);
        if (mongoUsers.isEmpty()) {
            // 兼容历史 SQL 定长字段同步到 Mongo 后残留的首尾空格；用户名主体仍精确转义匹配。
            mongoUserQuery = trimmedUserNameQuery(normalizedUserName);
            mongoUsers = primaryMongoTemplate.find(
                    mongoUserQuery, Document.class, USER_COLLECTION);
        }
        if (mongoUsers.size() != 1) {
            throw new UnbanConflictException(
                    mongoUsers.isEmpty()
                            ? "MongoDB User_Data 中未找到该账号：" + normalizedUserName
                            : "MongoDB User_Data 中存在多个同名账号，已停止解禁：" + normalizedUserName);
        }

        Document mongoUser = mongoUsers.get(0);
        Object mongoUserId = mongoUser.get("_id");
        Query mongoUserIdQuery = new Query(Criteria.where("_id").is(mongoUserId));
        boolean mongoHadDisabledField = mongoUser.containsKey("IsDisabled");
        Object oldMongoDisabled = mongoUser.get("IsDisabled");

        Query banedUserQuery = new Query(Criteria.where("UserName").is(normalizedUserName));
        List<Document> oldBanedDocuments = backupMongoTemplate.find(
                banedUserQuery, Document.class, BANED_LIST_COLLECTION);
        if (oldBanedDocuments.isEmpty()) {
            banedUserQuery = trimmedUserNameQuery(normalizedUserName);
            oldBanedDocuments = backupMongoTemplate.find(
                    banedUserQuery, Document.class, BANED_LIST_COLLECTION);
        }
        Document insertedBanedDocument = null;
        boolean mongoUpdated = false;
        boolean banedUpdated = false;

        try (Connection connection = userConnection()) {
            connection.setAutoCommit(false);
            try {
                int sqlMatches = lockOnlineUser(connection, normalizedUserName);
                if (sqlMatches != 1) {
                    throw new UnbanConflictException(
                            sqlMatches == 0
                                    ? "线上 cas.User_Data 中未找到该账号：" + normalizedUserName
                                    : "线上 cas.User_Data 中存在多个同名账号，已停止解禁：" + normalizedUserName);
                }

                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE dbo.User_Data SET IsDisabled = 0 WHERE UserName = ?")) {
                    statement.setString(1, normalizedUserName);
                    int updated = statement.executeUpdate();
                    if (updated != 1) {
                        throw new UnbanConflictException(
                                "线上 cas.User_Data 解禁命中数量异常，已停止操作：" + updated);
                    }
                }

                UpdateResult mongoResult = primaryMongoTemplate.updateFirst(
                        mongoUserIdQuery,
                        new Update().set("IsDisabled", 0),
                        USER_COLLECTION);
                if (mongoResult.getMatchedCount() != 1) {
                    throw new UnbanConflictException("MongoDB User_Data 更新失败，已停止解禁");
                }
                mongoUpdated = true;

                // 提前标记补偿范围；即使多条历史禁用记录只更新了一部分，
                // catch 中也会按操作前快照逐条恢复。
                banedUpdated = true;
                if (oldBanedDocuments.isEmpty()) {
                    insertedBanedDocument = new Document("UserName", normalizedUserName)
                            .append("NickName", mongoUser.get("NickName"))
                            .append("Institution", mongoUser.get("Institution"))
                            .append("IsUnbanned", true);
                    backupMongoTemplate.insert(insertedBanedDocument, BANED_LIST_COLLECTION);
                } else {
                    for (Document oldBanedDocument : oldBanedDocuments) {
                        Object id = oldBanedDocument.get("_id");
                        UpdateResult banedResult = backupMongoTemplate.updateFirst(
                                new Query(Criteria.where("_id").is(id)),
                                new Update().set("IsUnbanned", true),
                                BANED_LIST_COLLECTION);
                        if (banedResult.getMatchedCount() != 1) {
                            throw new UnbanConflictException(
                                    "BanedList 解禁标记更新失败，已停止解禁");
                        }
                    }
                }

                connection.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                if (mongoUpdated) {
                    restoreMongoDisabled(
                            mongoUserIdQuery, mongoHadDisabledField, oldMongoDisabled,
                            normalizedUserName);
                }
                if (banedUpdated || insertedBanedDocument != null) {
                    restoreBanedDocuments(
                            oldBanedDocuments, insertedBanedDocument, normalizedUserName);
                }
                throw e;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "账号已解禁");
        result.put("userName", normalizedUserName);
        return result;
    }

    private Query trimmedUserNameQuery(String userName) {
        Pattern exactTrimmedName = Pattern.compile(
                "^\\s*" + Pattern.quote(userName) + "\\s*$");
        return new Query(Criteria.where("UserName").regex(exactTrimmedName));
    }

    private int lockOnlineUser(Connection connection, String userName) throws SQLException {
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT UserName FROM dbo.User_Data WITH (UPDLOCK, HOLDLOCK) WHERE UserName = ?")) {
            statement.setString(1, userName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    count++;
                }
            }
        }
        return count;
    }

    private void restoreMongoDisabled(
            Query idQuery, boolean fieldExisted, Object oldValue, String userName) {
        try {
            Update restore = fieldExisted
                    ? new Update().set("IsDisabled", oldValue)
                    : new Update().unset("IsDisabled");
            primaryMongoTemplate.updateFirst(idQuery, restore, USER_COLLECTION);
        } catch (RuntimeException restoreError) {
            log.error("回滚账号 {} 的 MongoDB IsDisabled 失败，需人工核对", userName, restoreError);
        }
    }

    private void restoreBanedDocuments(
            List<Document> oldDocuments, Document insertedDocument, String userName) {
        try {
            if (insertedDocument != null && insertedDocument.get("_id") != null) {
                backupMongoTemplate.remove(
                        new Query(Criteria.where("_id").is(insertedDocument.get("_id"))),
                        BANED_LIST_COLLECTION);
            } else if (insertedDocument != null) {
                backupMongoTemplate.remove(
                        new Query(new Criteria().andOperator(
                                Criteria.where("UserName").is(userName),
                                Criteria.where("IsUnbanned").is(true))),
                        BANED_LIST_COLLECTION);
            }
            for (Document oldDocument : oldDocuments) {
                Update restore = oldDocument.containsKey("IsUnbanned")
                        ? new Update().set("IsUnbanned", oldDocument.get("IsUnbanned"))
                        : new Update().unset("IsUnbanned");
                backupMongoTemplate.updateFirst(
                        new Query(Criteria.where("_id").is(oldDocument.get("_id"))),
                        restore,
                        BANED_LIST_COLLECTION);
            }
        } catch (RuntimeException restoreError) {
            log.error("回滚账号 {} 的 BanedList 解禁标记失败，需人工核对", userName, restoreError);
        }
    }

    private ValidatedAccount validate(CreateAccountRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求内容不能为空");
        }
        ValidatedAccount account = new ValidatedAccount();
        account.userName = required(request.getUserName(), "账户名", 100);
        String rawPassword = required(request.getPassword(), "密码", 100);
        account.nickName = required(request.getNickName(), "姓名", 100);
        account.phone = required(request.getPhone(), "手机号", 200);
        account.mail = required(request.getMail(), "邮箱", 100);
        account.institution = required(request.getInstitution(), "学校/机构", 100);
        account.marketInitiator = required(request.getMarketInitiator(), "市场部发起人", 100);
        account.otherDescription = required(request.getOtherDescription(), "其他说明", 1000);

        if (!PHONE_PATTERN.matcher(account.phone).matches()) {
            throw new IllegalArgumentException("手机号必须为 11 位数字");
        }
        if (!MAIL_PATTERN.matcher(account.mail).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }

        account.password = DigestUtils.md5DigestAsHex(
                rawPassword.getBytes(StandardCharsets.UTF_8));
        account.college = optional(request.getCollege(), 500, "学院");
        account.collegeDept = optional(request.getCollegeDept(), 500, "系所");
        account.position = defaultValue(optional(request.getPosition(), 500, "职位"), "教师");
        account.userType = request.getUserType() == null ? 1 : request.getUserType();
        if (account.userType < 0 || account.userType > 3) {
            throw new IllegalArgumentException("账户类型不正确");
        }
        account.dataCode = optional(request.getDataCode(), 500, "学院（复核）");
        account.receiveMail = optional(request.getReceiveMail(), 500, "接收邮箱");
        account.contactors = optional(request.getContactors(), 500, "联系人");
        account.contactPhone = optional(request.getContactPhone(), 500, "联系人电话");
        account.contactMail = optional(request.getContactMail(), 500, "联系人邮箱");
        account.contactPosition = optional(request.getContactPosition(), 500, "联系人职位");
        account.addresses = optional(request.getAddresses(), 500, "地址");
        account.ipRange = normalizeIpRanges(request.getIpRange());
        account.xuezhi = optional(request.getXuezhi(), 100, "学制");
        account.ruxueYear = optional(request.getRuxueYear(), 100, "入学年份");
        account.endDate = optional(request.getEndDate(), 100, "结束日期");
        account.xueEnddate = optional(request.getXueEnddate(), 100, "学业结束日期");
        account.expireDate = parseOptionalDate(request.getExpireDateTime(), "账号过期时间");
        account.sendEmail = Boolean.TRUE.equals(request.getSendEmail());
        account.sendMessage = Boolean.TRUE.equals(request.getSendMessage());
        account.createdAt = LocalDateTime.now();
        return account;
    }

    String buildInfo(List<CreateAccountRequest.AccountPermission> permissions,
                     List<SystemOption> systems,
                     String institution) {
        Map<String, CreateAccountRequest.AccountPermission> requested = new LinkedHashMap<>();
        if (permissions != null) {
            for (CreateAccountRequest.AccountPermission permission : permissions) {
                if (permission == null) {
                    throw new IllegalArgumentException("数据库权限配置中包含空项");
                }
                String code = required(permission.getCode(), "权限代码", 100);
                if (requested.put(code, permission) != null) {
                    throw new IllegalArgumentException("权限代码重复：" + code);
                }
            }
        }

        Set<String> publishedCodes = new HashSet<>();
        for (SystemOption system : systems) {
            publishedCodes.add(system.code);
        }
        for (String code : requested.keySet()) {
            if (!publishedCodes.contains(code)) {
                throw new IllegalArgumentException("包含未发布或不存在的数据库权限：" + code);
            }
        }
        if (requested.size() != systems.size()) {
            throw new IllegalArgumentException("数据库权限配置已变化，请重新打开添加账号窗口");
        }

        Map<String, String> expiries = new LinkedHashMap<>();
        Set<String> selectedCodes = new HashSet<>();
        for (SystemOption system : systems) {
            CreateAccountRequest.AccountPermission permission = requested.get(system.code);
            if (permission == null) {
                throw new IllegalArgumentException("缺少数据库权限：" + system.code);
            }
            expiries.put(system.code, parseRequiredDateTime(
                    permission.getExpiryDate(), "权限过期时间（" + system.code + "）")
                    .format(SQL_DATE_TIME));
            if (Boolean.TRUE.equals(permission.getSelected())) {
                selectedCodes.add(system.code);
            }
        }

        // 旧工具先序列化正式权限，再序列化未勾选的高级试用权限。
        Map<String, Object> info = new LinkedHashMap<>();
        String guizhouUniversityExpiry = "";
        for (SystemOption system : systems) {
            if (selectedCodes.contains(system.code)) {
                String expiry = expiries.get(system.code);
                info.put(system.code, permissionDetail(2, expiry));
                guizhouUniversityExpiry = expiry;
            }
        }
        for (SystemOption system : systems) {
            if (!selectedCodes.contains(system.code)) {
                info.put(system.code, permissionDetail(1.5D, expiries.get(system.code)));
            }
        }

        if (institution.contains("贵州大学")) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("level", 2);
            detail.put("expirydate", guizhouUniversityExpiry);
            info.put("CICD", detail);
        }

        try {
            return objectMapper.writeValueAsString(info);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("权限配置无法生成", e);
        }
    }

    private List<SystemOption> loadSystems(Connection connection) throws SQLException {
        List<SystemOption> publishedSystems = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SYSTEM_LIST_SQL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String code = trim(rs.getString("ID"));
                if (code == null) {
                    continue;
                }
                publishedSystems.add(new SystemOption(
                        code,
                        trim(rs.getString("Name")),
                        trim(rs.getString("Type")),
                        trim(rs.getString("Partition"))));
            }
        }
        return legacySystemOrder(publishedSystems);
    }

    static List<SystemOption> legacySystemOrder(List<SystemOption> publishedSystems) {
        Map<String, List<SystemOption>> grouped = new LinkedHashMap<>();
        for (String partition : LEGACY_SYSTEM_PARTITIONS) {
            grouped.put(partition, new ArrayList<>());
        }
        for (SystemOption system : publishedSystems) {
            List<SystemOption> group = grouped.get(system.partition);
            if (group != null) {
                group.add(system);
            }
        }

        List<SystemOption> result = new ArrayList<>();
        for (String partition : LEGACY_SYSTEM_PARTITIONS) {
            result.addAll(grouped.get(partition));
        }
        return result;
    }

    private Map<String, Object> permissionDetail(Object level, String expiry) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("level", level);
        detail.put("expirydate", expiry);
        return detail;
    }

    private List<Map<String, Object>> toSystemMaps(List<SystemOption> systems) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SystemOption system : systems) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", system.code);
            item.put("name", system.name);
            item.put("type", system.type);
            item.put("partition", system.partition);
            result.add(item);
        }
        return result;
    }

    private List<String> loadSchools(Connection connection) throws SQLException {
        Set<String> schools = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT Adress FROM dbo.Ip_Adr");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String school = trim(rs.getString(1));
                if (school != null) {
                    schools.add(school);
                }
            }
        }
        return new ArrayList<>(schools);
    }

    private List<String> loadColleges(Connection connection) throws SQLException {
        List<String> colleges = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLLEGE_COLUMN + " FROM dbo.Colleges");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String college = trim(rs.getString(1));
                if (college != null) {
                    colleges.add(college);
                }
            }
        }
        return colleges;
    }

    private boolean exists(Connection connection, String userName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM dbo.User_Data WHERE UserName = ?")) {
            statement.setString(1, userName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void validatePhoneBinding(Connection connection, String userName, String phone)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT TOP 1 UserName FROM dbo.User_PhoneBinding WITH (UPDLOCK, HOLDLOCK) " +
                        "WHERE Phone = ?")) {
            statement.setString(1, phone);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    throw new PhoneAlreadyBoundException(phone, trim(rs.getString(1)));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT TOP 1 Phone FROM dbo.User_PhoneBinding WITH (UPDLOCK, HOLDLOCK) " +
                        "WHERE UserName = ?")) {
            statement.setString(1, userName);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    throw new AccountPhoneAlreadyBoundException(userName, trim(rs.getString(1)));
                }
            }
        }
    }

    private void insertPhoneBinding(Connection connection, String userName, String phone,
                                    LocalDateTime bindingDate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO dbo.User_PhoneBinding (UserName, Phone, BindingDate) VALUES (?,?,?)")) {
            statement.setString(1, userName);
            statement.setString(2, phone);
            statement.setTimestamp(3, Timestamp.valueOf(bindingDate));
            statement.executeUpdate();
        }
    }

    private Integer insert(Connection connection, ValidatedAccount a) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setString(i++, a.userName);
            ps.setString(i++, a.password);
            ps.setString(i++, a.nickName);
            ps.setString(i++, a.info);
            ps.setInt(i++, 0);
            ps.setString(i++, a.mail);
            ps.setString(i++, a.institution);
            ps.setString(i++, a.phone);
            ps.setString(i++, a.ipRange);
            ps.setString(i++, "{}");
            ps.setLong(i++, 3L);
            ps.setTimestamp(i++, Timestamp.valueOf(a.createdAt));
            ps.setInt(i++, a.userType);
            setNullableString(ps, i++, a.college);
            setNullableString(ps, i++, a.collegeDept);
            setNullableString(ps, i++, a.position);
            ps.setNull(i++, Types.NVARCHAR);
            setNullableString(ps, i++, a.dataCode);
            setNullableString(ps, i++, a.receiveMail);
            setNullableString(ps, i++, a.contactPhone);
            setNullableString(ps, i++, a.contactors);
            setNullableString(ps, i++, a.contactMail);
            setNullableString(ps, i++, a.addresses);
            setNullableString(ps, i++, a.contactPosition);
            if (a.expireDate == null) {
                ps.setNull(i++, Types.DATE);
            } else {
                ps.setDate(i++, java.sql.Date.valueOf(a.expireDate));
            }
            ps.setBoolean(i++, a.sendEmail);
            ps.setBoolean(i++, a.sendMessage);
            ps.setInt(i++, 8);
            ps.setNull(i++, Types.BIT);
            setNullableString(ps, i++, a.xuezhi);
            setNullableString(ps, i++, a.ruxueYear);
            setNullableString(ps, i++, a.endDate);
            setNullableString(ps, i, a.xueEnddate);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : null;
            }
        }
    }

    private Document toDocument(ValidatedAccount a) {
        return new Document("_id", a.userName)
                .append("UserName", a.userName)
                .append("Password", a.password)
                .append("NickName", a.nickName)
                .append("Info", a.info)
                .append("IsDisabled", 0)
                .append("Mail", a.mail)
                .append("Institution", a.institution)
                .append("Phone", a.phone)
                .append("IpRange", a.ipRange)
                .append("Extra", "{}")
                .append("InfoIndex", 3L)
                .append("CreateDate", Date.from(a.createdAt.atZone(ZoneId.systemDefault()).toInstant()))
                .append("UserType", a.userType)
                .append("College", a.college)
                .append("CollegeDept", a.collegeDept)
                .append("Position", a.position)
                .append("DataNames", null)
                .append("DataCode", a.dataCode)
                .append("ReceiveMail", a.receiveMail)
                .append("ContactPhone", a.contactPhone)
                .append("Contactors", a.contactors)
                .append("ContactMail", a.contactMail)
                .append("Addresses", a.addresses)
                .append("ContactPositon", a.contactPosition)
                .append("ExpireDateTime", a.expireDate == null ? null :
                        Date.from(a.expireDate.atStartOfDay(ZoneId.systemDefault()).toInstant()))
                .append("SendEmail", a.sendEmail)
                .append("SendMessage", a.sendMessage)
                .append("LoginTimes", 8)
                .append("AcceptAggrement", null)
                .append("xuezhi", a.xuezhi)
                .append("ruxueYear", a.ruxueYear)
                .append("endDate", a.endDate)
                .append("xueEnddate", a.xueEnddate);
    }

    static Document buildBackupDocument(Document commonDocument, Integer secondaryId) {
        Document document = new Document(commonDocument);
        document.put("ID", secondaryId);
        document.put("ExpireDate", null);
        return document;
    }

    static Document buildDisplayDocument(Document commonDocument, Integer primaryId) {
        Document document = new Document(commonDocument);
        document.put("ID", primaryId);
        document.put("ExpireDate", null);
        document.remove("Phone");
        return document;
    }

    static Document buildCreateUserLogDocument(
            Document commonDocument,
            Integer primaryId,
            Integer secondaryId,
            String marketInitiator,
            String otherDescription,
            Date createTime) {
        Document userInfo = new Document(commonDocument);
        userInfo.remove("_id");
        userInfo.remove("Password");
        userInfo.put("PrimaryUserId", primaryId);
        userInfo.put("DataHubSysUserId", secondaryId);
        return new Document("UserName", commonDocument.getString("UserName"))
                .append("PrimaryUserId", primaryId)
                .append("DataHubSysUserId", secondaryId)
                .append("MarketInitiator", marketInitiator)
                .append("OtherDescription", otherDescription)
                .append("CreateTime", createTime)
                .append("UserInfo", userInfo);
    }

    private void compensateSql(String userName, boolean primaryCommitted, boolean secondaryCommitted) {
        if (primaryCommitted) {
            deletePrimaryQuietly(userName);
        }
        if (secondaryCommitted) {
            deleteUserQuietly(SYS_SQL_URL, SYS_SQL_USER, SYS_SQL_PASSWORD, userName);
        }
    }

    private void deletePrimaryQuietly(String userName) {
        try (Connection connection = userConnection()) {
            connection.setAutoCommit(false);
            try {
                deleteUser(connection, userName);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM dbo.User_PhoneBinding WHERE UserName = ?")) {
                    statement.setString(1, userName);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw e;
            }
        } catch (SQLException e) {
            log.error("新增账号失败后的主库补偿删除也失败：{}", userName, e);
        }
    }

    private void deleteUserQuietly(String url, String user, String password, String userName) {
        try (Connection connection = SqlUtils.getConnection(url, user, password)) {
            deleteUser(connection, userName);
        } catch (SQLException e) {
            log.error("新增账号失败后的副库补偿删除也失败：{}", userName, e);
        }
    }

    private void deleteUser(Connection connection, String userName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM dbo.User_Data WHERE UserName = ?")) {
            statement.setString(1, userName);
            statement.executeUpdate();
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("数据库事务回滚失败", e);
        }
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NVARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    static String normalizeIpRanges(String input) {
        if (trim(input) == null) {
            return "*";
        }

        String cleanInput = input.replace("\r", "")
                .replace("\n", "")
                .replace("，", ",")
                .replace(" ", "");
        if ("*".equals(cleanInput)) {
            return "*";
        }

        List<String> formatted = new ArrayList<>();
        for (String range : cleanInput.split(",", -1)) {
            if (range.isEmpty()) {
                continue;
            }
            List<String> bounds = new ArrayList<>();
            for (String part : range.split("-", -1)) {
                if (!part.isEmpty()) {
                    bounds.add(part);
                }
            }
            if (bounds.size() != 2) {
                throw new IllegalArgumentException(
                        "IP 范围格式不正确：" + range + "，请使用 x.x.x.x-x.x.x.x 格式");
            }
            long start = parseIpv4(bounds.get(0), range);
            long end = parseIpv4(bounds.get(1), range);
            if (start > end) {
                throw new IllegalArgumentException("IP 范围起始地址不能大于结束地址：" + range);
            }
            if (end - start > 65536L) {
                throw new IllegalArgumentException("IP 范围过大：" + range + "，单个范围不得超过 65536");
            }
            formatted.add(range);
        }
        return String.join(",", formatted);
    }

    private static long parseIpv4(String value, String range) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("IP 地址无效：" + range);
        }
        long result = 0L;
        for (String part : parts) {
            if (!IPV4_PART_PATTERN.matcher(part).matches()) {
                throw new IllegalArgumentException("IP 地址无效：" + range);
            }
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("IP 地址无效：" + range);
            }
            if (number > 255) {
                throw new IllegalArgumentException("IP 地址无效：" + range);
            }
            result = (result << 8) | number;
        }
        return result;
    }

    private LocalDate parseOptionalDate(String value, String label) {
        String text = trim(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + "格式不正确");
        }
    }

    private LocalDateTime parseRequiredDateTime(String value, String label) {
        String text = required(value, label, 30);
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text).atTime(LocalTime.of(23, 59, 59));
            }
            return LocalDateTime.parse(text, SQL_DATE_TIME);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException ignored) {
                throw new IllegalArgumentException(label + "格式不正确");
            }
        }
    }

    private String normalizeDateTime(String value, String label) {
        return parseRequiredDateTime(value, label).format(SQL_DATE_TIME);
    }

    private String required(String value, String label, int maxLength) {
        String text = trim(value);
        if (text == null) {
            throw new IllegalArgumentException("请输入" + label);
        }
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    private String optional(String value, int maxLength, String label) {
        String text = trim(value);
        if (text != null && text.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private Map<String, Object> option(int value, String label) {
        Map<String, Object> option = new HashMap<>();
        option.put("value", value);
        option.put("label", label);
        return option;
    }

    private Connection userConnection() throws SQLException {
        return SqlUtils.getConnection(USER_SQL_URL, USER_SQL_USER, USER_SQL_PASSWORD);
    }

    private Connection sysConnection() throws SQLException {
        return SqlUtils.getConnection(SYS_SQL_URL, SYS_SQL_USER, SYS_SQL_PASSWORD);
    }

    private static String setting(String property, String env, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(env);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    static class SystemOption {
        private final String code;
        private final String name;
        private final String type;
        private final String partition;

        SystemOption(String code, String name, String type, String partition) {
            this.code = code;
            this.name = name;
            this.type = type;
            this.partition = partition;
        }
    }

    private static class ValidatedAccount {
        private String userName;
        private String password;
        private String nickName;
        private String phone;
        private String mail;
        private String institution;
        private String college;
        private String collegeDept;
        private String position;
        private int userType;
        private String dataCode;
        private String receiveMail;
        private String contactors;
        private String contactPhone;
        private String contactMail;
        private String contactPosition;
        private String addresses;
        private String ipRange;
        private String xuezhi;
        private String ruxueYear;
        private String endDate;
        private String xueEnddate;
        private LocalDate expireDate;
        private boolean sendEmail;
        private boolean sendMessage;
        private String marketInitiator;
        private String otherDescription;
        private LocalDateTime createdAt;
        private String info;
    }

    public abstract static class AccountCreationConflictException extends RuntimeException {
        protected AccountCreationConflictException(String message) {
            super(message);
        }
    }

    public static class AccountAlreadyExistsException extends AccountCreationConflictException {
        public AccountAlreadyExistsException(String userName) {
            super("用户名已存在：" + userName);
        }
    }

    public static class PhoneAlreadyBoundException extends AccountCreationConflictException {
        public PhoneAlreadyBoundException(String phone, String userName) {
            super("该手机号已经绑定了账号——" + defaultValue(userName, "未知账号"));
        }
    }

    public static class AccountPhoneAlreadyBoundException extends AccountCreationConflictException {
        public AccountPhoneAlreadyBoundException(String userName, String phone) {
            super("该账号已经绑定了手机号——" + defaultValue(phone, "未知手机号"));
        }
    }

    public static class CollegeAlreadyExistsException extends AccountCreationConflictException {
        public CollegeAlreadyExistsException(String college) {
            super("该学院名称已存在，无需添加：" + college);
        }
    }

    public static class UnbanConflictException extends RuntimeException {
        public UnbanConflictException(String message) {
            super(message);
        }
    }
}
