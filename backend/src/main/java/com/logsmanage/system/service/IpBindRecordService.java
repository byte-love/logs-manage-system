package com.logsmanage.system.service;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 加绑 IP 登记列表：读 139 公网库 DataHub 的 IpBindRecord 集合，供异常情况页展示。
 *
 * <p>这张表和账号、日志都不在主库 181，而在 datahubTemplate 指向的 139 上，
 * 所以这里注入 datahubTemplate（不是 @Primary 的那个）。学校字段本表没有，
 * 靠 UserName 关联同库的 User_Data.Institution 补出来（整页一次 $in 批量查，不逐行查）。
 */
@Service
public class IpBindRecordService {

    private static final String COLLECTION = "IpBindRecord";
    private static final String USER_DATA = "User_Data";

    private final MongoTemplate datahubTemplate;

    public IpBindRecordService(@Qualifier("datahubTemplate") MongoTemplate datahubTemplate) {
        this.datahubTemplate = datahubTemplate;
    }

    /**
     * 分页读取加绑 IP 登记。
     *
     * <p>只回传界面要的三列：姓名(OperatorName)、加绑时间(OperateTime)、学校；
     * userName 一并回传只作为前端行 key，不展示。
     */
    public Map<String, Object> getPage(int pageNum, int pageSize, String keyword) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);

        List<Criteria> criteriaList = new ArrayList<>();
        if (hasText(keyword)) {
            String quoted = Pattern.quote(keyword.trim());
            criteriaList.add(new Criteria().orOperator(
                    Criteria.where("OperatorName").regex(quoted, "i"),
                    Criteria.where("UserName").regex(quoted, "i")));
        }

        Query countQuery = new Query();
        applyCriteria(countQuery, criteriaList);
        long total = datahubTemplate.count(countQuery, COLLECTION);

        Query pageQuery = new Query();
        applyCriteria(pageQuery, criteriaList);
        pageQuery.fields().include("OperatorName").include("OperateTime").include("UserName").include("Ip");
        // OperateTime 是 'yyyy-MM-dd HH:mm:ss' 字符串，字典序即时间序；_id 兜底稳定翻页
        pageQuery.with(Sort.by(Sort.Order.desc("OperateTime"), Sort.Order.desc("_id")));
        pageQuery.skip((long) (safePageNum - 1) * safePageSize);
        pageQuery.limit(safePageSize);

        List<Document> docs = datahubTemplate.find(pageQuery, Document.class, COLLECTION);

        // 一次批量查 User_Data：按账号补出「学校」和「姓名」两列
        Map<String, String> schoolByUser = new HashMap<>();
        Map<String, String> nameByUser = new HashMap<>();
        loadUserInfo(docs, schoolByUser, nameByUser);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Document doc : docs) {
            String userName = trim(doc.get("UserName"));
            Map<String, Object> row = new LinkedHashMap<>();
            // operatorName 是本表登记的操作人；realName 是账号在 User_Data 里的真实姓名
            row.put("operatorName", trim(doc.get("OperatorName")));
            row.put("realName", nameByUser.get(userName));
            row.put("userName", userName);
            row.put("operateTime", trim(doc.get("OperateTime")));
            row.put("ip", trim(doc.get("Ip")));
            row.put("school", schoolByUser.get(userName));
            records.add(row);
        }

        long totalPages = total == 0 ? 0 : (total + safePageSize - 1) / safePageSize;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", hasText(keyword) ? "搜索成功" : "查询成功");
        result.put("records", records);
        result.put("total", total);
        result.put("pageNum", safePageNum);
        result.put("pageSize", safePageSize);
        result.put("totalPages", totalPages);
        return result;
    }

    /**
     * 整页一次批量查 User_Data：把本页所有 UserName 去重后 $in 查一次，
     * 同时填充「用户名 -> 学校(Institution)」和「用户名 -> 姓名(NickName)」两个映射。
     * User_Data._id 可能带首尾空格，按 UserName 字段查更稳，命中键也统一 trim。
     */
    private void loadUserInfo(List<Document> docs, Map<String, String> schoolByUser, Map<String, String> nameByUser) {
        List<String> userNames = new ArrayList<>();
        for (Document doc : docs) {
            String userName = trim(doc.get("UserName"));
            if (!userName.isEmpty() && !userNames.contains(userName)) {
                userNames.add(userName);
            }
        }
        if (userNames.isEmpty()) {
            return;
        }
        Query query = new Query(Criteria.where("UserName").in(userNames));
        query.fields().include("UserName").include("Institution").include("NickName");
        for (Document doc : datahubTemplate.find(query, Document.class, USER_DATA)) {
            String userName = trim(doc.get("UserName"));
            if (userName.isEmpty()) {
                continue;
            }
            String institution = trim(doc.get("Institution"));
            if (!institution.isEmpty()) {
                schoolByUser.put(userName, institution);
            }
            String nickName = trim(doc.get("NickName"));
            if (!nickName.isEmpty()) {
                nameByUser.put(userName, nickName);
            }
        }
    }

    private void applyCriteria(Query query, List<Criteria> criteriaList) {
        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }
    }

    private static String trim(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
