package com.logsmanage.system.service;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 学校列表：读 {@code Ip_Adr} 集合（181 主库 DataHub_Log，与 User_Data / NewLog 同库）。
 *
 * <p>这张表存的是各单位（学校）的 IP 段与授权信息。字段名是源系统留下的，拼写不规范但
 * <b>不要在代码里改名</b>——库里就叫这些名字，改了就查不到：
 * <ul>
 *   <li>{@code Adress} —— 单位名称/地址，正确拼写应为 Address</li>
 *   <li>{@code mangerName} —— 管理员姓名，正确拼写应为 managerName</li>
 *   <li>{@code agreement} / {@code agreement_student} —— 协议相关</li>
 *   <li>{@code base} / {@code suf} / {@code jurisdiction} —— 源系统含义未明确，原样透出</li>
 * </ul>
 * 展示层的中文列名在前端 {@code SchoolListPanel.vue} 里做映射，后端只负责原样取值。
 *
 * <p>值的类型不统一（同一字段可能是字符串/布尔/数字/日期），所以这里不套实体类、
 * 直接用 {@link Document} 取原始值交给前端渲染，避免反序列化失败。
 *
 * <p>没塞进已经很长的 {@code UserDataService}，这张表与账号/日志业务无关联。
 */
@Service
public class SchoolService {

    private static final String COLLECTION_NAME = "Ip_Adr";

    // 只透出前端需要的字段，其余不返回（源表还有别的列，含义不明先不暴露）
    private static final String[] OUTPUT_FIELDS = {
            "_id", "Adress", "Ip", "agreement", "agreement_student",
            "base", "endDate", "jurisdiction", "mangerName", "suf"
    };

    // 关键词模糊搜索覆盖的字段：单位名、IP、管理员、邮箱后缀
    private static final String[] KEYWORD_FIELDS = {"Adress", "Ip", "mangerName", "suf"};

    /**
     * 需要清理空白的字段。这张表也是 SQL Server 迁过来的，CHAR 字段带大量尾部填充空格
     * （实测 287 行的 {@code Adress}/{@code endDate}/{@code suf} 全部带填充），
     * 且 {@code suf} 里有 {@code "@\nfafu.edu.cn"} 这种夹着换行的值——不清理会把表格行撑高。
     * 所以统一 trim + 把内部连续空白折成一个空格。
     *
     * <p>2026-09-15 起同步写入侧（{@code UserAndLogExportToMongo.cleanIpAdrWhitespace}）也按
     * 同一口径洗一遍，库里存的就是干净值，这里的清洗因此只是双保险——留着是为了兜住改动前
     * 同步进来的旧数据、以及将来别的写入路径。两处字段名单与口径必须一致，改一处要同步改另一处。
     *
     * <p>{@code Ip} 与 {@code jurisdiction} 不在此列：前者是逗号分隔的 IP 段（实测最长
     * 2286 字符、84 段），后者是 JSON 原文（最长 12000 字符），都交给前端原样处理。
     */
    private static final String[] WHITESPACE_FIELDS = {"Adress", "endDate", "suf", "mangerName"};

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private final MongoTemplate mongoTemplate;

    public SchoolService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 分页查询学校列表。
     *
     * @param keyword 模糊匹配单位名/IP/管理员，为空则不筛选
     * @param base    正式库筛选，{@code null} 表示不筛选。
     *                前端把“正式库=是”反向映射到源表 {@code base=false}，“基础库=是”映射到 {@code base=true}。
     */
    public Map<String, Object> getPage(int pageNum, int pageSize, String keyword, Boolean base) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);

        List<Criteria> criteriaList = new ArrayList<>();
        if (hasValue(keyword)) {
            // 逐字段 or 模糊匹配。这张表量级小（学校数量级），全表正则扫描可接受
            String quoted = Pattern.quote(keyword.trim());
            List<Criteria> ors = new ArrayList<>();
            for (String field : KEYWORD_FIELDS) {
                ors.add(Criteria.where(field).regex(quoted, "i"));
            }
            criteriaList.add(new Criteria().orOperator(ors.toArray(new Criteria[0])));
        }
        if (base != null) {
            criteriaList.add(Criteria.where("base").is(base));
        }

        Query countQuery = new Query();
        applyCriteria(countQuery, criteriaList);
        long total = mongoTemplate.count(countQuery, COLLECTION_NAME);

        Query pageQuery = new Query();
        applyCriteria(pageQuery, criteriaList);
        pageQuery.fields().include(OUTPUT_FIELDS);
        // 按 _id 倒序：只为翻页稳定、走主键索引，不代表录入先后
        pageQuery.with(Sort.by(Sort.Direction.DESC, "_id"));
        pageQuery.skip((long) (safePageNum - 1) * safePageSize);
        pageQuery.limit(safePageSize);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Document document : mongoTemplate.find(pageQuery, Document.class, COLLECTION_NAME)) {
            records.add(cleanRecord(document));
        }

        long totalPages = total == 0 ? 0 : (total + safePageSize - 1) / safePageSize;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", hasValue(keyword) || base != null ? "搜索成功" : "查询成功");
        result.put("records", records);
        result.put("total", total);
        result.put("pageNum", safePageNum);
        result.put("pageSize", safePageSize);
        result.put("totalPages", totalPages);
        return result;
    }

    /** 协议类型 -> 库里的字段名。前端只传 kind，URL 一律由服务端查库得到。 */
    private static String agreementField(String kind) {
        if ("agree".equals(kind)) {
            return "agreement";
        }
        if ("student".equals(kind)) {
            return "agreement_student";
        }
        return null;
    }

    /**
     * 取某单位某类协议的 OSS 地址与单位名，供 {@code /api/school/agreement} 代理下载。
     *
     * <p><b>只按 id + kind 查库，不接受前端传入 URL</b> —— 否则等于开了个任意地址代理（SSRF）。
     *
     * @param kind {@code agree} 或 {@code student}
     * @return {@code {url, adress}}；id 不存在、kind 非法或该字段没值时返回 null
     */
    public Map<String, String> resolveAgreement(int id, String kind) {
        String field = agreementField(kind);
        if (field == null) {
            return null;
        }
        Query query = new Query(Criteria.where("_id").is(id));
        query.fields().include(field).include("Adress");
        Document document = mongoTemplate.findOne(query, Document.class, COLLECTION_NAME);
        if (document == null) {
            return null;
        }
        Object raw = document.get(field);
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
            return null;
        }
        Object adress = document.get("Adress");
        Map<String, String> result = new LinkedHashMap<>();
        result.put("url", ((String) raw).trim());
        result.put("adress", adress instanceof String
                ? WHITESPACE_RUN.matcher(((String) adress).trim()).replaceAll(" ")
                : "");
        return result;
    }

    /** 按 {@link #WHITESPACE_FIELDS} 清理 CHAR 填充空格与内部换行，其余字段原样保留。 */
    private Map<String, Object> cleanRecord(Document document) {
        Map<String, Object> record = new LinkedHashMap<>(document);
        for (String field : WHITESPACE_FIELDS) {
            Object value = record.get(field);
            if (value instanceof String) {
                record.put(field, WHITESPACE_RUN.matcher(((String) value).trim()).replaceAll(" "));
            }
        }
        return record;
    }

    private void applyCriteria(Query query, List<Criteria> criteriaList) {
        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }
    }

    private boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 全部学校（单位）名称，不分页，供前端下拉联想在本地做模糊匹配。
     *
     * <p>取的是 {@code Adress}（单位名）而<b>不是</b> {@code mangerName}——后者是本方
     * 客户经理姓名（实测值形如「负责人H基础库」「负责人A」），不是学校名。
     *
     * <p>清理方式与 {@link #getPage} 的 {@code WHITESPACE_FIELDS} 一致：这张表是
     * SQL Server 迁过来的，{@code Adress} 带尾部填充空格，不 trim 的话前端匹配不上，
     * 也会出现看起来一样的重复项。
     */
    public Map<String, Object> getAllSchoolNames() {
        Query query = new Query();
        query.fields().include("Adress");

        List<Document> documents = mongoTemplate.find(query, Document.class, COLLECTION_NAME);

        // TreeSet 去重 + 排序，避免同名不同填充的值重复出现在下拉里
        Set<String> names = new TreeSet<>();
        for (Document document : documents) {
            Object value = document.get("Adress");
            if (value instanceof String) {
                String name = WHITESPACE_RUN.matcher(((String) value).trim()).replaceAll(" ");
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("total", names.size());
        result.put("records", new ArrayList<>(names));
        return result;
    }
}
