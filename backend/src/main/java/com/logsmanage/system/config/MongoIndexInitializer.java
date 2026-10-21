package com.logsmanage.system.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等地确保查询所需索引存在。
 *
 * <p>账号状态筛选中的「到期」条件需要按 xuezhi 等值 + ruxueYear 范围检索，
 * {xuezhi, ruxueYear} 复合索引可让该条件走索引扫描，避免 12.9 万文档全表扫描。
 *
 * <p>ensureIndex 对已存在的同名同结构索引是空操作，重复启动不会重建。
 */
@Component
public class MongoIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private static final String USER_DATA = "User_Data";
    private static final String NEW_LOG = "NewLog";
    private static final String LOG_LOGIN = "Log_Login";
    // 中英文版项目同步过来的日志（websocket 首次同步才建集合，同 Log_Login 要判存在）
    private static final String EN_LOG = "En_Log";
    private static final String IDX_XUEZHI_RUXUE = "idx_xuezhi_ruxueYear";
    private static final String IDX_USERNAME_ODATE = "idx_userName_oDate";

    private final MongoTemplate mongoTemplate;

    public MongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // 用 ApplicationReadyEvent 而非构造期执行，避免建索引耗时拖慢启动健康检查
    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        ensureIndex(USER_DATA, IDX_XUEZHI_RUXUE, new Index()
                .on("xuezhi", Sort.Direction.ASC)
                .on("ruxueYear", Sort.Direction.ASC)
                .named(IDX_XUEZHI_RUXUE)
                .background());
        // 按用户名精确导出日志时，需要同时过滤 UserName 并按 O_date 倒序走游标；
        // 三张日志集合都建同结构索引，避免大用户导出退化成全表扫描或内存排序。
        ensureIndex(NEW_LOG, IDX_USERNAME_ODATE, new Index()
                .on("UserName", Sort.Direction.ASC)
                .on("O_date", Sort.Direction.DESC)
                .named(IDX_USERNAME_ODATE)
                .background());
        if (mongoTemplate.collectionExists(LOG_LOGIN)) {
            ensureIndex(LOG_LOGIN, IDX_USERNAME_ODATE, new Index()
                    .on("UserName", Sort.Direction.ASC)
                    .on("O_date", Sort.Direction.DESC)
                    .named(IDX_USERNAME_ODATE)
                    .background());
        }
        // 日志数据页/学校日志都要按 O_date 倒序取整页，En_Log 同样需要这个复合索引，
        // 否则按机构或时间浏览时退化成全表扫描 + 内存排序。
        if (mongoTemplate.collectionExists(EN_LOG)) {
            ensureIndex(EN_LOG, IDX_USERNAME_ODATE, new Index()
                    .on("UserName", Sort.Direction.ASC)
                    .on("O_date", Sort.Direction.DESC)
                    .named(IDX_USERNAME_ODATE)
                    .background());
        }
        // 曾经还建过 idx_createDate（{CreateDate:-1,_id:-1}），供「按开通时间倒序」的默认排序用。
        // 该排序已废弃（开通时间随续约重置），索引不再需要 —— 生产库里的旧索引可手工 dropIndex 清掉
    }

    private void ensureIndex(String collection, String name, Index index) {
        try {
            long start = System.currentTimeMillis();
            String created = mongoTemplate.indexOps(collection).ensureIndex(index);
            log.info("索引就绪: {}.{} ({}ms)", collection, created, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 索引建不上只影响查询速度，不该阻断应用启动
            log.warn("确保索引 {}.{} 失败，相关查询将退化为全表扫描/内存排序: {}",
                    collection, name, e.getMessage());
        }
    }
}
