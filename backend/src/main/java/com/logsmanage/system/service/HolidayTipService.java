package com.logsmanage.system.service;

import com.logsmanage.system.utils.SqlUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节假日公告：老版 DataHub 网站（login.api.example.com）注册流程页顶部那句「X 月 X 日至 X 月 X 日的用户注册，
 * 于 X 月 X 日统一审核处理」的读写。
 *
 * <p>数据落在两个生产 SQL Server，与桌面端「DataHub账号管理系统 2.0」的节假日公告页签<b>完全同一处</b>：
 * <ol>
 *   <li><b>公告真身</b>：{@code cas.dbo.HolidayAnonTip} 的单行 {@code ID = 0}（Tip / StartDate / EndDate）。</li>
 *   <li><b>热更新通知</b>：{@code DataHub_SYS.dbo.ConfigFilesUpdate} 插一行 {@code DB = 'HolTIP'}。
 *       老站启动时把公告读进内存静态字段（{@code Manager.HolidayAnonTip}），后台线程每 2 分钟查一次
 *       当天的未处理行，查到就整体重载配置 —— 所以只写第 1 处，老站要等重启才变。</li>
 * </ol>
 *
 * <p>另有一处<b>备份</b>（业务方 2026-09-21 要求）：DemoText那台生产 Mongo
 * {@code 203.0.113.12:30000/demotext} 的 {@code holiday_tip} 单行文档（{@code _id = HolidayAnonTip}），
 * 与 cas 的 {@code ID = 0} 同构 —— 每次改动整体覆盖，只留「当前公告」一份。
 * 它是<b>第三处被动落点</b>：不参与老站显示（老站与桌面端都不读它），写失败也不影响公告已经生效，
 * 页面用 {@code backupSynced} 如实告诉操作员 demotext 那份还是不是最新的。
 *
 * <p><b>显示口径照抄老站，不要「顺手修正」</b>：老站的条件是 {@code EndDate > DateTime.Now}，
 * 而 EndDate 存的是所选日期的 00:00，所以<b>结束日当天用户已经看不到了</b>，最后可见日是结束日的前一天。
 * 桌面端也是这么存的；这里保持一致，界面负责把这个真实可见区间讲清楚（见 {@code lastVisibleDate}）。
 *
 * <p>编辑历史与「上次谁改的」存本项目自己的库（181 {@code DataHub_Log.holiday_tip_log}）——
 * 不往那两个生产 SQL 库里加表加列，老站和桌面端都不受影响。
 */
@Service
public class HolidayTipService {

    private static final Logger log = LoggerFactory.getLogger(HolidayTipService.class);

    /**
     * cas 库（含公告表）与账号同步用的是同一个库、同一套账号。
     * loginTimeout 是给界面加的保护：这两台都在公网，连不上时要在十几秒内报错，
     * 而不是让浏览器一直转圈等 axios 超时（默认没有登录超时）。
     */
    private static final String CAS_SQL_URL = setting(
            "datahub.userSql.url", "DataHub_USER_SQL_URL",
            "jdbc:sqlserver://login.api.example.com:1433;databaseName=cas;responseBuffering=adaptive;loginTimeout=10");
    private static final String CAS_SQL_USER = setting(
            "datahub.userSql.user", "DataHub_USER_SQL_USER", "jhadmin");
    private static final String CAS_SQL_PASSWORD = setting(
            "datahub.userSql.password", "DataHub_USER_SQL_PASSWORD", "byteloveadmin");

    /** 热更新通知表在 145 的 DataHub_SYS —— 与「注册信息」同步同一台库、同一套账号 */
    private static final String HOT_UPDATE_SQL_URL = setting(
            "datahub.registerSql.url", "DataHub_REGISTER_SQL_URL",
            "jdbc:sqlserver://203.0.113.11:1433;databaseName=DataHub_SYS;responseBuffering=adaptive;loginTimeout=10");
    private static final String HOT_UPDATE_SQL_USER = setting(
            "datahub.registerSql.user", "DataHub_REGISTER_SQL_USER", "sa");
    private static final String HOT_UPDATE_SQL_PASSWORD = setting(
            "datahub.registerSql.password", "DataHub_REGISTER_SQL_PASSWORD", "change_me");

    /** 通知标签，与桌面端 {@code SqlServerHelper145.HotUpdate("HolTIP")} 一致；老站不按它分发，只是约定 */
    private static final String HOT_UPDATE_TAG = "HolTIP";

    /** Tip 列是 nvarchar(2000)，超了 SQL Server 会报截断错，先在服务端挡掉 */
    private static final int MAX_TIP_LENGTH = 2000;

    /** 历史集合：本项目自己的库，老站/桌面端都不读它 */
    static final String LOG_COLLECTION = "holiday_tip_log";
    private static final int HISTORY_LIMIT = 20;

    /**
     * 备份落点：DemoText那台生产 Mongo 的 {@code holiday_tip} 单行文档。
     * 集合与 {@code _id} 都是本工具自己定的（demotext 里原先没有这个集合），
     * 单行覆盖而不是追加流水 —— 流水在本项目自己的 {@link #LOG_COLLECTION} 里，
     * demotext 那份只回答「当前公告是什么」。
     */
    static final String BACKUP_COLLECTION = "holiday_tip";
    static final String BACKUP_ID = "HolidayAnonTip";

    /** 默认文案，与桌面端 {@code MainForm.holidayTip} 逐字一致 */
    static final String DEFAULT_TEMPLATE =
            "重要提示：#STARTDATE#至#ENDDATE#的用户注册，于#ENDDATE1#统一审核处理，给您带来的不便敬请谅解！祝您假日快乐，万事如意！";

    /** 还没有任何公告时，界面预填的默认区间长度（今天起 N 天） */
    private static final int DEFAULT_DAYS = 7;

    private static final String SELECT_SQL =
            "SELECT Tip, StartDate, EndDate FROM dbo.HolidayAnonTip WHERE ID = 0";
    private static final String UPDATE_SQL =
            "UPDATE dbo.HolidayAnonTip SET Tip = ?, StartDate = ?, EndDate = ? WHERE ID = 0";
    /** ID 不是自增列（老站 EF 模型里没有 Identity），补行时必须显式给 0，跟桌面端 Find(0) 对齐 */
    private static final String INSERT_SQL =
            "INSERT INTO dbo.HolidayAnonTip (ID, Tip, StartDate, EndDate) VALUES (0, ?, ?, ?)";
    private static final String NOTIFY_SQL =
            "INSERT INTO dbo.ConfigFilesUpdate (isTest, [Date], isEnd, DB, isDownloadEnd, isDownloadEnd2) "
                    + "VALUES (0, ?, 0, ?, 1, 1)";

    private final MongoTemplate mongoTemplate;
    private final MongoTemplate demotextTemplate;

    public HolidayTipService(@Qualifier("MongoTemplate") MongoTemplate mongoTemplate,
                             @Qualifier("demotextTemplate") MongoTemplate demotextTemplate) {
        this.mongoTemplate = mongoTemplate;
        this.demotextTemplate = demotextTemplate;
    }

    // ------------------------------------------------------------------ 读

    /** 当前公告 + 状态 + 上次保存信息 */
    public Map<String, Object> current() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("defaultTemplate", DEFAULT_TEMPLATE);
        try {
            Row row = readRow();
            LocalDate today = LocalDate.now();
            out.put("success", true);
            out.put("exists", row != null);
            if (row == null) {
                out.put("tip", null);
                out.put("startDate", null);
                out.put("endDate", null);
                out.put("status", "unset");
                out.put("lastVisibleDate", null);
                out.put("backupSynced", null);
            } else {
                out.put("tip", row.tip);
                out.put("startDate", row.start == null ? null : row.start.toString());
                out.put("endDate", row.end == null ? null : row.end.toString());
                out.put("status", statusOf(row.end, today));
                out.put("lastVisibleDate", row.end == null ? null : row.end.minusDays(1).toString());
                // 备份是不是最新的，看 demotext 里那份的内容而不是历史里的成败标志：
                // 桌面端改过公告、或上次保存备份失败，这里都能如实反映。读不到给 null（界面显示「状态未知」）
                out.put("backupSynced", backupSynced(row));
            }
            // 编辑器直接编辑「最终文案」：管理员不该看到 #STARTDATE# 这类占位符，
            // 所以库里存什么就原样给什么，日期由前端在改动区间时就地替换。
            out.put("today", today.toString());
            // 一条公告都还没有时，给界面一份可以直接改的初值（今天起 7 天的默认文案）
            out.put("defaultStartDate", today.toString());
            out.put("defaultEndDate", today.plusDays(DEFAULT_DAYS - 1).toString());
            out.put("defaultText", renderTip(DEFAULT_TEMPLATE, today, today.plusDays(DEFAULT_DAYS - 1)));
            Map<String, Object> last = lastRecord();
            out.put("lastSaveAt", last == null ? null : last.get("time"));
            out.put("lastOperator", last == null ? null : last.get("operator"));
        } catch (SQLException e) {
            log.error("读取节假日公告失败", e);
            out.put("success", false);
            out.put("message", "读取公告失败：" + e.getMessage());
        }
        return out;
    }

    /** 最近若干次改动，新的在前 */
    public List<Map<String, Object>> history() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        Query query = new Query();
        query.with(Sort.by(Sort.Order.desc("time"))).limit(HISTORY_LIMIT);
        for (Document doc : mongoTemplate.find(query, Document.class, LOG_COLLECTION)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("time", doc.get("time"));
            item.put("operator", doc.get("operator"));
            item.put("action", doc.get("action"));
            item.put("tip", doc.get("tip"));
            item.put("startDate", doc.get("startDate"));
            item.put("endDate", doc.get("endDate"));
            item.put("notified", doc.get("notified"));
            item.put("notifyError", doc.get("notifyError"));
            item.put("backedUp", doc.get("backedUp"));
            item.put("backupError", doc.get("backupError"));
            item.put("status", statusOf(parseDate((String) doc.get("endDate")), LocalDate.now()));
            list.add(item);
        }
        return list;
    }

    // ------------------------------------------------------------------ 预览 / 保存 / 停用 / 重新通知

    /** 预览：只拼文案，不碰库。界面拿它做实时预览，保证预览与落库是同一段代码 */
    public String preview(String startDate, String endDate, String template) {
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        if (start == null || end == null) {
            return "";
        }
        return renderTip(template, start, end);
    }

    /**
     * 保存。{@code tip} 是界面里那份「最终文案」（日期已经替换好），直接落库；
     * 只有没给最终文案时才退回按模板渲染（兼容老调用方/桌面端那套口径）。
     */
    public Map<String, Object> save(String startDate, String endDate, String tip, String template, String operator) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        if (start == null || end == null) {
            return fail(out, "请选择开始日期和结束日期");
        }
        if (end.isBefore(start)) {
            return fail(out, "结束日期不能早于开始日期");
        }
        String text = tip == null ? "" : tip.trim();
        if (text.isEmpty()) {
            text = renderTip(template, start, end);
        }
        if (text.isEmpty()) {
            return fail(out, "公告文案不能为空");
        }
        if (text.length() > MAX_TIP_LENGTH) {
            return fail(out, "公告文案过长（" + text.length() + " 字，最多 " + MAX_TIP_LENGTH + " 字）");
        }

        String beforeTip = null;
        try {
            Row before = readRow();
            beforeTip = before == null ? null : before.tip;
            writeRow(text, start, end);
        } catch (SQLException e) {
            log.error("保存节假日公告失败", e);
            return fail(out, "保存失败：" + e.getMessage());
        }

        NotifyResult notify = notifyHotUpdate();
        BackupResult backup = backupToDemoText("save", operator, text, start, end);
        record("save", operator, text, start, end, beforeTip, notify, backup);
        out.put("success", true);
        out.put("tip", text);
        out.put("startDate", start.toString());
        out.put("endDate", end.toString());
        out.put("status", statusOf(end, LocalDate.now()));
        out.put("notified", Boolean.valueOf(notify.ok));
        out.put("backedUp", Boolean.valueOf(backup.ok));
        out.put("message", messageOf("已保存", "，约 2 分钟后对用户生效",
                "但热更新通知失败，用户端暂时不会变（可稍后点「重新通知生效」重试）", notify, backup));
        return out;
    }

    /**
     * 停用：把结束日期改成昨天 —— 老站的条件是 {@code EndDate > 现在}，于是立刻不再显示。
     * 不删那一行、不清文案，历史里还看得到它原本是什么。
     */
    public Map<String, Object> disable(String operator) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Row before;
        String tip;
        try {
            before = readRow();
            if (before == null) {
                return fail(out, "当前没有公告，无需停用");
            }
            tip = before.tip == null ? "" : before.tip;
            // 起止都落到昨天：老站只看 EndDate，StartDate 一并归位免得界面回显出自相矛盾的区间
            writeRow(tip, yesterday.minusDays(1), yesterday);
        } catch (SQLException e) {
            log.error("停用节假日公告失败", e);
            return fail(out, "停用失败：" + e.getMessage());
        }
        NotifyResult notify = notifyHotUpdate();
        BackupResult backup = backupToDemoText("disable", operator, tip, yesterday.minusDays(1), yesterday);
        record("disable", operator, tip, yesterday.minusDays(1), yesterday, before.tip, notify, backup);
        out.put("success", true);
        out.put("notified", Boolean.valueOf(notify.ok));
        out.put("backedUp", Boolean.valueOf(backup.ok));
        out.put("message", messageOf("已停用", "，约 2 分钟后不再对用户显示",
                "但热更新通知失败，用户端可能还会显示（可稍后点「重新通知生效」重试）", notify, backup));
        return out;
    }

    /** 只补发一次热更新通知，用于「保存成功但通知失败」时重试 */
    public Map<String, Object> notifyAgain(String operator) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Row row;
        try {
            row = readRow();
            if (row == null) {
                return fail(out, "当前没有公告，先保存一次再通知");
            }
        } catch (SQLException e) {
            log.error("读取节假日公告失败", e);
            return fail(out, "读取公告失败：" + e.getMessage());
        }
        NotifyResult notify = notifyHotUpdate();
        record(notify.ok ? "notify" : "notify-failed", operator, row.tip, row.start, row.end, null, notify, null);
        out.put("success", Boolean.valueOf(notify.ok));
        out.put("notified", Boolean.valueOf(notify.ok));
        out.put("message", notify.ok ? "已通知，约 2 分钟后生效" : "通知失败：" + notify.error);
        return out;
    }

    /**
     * 只补发一次 demotext 备份，用于「保存成功但备份失败」时重试；
     * 不改公告、不发热更新（公告本身已经在 cas 里生效了）。
     */
    public Map<String, Object> backupAgain(String operator) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Row row;
        try {
            row = readRow();
            if (row == null) {
                return fail(out, "当前没有公告，先保存一次再备份");
            }
        } catch (SQLException e) {
            log.error("读取节假日公告失败", e);
            return fail(out, "读取公告失败：" + e.getMessage());
        }
        BackupResult backup = backupToDemoText(lastContentAction(), operator, row.tip, row.start, row.end);
        record(backup.ok ? "backup" : "backup-failed", operator, row.tip, row.start, row.end, null, null, backup);
        out.put("success", Boolean.valueOf(backup.ok));
        out.put("backedUp", Boolean.valueOf(backup.ok));
        out.put("message", backup.ok ? "已备份" : "备份失败：" + shortError(backup.error));
        return out;
    }

    // ------------------------------------------------------------------ SQL

    private Connection casConnection() throws SQLException {
        return SqlUtils.getConnection(CAS_SQL_URL, CAS_SQL_USER, CAS_SQL_PASSWORD);
    }

    private Row readRow() throws SQLException {
        try (Connection connection = casConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            Row row = new Row();
            row.tip = rs.getString("Tip");
            row.start = toLocalDate(rs.getDate("StartDate"));
            row.end = toLocalDate(rs.getDate("EndDate"));
            return row;
        }
    }

    /** 有行就更新、没有就补一行 —— 与桌面端 {@code Find(0)} 取不到时 new 一个等价 */
    private void writeRow(String tip, LocalDate start, LocalDate end) throws SQLException {
        try (Connection connection = casConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
                statement.setString(1, tip);
                statement.setDate(2, toSqlDate(start));
                statement.setDate(3, toSqlDate(end));
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                    statement.setString(1, tip);
                    statement.setDate(2, toSqlDate(start));
                    statement.setDate(3, toSqlDate(end));
                    statement.executeUpdate();
                }
            }
        }
    }

    private NotifyResult notifyHotUpdate() {
        try (Connection connection = SqlUtils.getConnection(
                HOT_UPDATE_SQL_URL, HOT_UPDATE_SQL_USER, HOT_UPDATE_SQL_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(NOTIFY_SQL)) {
            statement.setDate(1, java.sql.Date.valueOf(LocalDate.now()));
            statement.setString(2, HOT_UPDATE_TAG);
            statement.executeUpdate();
            return NotifyResult.ok();
        } catch (SQLException e) {
            log.error("插入节假日公告热更新通知失败", e);
            return NotifyResult.failed(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 历史

    private void record(String action, String operator, String tip, LocalDate start, LocalDate end,
                        String beforeTip, NotifyResult notify, BackupResult backup) {
        try {
            Document doc = new Document();
            doc.put("time", new java.util.Date());
            doc.put("operator", operator == null ? "" : operator);
            doc.put("action", action);
            doc.put("tip", tip);
            doc.put("startDate", start == null ? null : start.toString());
            doc.put("endDate", end == null ? null : end.toString());
            doc.put("beforeTip", beforeTip);
            // 补发通知/补发备份这两种动作只碰一个失败域，另一个留 null（前端据此判断「这条没管那件事」）
            doc.put("notified", notify == null ? null : Boolean.valueOf(notify.ok));
            doc.put("notifyError", notify == null || notify.ok ? null : notify.error);
            doc.put("backedUp", backup == null ? null : Boolean.valueOf(backup.ok));
            doc.put("backupError", backup == null || backup.ok ? null : backup.error);
            mongoTemplate.insert(doc, LOG_COLLECTION);
        } catch (RuntimeException e) {
            // 历史只是辅助信息（回显「上次谁改的」）。181 写不进去不该让已经生效的公告算失败
            log.warn("写入节假日公告历史失败（公告本身已保存）", e);
        }
    }

    private Map<String, Object> lastRecord() {
        try {
            Query query = new Query();
            query.with(Sort.by(Sort.Order.desc("time"))).limit(1);
            List<Document> docs = mongoTemplate.find(query, Document.class, LOG_COLLECTION);
            if (docs.isEmpty()) {
                return null;
            }
            return docs.get(0);
        } catch (RuntimeException e) {
            log.warn("读取节假日公告历史失败", e);
            return null;
        }
    }

    /**
     * 补发备份时不知道该写 {@code save} 还是 {@code disable}（那取决于公告是被保存还是被停用改成现在这样的），
     * 从 181 历史里取最近一次内容改动的动作；取不到按 {@code save}。
     */
    private String lastContentAction() {
        try {
            Query query = Query.query(Criteria.where("action").in("save", "disable"));
            query.with(Sort.by(Sort.Order.desc("time"))).limit(1);
            List<Document> docs = mongoTemplate.find(query, Document.class, LOG_COLLECTION);
            return docs.isEmpty() ? "save" : String.valueOf(docs.get(0).get("action"));
        } catch (RuntimeException e) {
            log.warn("读取节假日公告上次动作失败", e);
            return "save";
        }
    }

    // ------------------------------------------------------------------ demotext 备份

    /**
     * 把当前公告整体覆盖到 demotext 的 {@code holiday_tip}（单行、固定 {@code _id}）。
     *
     * <p>用 upsert + {@code $set} 而不是 save 整文档替换：{@code _id} 由查询条件（等值）带出，
     * Mongo 在插入时会自动补进新文档，所以这里不需要、也不能用 {@code $set} 去写 {@code _id}。
     * 这条路失败只影响备份，不影响已经写进 cas 的公告。
     */
    private BackupResult backupToDemoText(String action, String operator, String tip, LocalDate start, LocalDate end) {
        try {
            Query query = Query.query(Criteria.where("_id").is(BACKUP_ID));
            Update update = new Update();
            for (Map.Entry<String, Object> field
                    : backupFields(tip, start, end, action, operator, new java.util.Date()).entrySet()) {
                update.set(field.getKey(), field.getValue());
            }
            demotextTemplate.upsert(query, update, BACKUP_COLLECTION);
            return BackupResult.ok();
        } catch (RuntimeException e) {
            log.error("备份节假日公告到 demotext 失败", e);
            return BackupResult.failed(e.getMessage());
        }
    }

    /**
     * demotext 那份是不是就是 cas 当前这份。
     * 读不到（备份还没写过、或那台库不可达）都不该让公告页报错，所以异常吞成 {@code null}（界面显示「状态未知」）。
     */
    private Boolean backupSynced(Row row) {
        try {
            Document backup = demotextTemplate.findById(BACKUP_ID, Document.class, BACKUP_COLLECTION);
            return Boolean.valueOf(backupMatches(row.tip, row.start, row.end, backup));
        } catch (RuntimeException e) {
            log.warn("读取 demotext 备份状态失败", e);
            return null;
        }
    }

    /**
     * 保存/停用共用的话术：热更新（用户端）和 demotext 备份是两个独立失败域，
     * 谁能成谁先成，失败的各给一个补救动作，不把「备份没写进去」说成公告没保存。
     */
    private static String messageOf(String done, String okSuffix, String notifyFailClause,
                                    NotifyResult notify, BackupResult backup) {
        if (notify.ok && backup.ok) {
            return done + okSuffix;
        }
        StringBuilder text = new StringBuilder(done);
        if (!notify.ok) {
            text.append("，").append(notifyFailClause);
        }
        if (!backup.ok) {
            text.append(notify.ok ? "，但备份失败（可稍后点「重新备份」重试）"
                    : "；备份也失败了（可稍后点「重新备份」重试）");
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ 纯函数（被 HolidayTipServiceTest 钉住）

    /**
     * 备份文档的业务字段（<b>不含 {@code _id}</b> —— 它由 upsert 的查询条件带出）。
     * 日期存 {@code yyyy-MM-dd} 字符串，与 181 历史表同口径，回读比对时不用再管时区。
     */
    static Document backupFields(String tip, LocalDate start, LocalDate end, String action, String operator,
                                java.util.Date time) {
        Document doc = new Document();
        doc.put("tip", tip);
        doc.put("startDate", start == null ? null : start.toString());
        doc.put("endDate", end == null ? null : end.toString());
        doc.put("action", action);
        doc.put("operator", operator == null ? "" : operator);
        doc.put("time", time);
        return doc;
    }

    /** 备份文档是否就是这份公告：文案 + 起止日期三样都对上才算同步（空值也要两边都空） */
    static boolean backupMatches(String tip, LocalDate start, LocalDate end, Document backup) {
        if (backup == null) {
            return false;
        }
        return sameText(tip, backup.getString("tip"))
                && sameText(start == null ? null : start.toString(), backup.getString("startDate"))
                && sameText(end == null ? null : end.toString(), backup.getString("endDate"));
    }

    private static boolean sameText(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /**
     * Mongo 驱动的异常原文动辄几百字（集群状态、嵌套异常全在里面），整条塞进界面提示只会变成一坨。
     * 去掉 Spring 包的那层嵌套、只留第一句并截到 200 字 —— 界面要的是「连不上还是写不进去」。
     */
    static String shortError(String error) {
        if (error == null || error.trim().isEmpty()) {
            return "原因未知";
        }
        String text = error.replace('\n', ' ').trim();
        int nested = text.indexOf("; nested exception");
        if (nested > 0) {
            text = text.substring(0, nested);
        }
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    /**
     * 文案渲染：{@code #STARTDATE#} 开始日、{@code #ENDDATE#} 结束日、{@code #ENDDATE1#} 结束日次日，
     * 一律 {@code MM月dd日}（与桌面端 {@code ToString("MM月dd日")} 一致）。
     * 模板为空时用默认文案；模板里没有占位符就原样输出（允许纯文本公告）。
     */
    static String renderTip(String template, LocalDate start, LocalDate end) {
        String text = effectiveTemplate(template);
        return text.replace("#STARTDATE#", cnDate(start))
                .replace("#ENDDATE#", cnDate(end))
                .replace("#ENDDATE1#", cnDate(end.plusDays(1)));
    }

    /** 模板为空就用默认文案；存历史时也存这份「实际用的模板」，编辑器下次回显才逐字准确 */
    static String effectiveTemplate(String template) {
        return template == null || template.trim().isEmpty() ? DEFAULT_TEMPLATE : template;
    }

    /** 不依赖 Locale 的格式化 —— DateTimeFormatter 在中文 pattern 上的行为不必赌 */
    static String cnDate(LocalDate date) {
        return String.format("%02d月%02d日", Integer.valueOf(date.getMonthValue()),
                Integer.valueOf(date.getDayOfMonth()));
    }

    /**
     * 与老站 {@code Login.cshtml} 的显示条件一致：{@code EndDate > 现在} 才显示。
     * 注意 EndDate 存的是当天 00:00，所以<b>结束日当天就是 expired</b>，最后可见日是它的前一天。
     */
    static String statusOf(LocalDate end, LocalDate today) {
        if (end == null) {
            return "unset";
        }
        return end.isAfter(today) ? "active" : "expired";
    }

    /** 兼容 {@code yyyy-MM-dd} 与带时间的写法（前端日期选择器给的是前者） */
    static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > 10) {
            text = text.substring(0, 10);
        }
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDate toLocalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Date toSqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("success", false);
        out.put("message", message);
        return out;
    }

    private static String setting(String property, String env, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(env);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static class Row {
        private String tip;
        private LocalDate start;
        private LocalDate end;
    }

    private static class NotifyResult {
        private boolean ok;
        private String error;

        static NotifyResult ok() {
            NotifyResult result = new NotifyResult();
            result.ok = true;
            return result;
        }

        static NotifyResult failed(String error) {
            NotifyResult result = new NotifyResult();
            result.ok = false;
            result.error = error;
            return result;
        }
    }

    /** demotext 备份那一处的成败，与 {@link NotifyResult} 分开 —— 两个失败域互不牵连 */
    private static class BackupResult {
        private boolean ok;
        private String error;

        static BackupResult ok() {
            BackupResult result = new BackupResult();
            result.ok = true;
            return result;
        }

        static BackupResult failed(String error) {
            BackupResult result = new BackupResult();
            result.ok = false;
            result.error = error;
            return result;
        }
    }
}
