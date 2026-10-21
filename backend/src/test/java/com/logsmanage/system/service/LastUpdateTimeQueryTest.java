package com.logsmanage.system.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * getLastUpdateTime() 的查询条件形状，不连库、只校验拼出来的 BSON。
 *
 * 三种同步（User_Data / Log / Log_Login）都往 User_Data_UpdateTime 插完成时间，
 * 读取端必须按 type 过滤；而 type 字段是 r13342 才加的，之前的历史记录没有这个
 * 字段，当时只有账号表会写这个集合 —— 所以「字段缺失」必须等同于 User_Data。
 * 少了 $exists:false 那个分支，界面会退回「未同步过」。
 */
class LastUpdateTimeQueryTest {

    /** 与 UserDataService 里同名常量保持一致。 */
    private static final String SYNC_TYPE_USER_DATA = "User_Data";

    /** 复刻 UserDataService.getLastUpdateTime(type) 的条件构造。 */
    private static Query buildQuery(String type) {
        Query query = new Query();
        if (type != null) {
            if (SYNC_TYPE_USER_DATA.equals(type)) {
                query.addCriteria(new Criteria().orOperator(
                        Criteria.where("type").is(SYNC_TYPE_USER_DATA),
                        Criteria.where("type").exists(false)));
            } else {
                query.addCriteria(Criteria.where("type").is(type));
            }
        }
        query.with(Sort.by(Sort.Direction.DESC, "updateTime"));
        query.limit(1);
        return query;
    }

    @Test
    void 账号表要同时匹配type等于User_Data和type缺失() {
        String json = buildQuery(SYNC_TYPE_USER_DATA).getQueryObject().toJson();
        assertTrue(json.contains("$or"), "必须是 $or 两个分支: " + json);
        assertTrue(json.contains("User_Data"), "要有 type == User_Data 分支: " + json);
        assertTrue(json.contains("$exists"), "要有 type 缺失分支，兼容 r13342 之前的历史记录: " + json);
        assertTrue(json.contains("false"), "$exists 必须是 false: " + json);
    }

    @Test
    void 日志类型走严格相等不带exists分支() {
        // 日志的完成时间是 r13342 才开始写的，不存在「字段缺失」的历史记录；
        // 若也加上 $exists:false，会把账号表的老记录误当成日志的。
        String json = buildQuery("Log").getQueryObject().toJson();
        assertTrue(json.contains("Log"), "要按 type == Log 过滤: " + json);
        assertTrue(!json.contains("$exists"), "日志类型不该有 $exists 分支: " + json);
        assertTrue(!json.contains("$or"), "日志类型不该是 $or: " + json);
    }

    @Test
    void 传null表示不过滤() {
        assertEquals("{}", buildQuery(null).getQueryObject().toJson(),
                "type 传 null 时不加任何条件");
    }

    @Test
    void 一律按updateTime倒序取一条() {
        Query query = buildQuery(SYNC_TYPE_USER_DATA);
        assertEquals(1, query.getLimit(), "只取最新一条");
        String sort = query.getSortObject().toJson();
        assertTrue(sort.contains("updateTime"), "要按 updateTime 排序: " + sort);
        assertTrue(sort.contains("-1"), "必须是倒序: " + sort);
    }
}
