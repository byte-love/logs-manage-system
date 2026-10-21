//package com.logsmanage.system.utils;
//
//import com.mongodb.client.MongoClient;
//import com.mongodb.client.MongoClients;
//import com.mongodb.client.MongoCollection;
//import com.mongodb.client.MongoDatabase;
//import com.mongodb.client.model.*;
//import org.bson.Document;
//import org.bson.types.Binary;
//import org.bson.types.Decimal128;
//
//import java.math.BigDecimal;
//import java.sql.*;
//import java.time.Instant;
//import java.util.ArrayList;
//import java.util.Date;
//import java.util.List;
//
//public class UserAndLogExportToMongo {
//    private static final String USER_SQL_URL = setting(
//            "datahub.userSql.url", "DataHub_USER_SQL_URL",
//            "jdbc:sqlserver://login.api.example.com:1433;databaseName=cas;responseBuffering=adaptive");
//    private static final String USER_SQL_USER = setting(
//            "datahub.userSql.user", "DataHub_USER_SQL_USER", "jhadmin");
//    private static final String USER_SQL_PASSWORD = setting(
//            "datahub.userSql.password", "DataHub_USER_SQL_PASSWORD", "byteloveadmin");
//
//    private static final String LOG_SQL_URL = setting(
//            "datahub.logSql.url", "DataHub_LOG_SQL_URL",
//            "jdbc:sqlserver://203.0.113.13:1433;databaseName=DataHub_Log;responseBuffering=adaptive");
//    private static final String LOG_SQL_USER = setting(
//            "datahub.logSql.user", "DataHub_LOG_SQL_USER", "sa");
//    private static final String LOG_SQL_PASSWORD = setting(
//            "datahub.logSql.password", "DataHub_LOG_SQL_PASSWORD", "ByteLove0713");
//
//    private static final String MONGO_URL = setting(
//            "datahub.mongo.url", "DEMO_MONGO_URL", "mongodb://127.0.0.1:27017");
//    private static final String MONGO_DATABASE = "DataHub_Log";
//    private static final int BATCH_SIZE = 2000;
//    private static final int LOG_EXPORT_VERSION = 2;
//
//    public static void main(String[] args) throws Exception {
//        String task = "all";
//        boolean full = false;
//        for (String arg : args) {
//            if ("--full".equalsIgnoreCase(arg)) {
//                full = true;
//            } else {
//                task = arg.toLowerCase();
//            }
//        }
//
//        if (!"all".equals(task) && !"user".equals(task) && !"log".equals(task)) {
//            throw new IllegalArgumentException("参数只能是 all、user、log 或 --full");
//        }
//
//        try (MongoClient mongoClient = MongoClients.create(MONGO_URL)) {
//            MongoDatabase targetDatabase = mongoClient.getDatabase(MONGO_DATABASE);
//            if ("all".equals(task) || "user".equals(task)) {
//                exportUserData(targetDatabase.getCollection("User_Data"));
//            }
////            if ("all".equals(task) || "log".equals(task)) {
////                try (IpCityResolver cityResolver = new IpCityResolver()) {
////                    exportNewLog(targetDatabase.getCollection("NewLog"),
////                            targetDatabase.getCollection("_sync_state"), full, cityResolver);
////                }
////            }
//        }
//    }
//
//    private static void exportUserData(MongoCollection<Document> target) throws SQLException {
//        System.out.println("开始同步 cas.dbo.User_Data -> DataHub_Log.User_Data");
//        long total = 0;
//        try (Connection connection = SqlUtils.getConnection(USER_SQL_URL, USER_SQL_USER, USER_SQL_PASSWORD);
//             PreparedStatement statement = connection.prepareStatement("SELECT * FROM dbo.User_Data");
//             ResultSet resultSet = statement.executeQuery()) {
//            ResultSetMetaData metaData = resultSet.getMetaData();
//            List<WriteModel<Document>> writes = new ArrayList<>(BATCH_SIZE);
//            while (resultSet.next()) {
//                Document document = readDocument(resultSet, metaData);
//                // 手机号属于敏感信息，不导出到目标 MongoDB。
//                document.remove("Phone");
//                String userName = document.getString("UserName");
//                if (userName == null || userName.isEmpty()) {
//                    throw new SQLException("User_Data 存在空 UserName，无法生成 MongoDB _id");
//                }
//                document.put("_id", userName);
//                writes.add(replaceUpsert(document));
//                total++;
//                if (writes.size() >= BATCH_SIZE) {
//                    writeBatch(target, writes);
//                    printProgress("User_Data", total);
//                }
//            }
//            writeBatch(target, writes);
//        }
//        System.out.println("User_Data 同步完成，共处理 " + total + " 条");
//    }
//
//    private static void exportNewLog(MongoCollection<Document> target,
//                                     MongoCollection<Document> syncState,
//                                     boolean full,
//                                     IpCityResolver cityResolver) throws SQLException {
//        int lastId = full ? 0 : readLastLogId(syncState);
//        long total = 0;
//        System.out.println("开始同步 DataHub_Log.dbo.NewLog -> DataHub_Log.NewLog，起始 ID > " + lastId);
//
//        String sql = "SELECT * FROM dbo.NewLog WHERE ID > ? ORDER BY ID";
//        try (Connection connection = SqlUtils.getConnection(LOG_SQL_URL, LOG_SQL_USER, LOG_SQL_PASSWORD);
//             PreparedStatement statement = connection.prepareStatement(
//                     sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
//            statement.setInt(1, lastId);
//            statement.setFetchSize(BATCH_SIZE);
//            try (ResultSet resultSet = statement.executeQuery()) {
//                ResultSetMetaData metaData = resultSet.getMetaData();
//                List<WriteModel<Document>> writes = new ArrayList<>(BATCH_SIZE);
//                int batchLastId = lastId;
//                while (resultSet.next()) {
//                    Document document = readDocument(resultSet, metaData);
//                    // 日志中的手机号同样不写入目标 MongoDB。
//                    document.remove("Phone");
//                    document.put("O_city", cityResolver.resolve(document.getString("O_srcIP")));
//                    Integer id = document.getInteger("ID");
//                    if (id == null) {
//                        throw new SQLException("NewLog 存在空 ID，无法生成 MongoDB _id");
//                    }
//                    document.put("_id", id);
//                    writes.add(replaceUpsert(document));
//                    batchLastId = id;
//                    total++;
//                    if (writes.size() >= BATCH_SIZE) {
//                        writeBatch(target, writes);
//                        saveLastLogId(syncState, batchLastId);
//                        printProgress("NewLog", total);
//                    }
//                }
//                if (!writes.isEmpty()) {
//                    writeBatch(target, writes);
//                    saveLastLogId(syncState, batchLastId);
//                }
//            }
//        }
//        System.out.println("NewLog 同步完成，本次共处理 " + total + " 条");
//    }
//
//    private static Document readDocument(ResultSet resultSet, ResultSetMetaData metaData)
//            throws SQLException {
//        Document document = new Document();
//        for (int i = 1; i <= metaData.getColumnCount(); i++) {
//            document.put(metaData.getColumnLabel(i), toBsonValue(resultSet.getObject(i)));
//        }
//        return document;
//    }
//
//    private static Object toBsonValue(Object value) {
//        if (value instanceof Timestamp) {
//            return new Date(((Timestamp) value).getTime());
//        }
//        if (value instanceof java.sql.Date) {
//            return new Date(((java.sql.Date) value).getTime());
//        }
//        if (value instanceof java.sql.Time) {
//            return new Date(((java.sql.Time) value).getTime());
//        }
//        if (value instanceof BigDecimal) {
//            return new Decimal128((BigDecimal) value);
//        }
//        if (value instanceof byte[]) {
//            return new Binary((byte[]) value);
//        }
//        return value;
//    }
//
//    private static ReplaceOneModel<Document> replaceUpsert(Document document) {
//        return new ReplaceOneModel<>(new Document("_id", document.get("_id")), document,
//                new ReplaceOptions().upsert(true));
//    }
//
//    private static void writeBatch(MongoCollection<Document> target,
//                                   List<WriteModel<Document>> writes) {
//        if (writes.isEmpty()) {
//            return;
//        }
//        target.bulkWrite(writes, new BulkWriteOptions().ordered(false));
//        writes.clear();
//    }
//
//    private static int readLastLogId(MongoCollection<Document> syncState) {
//        Document state = syncState.find(new Document("_id", "NewLog")).first();
//        if (state == null
//                || !(state.get("lastId") instanceof Number)
//                || !(state.get("version") instanceof Number)
//                || ((Number) state.get("version")).intValue() != LOG_EXPORT_VERSION) {
//            System.out.println("NewLog 导出结构已更新，将从头 upsert 以补齐 O_city 并移除 Phone");
//            return 0;
//        }
//        return ((Number) state.get("lastId")).intValue();
//    }
//
//    private static void saveLastLogId(MongoCollection<Document> syncState, int lastId) {
//        Document values = new Document("lastId", lastId)
//                .append("version", LOG_EXPORT_VERSION)
//                .append("updatedAt", Date.from(Instant.now()));
//        syncState.updateOne(new Document("_id", "NewLog"), new Document("$set", values),
//                new UpdateOptions().upsert(true));
//    }
//
//    private static void printProgress(String name, long total) {
//        System.out.println(name + " 已处理 " + total + " 条");
//    }
//
//    private static String setting(String propertyName, String environmentName,
//                                  String defaultValue) {
//        String value = System.getProperty(propertyName);
//        if (value == null || value.trim().isEmpty()) {
//            value = System.getenv(environmentName);
//        }
//        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
//    }
//}
