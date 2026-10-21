package com.logsmanage.system.service;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给学校日志补「学院」列（页面表格与 Excel 导出共用）：按 {@code UserName} 去 181
 * {@code User_Data} 取 {@code College}。
 *
 * <p><b>关联键是 {@code UserName}（即 {@code User_Data._id}）。</b>日志行本身没有学院字段，
 * 只有 {@code UserName}/{@code NickName}/{@code Mail}/{@code Institution}；
 * {@code User_Data._id} 存的就是 UserName，两边同名，与 En_Log 同步时补昵称/机构/邮箱
 * 用的是同一套匹配（{@code UserAndLogExportToMongo.enrichEnLogBatch}）。
 * 2026-09-21 实测示例师范大学：该机构日志的 366 个去重用户名命中 358 个。
 *
 * <p><b>不要改用邮箱匹配。</b>{@code User_Data.Mail} 有「无」、空串这类脏值，而且同一邮箱
 * 对应多个账号（实测 {@code liuyuxin1} / {@code liuyuxin3} 共用一个邮箱），
 * 按邮箱会把别人的学院安到当前行上。
 *
 * <p><b>学校共用账号（登录名 {@code schooluser}、姓名「学校账号」）跳过。</b>
 * 一个学校共用这一个账号登录，它不是某个人、没有学院；实测它压根不在 {@code User_Data} 里
 * （示例师范大学未命中的用户名里就有它），且占比很大（示例大学 149589 行里 101982 行是它）。
 * 这里显式跳过、而不是指望「查不到自然为空」：万一将来 {@code User_Data} 里真出现同名记录，
 * 也不会把学校层面的学院安到全校共用账号头上。跳过＝该行学院留空。
 */
@Service
public class UserCollegeService {

    private static final String COLLECTION_NAME = "User_Data";
    /** 导出列名，与 {@code ExportController.schoolLogColumns()} 里的 key 一致。 */
    public static final String FIELD_COLLEGE = "College";
    /** 学校共用账号的登录名。 */
    static final String SCHOOL_SHARED_USER = "schooluser";
    /** 学校共用账号在日志里的姓名；En_Log 那批补不到姓名，只能靠 UserName 认。 */
    static final String SCHOOL_SHARED_NICK = "学校账号";
    /** 学院几乎只在开通账号时写一次，全表映射带 30 分钟缓存（与 register 材料映射同一档）。 */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    private final MongoTemplate mongoTemplate;
    private volatile CachedCollege cache;
    private final Object cacheLock = new Object();

    public UserCollegeService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    private static final class CachedCollege {
        final Map<String, String> byUserName;
        final long loadedAt;

        CachedCollege(Map<String, String> byUserName, long loadedAt) {
            this.byUserName = byUserName;
            this.loadedAt = loadedAt;
        }
    }

    /**
     * 全表「UserName → 学院」映射（约 13 万条，空学院不进去），供大批量导出逐行 O(1) 查。
     *
     * <p>导出是单趟流式（游标按 O_date 归并，行随时吐给 Excel），没法先收集用户名再批量
     * {@code $in}，逐行查库又等于几万次往返。这里与 {@code RegisterFileService.latestFilesByMail()}
     * 同一套做法：预载一次全表映射 + TTL 缓存 + 单飞。
     *
     * <p><b>映射加载失败就让导出失败</b>，不做「补不到字段也照导」的降级 —— 那样用户拿到的
     * 是一列全空却看不出原因的 Excel，比直接报错更难排查。
     */
    public Map<String, String> collegeByUserName() {
        CachedCollege cached = cache;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < CACHE_TTL_MS) {
            return cached.byUserName;
        }
        synchronized (cacheLock) {
            cached = cache;
            if (cached != null && now - cached.loadedAt < CACHE_TTL_MS) {
                return cached.byUserName;
            }
            Map<String, String> byUserName = loadCollegeByUserName();
            cache = new CachedCollege(byUserName, System.currentTimeMillis());
            return byUserName;
        }
    }

    /**
     * 只取 {@code College} 一个字段全表扫一遍（{@code _id} 由驱动自动带上）。
     * 键值都 trim：{@code User_Data.College} 实测有 38 条带首尾空白（含 {@code "\n\n金融学院"}、
     * 尾部 {@code \xa0}），日志侧的 {@code UserName} 也有带尾空格的（如 {@code 'jxut.edu.cn-gj '}），
     * 两边都归一才碰得上；展示口径与账号数据页的 {@code trimValue} 一致。
     */
    private Map<String, String> loadCollegeByUserName() {
        Map<String, String> out = new HashMap<>();
        collectCollege(out, mongoTemplate.getCollection(COLLECTION_NAME)
                .find()
                .projection(new Document(FIELD_COLLEGE, 1)));
        return out;
    }

    /**
     * 把游标里的文档收进「UserName → 学院」映射（空学院不入表）。
     * 单独拆出来是为了让 {@code UserCollegeServiceTest} 直接喂文档列表钉住归一化口径，
     * 不用去 mock Mongo 驱动的 {@code FindIterable}。
     */
    static void collectCollege(Map<String, String> out, Iterable<Document> documents) {
        for (Document document : documents) {
            String userName = trimToNull(document.get("_id"));
            String college = trimToNull(document.get(FIELD_COLLEGE));
            if (userName != null && college != null) {
                out.put(userName, college);
            }
        }
    }

    /** 学院插在机构后面：学校日志页的表格列是按行的字段顺序推出来的（见 `constants/logColumns.js`）。 */
    private static final String FIELD_INSTITUTION = "Institution";

    /**
     * 给一条日志行补学院列（学校日志页面与导出逐行调用）。
     *
     * <p>三种情况都留空：学校共用账号、{@code User_Data} 里查不到该用户、库里学院本身为空
     * （实测 13.17 万账号里 1500 条 null、29 条空串）。库里写的是「无」的（3528 条）照原值导出，
     * 与账号数据页展示保持一致，不在这里做二次判定。
     *
     * <p>键的位置有讲究：{@code logRecord()} 给的是 LinkedHashMap，直接 {@code put} 会把学院排到
     * 最后（操作时间之后）。页面表格的列顺序取自首行的字段顺序，所以这里插到「机构」后面，
     * 与 Excel 的列顺序（{@code ExportController.schoolLogColumns()}）对齐。
     */
    public static void fillCollege(Map<String, Object> record, Map<String, String> byUserName) {
        if (record == null) {
            return;
        }
        String college = isSchoolUser(record.get("UserName"), record.get("NickName"))
                ? null
                : lookupCollege(record.get("UserName"), byUserName);
        if (record.containsKey(FIELD_COLLEGE)) {
            // 键已存在（同一行被处理两次）就地覆盖，别打乱它原来的位置
            record.put(FIELD_COLLEGE, college);
            return;
        }
        insertAfter(record, FIELD_INSTITUTION, FIELD_COLLEGE, college);
    }

    private static String lookupCollege(Object userName, Map<String, String> byUserName) {
        if (byUserName == null) {
            return null;
        }
        String name = trimToNull(userName);
        return name == null ? null : byUserName.get(name);
    }

    /**
     * 把 {@code key} 插到 {@code anchor} 之后（锚点不存在就追加到末尾）。
     * LinkedHashMap 没有「插到中间」的 API，只能重建一份再写回同一个实例，
     * 保证调用方手里的引用不变。
     */
    private static void insertAfter(Map<String, Object> record, String anchor, String key, Object value) {
        if (!record.containsKey(anchor)) {
            record.put(key, value);
            return;
        }
        Map<String, Object> reordered = new LinkedHashMap<>(record.size() + 1);
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            reordered.put(entry.getKey(), entry.getValue());
            if (anchor.equals(entry.getKey())) {
                reordered.put(key, value);
            }
        }
        record.clear();
        record.putAll(reordered);
    }

    /**
     * 这一行的用户是不是学校共用账号。
     *
     * <p>两个判据都要留：NewLog 的行姓名固定是「学校账号」，而 En_Log 的行姓名/机构是同步时
     * 从 {@code User_Data} 补的、补不到就为空，只认姓名会漏。两边都容忍首尾空格，
     * 登录名另忽略大小写 —— 与 {@code AgentSyncFilterTest} 钉住的 {@code zhengshi} 判定同款口径。
     */
    public static boolean isSchoolUser(Object userName, Object nickName) {
        String user = trimToNull(userName);
        if (user != null && SCHOOL_SHARED_USER.equalsIgnoreCase(user)) {
            return true;
        }
        String nick = trimToNull(nickName);
        return nick != null && SCHOOL_SHARED_NICK.equals(nick);
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
