package com.logsmanage.system.service;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 给账号数据补充 register 集合里的「使用协议」与「注册资料」两个文件链接。
 *
 * <p><b>关联键是邮箱，不是 id。</b>看起来 {@code User_Data.ID} 和 {@code register._id}
 * 都是整数、值域还重叠（9~156563 对 149~94007），按 id 关联能「匹配上」大半——但匹配到的是
 * 完全无关的人：{@code User_Data.ID=23142}（北京邮电大学试用号）会命中 register 里的
 * 「王浩宇 / 四川农业大学」。两套编号各自独立，纯属数值碰撞。按 id 做会把别人的申请材料
 * 显示到当前账号上，既错也泄露隐私，所以这里一律走邮箱。
 *
 * <p>邮箱匹配已验证：命中行的 {@code NickName} 与 {@code register.name}、
 * {@code Institution} 与 {@code register.school} 全部一致。
 *
 * <p>两个必须处理的数据特征：
 * <ul>
 *   <li>{@code register} 是从 SQL Server 迁过来的，CHAR 字段带大量尾部填充空格，
 *       {@code Email} 得按 {@code ^邮箱\s*$} 匹配，不能用等值。</li>
 *   <li>同一邮箱可能有多条申请记录（最多 187 条，像是批量代申请）。取
 *       {@code applicationTime} 最新的一条，并把总条数返回给前端提示。</li>
 * </ul>
 */
@Service
public class RegisterFileService {

    private static final String REGISTER_COLLECTION = "register";

    // 输出到前端的字段名
    public static final String FIELD_AGREE = "AgreeFile";
    public static final String FIELD_WORK = "Workfile";
    // 同邮箱的申请记录条数，>1 时前端提示还有历史材料
    public static final String FIELD_MATCH_COUNT = "RegisterMatchCount";
    // 命中的 register 记录 id，便于对账排查
    public static final String FIELD_REGISTER_ID = "RegisterId";

    // 邮箱脏数据兜底：库里存在 "2522782769@qq. com" 这种带空格的，
    // 内部空格无法安全归一（可能本就是两个不同地址），只跳过明显不含 @ 的
    private static final Pattern LOOKS_LIKE_MAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    // 单页邮箱数上限，防御性兜底：正常分页最多 100
    private static final int MAX_MAILS_PER_BATCH = 200;

    // 有材料的邮箱集合缓存有效期。申请材料几乎不变，构建要全表扫描+分组
    private static final long MATERIAL_MAIL_CACHE_TTL_MS = 30 * 60 * 1000L;

    private final MongoTemplate mongoTemplate;
    // 有材料的邮箱集合（约 6 万个），供申请通道筛选下推使用
    private volatile CachedMails materialMailCache;
    private final Object materialMailLock = new Object();
    // 全表「邮箱 -> 最新记录文件字段」映射，供大批量导出逐行查
    private volatile CachedFileMap fileMapCache;
    private final Object fileMapLock = new Object();

    public RegisterFileService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    private static final class CachedMails {
        final Set<String> mails;
        final long loadedAt;

        CachedMails(Set<String> mails, long loadedAt) {
            this.mails = mails;
            this.loadedAt = loadedAt;
        }
    }

    private static final class CachedFileMap {
        final Map<String, Document> byMail;
        final long loadedAt;

        CachedFileMap(Map<String, Document> byMail, long loadedAt) {
            this.byMail = byMail;
            this.loadedAt = loadedAt;
        }
    }

    /**
     * 给一页账号记录就地补上两个文件字段。
     *
     * <p>整页只发一次聚合查询：先按邮箱正则批量 match，再按 trim 后的邮箱分组、
     * 取 applicationTime 最新的一条。避免每行一次查询（分页 50 行就是 50 次往返）。
     *
     * <p>register 上没有 Email 索引，这一次聚合是全集合扫描（7.8 万文档）。
     * 实测单页开销可接受；若后续变慢，给 register.Email 建索引即可，
     * 但那属于改生产库，需要单独授权。
     */
    public void attachFiles(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        // 收集本页的合法邮箱（去重），同时给每行先填好空值，
        // 保证前端拿到的字段结构一致、不用判 undefined
        Set<String> mails = new HashSet<>();
        for (Map<String, Object> r : records) {
            r.put(FIELD_AGREE, null);
            r.put(FIELD_WORK, null);
            r.put(FIELD_MATCH_COUNT, 0);
            r.put(FIELD_REGISTER_ID, null);

            String mail = normalizeMail(r.get("Mail"));
            if (mail != null && mails.size() < MAX_MAILS_PER_BATCH) {
                mails.add(mail);
            }
        }
        if (mails.isEmpty()) {
            return;
        }

        Map<String, Document> byMail = queryLatestByMail(mails);
        if (byMail.isEmpty()) {
            return;
        }

        for (Map<String, Object> r : records) {
            String mail = normalizeMail(r.get("Mail"));
            if (mail == null) {
                continue;
            }
            Document hit = byMail.get(mail.toLowerCase());
            if (hit == null) {
                continue;
            }
            r.put(FIELD_AGREE, blankToNull(hit.get("agreeFile")));
            r.put(FIELD_WORK, blankToNull(hit.get("workFile")));
            r.put(FIELD_MATCH_COUNT, hit.get("matchCount"));
            r.put(FIELD_REGISTER_ID, hit.get("registerId"));
        }
    }

    /**
     * 一次聚合查出每个邮箱最新的一条 register 记录。
     * 返回 key 是小写邮箱（库里大小写不统一，统一降级比较）。
     */
    private Map<String, Document> queryLatestByMail(Collection<String> mails) {
        List<Pattern> patterns = new ArrayList<>(mails.size());
        for (String m : mails) {
            // 尾部填充空格用 \s*$ 吃掉；前缀锚定，将来若给 Email 建了索引也能用上
            patterns.add(Pattern.compile("^" + Pattern.quote(m) + "\\s*$",
                    Pattern.CASE_INSENSITIVE));
        }

        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match",
                new Document("Email", new Document("$in", patterns))));
        // applicationTime 可能为 null，_id 作为次级排序保证结果稳定
        pipeline.add(new Document("$sort",
                new Document("applicationTime", -1).append("_id", -1)));
        pipeline.add(new Document("$group", new Document("_id",
                new Document("$toLower", new Document("$trim", new Document("input", "$Email"))))
                .append("agreeFile", new Document("$first", "$AgreeFile"))
                .append("workFile", new Document("$first", "$Workfile"))
                .append("registerId", new Document("$first", "$_id"))
                .append("matchCount", new Document("$sum", 1))));

        Map<String, Document> out = new HashMap<>();
        for (Document d : mongoTemplate.getCollection(REGISTER_COLLECTION)
                .aggregate(pipeline).allowDiskUse(true)) {
            Object key = d.get("_id");
            if (key != null) {
                out.put(String.valueOf(key), d);
            }
        }
        return out;
    }

    // User_Data.Mail 有 "无" / 空串 / 带空格等脏值，统一在这里挡掉
    private String normalizeMail(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String s = ((String) value).trim();
        if (s.isEmpty() || "无".equals(s)) {
            return null;
        }
        return LOOKS_LIKE_MAIL.matcher(s).matches() ? s : null;
    }

    // register 里字段可能存在但值为 null，也可能是空串
    private Object blankToNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 「最新一条申请记录里带材料」的邮箱集合（小写、已 trim）。
     * 供申请通道筛选把「有材料」这个跨集合条件下推到 User_Data 查询。
     *
     * <p><b>选取规则必须与 {@link #attachFiles} 一致</b>：同邮箱多条申请时只看
     * {@code applicationTime} 最新的那条有没有材料。若改成「任一条记录有材料」，
     * 筛选结果会和列表里显示的值不一致（存在最新一条为空、旧记录有材料的账号）。
     *
     * <p>带 TTL 缓存：构建要扫全表 + 分组（实测 320ms），而申请材料几乎不变。
     */
    public Set<String> materialMails() {
        CachedMails cached = materialMailCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < MATERIAL_MAIL_CACHE_TTL_MS) {
            return cached.mails;
        }
        synchronized (materialMailLock) {
            cached = materialMailCache;
            if (cached != null && now - cached.loadedAt < MATERIAL_MAIL_CACHE_TTL_MS) {
                return cached.mails;
            }
            Set<String> mails = loadMaterialMails();
            materialMailCache = new CachedMails(mails, System.currentTimeMillis());
            return mails;
        }
    }

    private Set<String> loadMaterialMails() {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match",
                new Document("Email", new Document("$ne", null))));
        // 与 attachFiles 同一套排序，保证「最新一条」的定义一致
        pipeline.add(new Document("$sort",
                new Document("applicationTime", -1).append("_id", -1)));
        pipeline.add(new Document("$group", new Document("_id",
                new Document("$toLower", new Document("$trim", new Document("input", "$Email"))))
                .append("agree", new Document("$first", "$AgreeFile"))
                .append("work", new Document("$first", "$Workfile"))));
        // 字段可能是纯空格，trim 后再判空，与 blankToNull 对齐
        pipeline.add(new Document("$addFields", new Document("a",
                new Document("$trim", new Document("input",
                        new Document("$ifNull", Arrays.asList("$agree", "")))))
                .append("w", new Document("$trim", new Document("input",
                        new Document("$ifNull", Arrays.asList("$work", "")))))));
        pipeline.add(new Document("$match", new Document("$or", Arrays.asList(
                new Document("a", new Document("$ne", "")),
                new Document("w", new Document("$ne", ""))))));
        pipeline.add(new Document("$project", new Document("_id", 1)));

        Set<String> out = new HashSet<>();
        for (Document d : mongoTemplate.getCollection(REGISTER_COLLECTION)
                .aggregate(pipeline).allowDiskUse(true)) {
            Object id = d.get("_id");
            if (id != null) {
                out.add(String.valueOf(id));
            }
        }
        return out;
    }

    /**
     * 判断某个 User_Data.Mail 是否有申请材料。
     * 归一化口径与 {@link #attachFiles} 完全相同（脏值一律视为无材料）。
     */
    public boolean hasMaterial(Object mailValue) {
        String mail = normalizeMail(mailValue);
        return mail != null && materialMails().contains(mail.toLowerCase());
    }

    /**
     * 全表 register「按邮箱取最新一条」的映射（key 小写邮箱 → 该邮箱最新记录的
     * 文件字段 + 总条数）。供**大批量导出**用：逐行走 {@link #attachFiles} 会被
     * {@link #MAX_MAILS_PER_BATCH} 上限截断、且每批一次全表扫描（几十万行会跑死）。
     * 预载一次整表映射后，导出时逐行 O(1) 查即可，材料列也不会被 200 上限丢掉。
     *
     * <p>选取规则（applicationTime 最新一条）与 {@link #attachFiles}、
     * {@link #materialMails} 完全一致，保证导出值与列表展示值不冲突。
     *
     * <p>约 6.6 万个邮箱，带 30 分钟 TTL 缓存 + 单飞，构建要全表扫描 + 分组。
     */
    public Map<String, Document> latestFilesByMail() {
        CachedFileMap cached = fileMapCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < MATERIAL_MAIL_CACHE_TTL_MS) {
            return cached.byMail;
        }
        synchronized (fileMapLock) {
            cached = fileMapCache;
            if (cached != null && now - cached.loadedAt < MATERIAL_MAIL_CACHE_TTL_MS) {
                return cached.byMail;
            }
            Map<String, Document> byMail = loadLatestFilesByMail();
            fileMapCache = new CachedFileMap(byMail, System.currentTimeMillis());
            return byMail;
        }
    }

    private Map<String, Document> loadLatestFilesByMail() {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", new Document("Email", new Document("$ne", null))));
        // 与 attachFiles / materialMails 同一套排序，保证「最新一条」定义一致
        pipeline.add(new Document("$sort",
                new Document("applicationTime", -1).append("_id", -1)));
        pipeline.add(new Document("$group", new Document("_id",
                new Document("$toLower", new Document("$trim", new Document("input", "$Email"))))
                .append("agreeFile", new Document("$first", "$AgreeFile"))
                .append("workFile", new Document("$first", "$Workfile"))
                .append("registerId", new Document("$first", "$_id"))
                .append("matchCount", new Document("$sum", 1))));

        Map<String, Document> out = new HashMap<>();
        for (Document d : mongoTemplate.getCollection(REGISTER_COLLECTION)
                .aggregate(pipeline).allowDiskUse(true)) {
            Object key = d.get("_id");
            if (key != null) {
                out.put(String.valueOf(key), d);
            }
        }
        return out;
    }

    /**
     * 用预载的整表映射给一条记录补文件字段（大批量导出逐行调用）。
     * 与 {@link #attachFiles} 的补字段逻辑一致，只是数据来源换成传入的 map。
     */
    public void attachFilesFromMap(Map<String, Object> record, Map<String, Document> byMail) {
        record.put(FIELD_AGREE, null);
        record.put(FIELD_WORK, null);
        record.put(FIELD_MATCH_COUNT, 0);
        record.put(FIELD_REGISTER_ID, null);

        String mail = normalizeMail(record.get("Mail"));
        if (mail == null) {
            return;
        }
        Document hit = byMail.get(mail.toLowerCase());
        if (hit == null) {
            return;
        }
        record.put(FIELD_AGREE, blankToNull(hit.get("agreeFile")));
        record.put(FIELD_WORK, blankToNull(hit.get("workFile")));
        record.put(FIELD_MATCH_COUNT, hit.get("matchCount"));
        record.put(FIELD_REGISTER_ID, hit.get("registerId"));
    }

    /** 单个账号的全部申请材料，供「查看历史材料」用。按申请时间倒序。 */
    public List<Map<String, Object>> listAllByMail(String mail) {
        String normalized = normalizeMail(mail);
        if (normalized == null) {
            return new ArrayList<>();
        }
        Pattern p = Pattern.compile("^" + Pattern.quote(normalized) + "\\s*$",
                Pattern.CASE_INSENSITIVE);

        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", new Document("Email", p)));
        pipeline.add(new Document("$sort",
                new Document("applicationTime", -1).append("_id", -1)));
        pipeline.add(new Document("$limit", 50));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : mongoTemplate.getCollection(REGISTER_COLLECTION)
                .aggregate(pipeline)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("registerId", d.get("_id"));
            item.put("name", trimValue(d.get("name")));
            item.put("school", trimValue(d.get("school")));
            item.put("college", trimValue(d.get("college")));
            item.put("applicationTime", d.get("applicationTime"));
            item.put(FIELD_AGREE, blankToNull(d.get("AgreeFile")));
            item.put(FIELD_WORK, blankToNull(d.get("Workfile")));
            out.add(item);
        }
        return out;
    }

    private String trimValue(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
