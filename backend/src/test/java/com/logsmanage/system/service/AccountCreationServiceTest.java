package com.logsmanage.system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logsmanage.system.dto.CreateAccountRequest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountCreationServiceTest {

    @Test
    void blankIpRangeMatchesLegacyWildcardDefault() {
        assertThat(AccountCreationService.normalizeIpRanges(null)).isEqualTo("*");
        assertThat(AccountCreationService.normalizeIpRanges("   ")).isEqualTo("*");
        assertThat(AccountCreationService.normalizeIpRanges("*")).isEqualTo("*");
    }

    @Test
    void normalizesLegacySeparatorsAndWhitespace() {
        assertThat(AccountCreationService.normalizeIpRanges(
                "198.51.100.1 - 198.51.100.5，\r\n198.51.100.6-198.51.100.7"))
                .isEqualTo("198.51.100.1-198.51.100.5,198.51.100.6-198.51.100.7");
    }

    @Test
    void acceptsTheSameMaximumDifferenceAsLegacyTool() {
        assertThat(AccountCreationService.normalizeIpRanges(
                "198.51.0.0-198.52.0.0"))
                .isEqualTo("198.51.0.0-198.52.0.0");
    }

    @Test
    void rejectsInvalidOrOversizedRanges() {
        assertThatThrownBy(() -> AccountCreationService.normalizeIpRanges(
                "198.51.100.2-198.51.100.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("起始地址");
        assertThatThrownBy(() -> AccountCreationService.normalizeIpRanges(
                "198.51.0.0-198.52.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("范围过大");
        assertThatThrownBy(() -> AccountCreationService.normalizeIpRanges(
                "198.51.100.999-198.51.100.999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IP 地址无效");
    }

    @Test
    void buildsFormalAndTrialLevelsForEveryPublishedSystem() throws Exception {
        AccountCreationService service = new AccountCreationService(null, null, new ObjectMapper());
        CreateAccountRequest.AccountPermission formal = permission(
                "FORMAL", true, "2027-08-08 12:00:00");
        CreateAccountRequest.AccountPermission trial = permission(
                "TRIAL", false, "2027-09-09 13:00:00");

        String json = service.buildInfo(
                Arrays.asList(formal, trial),
                Arrays.asList(
                        new AccountCreationService.SystemOption("FORMAL", "正式库", "普通", null),
                        new AccountCreationService.SystemOption("TRIAL", "试用库", "高级", null)),
                "贵州大学经济学院");

        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("FORMAL").get("level").asDouble()).isEqualTo(2D);
        assertThat(root.get("TRIAL").get("level").asDouble()).isEqualTo(1.5D);
        assertThat(root.get("TRIAL").get("expirydate").asText())
                .isEqualTo("2027-09-09 13:00:00");
        assertThat(root.get("CICD").get("level").asDouble()).isEqualTo(2D);
        assertThat(root.get("CICD").get("expirydate").asText())
                .isEqualTo("2027-08-08 12:00:00");
    }

    @Test
    void writesFormalPermissionsBeforeTrialAndUsesLastFormalExpiryForCicd() throws Exception {
        AccountCreationService service = new AccountCreationService(null, null, new ObjectMapper());
        List<AccountCreationService.SystemOption> systems = Arrays.asList(
                new AccountCreationService.SystemOption("TRIAL", "试用库", "普通", "上市公司经营研究"),
                new AccountCreationService.SystemOption("FORMAL_A", "正式库 A", "普通", "上市公司经营研究"),
                new AccountCreationService.SystemOption("FORMAL_B", "正式库 B", "普通", "上市公司经营研究"));

        String json = service.buildInfo(
                Arrays.asList(
                        permission("TRIAL", false, "2027-01-01 01:00:00"),
                        permission("FORMAL_A", true, "2027-02-02 02:00:00"),
                        permission("FORMAL_B", true, "2027-03-03 03:00:00")),
                systems,
                "贵州大学");

        JsonNode root = new ObjectMapper().readTree(json);
        List<String> fieldOrder = new ArrayList<>();
        root.fieldNames().forEachRemaining(fieldOrder::add);

        assertThat(fieldOrder).containsExactly("FORMAL_A", "FORMAL_B", "TRIAL", "CICD");
        assertThat(root.get("CICD").get("expirydate").asText())
                .isEqualTo("2027-03-03 03:00:00");
    }

    @Test
    void filtersAndGroupsSystemsUsingTheLegacyPartitionOrder() {
        AccountCreationService.SystemOption secondA = new AccountCreationService.SystemOption(
                "SECOND_A", "第二组 A", "普通", "上市公司文本信息");
        AccountCreationService.SystemOption firstA = new AccountCreationService.SystemOption(
                "FIRST_A", "第一组 A", "普通", "上市公司经营研究");
        AccountCreationService.SystemOption unknown = new AccountCreationService.SystemOption(
                "UNKNOWN", "新分组", "普通", "未来新增分组");
        AccountCreationService.SystemOption firstB = new AccountCreationService.SystemOption(
                "FIRST_B", "第一组 B", "普通", "上市公司经营研究");
        List<AccountCreationService.SystemOption> published =
                Arrays.asList(secondA, firstA, unknown, firstB);

        List<AccountCreationService.SystemOption> ordered =
                AccountCreationService.legacySystemOrder(published);

        assertThat(ordered).containsExactly(firstA, firstB, secondA);
    }

    @Test
    void rejectsMissingOrUnknownPublishedPermissions() {
        AccountCreationService service = new AccountCreationService(null, null, new ObjectMapper());
        AccountCreationService.SystemOption published =
                new AccountCreationService.SystemOption("KNOWN", "已发布库", "普通", null);

        assertThatThrownBy(() -> service.buildInfo(
                Arrays.asList(permission("UNKNOWN", true, "2027-08-08 12:00:00")),
                Arrays.asList(published),
                "测试大学"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未发布或不存在");
        assertThatThrownBy(() -> service.buildInfo(
                Arrays.asList(), Arrays.asList(published), "测试大学"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("配置已变化");
    }

    @Test
    void mapsEachSqlGeneratedIdAndExpireDateToTheCorrectMongoDocument() {
        Document commonDocument = new Document("UserName", "test-user")
                .append("Phone", "13800138000");

        Document backupDocument =
                AccountCreationService.buildBackupDocument(commonDocument, 202);
        Document displayDocument =
                AccountCreationService.buildDisplayDocument(commonDocument, 101);

        assertThat(backupDocument.getInteger("ID")).isEqualTo(202);
        assertThat(backupDocument.getString("Phone")).isEqualTo("13800138000");
        assertThat(backupDocument).containsKey("ExpireDate");
        assertThat(backupDocument.get("ExpireDate")).isNull();

        assertThat(displayDocument.getInteger("ID")).isEqualTo(101);
        assertThat(displayDocument).doesNotContainKey("Phone");
        assertThat(displayDocument).containsKey("ExpireDate");
        assertThat(displayDocument.get("ExpireDate")).isNull();

        assertThat(commonDocument).doesNotContainKeys("ID", "ExpireDate");
        assertThat(commonDocument).containsKey("Phone");
    }

    @Test
    void buildsCreateUserLogWithoutPersistingThePassword() {
        Date createTime = new Date(1786165200000L);
        Document commonDocument = new Document("_id", "new-user")
                .append("UserName", "new-user")
                .append("Password", "secret-hash")
                .append("NickName", "测试用户")
                .append("Institution", "测试大学")
                .append("Info", "{\"TEST\":{\"level\":2}}");

        Document logDocument = AccountCreationService.buildCreateUserLogDocument(
                commonDocument, 101, 202, "张三", "市场部申请创建", createTime);
        Document userInfo = logDocument.get("UserInfo", Document.class);

        assertThat(logDocument.getString("UserName")).isEqualTo("new-user");
        assertThat(logDocument.getString("MarketInitiator")).isEqualTo("张三");
        assertThat(logDocument.getString("OtherDescription")).isEqualTo("市场部申请创建");
        assertThat(logDocument.getDate("CreateTime")).isEqualTo(createTime);
        assertThat(userInfo.getInteger("PrimaryUserId")).isEqualTo(101);
        assertThat(userInfo.getInteger("DataHubSysUserId")).isEqualTo(202);
        assertThat(userInfo.getString("Institution")).isEqualTo("测试大学");
        assertThat(userInfo).doesNotContainKeys("_id", "Password");
        assertThat(commonDocument).containsKeys("_id", "Password");
    }

    private CreateAccountRequest.AccountPermission permission(
            String code, boolean selected, String expiryDate) {
        CreateAccountRequest.AccountPermission permission =
                new CreateAccountRequest.AccountPermission();
        permission.setCode(code);
        permission.setSelected(selected);
        permission.setExpiryDate(expiryDate);
        return permission;
    }
}
