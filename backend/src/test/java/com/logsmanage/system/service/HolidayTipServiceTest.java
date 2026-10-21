package com.logsmanage.system.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节假日公告的纯逻辑：文案渲染、日期格式、显示状态判定。都不连库。
 *
 * <p>这三样是「界面上看到的 / 库里存的 / 老站上显示的」三者对齐的关键：
 * <ul>
 *   <li>渲染口径必须与桌面端 {@code MainForm.holidayTip} 一致（{@code MM月dd日}、审核日 = 结束日 + 1 天）；</li>
 *   <li>状态判定必须与老站 {@code Login.cshtml} 的 {@code EndDate > DateTime.Now} 一致 ——
 *       结束日存的是当天 00:00，所以<b>结束日当天就是已过期</b>，最后可见日是它的前一天。
 *       看着别扭但那是线上真实行为，改这里等于改线上表现。</li>
 * </ul>
 */
class HolidayTipServiceTest {

    @Test
    void 默认文案把三个占位符换成中文日期且审核日是结束日次日() {
        String tip = HolidayTipService.renderTip(null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));
        assertEquals("重要提示：10月01日至10月07日的用户注册，于10月08日统一审核处理，"
                + "给您带来的不便敬请谅解！祝您假日快乐，万事如意！", tip);
    }

    @Test
    void 占位符替换要跨月和跨年都不出错() {
        String tip = HolidayTipService.renderTip("休 #STARTDATE# 到 #ENDDATE#，次日 #ENDDATE1# 处理",
                LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 31));
        assertEquals("休 12月31日 到 12月31日，次日 01月01日 处理", tip);
    }

    @Test
    void 自定义文案里没有占位符就原样输出() {
        String tip = HolidayTipService.renderTip("系统维护中，请稍后再试",
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        assertEquals("系统维护中，请稍后再试", tip);
    }

    @Test
    void 模板为空或空白时退回默认文案() {
        assertEquals(HolidayTipService.DEFAULT_TEMPLATE, HolidayTipService.effectiveTemplate(null));
        assertEquals(HolidayTipService.DEFAULT_TEMPLATE, HolidayTipService.effectiveTemplate("   "));
        assertEquals("自定义", HolidayTipService.effectiveTemplate("自定义"));
    }

    @Test
    void 日期一律两位补零() {
        assertEquals("01月05日", HolidayTipService.cnDate(LocalDate.of(2026, 1, 5)));
        assertEquals("12月31日", HolidayTipService.cnDate(LocalDate.of(2026, 12, 31)));
    }

    @Test
    void 结束日当天按老站口径已经算过期() {
        LocalDate end = LocalDate.of(2026, 10, 7);
        // 老站条件 EndDate > 现在；EndDate 是 10-07 00:00，所以 10-07 当天就不显示了
        assertEquals("active", HolidayTipService.statusOf(end, LocalDate.of(2026, 10, 6)));
        assertEquals("expired", HolidayTipService.statusOf(end, LocalDate.of(2026, 10, 7)));
        assertEquals("expired", HolidayTipService.statusOf(end, LocalDate.of(2026, 10, 8)));
    }

    @Test
    void 没有结束日期时状态是未设置() {
        assertEquals("unset", HolidayTipService.statusOf(null, LocalDate.of(2026, 10, 6)));
    }

    @Test
    void 解析日期兼容纯日期带时间与脏值() {
        assertEquals(LocalDate.of(2026, 10, 1), HolidayTipService.parseDate("2026-10-01"));
        assertEquals(LocalDate.of(2026, 10, 1), HolidayTipService.parseDate("2026-10-01T00:00:00.000+00:00"));
        assertEquals(LocalDate.of(2026, 10, 1), HolidayTipService.parseDate(" 2026-10-01 "));
        assertNull(HolidayTipService.parseDate(null));
        assertNull(HolidayTipService.parseDate(""));
        assertNull(HolidayTipService.parseDate("不是日期"));
    }

    @Test
    void 默认文案必须带齐三个占位符() {
        // 少一个占位符不会报错，只会在页面上留一句带 # 的原文 —— 桌面端改文案时也会撞到，
        // 所以在这里钉住默认文案的形状
        assertTrue(HolidayTipService.DEFAULT_TEMPLATE.contains("#STARTDATE#"));
        assertTrue(HolidayTipService.DEFAULT_TEMPLATE.contains("#ENDDATE#"));
        assertTrue(HolidayTipService.DEFAULT_TEMPLATE.contains("#ENDDATE1#"));
    }

    // ---------------------------------------------------------------- demotext 备份

    @Test
    void 备份文档带业务字段且日期是纯日期串() {
        java.util.Date time = new java.util.Date(1789000000000L);
        Document doc = HolidayTipService.backupFields("公告正文", LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7), "save", "张三", time);
        assertEquals("公告正文", doc.getString("tip"));
        assertEquals("2026-10-01", doc.getString("startDate"));
        assertEquals("2026-10-07", doc.getString("endDate"));
        assertEquals("save", doc.getString("action"));
        assertEquals("张三", doc.getString("operator"));
        assertEquals(time, doc.get("time"));
        // _id 由 upsert 的等值查询条件带出，塞进业务字段里会和 $set 冲突
        assertFalse(doc.containsKey("_id"));
    }

    @Test
    void 备份文档的缺省值不写成字符串null() {
        Document doc = HolidayTipService.backupFields(null, null, null, "disable", null, new java.util.Date());
        assertNull(doc.get("tip"));
        assertNull(doc.get("startDate"));
        assertNull(doc.get("endDate"));
        assertEquals("", doc.get("operator"));
    }

    @Test
    void 备份是否同步按文案和起止三样比对() {
        Document backup = HolidayTipService.backupFields("公告正文", LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7), "save", "张三", new java.util.Date());
        assertTrue(HolidayTipService.backupMatches("公告正文",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), backup));
        // 文案改了、或任一日期改了，都算没同步（桌面端改过公告也落在这一支）
        assertFalse(HolidayTipService.backupMatches("改过的正文",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), backup));
        assertFalse(HolidayTipService.backupMatches("公告正文",
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 7), backup));
        assertFalse(HolidayTipService.backupMatches("公告正文",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), backup));
        // 备份还没写过
        assertFalse(HolidayTipService.backupMatches("公告正文",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), null));
    }

    @Test
    void 备份失败的原因要截断成给用户看的一句话() {
        assertEquals("原因未知", HolidayTipService.shortError(null));
        assertEquals("原因未知", HolidayTipService.shortError("   "));
        // 驱动原文里那截「; nested exception ...」只是 Spring 包的一层，界面上看不出额外信息
        assertEquals("Timed out after 3000 ms while waiting to connect.",
                HolidayTipService.shortError("Timed out after 3000 ms while waiting to connect."
                        + "; nested exception is com.mongodb.MongoTimeoutException: Timed out after 3000 ms"));
        String longText = new String(new char[400]).replace('\0', 'x');
        assertEquals(201, HolidayTipService.shortError(longText).length());
        assertTrue(HolidayTipService.shortError(longText).endsWith("…"));
    }
}
