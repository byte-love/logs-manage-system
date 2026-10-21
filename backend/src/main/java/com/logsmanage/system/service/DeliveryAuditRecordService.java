package com.logsmanage.system.service;

import com.logsmanage.system.utils.SensitiveFields;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Delivery 审核记录分页，并批量补齐同库申请资料。 */
@Service
public class DeliveryAuditRecordService {
    private static final String AUDIT_COLLECTION = "delivery_auditRecord";
    private static final String APPLY_COLLECTION = "delivery_apply";
    private final MongoTemplate template;
    public DeliveryAuditRecordService(@Qualifier("datahubSysTemplate") MongoTemplate template) { this.template = template; }

    public Map<String, Object> page(int pageNum, int pageSize, String keyword) {
        Query q = new Query();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = java.util.regex.Pattern.quote(keyword.trim());
            // 只搜本表已知字段：候选值用 Pattern.quote 转义，避免用户输入被当成正则
            q.addCriteria(new Criteria().orOperator(
                    Criteria.where("account").regex(k, "i"), Criteria.where("nickName").regex(k, "i"),
                    Criteria.where("school").regex(k, "i"), Criteria.where("responsiblePerson").regex(k, "i"),
                    Criteria.where("deliveryApplyId").regex(k, "i"), Criteria.where("_id").regex(k, "i")));
        }
        long total = template.count(q, AUDIT_COLLECTION);
        q.with(Sort.by(Sort.Order.desc("operateTime"), Sort.Order.desc("_id")));
        q.skip((long)(pageNum - 1) * pageSize).limit(pageSize);
        List<Document> docs = template.find(q, Document.class, AUDIT_COLLECTION);
        List<Object> ids = new ArrayList<Object>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Document d : docs) { Object id=d.get("deliveryApplyId"); if(id!=null && seen.add(String.valueOf(id))) ids.add(id); }
        Map<String, Document> applies = new LinkedHashMap<String, Document>();
        // 申请表的关联键是 _id（32 位字符串），不是 id —— 表里压根没有 id 字段，按 id 查恒不命中
        if (!ids.isEmpty()) for (Document d : template.find(Query.query(Criteria.where("_id").in(ids)), Document.class, APPLY_COLLECTION)) applies.put(String.valueOf(d.get("_id")), d);
        List<Map<String,Object>> records=new ArrayList<Map<String,Object>>();
        for(Document d:docs){
            Map<String,Object> row=new LinkedHashMap<String,Object>(d);
            // 手机号属敏感信息，不回传前端（与同步侧 document.remove("Phone") 同一口径）。
            // 主表和申请表各洗一遍，省得将来哪张表多个手机号字段就漏出去
            SensitiveFields.stripPhone(row);
            Object id=d.get("deliveryApplyId");
            if(id!=null&&applies.containsKey(String.valueOf(id))){
                Map<String,Object> apply=new LinkedHashMap<String,Object>(applies.get(String.valueOf(id)));
                SensitiveFields.stripPhone(apply);
                row.put("apply",apply);
            }
            records.add(row);
        }
        Map<String,Object> out=new LinkedHashMap<String,Object>(); out.put("success",true); out.put("message","查询成功"); out.put("records",records); out.put("total",total); out.put("pageNum",pageNum); out.put("pageSize",pageSize); out.put("totalPages",total==0?0:(total+pageSize-1)/pageSize); return out;
    }
}
