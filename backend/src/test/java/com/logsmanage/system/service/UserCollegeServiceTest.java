package com.logsmanage.system.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住学校日志导出「学院」列的两条口径：学校共用账号跳过、映射键值都按 trim 归一。
 * 不连库：映射由文档列表直接喂给生产的 {@code collectCollege}。
 */
class UserCollegeServiceTest {

    @Test
    void treatsSharedSchoolAccountAsSchoolUser() {
        // 登录名：日志里 schooluser 有带尾空格的，也见过大小写不同的写法
        assertThat(UserCollegeService.isSchoolUser("schooluser", "学校账号")).isTrue();
        assertThat(UserCollegeService.isSchoolUser("  schooluser ", null)).isTrue();
        assertThat(UserCollegeService.isSchoolUser("SchoolUser", null)).isTrue();
        // 姓名：En_Log 那批补不到姓名，只有 UserName 可认；NewLog 那批两样都有
        assertThat(UserCollegeService.isSchoolUser(null, "学校账号")).isTrue();
        assertThat(UserCollegeService.isSchoolUser("other", " 学校账号 ")).isTrue();
    }

    @Test
    void keepsNearMissNamesAsNormalUsers() {
        // 必须是精确匹配（只容忍首尾空格/大小写）：不能把含「学校账号」的真实姓名、
        // 或 zhengshi 这类内部测试账号一并跳过 —— 它们有各自的学院
        assertThat(UserCollegeService.isSchoolUser("schooluser2", "学校账号2")).isFalse();
        assertThat(UserCollegeService.isSchoolUser("zhengshi", "正式账户")).isFalse();
        assertThat(UserCollegeService.isSchoolUser("xiongyaqi-hzsfdx", "熊雅琪")).isFalse();
        assertThat(UserCollegeService.isSchoolUser(null, null)).isFalse();
    }

    @Test
    void leavesCollegeEmptyForSchoolUserEvenWhenUserDataHasSuchAccount() {
        // 学校账号留空是「判定跳过」，不是「恰好查不到」：映射里真有 schooluser 也照样留空
        Map<String, String> byUserName = new HashMap<>();
        byUserName.put("schooluser", "经济与工商管理学院");

        Map<String, Object> row = new HashMap<>();
        row.put("UserName", "schooluser");
        row.put("NickName", "学校账号");
        UserCollegeService.fillCollege(row, byUserName);

        assertThat(row.get("College")).isNull();
    }

    @Test
    void fillsCollegeByNameAndLeavesUnknownUserEmpty() {
        Map<String, String> byUserName = new HashMap<>();
        byUserName.put("xiongyaqi-hzsfdx", "经济与工商管理学院");

        Map<String, Object> hit = new HashMap<>();
        hit.put("UserName", "xiongyaqi-hzsfdx");
        hit.put("NickName", "熊雅琪");
        UserCollegeService.fillCollege(hit, byUserName);
        assertThat(hit.get("College")).isEqualTo("经济与工商管理学院");

        Map<String, Object> miss = new HashMap<>();
        miss.put("UserName", "kongluyao");
        miss.put("NickName", "孔令瑶");
        UserCollegeService.fillCollege(miss, byUserName);
        assertThat(miss.get("College")).isNull();

        // 键也要 trim：日志侧 UserName 有带尾空格的（如 'jxut.edu.cn-gj '）
        Map<String, Object> padded = new HashMap<>();
        padded.put("UserName", "xiongyaqi-hzsfdx ");
        UserCollegeService.fillCollege(padded, byUserName);
        assertThat(padded.get("College")).isEqualTo("经济与工商管理学院");
    }

    @Test
    void loadsMapWithTrimmedKeysAndSkipsBlankCollege() {
        List<Document> documents = Arrays.asList(
                new Document("_id", "houyitian").append("College", "经济与工商管理学院"),
                // 库里 College 实测有 38 条带首尾空白（含换行、\xa0）
                new Document("_id", " wangqiao ").append("College", "\n\n金融学院 "),
                // 1500 条 null + 29 条空串不该进映射（查不到与学院为空对导出是同一件事）
                new Document("_id", "nocollege-null").append("College", null),
                new Document("_id", "nocollege-blank").append("College", "   "),
                new Document("_id", "nocollege-missing"));

        Map<String, String> out = new HashMap<>();
        UserCollegeService.collectCollege(out, documents);

        assertThat(out).containsOnlyKeys("houyitian", "wangqiao");
        assertThat(out.get("houyitian")).isEqualTo("经济与工商管理学院");
        assertThat(out.get("wangqiao")).isEqualTo("金融学院");
    }

    @Test
    void insertsCollegeRightAfterInstitution() {
        // 学校日志页的表格列顺序取自首行的字段顺序（buildLogColumns），学院必须落在
        // 「机构」后面，才能和 Excel 的列顺序一致（直接 put 会排到操作时间之后）。
        Map<String, String> byUserName = new HashMap<>();
        byUserName.put("xiongyaqi-hzsfdx", "经济与工商管理学院");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("UserName", "xiongyaqi-hzsfdx");
        row.put("NickName", "熊雅琪");
        row.put("Institution", "示例师范大学");
        row.put("Mail", "1919637462@qq.com");
        UserCollegeService.fillCollege(row, byUserName);

        assertThat(new ArrayList<>(row.keySet()))
                .containsExactly("UserName", "NickName", "Institution", "College", "Mail");
        assertThat(row.get("College")).isEqualTo("经济与工商管理学院");

        // 已带 College 键的行（重复处理）就地覆盖，不许把位置挪到最后
        UserCollegeService.fillCollege(row, byUserName);
        assertThat(new ArrayList<>(row.keySet()))
                .containsExactly("UserName", "NickName", "Institution", "College", "Mail");
    }

    @Test
    void appendsCollegeWhenInstitutionKeyIsMissing() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("NickName", "熊雅琪");
        UserCollegeService.fillCollege(row, new HashMap<String, String>());

        assertThat(new ArrayList<>(row.keySet())).containsExactly("NickName", "College");
        assertThat(row.get("College")).isNull();
    }

    @Test
    void handlesEmptyCursorAndNullRecord() {
        Map<String, String> out = new HashMap<>();
        UserCollegeService.collectCollege(out, Collections.<Document>emptyList());
        assertThat(out).isEmpty();
        // 防御性：记录为 null 不该抛异常
        UserCollegeService.fillCollege(null, out);
    }
}
