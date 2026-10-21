package com.logsmanage.system.websocket;

import com.logsmanage.system.utils.IpCityResolver;
import com.logsmanage.system.utils.SqlUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import org.bson.BsonRegularExpression;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
@ServerEndpoint("/websocket/{type}")
public class UserAndLogExportToMongo {

    private static MongoTemplate datahubTemplate;
    private static MongoTemplate datahubSysTemplate;

    @Autowired
    @Qualifier("datahubTemplate")
    public void setDataHubsysTemplate(MongoTemplate datahubTemplate) {
        UserAndLogExportToMongo.datahubTemplate = datahubTemplate;
    }

    @Autowired
    @Qualifier("datahubSysTemplate")
    public void setDataHubSysTemplate(MongoTemplate datahubSysTemplate) {
        UserAndLogExportToMongo.datahubSysTemplate = datahubSysTemplate;
    }

    private static final String USER_SQL_URL = setting(
            "datahub.userSql.url", "DataHub_USER_SQL_URL",
            "jdbc:sqlserver://login.api.example.com:1433;databaseName=cas;responseBuffering=adaptive");
    private static final String USER_SQL_USER = setting(
            "datahub.userSql.user", "DataHub_USER_SQL_USER", "jhadmin");
    private static final String USER_SQL_PASSWORD = setting(
            "datahub.userSql.password", "DataHub_USER_SQL_PASSWORD", "byteloveadmin");

    private static final String LOG_SQL_URL = setting(
            "datahub.logSql.url", "DataHub_LOG_SQL_URL",
            "jdbc:sqlserver://203.0.113.13:1433;databaseName=DataHub_Log;responseBuffering=adaptive");
    private static final String LOG_SQL_USER = setting(
            "datahub.logSql.user", "DataHub_LOG_SQL_USER", "sa");
    private static final String LOG_SQL_PASSWORD = setting(
            "datahub.logSql.password", "DataHub_LOG_SQL_PASSWORD", "ByteLove0713");

    private static final String REGISTER_SQL_URL = setting(
            "datahub.registerSql.url", "DataHub_REGISTER_SQL_URL",
            "jdbc:sqlserver://203.0.113.11:1433;databaseName=DataHub_SYS;responseBuffering=adaptive");
    private static final String REGISTER_SQL_USER = setting(
            "datahub.registerSql.user", "DataHub_REGISTER_SQL_USER", "sa");
    private static final String REGISTER_SQL_PASSWORD = setting(
            "datahub.registerSql.password", "DataHub_REGISTER_SQL_PASSWORD", "change_me");

    private static final String OSS_ENDPOINT = setting(
            "datahub.oss.endpoint", "DataHub_OSS_ENDPOINT", "http://oss-cn-shanghai.aliyuncs.com");
    private static final String OSS_ACCESS_KEY_ID = setting(
            "datahub.oss.accessKeyId", "DataHub_OSS_ACCESS_KEY_ID", "AKIDEXAMPLE000000000");
    private static final String OSS_ACCESS_KEY_SECRET = setting(
            "datahub.oss.accessKeySecret", "DataHub_OSS_ACCESS_KEY_SECRET", "SECRETEXAMPLE0000000000");
    private static final String OSS_BUCKET_NAME = setting(
            "datahub.oss.bucketName", "DataHub_OSS_BUCKET_NAME", "datahub-statics");
    private static final String OSS_PUBLIC_URL = "https://datahub-statics.oss-cn-shanghai.aliyuncs.com/";

    private static final int REGISTER_EXPORT_VERSION = 2;

    private static final String MONGO_URL = setting(
            "datahub.mongo.url", "DEMO_MONGO_URL", "mongodb://127.0.0.1:27017");
    // 139 的 MongoDB（读取旧 register 的 OSS URL、完成时间等）
    private static final String DataHub_URL = setting(
            "datahub.uri", "DataHub_URI", "mongodb://root:change_me@203.0.113.10:27017/?authSource=admin");
    private static final String MONGO_DATABASE = "DataHub_Log";
    private static final String IP_ADR_COLLECTION = "Ip_Adr";

    /**
     * 学校表（Ip_Adr）同步写入前要清理空白的字段。
     *
     * <p>这张表是从 SQL Server 迁过来的，这几列当年是 CHAR 定长类型、尾部用空格补齐，
     * 原样搬进目标库后，谁直连去看都会看到一列带填充空格的学校名。所以写入前先洗一遍，
     * 让库里存的就是干净值——读取侧 {@code SchoolService} 的那套 trim 从此只是双保险。
     *
     * <p>字段名单必须与 {@code SchoolService.WHITESPACE_FIELDS} 逐字一致，口径也得一致
     * （trim + 内部连续空白折成一个空格），改一处要同步改另一处。
     *
     * <p>{@code Ip} 与 {@code jurisdiction} 不在名单内：前者是逗号分隔的 IP 段（实测最长
     * 2286 字符、84 段，前端已逐段 trim），后者是 JSON 原文（最长 12000 字符），
     * 折叠空白会破坏其结构。
     */
    private static final String[] IP_ADR_WHITESPACE_FIELDS = {"Adress", "endDate", "suf", "mangerName"};

    /** 折叠连续空白用。{@code \s} 覆盖半角空格、制表符与换行。 */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final String AGENT_TYPE = "Agent";
    private static final String AGENT_SOURCE_COLLECTION = "gptReplyHistory";
    private static final String AGENT_TARGET_COLLECTION = "GptReplyHistory";

    /**
     * 智能体同步要跳过的账号：{@code userId} 命中就不进目标库。
     *
     * <p>{@code zhengshi} 是公司内部测试账号，它在 gptReplyHistory 里的问答是测试数据。
     * 2026-09-14 实测源集合 902 条里 567 条是它（占六成），用户要求不同步进来；
     * 此前已经同步进去的那批也在当天从目标集合删掉了。
     *
     * <p>字段名只认 {@code userId}：智能体记录的用户名字段就叫这个，不是 User_Data 和两张日志表
     * 用的 {@code UserName}，别照搬。忽略大小写、容忍首尾空格（本项目账号名出现过 -gj / -GJ
     * 两种写法）；用 ^...$ 锚定而不是包含匹配，免得误伤 {@code zhengzhenji} 这类名字相近的真实账号。
     */
    private static final Pattern SKIPPED_AGENT_USER =
            Pattern.compile("^\\s*zhengshi\\s*$", Pattern.CASE_INSENSITIVE);

    private static final int BATCH_SIZE = 200;
    private static final int LOG_EXPORT_VERSION = 2;

    // 中英文版项目（datahub 后端 + DataHub_NEW_WEB 前端）在 213/DataHub.En_Log 写的操作日志，
    // 同步到 181/DataHub_Log 下新建的同名集合。与既有的 NewLog / Log_Login 完全独立：
    // 不合并、不回写，只供日志管理系统在读取侧当作第三个数据源。
    private static final String EN_LOG_TYPE = "En_Log";
    private static final String EN_LOG_SOURCE_COLLECTION = "En_Log";
    private static final String EN_LOG_TARGET_COLLECTION = "En_Log";
    /** En_Log 的导出结构版本，与 NewLog/Log_Login 各自独立：补字段逻辑变更时只重刷这一段。 */
    private static final int EN_LOG_EXPORT_VERSION = 1;

    // 日志表字段数：写回 181 的 NewLog 文档（tableFieldCount）。
    // 不再有独立的「日志表字段数」同步 —— 改在 Log(NewLog) 同步写入时就地查字典补上，
    // 字典源仍是源库 SQL Server(101/DataHub_Log) 的库简称+表名 -> 表字段数。
    private static final String LOG_TABLE_DICT_TABLE = "DataHub数据库表名列表20210513";
    private static final String TABLE_FIELD_COUNT = "tableFieldCount";

    /**
     * 单次同步最多处理多少行，0 = 不限。
     *
     * 日志表全量是百万级、公网拉源库要几小时且无法取消。因为书签每 200 行就推进、
     * 下次从书签接着走，所以「一次只拉 N 行」是合法的部分同步 —— 可以分几次点完，
     * 每次几分钟，而不是一次堵几个小时。
     *
     * 可用 -Ddatahub.sync.maxRows=50000 或环境变量 DataHub_SYNC_MAX_ROWS 设置；
     * 也可以在 WebSocket 消息里带上行数（如 "Log_Login 50000"）临时覆盖。
     */
    private static final long DEFAULT_MAX_ROWS = parseMaxRows(
            setting("datahub.sync.maxRows", "DataHub_SYNC_MAX_ROWS", "0"));

    /** 本次会话生效的行数上限，由 onMessage 解析消息后确定。 */
    private long maxRows = DEFAULT_MAX_ROWS;

    /** 本次是否因为达到行数上限而提前停止（而不是源表真的拉完了）。 */
    private boolean cappedStop = false;

    private static long parseMaxRows(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // 存储所有WebSocket会话
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    // 当前会话的日志输出器
    private WebSocketLogger logger;

    private String type;

    private Long UserDataCount = 0L;

//    private Long LogCount = 0L;
    private Long newLogTotal = 0L;
    private Long logLoginTotal = 0L;
    private Long registerReusedFiles = 0L;
    private Long registerUploadedFiles = 0L;

    private Session session;

    @OnOpen
    public void onOpen(Session session, @PathParam("type") String type) {
        this.session = session;
        this.type = type;
        this.logger = new WebSocketLogger(session);
        SESSIONS.put(session.getId(), session);
        sendMessage(session, "WebSocket连接已建立，准备开始同步任务...");
        System.out.println("WebSocket连接已建立，会话ID: " + session.getId());
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session.getId());
        System.out.println("WebSocket连接已关闭，会话ID: " + session.getId());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        sendMessage(session, "错误: " + error.getMessage());
        error.printStackTrace();
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            // 解析前端发送的消息，支持格式: "all"、"user"、"log"、"all --full" 等
//            String[] parts = message.trim().split("\\s+");
//            String task = parts.length > 0 ? parts[0].toLowerCase() : "all";
//            boolean full = false;
//            for (String part : parts) {
//                if ("--full".equalsIgnoreCase(part)) {
//                    full = true;
//                    break;
//                }
//            }
//
//            if (!"all".equals(task) && !"user".equals(task) && !"log".equals(task)) {
//                sendMessage(session, "参数只能是 all、user、log 或 --full");
//                return;
//            }

            // 重新设置logger（确保使用当前会话）
            this.logger = new WebSocketLogger(session);

            // 消息里可以带行数上限，如 "Log_Login 50000"；不带就用启动参数的默认值。
            // 前端现在发的是 type 本身（不含数字），走默认值，行为不变。
            this.maxRows = DEFAULT_MAX_ROWS;
            if (message != null) {
                String[] parts = message.trim().split("\\s+");
                for (String part : parts) {
                    long parsed = parseMaxRows(part);
                    if (parsed > 0) {
                        this.maxRows = parsed;
                        break;
                    }
                }
            }
            if (maxRows > 0) {
                logMessage("本次最多同步 " + maxRows + " 行，之后可再次同步接着拉");
            }

//            sendMessage(session, "开始执行同步任务: " + task + (full ? " (全量同步)" : " (增量同步)"));

            try (MongoClient mongoClient = MongoClients.create(MONGO_URL)) {
                MongoDatabase targetDatabase = mongoClient.getDatabase(MONGO_DATABASE);

                MongoCollection<Document> syncState = targetDatabase.getCollection("_sync_state");
                MongoCollection<Document> newLogTarget = targetDatabase.getCollection("NewLog");
                MongoCollection<Document> logLoginTarget = targetDatabase.getCollection("Log_Login");

                if ("User_Data".equals(type.trim())) {   //用户表是全量更新
                    exportUserData(targetDatabase.getCollection("User_Data"));
                } else if ("Log".equals(type.trim())) {  // 日志表是增量更新
                    try (IpCityResolver cityResolver = new IpCityResolver()) {
                        getLogCount(syncState, newLogTarget, logLoginTarget);
                        exportNewLog(newLogTarget, syncState, false, cityResolver);
                    }
                } else if ("Log_Login".equals(type.trim())) { //登录日志表增量更新
                    try (IpCityResolver cityResolver = new IpCityResolver()) {
                        // 这里原来没调 getLogCount()，logLoginTotal 恒为 0，
                        // 发给前端的进度永远是 count:0（前端只能退回不确定态、算不出百分比）。
                        getLogCount(syncState, newLogTarget, logLoginTarget);
                        exportLog_Login(logLoginTarget, syncState, false, cityResolver);
                    }

                } else if (IP_ADR_COLLECTION.equals(type.trim())) { // 学校 IP 段表，Mongo -> Mongo 全量对齐
                    exportIpAdr(targetDatabase.getCollection(IP_ADR_COLLECTION));
                } else if ("register".equals(type.trim())) { // 注册信息表，SQL Server -> Mongo，带 OSS 文件上传
                    exportRegister(targetDatabase.getCollection("register"), syncState);
                } else if (AGENT_TYPE.equals(type.trim())) { // 智能体历史：139/DataHub_SYS Mongo -> 181/DataHub_Log
                    exportAgentHistory(targetDatabase.getCollection(AGENT_TARGET_COLLECTION), syncState);
                } else if (EN_LOG_TYPE.equals(type.trim())) { // 中英文版日志：213/DataHub.En_Log -> 181/DataHub_Log.En_Log
                    try (IpCityResolver cityResolver = new IpCityResolver()) {
                        exportEnLog(targetDatabase.getCollection(EN_LOG_TARGET_COLLECTION), syncState,
                                targetDatabase.getCollection("User_Data"), cityResolver);
                    }
                } else {
                    throw new IllegalArgumentException("不支持的同步类型: " + type);
                }

            }
            // 前端靠「所有同步任务执行完毕」判成功终态，这句话必须保留原样。
            //
            // 封顶停下时先发一帧结构化标记：自由文本会被紧随其后的终态覆盖，
            // 而前端要在终态里显示「未拉完」，得有个能可靠读到的字段。
            // 用 JSON 而不是约定某句话，免得将来改文案就失效。
            if (maxRows > 0 && cappedStop) {
                sendMessage(session, "{\"capped\": true, \"limit\": " + maxRows + "}");
                logMessage("已达本次行数上限 " + maxRows + " 行，剩余部分再次同步即可接着拉");
            }
            sendMessage(session, "✅ 所有同步任务执行完毕！");
        } catch (Exception e) {
            sendMessage(session, "❌ 同步任务执行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 向前端发送消息
     */
    private void sendMessage(Session session, String message) {
        try {
            if (session != null && session.isOpen()) {
                session.getBasicRemote().sendText(message);
            }
        } catch (IOException e) {
            System.err.println("发送WebSocket消息失败: " + e.getMessage());
        }
    }

    /**
     * 向所有连接的客户端广播消息
     */
    public static void broadcastMessage(String message) {
        for (Session session : SESSIONS.values()) {
            try {
                if (session.isOpen()) {
                    session.getBasicRemote().sendText(message);
                }
            } catch (IOException e) {
                System.err.println("广播消息失败: " + e.getMessage());
            }
        }
    }

    private void exportUserData(MongoCollection<Document> target) throws SQLException {
//        logMessage("开始同步 cas.dbo.User_Data -> DataHub_Log.User_Data");
        long total = 0;
        try (Connection connection = SqlUtils.getConnection(USER_SQL_URL, USER_SQL_USER, USER_SQL_PASSWORD)) {
            try (PreparedStatement countStmt = connection.prepareStatement("SELECT COUNT(*) FROM dbo.User_Data");
                 ResultSet countRs = countStmt.executeQuery()) {
                if (countRs.next()) {
                    UserDataCount = countRs.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM dbo.User_Data");
                 ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<WriteModel<Document>> writes = new ArrayList<>(BATCH_SIZE);
                while (resultSet.next()) {
                    Document document = readDocument(resultSet, metaData);
                    // 手机号属于敏感信息，不导出到目标 MongoDB。
                    document.remove("Phone");
                    String userName = document.getString("UserName");
                    if (userName == null || userName.isEmpty()) {
                        throw new SQLException("User_Data 存在空 UserName，无法生成 MongoDB _id");
                    }
                    document.put("_id", userName);
                    writes.add(accountUpsert(document));
                    total++;
                    if (writes.size() >= BATCH_SIZE) {
                        writeBatch(target, writes);
                        sendMessage(session, "{\"done\": " + total + ", \"count\": " + UserDataCount + "}");
                    }
                }
                if (!writes.isEmpty()) {
                    writeBatch(target, writes);
                    sendMessage(session, "{\"done\": " + total + ", \"count\": " + UserDataCount + "}");
                }
            }
        }
        //记录完成时间
        Document document = new Document();
        document.append("updateTime", LocalDateTime.now());
        document.append("type", "User_Data");
        datahubTemplate.insert(document, "User_Data_UpdateTime");
//        logMessage("✅ User_Data 同步完成，共处理 " + total + " 条");
    }

    /**
     * 同步 139/DataHub.Ip_Adr 到 181/DataHub_Log.Ip_Adr。
     *
     * 源端是 datahubTemplate 指向的公网 DataHub 库，目标端是同步程序当前打开的主库。
     * 这张表要全量对齐：源端有的 upsert，源端没有而目标端残留的删除。
     */
    private void exportIpAdr(MongoCollection<Document> target) {
        if (datahubTemplate == null) {
            throw new IllegalStateException("datahubTemplate 未注入，无法读取 Ip_Adr 源集合");
        }
        MongoCollection<Document> source = datahubTemplate.getCollection(IP_ADR_COLLECTION);
        long sourceTotal = source.countDocuments();
        long done = 0;

        Set<Object> sourceIds = new HashSet<>();
        List<WriteModel<Document>> writes = new ArrayList<>(BATCH_SIZE);
        try (com.mongodb.client.MongoCursor<Document> cursor = source.find()
                .noCursorTimeout(true)
                .batchSize(BATCH_SIZE)
                .iterator()) {
            while (cursor.hasNext()) {
                Document document = new Document(cursor.next());
                Object id = document.get("_id");
                if (id == null) {
                    throw new IllegalStateException("Ip_Adr 源集合存在空 _id，无法同步");
                }
                sourceIds.add(id);
                // 先洗掉源表 CHAR 列的填充空格，base 由洗过的 mangerName 派生（清洗不影响 contains 结果）
                cleanIpAdrWhitespace(document);
                String mangerName = document.getString("mangerName");
                document.put("base", mangerName != null && mangerName.contains("基础库"));
                writes.add(replaceUpsert(document));
                done++;
                if (writes.size() >= BATCH_SIZE) {
                    writeBatch(target, writes);
                    sendMessage(session, "{\"done\": " + done + ", \"count\": " + sourceTotal + "}");
                }
            }
        }
        if (!writes.isEmpty()) {
            writeBatch(target, writes);
            sendMessage(session, "{\"done\": " + done + ", \"count\": " + sourceTotal + "}");
        }

        List<WriteModel<Document>> deletes = new ArrayList<>(BATCH_SIZE);
        try (com.mongodb.client.MongoCursor<Document> cursor = target.find()
                .projection(new Document("_id", 1))
                .noCursorTimeout(true)
                .batchSize(BATCH_SIZE)
                .iterator()) {
            while (cursor.hasNext()) {
                Object id = cursor.next().get("_id");
                if (!sourceIds.contains(id)) {
                    deletes.add(new DeleteOneModel<Document>(new Document("_id", id)));
                }
                done++;
                if (deletes.size() >= BATCH_SIZE) {
                    writeBatch(target, deletes);
                    sendMessage(session, "{\"done\": " + done + ", \"count\": " + sourceTotal + "}");
                }
            }
        }
        if (!deletes.isEmpty()) {
            writeBatch(target, deletes);
        }
        sendMessage(session, "{\"done\": " + sourceTotal + ", \"count\": " + sourceTotal + "}");

        Document record = new Document();
        record.append("updateTime", LocalDateTime.now());
        record.append("type", IP_ADR_COLLECTION);
        datahubTemplate.insert(record, "User_Data_UpdateTime");
    }



    private void exportNewLog(MongoCollection<Document> target,
                              MongoCollection<Document> syncState,
                              boolean full,
                              IpCityResolver cityResolver) throws SQLException {
        int lastId = full ? 0 : readLastLogId(syncState, target);
        long total = 0;
        // 日志表字段数随 Log 同步一并补：导入行时就地查字典写 tableFieldCount。
        // 独立「日志表字段数」同步已移除；整文档 replace 自带该字段，version 全量重刷也不丢。
        Map<String, Integer> fieldDict = loadTableFieldDict();
//        logMessage("开始同步 DataHub_Log.dbo.NewLog -> DataHub_Log.NewLog，起始 ID > " + lastId);

        // 同上：上限下推到 SQL，避免服务端为剩余行做无用准备（见 exportLog_Login 的注释）
        boolean capped = maxRows > 0;
        String sql = capped
                ? "SELECT TOP (?) * FROM dbo.NewLog WHERE ID > ? ORDER BY ID"
                : "SELECT * FROM dbo.NewLog WHERE ID > ? ORDER BY ID";
        try (Connection connection = SqlUtils.getConnection(LOG_SQL_URL, LOG_SQL_USER, LOG_SQL_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(
                     sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            if (capped) {
                statement.setLong(1, maxRows);
                statement.setInt(2, lastId);
            } else {
                statement.setInt(1, lastId);
            }
            statement.setFetchSize(BATCH_SIZE);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<WriteModel<Document>> writes = new ArrayList<>(BATCH_SIZE);
                int batchLastId = lastId;
                while (resultSet.next()) {
                    Document document = readDocument(resultSet, metaData);
                    // 日志中的手机号同样不写入目标 MongoDB。
                    document.remove("Phone");
                    document.put("O_city", cityResolver.resolve(document.getString("O_srcIP")));
                    Integer id = document.getInteger("ID");
                    if (id == null) {
                        throw new SQLException("NewLog 存在空 ID，无法生成 MongoDB _id");
                    }
                    document.put("_id", id);
                    // 当场补字段数；字典查不到的（O_system/O_table 为空或不在字典）不 put，
                    // 与既有语义一致——该日志文档就不带 tableFieldCount。
                    Integer fieldCount = fieldDict.get(dictKey(
                            trimToNull(document.getString("O_system")),
                            trimToNull(document.getString("O_table"))));
                    if (fieldCount != null) {
                        document.put(TABLE_FIELD_COUNT, fieldCount);
                    }
                    writes.add(replaceUpsert(document));
                    batchLastId = id;
                    total++;
                    if (writes.size() >= BATCH_SIZE) {
                        writeBatch(target, writes);
                        saveLastLogId(syncState, batchLastId);
                        sendMessage(session, "{\"done\": " + total + ", \"count\": " + progressTotal(newLogTotal) + "}");
                        logProgress("NewLog", total + "");
                    }
                    // 达到上限就停：书签已推进到 batchLastId，下次从这里接着拉
                    if (maxRows > 0 && total >= maxRows) {
                        cappedStop = true;
                        break;
                    }
                }
                if (!writes.isEmpty()) {
                    writeBatch(target, writes);
                    saveLastLogId(syncState, batchLastId);
                    sendMessage(session, "{\"done\": " + total + ", \"count\": " + progressTotal(newLogTotal) + "}");
                }
            }
        }
        //记录完成时间
        Document document = new Document();
        document.append("updateTime", LocalDateTime.now());
        document.append("type", "Log");
        datahubTemplate.insert(document, "User_Data_UpdateTime");
//        logMessage("✅ NewLog 同步完成，本次共处理 " + total + " 条");
    }

    private  void exportLog_Login(MongoCollection<Document> target,
                                        MongoCollection<Document> syncState,
                                        boolean full,
                                        IpCityResolver cityResolver) throws SQLException {
        int lastId = full ? 0 : readLogLog_Login(syncState, target);  //读取上次同步进度
        long total = 0;
//        System.out.println("开始同步 DataHub_Log.dbo.Log_Login -> DataHub_Log.NewLog，起始 ID > " + lastId);

        // 上限要下推到 SQL 里，不能只在客户端 break：
        // 不带 TOP 的话服务端游标是冲着全表去的，客户端数到 N 就停，
        // 剩下的行仍挂在服务端，关 ResultSet/连接时要收尾 —— 实测封顶 5 万，
        // 数据 30 秒就写完了，却在关连接上又耗了 2 分半（剩 183 万行）。
        // TOP 用占位符绑定，不拼字符串。
        boolean capped = maxRows > 0;
        String sql = capped
                ? "SELECT TOP (?) * FROM dbo.Log_Login WHERE id > ? ORDER BY id"
                : "SELECT * FROM dbo.Log_Login WHERE id > ? ORDER BY id";
        try (Connection connection = SqlUtils.getConnection(LOG_SQL_URL, LOG_SQL_USER, LOG_SQL_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(
                     sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            if (capped) {
                statement.setLong(1, maxRows);
                statement.setInt(2, lastId);
            } else {
                statement.setInt(1, lastId);
            }
            statement.setFetchSize(BATCH_SIZE);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<WriteModel<Document>> writes = new ArrayList<>(BATCH_SIZE);
                int batchLastId = lastId;
                while (resultSet.next()) {
                    Document document = readLog_LoginDocument(resultSet, metaData);
                    // 日志中的手机号同样不写入目标 MongoDB。
                    document.remove("Phone");
                    document.put("O_city", cityResolver.resolve(document.getString("O_srcIP")));
                    Integer id = document.getInteger("ID");
                    if (id == null) {
                        throw new SQLException("Log_Login 存在空 id，无法生成 MongoDB _id");
                    }
                    document.put("_id", id);
                    writes.add(replaceUpsert(document));
                    batchLastId = id;
                    total++;
                    if (writes.size() >= BATCH_SIZE) {
                        writeBatch(target, writes);
                        sendMessage(session, "{\"done\": " + total   + ", \"count\": "  + progressTotal(logLoginTotal) + "}");
                        saveLog_LoginLastLogId(syncState, batchLastId);
                        logProgress("Log_Login", total + "");
                    }
                    // 达到上限就停：书签已推进到 batchLastId，下次从这里接着拉
                    if (maxRows > 0 && total >= maxRows) {
                        cappedStop = true;
                        break;
                    }
                }
                if (!writes.isEmpty()) {
                    writeBatch(target, writes);
                    sendMessage(session, "{\"done\": " + total  + ", \"count\": "  + progressTotal(logLoginTotal) + "}");
                    saveLog_LoginLastLogId(syncState, batchLastId);
                }
            }
        }
        Document document = new Document();
        document.append("updateTime", LocalDateTime.now());
        document.append("type", "Log_Login");
        datahubTemplate.insert(document, "User_Data_UpdateTime");
//        System.out.println("Log_Login 同步完成，本次共处理 " + total + " 条");
    }


    private Document readDocument(ResultSet resultSet, ResultSetMetaData metaData)
            throws SQLException {
        Document document = new Document();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            document.put(metaData.getColumnLabel(i), toBsonValue(resultSet.getObject(i)));
        }
        return document;
    }

    private Object toBsonValue(Object value) {
        if (value instanceof Timestamp) {
            return new Date(((Timestamp) value).getTime());
        }
        if (value instanceof java.sql.Date) {
            return new Date(((java.sql.Date) value).getTime());
        }
        if (value instanceof java.sql.Time) {
            return new Date(((java.sql.Time) value).getTime());
        }
        if (value instanceof BigDecimal) {
            return new Decimal128((BigDecimal) value);
        }
        if (value instanceof byte[]) {
            return new Binary((byte[]) value);
        }
        return value;
    }

    private ReplaceOneModel<Document> replaceUpsert(Document document) {
        return new ReplaceOneModel<>(new Document("_id", document.get("_id")), document,
                new ReplaceOptions().upsert(true));
    }

    /**
     * 账号表同步要保留目标库里人工维护的字段（目前是 piliang）。
     *
     * 源 SQL Server 没有这些字段，用整文档 replace 会把业务方在目标 Mongo 上补的人工审核结果抹掉；
     * 这里改成 $set 源字段，并显式不 $unset 目标端额外字段。
     */
    private UpdateOneModel<Document> accountUpsert(Document document) {
        return new UpdateOneModel<>(new Document("_id", document.get("_id")),
                new Document("$set", document), new UpdateOptions().upsert(true));
    }

    /**
     * 加载字段数字典：(库简称, 表名) -> 表字段数。
     *
     * <p>供 {@link #exportNewLog} 在 Log 同步写入时就地补 tableFieldCount。
     * 字典是小表（每张数据表一行），整读进内存、逐日志 O(1) 查，不逐行连源库。
     */
    private Map<String, Integer> loadTableFieldDict() throws SQLException {
        Map<String, Integer> dict = new HashMap<>();
        try (Connection connection = SqlUtils.getConnection(LOG_SQL_URL, LOG_SQL_USER, LOG_SQL_PASSWORD);
             PreparedStatement stmt = connection.prepareStatement(
                     "SELECT [数据库简称], [表名], [表字段数] FROM dbo.[" + LOG_TABLE_DICT_TABLE + "]");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String sys = trimToNull(rs.getString(1));
                String tbl = trimToNull(rs.getString(2));
                Integer fieldCount = toIntOrNull(rs.getObject(3));
                if (sys == null || tbl == null || fieldCount == null) {
                    continue;
                }
                dict.put(dictKey(sys, tbl), fieldCount);
            }
        }
        return dict;
    }

    /** 字典 Map 的组合键。库简称与表名用控制字符分隔，二者不会含它，不会撞键。 */
    private static String dictKey(String system, String table) {
        return system + "\u0001" + table;
    }

    /** SQL Server CHAR 字段可能带填充空格，去掉后再比对，否则 Map 查不到。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 清洗学校表文档里 {@link #IP_ADR_WHITESPACE_FIELDS} 那几个字段的填充空白，就地改文档。
     *
     * <p>只处理字符串值：这张表的字段类型不统一（同一字段可能是字符串/数字/布尔/日期），
     * 非字符串原样留着，强转会炸。字段缺失时 {@code get} 返回 null，不报错也不凭空补字段。
     *
     * <p><b>不能用 {@link #trimToNull}</b>：它会把空串变成 null，等于改变「字段是空串还是
     * 不存在」的语义，前端 {@code text()} 对两者的显示不同。空串洗过仍是空串。
     *
     * <p>改的是调用方传进来的文档对象（同步侧是游标结果的副本），源库不受影响。
     * 包可见是为了让测试能直接调生产实现，而不是另写一份条件。
     */
    static void cleanIpAdrWhitespace(Document document) {
        for (String field : IP_ADR_WHITESPACE_FIELDS) {
            Object value = document.get(field);
            if (value instanceof String) {
                document.put(field, WHITESPACE_RUN.matcher(((String) value).trim()).replaceAll(" "));
            }
        }
    }

    /** 表字段数兼容数字/数字字符串两种形态（没验证过源库该列的确切类型）。 */
    private static Integer toIntOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writeBatch(MongoCollection<Document> target,
                            List<WriteModel<Document>> writes) {
        if (writes.isEmpty()) {
            return;
        }
        target.bulkWrite(writes, new BulkWriteOptions().ordered(false));
        writes.clear();
    }

    /**
     * 智能体同步的取数条件：断点之后（{@code lastId} 为 null 则全量）+ 排除要跳过的账号。
     *
     * <p><b>跳过是下推到查询条件里做的，不是在游标循环里 continue。</b>这样 {@code total} 和
     * {@code done} 都是真实的待同步条数，进度分母和终态那句「已完成，同步 N 条」不会把跳过的算进去
     * （循环里跳过的写法会让 902 条里只有 335 条真正落库、却报 902）。因为过滤条件每次同步都带上，
     * 断点在这些行之间推进也不会让被跳过的行漏检或重复拉。
     *
     * <p>包级可见是为了让 AgentSyncFilterTest 直接断言真实条件，而不是在测试里复刻一份。
     */
    static Document agentPendingFilter(Object lastId) {
        Document filter = lastId == null
                ? new Document()
                : new Document("_id", new Document("$gt", lastId));
        // {userId: {$not: {$regex: ..., $options: ...}}}：$not 配正则，字段缺失时正则不匹配、
        // $not 为真，即「没有 userId 的文档照常同步」。这条形状在 181 上只读验过：902 条剩下 335 条。
        return filter.append("userId", new Document("$not", new BsonRegularExpression(
                SKIPPED_AGENT_USER.pattern(), regexOptions(SKIPPED_AGENT_USER))));
    }

    /**
     * 把 {@link Pattern} 的旗标折算成 MongoDB 正则的 options 串。
     *
     * <p>这个驱动的 {@code BsonRegularExpression} 只有 {@code (String)} / {@code (String, String)}
     * 两个构造器，不吃 {@link Pattern}，所以选项串只能自己算。折算而不是给查询条件写死 "i"，
     * 是为了让 {@link #SKIPPED_AGENT_USER} 保持唯一判据来源 —— 改常量的旗标不用记得同步改条件。
     */
    private static String regexOptions(Pattern pattern) {
        int flags = pattern.flags();
        StringBuilder options = new StringBuilder();
        if ((flags & Pattern.CASE_INSENSITIVE) != 0) {
            options.append('i');
        }
        if ((flags & Pattern.MULTILINE) != 0) {
            options.append('m');
        }
        if ((flags & Pattern.DOTALL) != 0) {
            options.append('s');
        }
        if ((flags & Pattern.COMMENTS) != 0) {
            options.append('x');
        }
        return options.toString();
    }

    /**
     * 同步 139/DataHub_SYS.gptReplyHistory 到 181/DataHub_Log.GptReplyHistory。
     *
     * 智能体记录使用 ObjectId 作为天然唯一键和增量游标，整文档复制，避免对未知业务字段
     * 做错误的字段映射。批量写入成功后才推进断点，重试时由 upsert 保证幂等。
     */
    private void exportAgentHistory(MongoCollection<Document> target,
                                    MongoCollection<Document> syncState) {
        if (datahubSysTemplate == null) {
            throw new IllegalStateException("datahubSysTemplate 未注入，无法读取智能体源集合");
        }
        MongoCollection<Document> source = datahubSysTemplate.getCollection(AGENT_SOURCE_COLLECTION);
        Document state = syncState.find(new Document("_id", AGENT_TARGET_COLLECTION)).first();
        Object lastId = state == null ? null : state.get("lastId");
        int version = state != null && state.get("version") instanceof Number
                ? ((Number) state.get("version")).intValue() : 0;
        if (version != LOG_EXPORT_VERSION) {
            lastId = null;
        }

        Document pending = agentPendingFilter(lastId);
        long total = source.countDocuments(pending);
        long done = 0;
        Object batchLastId = lastId;
        List<WriteModel<Document>> writes = new ArrayList<>(BATCH_SIZE);

        try (com.mongodb.client.MongoCursor<Document> cursor = source.find(pending)
                .sort(new Document("_id", 1))
                .noCursorTimeout(true)
                .batchSize(BATCH_SIZE)
                .iterator()) {
            while (cursor.hasNext()) {
                Document document = new Document(cursor.next());
                Object id = document.get("_id");
                if (id == null) {
                    throw new IllegalStateException("gptReplyHistory 源集合存在空 _id，无法同步");
                }
                writes.add(replaceUpsert(document));
                batchLastId = id;
                done++;

                boolean capped = maxRows > 0 && done >= maxRows;
                if (writes.size() >= BATCH_SIZE || capped) {
                    writeBatch(target, writes);
                    saveAgentLastId(syncState, batchLastId);
                    sendMessage(session, "{\"done\": " + done + ", \"count\": " + progressTotal(total) + "}");
                }
                if (capped) {
                    cappedStop = cursor.hasNext();
                    break;
                }
            }
        }
        if (!writes.isEmpty()) {
            writeBatch(target, writes);
            saveAgentLastId(syncState, batchLastId);
            sendMessage(session, "{\"done\": " + done + ", \"count\": " + progressTotal(total) + "}");
        }

        Document updateTime = new Document("updateTime", LocalDateTime.now()).append("type", AGENT_TYPE);
        datahubTemplate.insert(updateTime, "User_Data_UpdateTime");
    }

    private void saveAgentLastId(MongoCollection<Document> syncState, Object lastId) {
        Document values = new Document("lastId", lastId)
                .append("version", LOG_EXPORT_VERSION)
                .append("updatedAt", java.util.Date.from(Instant.now()))
                .append("source", "DataHub_SYS." + AGENT_SOURCE_COLLECTION)
                .append("target", MONGO_DATABASE + "." + AGENT_TARGET_COLLECTION);
        syncState.updateOne(new Document("_id", AGENT_TARGET_COLLECTION), new Document("$set", values),
                new UpdateOptions().upsert(true));
    }

    /**
     * 同步 213/DataHub.En_Log 到 181/DataHub_Log.En_Log。
     *
     * <p>搬的是中英文版项目（datahub 后端 + DataHub_NEW_WEB 前端）写在 213 的操作日志。
     * 目标是 181 上新建的独立集合，既有的 NewLog / Log_Login 一个字节都不动。
     *
     * <p>源 {@code _id} 是 ObjectId，天然单调，直接当增量游标（与智能体那张表同款）。
     * <b>不能套用 {@link #alignWithTarget}</b> —— 它按 Number 比较 {@code _id}，
     * ObjectId 取不到 max，会静默失效。
     *
     * <p>落地前就地补齐 NewLog 口径的展示字段：机构/昵称/邮箱取自 181 现成的 User_Data，
     * 城市走本地 IP 库，表字段数查字典（查不到就跳过）。不补的话新表在日志列表里
     * 机构恒空，按机构筛的学校日志也永远匹配不到它。
     */
    private void exportEnLog(MongoCollection<Document> target,
                             MongoCollection<Document> syncState,
                             MongoCollection<Document> userData,
                             IpCityResolver cityResolver) {
        if (datahubTemplate == null) {
            throw new IllegalStateException("datahubTemplate 未注入，无法读取 213 的 En_Log 源集合");
        }
        MongoCollection<Document> source = datahubTemplate.getCollection(EN_LOG_SOURCE_COLLECTION);
        Document state = syncState.find(new Document("_id", EN_LOG_TARGET_COLLECTION)).first();
        Object lastId = state == null ? null : state.get("lastId");
        int version = state != null && state.get("version") instanceof Number
                ? ((Number) state.get("version")).intValue() : 0;
        if (version != EN_LOG_EXPORT_VERSION) {
            lastId = null;
        }

        Document pending = lastId == null ? new Document() : new Document("_id", new Document("$gt", lastId));
        long total = source.countDocuments(pending);
        long done = 0;
        Object batchLastId = lastId;
        List<WriteModel<Document>> writes = new ArrayList<>(BATCH_SIZE);
        List<Document> batch = new ArrayList<>(BATCH_SIZE);
        // 字典来自源库 SQL Server，连不上只是少一列，不该让整个同步失败。
        Map<String, Integer> fieldDict = loadTableFieldDictQuietly();

        try (com.mongodb.client.MongoCursor<Document> cursor = source.find(pending)
                .sort(new Document("_id", 1))
                .noCursorTimeout(true)
                .batchSize(BATCH_SIZE)
                .iterator()) {
            while (cursor.hasNext()) {
                Document document = new Document(cursor.next());
                Object id = document.get("_id");
                if (id == null) {
                    throw new IllegalStateException("En_Log 源集合存在空 _id，无法同步");
                }
                batch.add(document);
                batchLastId = id;
                done++;

                boolean capped = maxRows > 0 && done >= maxRows;
                if (batch.size() >= BATCH_SIZE || capped) {
                    enrichEnLogBatch(batch, userData, cityResolver, fieldDict);
                    for (Document enriched : batch) {
                        writes.add(replaceUpsert(enriched));
                    }
                    batch.clear();
                    writeBatch(target, writes);
                    saveEnLogLastId(syncState, batchLastId);
                    sendMessage(session, "{\"done\": " + done + ", \"count\": " + progressTotal(total) + "}");
                    logProgress("En_Log", done + "");
                }
                if (capped) {
                    cappedStop = cursor.hasNext();
                    break;
                }
            }
        }
        if (!batch.isEmpty()) {
            enrichEnLogBatch(batch, userData, cityResolver, fieldDict);
            for (Document enriched : batch) {
                writes.add(replaceUpsert(enriched));
            }
            writeBatch(target, writes);
            saveEnLogLastId(syncState, batchLastId);
            sendMessage(session, "{\"done\": " + done + ", \"count\": " + progressTotal(total) + "}");
        }

        Document updateTime = new Document("updateTime", LocalDateTime.now()).append("type", EN_LOG_TYPE);
        datahubTemplate.insert(updateTime, "User_Data_UpdateTime");
    }

    /**
     * 给一批 En_Log 文档补展示字段。
     *
     * <p>机构/昵称/邮箱按 UserName 一次性 {@code $in} 查 181 的 User_Data（该集合以
     * UserName 作 {@code _id}），不逐行查库。查不到的用户就不补，留空由前端显示「—」。
     */
    private void enrichEnLogBatch(List<Document> batch,
                                  MongoCollection<Document> userData,
                                  IpCityResolver cityResolver,
                                  Map<String, Integer> fieldDict) {
        Set<String> userNames = new HashSet<>();
        for (Document document : batch) {
            String userName = trimToNull(document.getString("UserName"));
            if (userName != null) {
                userNames.add(userName);
            }
        }
        Map<String, Document> profiles = new HashMap<>();
        if (!userNames.isEmpty()) {
            for (Document profile : userData
                    .find(new Document("_id", new Document("$in", userNames)))
                    .projection(new Document("NickName", 1)
                            .append("Institution", 1)
                            .append("Mail", 1))) {
                profiles.put(profile.getString("_id"), profile);
            }
        }
        for (Document document : batch) {
            String userName = trimToNull(document.getString("UserName"));
            Document profile = userName == null ? null : profiles.get(userName);
            if (profile != null) {
                document.put("NickName", profile.get("NickName"));
                document.put("Institution", profile.get("Institution"));
                document.put("Mail", profile.get("Mail"));
            }
            document.put("O_city", cityResolver.resolve(document.getString("O_srcIP")));
            Integer fieldCount = fieldDict.get(dictKey(
                    trimToNull(document.getString("O_system")),
                    trimToNull(document.getString("O_table"))));
            if (fieldCount != null) {
                document.put(TABLE_FIELD_COUNT, fieldCount);
            }
        }
    }

    /** 字段数字典读失败只降级（不补 tableFieldCount），不让 Mongo→Mongo 同步被源库连通性拖垮。 */
    private Map<String, Integer> loadTableFieldDictQuietly() {
        try {
            return loadTableFieldDict();
        } catch (Exception e) {
            logMessage("表字段数字典读取失败，本次同步不补 tableFieldCount：" + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private void saveEnLogLastId(MongoCollection<Document> syncState, Object lastId) {
        Document values = new Document("lastId", lastId)
                .append("version", EN_LOG_EXPORT_VERSION)
                .append("updatedAt", java.util.Date.from(Instant.now()))
                .append("source", "DataHub." + EN_LOG_SOURCE_COLLECTION)
                .append("target", MONGO_DATABASE + "." + EN_LOG_TARGET_COLLECTION);
        syncState.updateOne(new Document("_id", EN_LOG_TARGET_COLLECTION), new Document("$set", values),
                new UpdateOptions().upsert(true));
    }

    private int readLastLogId(MongoCollection<Document> syncState,
                              MongoCollection<Document> target) {
        Document state = syncState.find(new Document("_id", "NewLog")).first();
        if (state == null
                || !(state.get("lastId") instanceof Number)
                || !(state.get("version") instanceof Number)
                || ((Number) state.get("version")).intValue() != LOG_EXPORT_VERSION) {
            logMessage("NewLog 导出结构已更新，将从头 upsert 以补齐 O_city 并移除 Phone");
            return 0;
        }
        int bookmark = ((Number) state.get("lastId")).intValue();
        return alignWithTarget("NewLog", bookmark, target);
    }

    /**
     * 断点自愈：书签落后于目标集合实际内容时，用集合里的 max(_id) 当起点。
     *
     * 实测遇到过书签 5,634,018 而集合已到 22,505,146 的情况（中间 1687 万行都在，
     * 且都带 O_city，是当前版本写进去的）。照书签重拉是同 _id 覆盖、数据不变，
     * 但公网拉源库是几天量级 —— 纯粹白跑。
     *
     * 只在 version 匹配时才抬高起点：version 不匹配意味着导出结构变了、
     * 需要整表重刷补字段，那条语义必须保留（见上面的 version 检查）。
     *
     * 空集合返回书签本身（通常是 0），也就是全量拉 —— Log_Login 就是这种情况。
     */
    private int alignWithTarget(String name, int bookmark, MongoCollection<Document> target) {
        Document newest = target.find()
                .sort(new Document("_id", -1))
                .projection(new Document("_id", 1))
                .limit(1)
                .first();
        if (newest == null || !(newest.get("_id") instanceof Number)) {
            return bookmark;
        }
        int maxExisting = ((Number) newest.get("_id")).intValue();
        if (maxExisting <= bookmark) {
            return bookmark;
        }
        logMessage(name + " 断点书签(" + bookmark + ")落后于集合实际进度(" + maxExisting
                + ")，跳过已同步的 " + (maxExisting - bookmark) + " 行");
        return maxExisting;
    }

    private void saveLastLogId(MongoCollection<Document> syncState, int lastId) {
        Document values = new Document("lastId", lastId)
                .append("version", LOG_EXPORT_VERSION)
                .append("updatedAt", Date.from(Instant.now()));
        syncState.updateOne(new Document("_id", "NewLog"), new Document("$set", values),
                new UpdateOptions().upsert(true));
    }

    /**
     * 发给前端算百分比的分母。设了行数上限时要按上限报，
     * 否则进度会朝着「待同步总数」爬（比如封顶 5 万却显示 /188 万），看着像卡住。
     */
    private long progressTotal(long pending) {
        if (maxRows > 0 && (pending <= 0 || maxRows < pending)) {
            return maxRows;
        }
        return pending;
    }

    private void logProgress(String name, String total) {
        // 原来发的是裸 total（笔误），前端只好兼容「纯数字」这种帧。
        // 现在发完整文案，前端的数字兼容分支留着兜旧行为即可。
        logMessage(name + " 已处理 " + total + " 条");
    }

    private void logMessage(String message) {
        if (logger != null) {
            logger.log(message);
        } else {
            System.out.println(message);
        }
    }

    /**
     * 只读探测：源库两张日志表的最大 id 与「按给定起点还剩多少行」。
     *
     * 不写任何库，纯 SELECT。用途是在真正触发同步（会写生产库、且无法取消）之前
     * 先量清这次要拉多少行 —— 全量与增量差着几个数量级。
     */
    public static Map<String, Object> probeSource(int newLogFrom, int logLoginFrom) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection connection = SqlUtils.getConnection(LOG_SQL_URL, LOG_SQL_USER, LOG_SQL_PASSWORD)) {
            // 两张表分别 try：NewLog 的 COUNT(*) 在 2250 万行上时常超时，
            // 不能让它拖着 Log_Login 的结果一起拿不到。
            for (Object[] spec : new Object[][]{
                    {"NewLog", "SELECT COUNT(*) FROM dbo.NewLog WHERE ID > ?",
                            "SELECT MAX(ID) FROM dbo.NewLog", newLogFrom},
                    {"Log_Login", "SELECT COUNT(*) FROM dbo.Log_Login WHERE id > ?",
                            "SELECT MAX(id) FROM dbo.Log_Login", logLoginFrom}}) {
                try {
                    result.put((String) spec[0], probeTable(connection, (String) spec[1],
                            (String) spec[2], (Integer) spec[3]));
                } catch (SQLException e) {
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("error", e.getMessage());
                    result.put((String) spec[0], failed);
                }
            }
        }
        return result;
    }

    /**
     * 只读探测源表列名与类型。
     *
     * exportLog_Login 里假定了两件事：有 O_srcIP 列（用来算城市）、id 能当 int 取。
     * Log_Login 至今没真跑过（目标集合 0 条），这两条都没验证，所以先看真实结构。
     */
    public static Map<String, Object> probeSchema() throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection connection = SqlUtils.getConnection(LOG_SQL_URL, LOG_SQL_USER, LOG_SQL_PASSWORD)) {
            result.put("Log_Login", columnsOf(connection, "dbo.Log_Login"));
            result.put("NewLog", columnsOf(connection, "dbo.NewLog"));
        }
        return result;
    }

    private static Map<String, String> columnsOf(Connection connection, String table) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        // WHERE 1=0 只取元数据，不读任何行
        try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM " + table + " WHERE 1=0");
             ResultSet rs = stmt.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                columns.put(md.getColumnLabel(i),
                        md.getColumnTypeName(i) + "/" + md.getColumnClassName(i));
            }
        }
        return columns;
    }

    private static Map<String, Object> probeTable(Connection connection, String countSql,
                                                  String maxSql, int from) throws SQLException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("from", from);
        try (PreparedStatement stmt = connection.prepareStatement(countSql)) {
            stmt.setInt(1, from);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) info.put("pending", rs.getLong(1));
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement(maxSql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) info.put("sourceMaxId", rs.getLong(1));
        }
        return info;
    }

    /**
     * 只读探测：源表 O_date 的最早/最晚，以及 NewLog 里登录类操作的时间边界。
     *
     * 用来判断 Log_Login 那 188 万行到底是 NewLog 已有登录记录的重复，
     * 还是 NewLog 缺失时段的数据 —— 决定「只用 NewLog」会不会丢信息。
     */
    public static Map<String, Object> probeLoginCoverage() throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection connection = SqlUtils.getConnection(LOG_SQL_URL, LOG_SQL_USER, LOG_SQL_PASSWORD)) {
            result.put("Log_Login", dateRange(connection,
                    "SELECT MIN(O_date), MAX(O_date), COUNT(*) FROM dbo.Log_Login"));
            result.put("NewLog_登录登出", dateRange(connection,
                    "SELECT MIN(O_date), MAX(O_date), COUNT(*) FROM dbo.NewLog "
                            + "WHERE O_Operation_type IN (N'登录', N'登出')"));
            result.put("NewLog_全部", dateRange(connection,
                    "SELECT MIN(O_date), MAX(O_date), COUNT(*) FROM dbo.NewLog"));
        }
        return result;
    }

    /**
     * 只读探测：从 Log_Login 取一批用户名样本，供上层与 User_Data / NewLog 比对。
     *
     * 方案 A 要在读取时合并两个集合，前提是两边的用户名是同一种写法 ——
     * 若一边是登录名、另一边是邮箱或数字 id，按用户合并就会对不上。
     * 两张表时间上不重叠（NewLog 登录止于 2022-10，Log_Login 起于 2022-11），
     * 所以没法比对同一条记录，只能比字段格式。
     */
    public static List<String> sampleLog_LoginUsernames(int limit) throws SQLException {
        List<String> names = new ArrayList<>();
        String sql = "SELECT DISTINCT TOP (?) U_Username FROM dbo.Log_Login "
                + "WHERE U_Username IS NOT NULL AND LTRIM(RTRIM(U_Username)) <> ''";
        try (Connection connection = SqlUtils.getConnection(LOG_SQL_URL, LOG_SQL_USER, LOG_SQL_PASSWORD);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static Map<String, Object> dateRange(Connection connection, String sql) throws SQLException {
        Map<String, Object> info = new LinkedHashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                info.put("min", String.valueOf(rs.getTimestamp(1)));
                info.put("max", String.valueOf(rs.getTimestamp(2)));
                info.put("count", rs.getLong(3));
            }
        }
        return info;
    }

    private static String setting(String propertyName, String environmentName,
                                  String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environmentName);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }


    /**
     * 统计两张源表待同步条数，用于前端算百分比。
     *
     * 起点必须与 exportXxx 用的完全一致（都走 readXxx → alignWithTarget），
     * 否则分母算的是「从书签起」、分子跑的是「从集合实际进度起」，百分比会失真。
     */
    public void getLogCount(MongoCollection<Document> syncState,
                            MongoCollection<Document> newLogTarget,
                            MongoCollection<Document> logLoginTarget) throws SQLException{
        try (Connection connection = SqlUtils.getConnection(LOG_SQL_URL, LOG_SQL_USER, LOG_SQL_PASSWORD)) {

            // 统计 NewLog
            try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM dbo.NewLog WHERE ID > ?")) {
                stmt.setInt(1, readLastLogId(syncState, newLogTarget));
                try (ResultSet rs = stmt.executeQuery()) { if (rs.next()) newLogTotal = rs.getLong(1); }
            }

            // 统计 Log_Login
            try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM dbo.Log_Login WHERE id > ?")) {
                stmt.setInt(1, readLogLog_Login(syncState, logLoginTarget));
                try (ResultSet rs = stmt.executeQuery()) { if (rs.next()) logLoginTotal = rs.getLong(1); }
            }

            System.out.println("NewLog 待同步: " + newLogTotal + " 条, Log_Login 待同步: " + logLoginTotal + " 条");

        }

    }


    private int readLogLog_Login(MongoCollection<Document> syncState,
                                 MongoCollection<Document> target) {
        Document state = syncState.find(new Document("_id", "Log_Login")).first();
        if (state == null
                || !(state.get("lastId") instanceof Number)
                || !(state.get("version") instanceof Number)
                || ((Number) state.get("version")).intValue() != LOG_EXPORT_VERSION) {
            logMessage("Log_Login 导出结构已更新，将从头 upsert 以补齐 O_city 并移除 Phone");
            return 0;
        }
        int bookmark = ((Number) state.get("lastId")).intValue();
        return alignWithTarget("Log_Login", bookmark, target);
    }

    private static void saveLog_LoginLastLogId(MongoCollection<Document> syncState, int lastId) {
        Document values = new Document("lastId", lastId)
                .append("version", LOG_EXPORT_VERSION)
                .append("updatedAt", java.util.Date.from(Instant.now()));
        syncState.updateOne(new Document("_id", "Log_Login"), new Document("$set", values),
                new UpdateOptions().upsert(true));
    }

    private  Document readLog_LoginDocument(ResultSet resultSet, ResultSetMetaData metaData)
            throws SQLException {
        Document document = new Document();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String columnName = metaData.getColumnLabel(i);
            String targetFieldName = getTargetFieldName(columnName);  // 获取映射后的字段名
            document.put(targetFieldName, toBsonValue(resultSet.getObject(i)));
        }
        return document;
    }


    /**
     * 字段名称映射：源字段名 -> 目标字段名
     */
    private static String getTargetFieldName(String sourceColumnName) {
        // 在这里定义所有需要映射的字段
        switch (sourceColumnName) {
            case "id":
                return "ID";
            case "U_Username":
                return "UserName";
            default:
                return sourceColumnName;  // 没有映射则保持原名
        }
    }

    /**
     * 同步 register 表：从 SQL Server 读取注册信息，复用旧 OSS URL 或上传新文件，写入目标 Mongo。
     */
    private void exportRegister(MongoCollection<Document> target, MongoCollection<Document> syncState) {
        logMessage("开始同步 register（注册信息）");
        registerReusedFiles = 0L;
        registerUploadedFiles = 0L;
        long total = 0;
        int batchLastId = readRegisterLastId(syncState);

        // 预先统计待同步总数，用于进度显示
        long registerTotalCount = 0;
        try (Connection countConn = SqlUtils.getConnection(REGISTER_SQL_URL, REGISTER_SQL_USER, REGISTER_SQL_PASSWORD);
             PreparedStatement countStmt = countConn.prepareStatement("SELECT COUNT(*) FROM dbo.register WHERE Id > ?")) {
            countStmt.setInt(1, batchLastId);
            try (ResultSet rs = countStmt.executeQuery()) {
                if (rs.next()) {
                    registerTotalCount = rs.getLong(1);
                    logMessage("register 待同步: " + registerTotalCount + " 条（从 Id=" + batchLastId + " 开始）");
                }
            }
        } catch (SQLException e) {
            logMessage("⚠️ 统计 register 待同步数失败: " + e.getMessage() + "，进度将无法显示");
        }

        try (MongoClient oldMongoClient = MongoClients.create(DataHub_URL)) {
            MongoCollection<Document> oldRegister = oldMongoClient.getDatabase("DataHub_SYS")
                    .getCollection("register");
            Map<Integer, Document> oldOssUrls = loadExistingOssUrls(oldRegister);

            com.logsmanage.system.utils.OssClientDataHub ossClient = new com.logsmanage.system.utils.OssClientDataHub(
                    OSS_ENDPOINT, OSS_ACCESS_KEY_ID, OSS_ACCESS_KEY_SECRET, OSS_BUCKET_NAME);

            // 主查询不读取大字段，phone 按此前要求不导出
            String sql = "SELECT [Id],[name],[Email],[emailstatus],[school],[department],"
                    + "[isdoctor],[isdoctorteach],[other],[college],[applicationTime],"
                    + "[Okstatus],[status],[isactive],[random],[bodaoname],[isend],[isDaitongzhi],"
                    + "[daikaitongTime],[daitongzhiTime],[isDaikaitong],[isotheruser],[StudentNo],"
                    + "[OtherUserIdentity],[ActiveCount],[sendMessage],[sendEmail],[ActiveEmailSend],"
                    + "[isDaishenhe],[isShenheTongguo],[ruxueYear],[xuezhi],[username],[Pass],"
                    + "[firstStepSend] FROM dbo.register WHERE Id > ? ORDER BY Id";
            String fileSql = "SELECT [AgreeFile],[Workfile] FROM dbo.register WHERE Id = ?";

            try (Connection connection = SqlUtils.getConnection(REGISTER_SQL_URL, REGISTER_SQL_USER, REGISTER_SQL_PASSWORD);
                 Connection fileConnection = SqlUtils.getConnection(REGISTER_SQL_URL, REGISTER_SQL_USER, REGISTER_SQL_PASSWORD);
                 PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                 PreparedStatement fileStatement = fileConnection.prepareStatement(fileSql)) {

                statement.setInt(1, batchLastId);
                statement.setFetchSize(BATCH_SIZE);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    List<WriteModel<Document>> writes = new ArrayList<>(BATCH_SIZE);

                    // 在循环开始前发送初始进度，避免待同步为 0 时前端从 0% 直接跳 100%
                    sendMessage(this.session, buildRegisterProgress(0, registerTotalCount));

                    while (resultSet.next()) {
                        int id = resultSet.getInt("Id");
                        Document document = readRegisterFields(resultSet, metaData);
                        Document oldUrls = oldOssUrls.get(id);
                        String agreeUrl = oldUrls == null ? null : validOssUrl(oldUrls.get("AgreeFile"));
                        String workUrl = oldUrls == null ? null : validOssUrl(oldUrls.get("Workfile"));

                        if (agreeUrl != null) {
                            registerReusedFiles++;
                        }
                        if (workUrl != null) {
                            registerReusedFiles++;
                        }

                        // 缺失的文件才上传
                        if (agreeUrl == null || workUrl == null) {
                            fileStatement.setInt(1, id);
                            try (ResultSet files = fileStatement.executeQuery()) {
                                if (!files.next()) {
                                    logMessage("⚠️ register 文件记录不存在，Id=" + id);
                                    continue;
                                }
                                if (agreeUrl == null) {
                                    agreeUrl = uploadRegisterFile(ossClient, files, id, "AgreeFile");
                                    if (agreeUrl != null) {
                                        registerUploadedFiles++;
                                    }
                                }
                                if (workUrl == null) {
                                    workUrl = uploadRegisterFile(ossClient, files, id, "Workfile");
                                    if (workUrl != null) {
                                        registerUploadedFiles++;
                                    }
                                }
                            }
                        }

                        document.put("AgreeFile", agreeUrl);
                        document.put("Workfile", workUrl);
                        document.put("_id", id);
                        Document fields = new Document(document);
                        fields.remove("_id");
                        Document update = new Document("$set", fields)
                                .append("$unset", new Document("phone", ""));
                        writes.add(new UpdateOneModel<>(new Document("_id", id), update,
                                new UpdateOptions().upsert(true)));
                        batchLastId = id;
                        total++;
                        // 每条都发进度（小批量也能看到进度），不只在 BATCH_SIZE 时才发
                        sendMessage(this.session, buildRegisterProgress(total, registerTotalCount));

                        if (writes.size() >= BATCH_SIZE) {
                            target.bulkWrite(writes, new BulkWriteOptions().ordered(false));
                            writes.clear();
                            saveRegisterLastId(syncState, batchLastId);
                            logMessage("register 已处理 " + total + " 条，当前 Id=" + batchLastId
                                    + "，复用文件=" + registerReusedFiles + "，新上传=" + registerUploadedFiles);
                        }
                    }

                    if (!writes.isEmpty()) {
                        target.bulkWrite(writes, new BulkWriteOptions().ordered(false));
                        writes.clear();
                        saveRegisterLastId(syncState, batchLastId);
                    }
                }
            }

            // 查询目标集合总数
            long finalTotal = target.estimatedDocumentCount();

            logMessage("✅ register 同步完成：本次处理 " + total + " 条"
                    + "，复用文件=" + registerReusedFiles + "，新上传=" + registerUploadedFiles
                    + "，目标集合现有 " + finalTotal + " 条");

            // 写入完成时间
            Document document = new Document();
            document.append("updateTime", LocalDateTime.now());
            document.append("type", "register");
            datahubTemplate.insert(document, "User_Data_UpdateTime");

        } catch (Exception e) {
            logMessage("❌ register 同步失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Map<Integer, Document> loadExistingOssUrls(MongoCollection<Document> oldRegister) {
        Map<Integer, Document> result = new HashMap<>();
        Document projection = new Document("Id", 1)
                .append("AgreeFile", 1).append("Workfile", 1);
        for (Document document : oldRegister.find().projection(projection)) {
            Object idValue = document.get("Id");
            if (idValue instanceof Number
                    && (validOssUrl(document.get("AgreeFile")) != null
                    || validOssUrl(document.get("Workfile")) != null)) {
                result.put(((Number) idValue).intValue(), document);
            }
        }
        logMessage("旧 Mongo 中可复用 OSS 记录：" + result.size() + " 条");
        return result;
    }

    private String validOssUrl(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String url = (String) value;
        return url.startsWith(OSS_PUBLIC_URL + "register/") ? url : null;
    }

    private Document readRegisterFields(ResultSet resultSet, ResultSetMetaData metaData)
            throws SQLException {
        Document document = new Document();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            document.put(metaData.getColumnLabel(i), toBsonValue(resultSet.getObject(i)));
        }
        return document;
    }

    private String uploadRegisterFile(com.logsmanage.system.utils.OssClientDataHub ossClient,
                                      ResultSet resultSet,
                                      int id,
                                      String fieldName) throws SQLException {
        byte[] data = resultSet.getBytes(fieldName);
        if (data == null || data.length == 0) {
            return null;
        }
        String objectName = "register/" + fieldName + "_" + id;
        String uploadedName = ossClient.upload(new java.io.ByteArrayInputStream(data), objectName);
        if (uploadedName == null) {
            logMessage("⚠️ OSS 上传失败: " + objectName);
            return null;
        }
        return OSS_PUBLIC_URL + uploadedName;
    }

    private int readRegisterLastId(MongoCollection<Document> syncState) {
        Document state = syncState.find(new Document("_id", "register_oss")).first();
        if (state == null || !(state.get("lastId") instanceof Number)
                || !(state.get("version") instanceof Number)
                || ((Number) state.get("version")).intValue() != REGISTER_EXPORT_VERSION) {
            return 0;
        }
        return ((Number) state.get("lastId")).intValue();
    }

    private void saveRegisterLastId(MongoCollection<Document> syncState, int lastId) {
        Document values = new Document("lastId", lastId)
                .append("version", REGISTER_EXPORT_VERSION)
                .append("updatedAt", java.util.Date.from(Instant.now()));
        syncState.updateOne(new Document("_id", "register_oss"),
                new Document("$set", values), new UpdateOptions().upsert(true));
    }

    private String buildRegisterProgress(long done, long count) {
        return "{\"done\":" + done + ",\"count\":" + count
                + ",\"reused\":" + registerReusedFiles + ",\"uploaded\":" + registerUploadedFiles + "}";
    }

    /**
     * 探测 register 源库数据（诊断用，只读）。
     * 返回源库总数、最大 id、书签位置、待同步条数。
     */
    public static Map<String, Object> probeRegisterSource(int bookmarkLastId) throws Exception {
        Map<String, Object> result = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(REGISTER_SQL_URL, REGISTER_SQL_USER, REGISTER_SQL_PASSWORD);
             Statement stmt = conn.createStatement()) {
            // 源库总数
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM register")) {
                if (rs.next()) {
                    result.put("sourceTotal", rs.getInt(1));
                }
            }
            // 源库最大 id
            try (ResultSet rs = stmt.executeQuery("SELECT MAX(id) FROM register")) {
                if (rs.next()) {
                    result.put("sourceMaxId", rs.getInt(1));
                }
            }
            // 书签以上待同步条数
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM register WHERE id > " + bookmarkLastId)) {
                if (rs.next()) {
                    result.put("pendingCount", rs.getInt(1));
                }
            }
        }
        result.put("bookmarkLastId", bookmarkLastId);
        return result;
    }

    /**
     * WebSocket日志输出器
     */
    private static class WebSocketLogger {
        private final Session session;

        public WebSocketLogger(Session session) {
            this.session = session;
        }

        public void log(String message) {
            try {
                if (session != null && session.isOpen()) {
                    session.getBasicRemote().sendText(message);
                }
            } catch (IOException e) {
                System.err.println("发送日志消息失败: " + e.getMessage());
            }
        }
    }
}
