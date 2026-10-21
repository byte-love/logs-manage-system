package com.logsmanage.system.websocket;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学校表（Ip_Adr）同步写入前的空白清洗。不连库、不连源。
 *
 * <p>直接调生产的 {@code cleanIpAdrWhitespace}，不在测试里复刻一份清洗逻辑 —— 复刻的写法
 * 生产代码改坏了也不会红。
 *
 * <p>背景：这张表是从 SQL Server 迁过来的，{@code Adress}/{@code endDate}/{@code suf}/
 * {@code mangerName} 当年是 CHAR 定长列、尾部补空格。同步原本整文档原样搬运，于是 181 库里
 * 存的就是一列带填充空格的学校名（协作者 2026-09-15 就是从库里看出来的）。现在改成写入前洗，
 * 口径与读取侧 {@code SchoolService} 一致：trim + 内部连续空白折成一个空格。
 *
 * <p>这里钉住三件事：四个字段确实都洗了、{@code Ip}/{@code jurisdiction} 确实没被碰、
 * 以及空串不会被顺手改成 null（那会改变「字段是空串还是不存在」的语义，前端显示不同）。
 */
class IpAdrWhitespaceTest {

    @Test
    void 尾部填充空格被去掉() {
        // 学校账号那批实测是补齐到 100 字符，这里等价地补一长串
        Document document = new Document("Adress", "示例师范大学                    ");
        UserAndLogExportToMongo.cleanIpAdrWhitespace(document);
        assertEquals("示例师范大学", document.getString("Adress"));
    }

    @Test
    void 内部连续空格折成一个() {
        Document document = new Document("Adress", "  华中  师范大学  ");
        UserAndLogExportToMongo.cleanIpAdrWhitespace(document);
        assertEquals("华中 师范大学", document.getString("Adress"));
    }

    @Test
    void 内部换行折成一个空格() {
        // suf 实测有 "@\nfafu.edu.cn" 这种夹换行的值
        Document document = new Document("suf", "@\nfafu.edu.cn");
        UserAndLogExportToMongo.cleanIpAdrWhitespace(document);
        assertEquals("@ fafu.edu.cn", document.getString("suf"));
    }

    @Test
    void 四个字段都洗() {
        Document document = new Document("Adress", " 学校名 ")
                .append("endDate", "2026-12-31   ")
                .append("suf", "  @edu.cn ")
                .append("mangerName", "  负责人H基础库   ");
        UserAndLogExportToMongo.cleanIpAdrWhitespace(document);
        assertEquals("学校名", document.getString("Adress"));
        assertEquals("2026-12-31", document.getString("endDate"));
        assertEquals("@edu.cn", document.getString("suf"));
        assertEquals("负责人H基础库", document.getString("mangerName"));
    }

    @Test
    void Ip与jurisdiction原样保留() {
        String ip = "1.1.1.1, 2.2.2.2,\n3.3.3.3";
        String jurisdiction = "{ \"DB001\": { \"expirydate\": \"2026-12-31\" } }";
        Document document = new Document("Ip", ip).append("jurisdiction", jurisdiction);
        UserAndLogExportToMongo.cleanIpAdrWhitespace(document);
        assertEquals(ip, document.getString("Ip"), "IP 段是逗号分隔的多值串，折叠空白会破坏它");
        assertEquals(jurisdiction, document.getString("jurisdiction"), "jurisdiction 是 JSON 原文");
    }

    @Test
    void 非字符串值原样不动() {
        // 这张表字段类型不统一（数字/布尔/日期都出现过），强转字符串会炸
        Date endDate = new Date(1767225600000L);
        Document document = new Document("Adress", 123)
                .append("endDate", endDate)
                .append("suf", Boolean.TRUE)
                .append("mangerName", 45);
        UserAndLogExportToMongo.cleanIpAdrWhitespace(document);
        assertEquals(123, document.get("Adress"));
        assertEquals(endDate, document.get("endDate"));
        assertEquals(Boolean.TRUE, document.get("suf"));
        assertEquals(45, document.get("mangerName"));
    }

    @Test
    void 缺失字段不报错也不凭空补字段() {
        Document document = new Document("Adress", "学校");
        UserAndLogExportToMongo.cleanIpAdrWhitespace(document);
        assertFalse(document.containsKey("endDate"));
        assertFalse(document.containsKey("suf"));
        assertFalse(document.containsKey("mangerName"));
        assertEquals(1, document.size());
    }

    @Test
    void 空串洗过仍是空串不变成null() {
        Document document = new Document("suf", "   ");
        UserAndLogExportToMongo.cleanIpAdrWhitespace(document);
        assertTrue(document.containsKey("suf"), "字段不能被抹掉");
        assertEquals("", document.getString("suf"), "空串洗过仍是空串");
    }
}
