# 日志管理与账号运维后台

单页后台，把「账号数据 / 操作日志」从源库同步到本地 MongoDB，提供检索、排行、导出和一批运维工具。

Spring Boot + Vue 3，前端产物打进后端 jar，生产单个进程对外服务（含 WebSocket 增量同步）。

## 技术栈

| 层 | 用的什么 |
|---|---|
| 后端 | Spring Boot 2.7.18 / **Java 8** / Spring Data MongoDB（多数据源）/ Apache POI 5.2.5 / spring-boot-starter-websocket / spring-security-crypto / ip2region 2.7.0 / aliyun-sdk-oss / mssql-jdbc |
| 前端 | Vue 3 + Vite 5 + Element Plus 2.8 + axios（`baseURL: '/api'`，同源相对路径） |

> 字节码必须是 Java 8（class 52），Maven 里用 `<release>8</release>` 而不是 `source`/`target`。
> `source/target=8` 只约束字节码版本，编译时仍拿高版本 JDK 的类库，`InputStream.readAllBytes()`、`Map.of()` 这类 Java 9+ API 能编过、上线才抛 `NoSuchMethodError`；`release=8` 会把 API 也限制在 8，让问题在编译期就暴露。

## 目录

```text
backend/
  src/main/java/com/logsmanage/system/
    controller/      AuthController / UserDataController / ExportController / SchoolController /
                     HolidayTipController / IpBindRecordController / ResponsibleListController /
                     DeliveryAuditRecordController
    service/         UserDataService（检索与聚合主体）/ AccountCreationService / RegisterFileService /
                     SchoolService / UserCollegeService / HolidayTipService / ...
    websocket/       UserAndLogExportToMongo —— @ServerEndpoint("/websocket/{type}")
    config/          MultipleMongoConfig（多数据源 bean）/ MongoIndexInitializer / WebConfig / SpaForwardController
    utils/           IpCityResolver（ip2region）/ SensitiveFields / OssClientDataHub
  src/main/resources/
    application.yml      多数据源与初始管理员配置
    ip2region_v4.xdb     离线 IP 归属地库
  src/test/java/          9 个测试类，73 个用例，不连库不连源
frontend/
  src/views/        Home（主界面）/ Login
  src/components/    home/ 各面板 + layout/ 侧栏与顶栏
  src/constants/     nav.js（导航结构）/ logColumns.js（表格列定义）/ brand.js
scripts/            build.sh（打包）/ deploy.sh（部署）
```

## 功能页签

侧栏结构在 `frontend/src/constants/nav.js`，前端按 key 渲染内容区：

| 一级 | 二级 |
|---|---|
| 账号数据 | — |
| 日志数据 | — |
| 学校列表 | 学校列表 / 负责人列表 |
| 审核记录 | 审核记录 / 数据交付记录 |
| 异常情况 | 异常操作 / 禁用列表 / 加绑 IP 登记 |
| 实用工具 | 卡片墙：用户日志导出 / 学校日志导出 / 创建正式账号 / 节假日公告 |
| 数据同步 | 任务卡片（一条连接同步一张表） |

## 本地启动

后端（8081，devtools 热重启）：

```bash
cd backend
mvn spring-boot:run
```

前端（5173，`/api` 与 `/websocket` 都代理到 8081）：

```bash
cd frontend
npm install
npm run dev
```

验证「一体化单 jar」形态（前端与 API 同源，与生产一致）：

```bash
cd backend && mvn package -DskipTests
java -jar target/logs-manage-system-1.0.0.jar --server.port=8090
```

## 配置

`backend/src/main/resources/application.yml`：

```yaml
spring.data.mongodb.uri: mongodb://127.0.0.1:27017/<主库>   # @Primary，检索与写入目标
datahub.uri:   ...   # 辅助库（只读）
datahubsys.uri: ...  # 另一个库的独立集合
demotext.uri:  ...   # 公告备份落点（只写）
app.init-admin:
  enabled: true      # 启动时若管理员集合为空则插入一个默认管理员（幂等）
```

四个数据源各自是**独立的顶层前缀 + 无参 `@Bean @ConfigurationProperties`**，见 `config/MultipleMongoConfig.java`。不要把带 `@Value` 参数的 `@Bean` 塞进 `@Configuration` 里设 uri —— CGLIB 代理下注入会失效且不报错。

## 打包

```bash
bash scripts/build.sh
```

依次做五件事：停本地 8081/8090（否则 jar 被占用）→ 前端 build → 清空并同步进 `backend/src/main/resources/static/` → `mvn clean package -DskipTests` → 校验 jar 里的字节码版本必须是 52。

前端产物名带哈希，同步前必须先清空 `static/`，否则会积压孤儿文件。

## 测试

```bash
cd backend && mvn test
```

9 个测试类 / 73 个用例，全部 Mock，不连库不连源，几秒跑完。覆盖：IP 段归一化与校验、IP 归属地解析、日志聚合的 BSON 形状、审核记录联查、学校账号判定、公告保存与备份的文档形状、机构匹配的空白容忍。

## 说明

脱敏演示版：库地址、库名、账号密码、OSS AK/SK、同事姓名与邮箱、客户学校名、产品名均已替换为占位值；公司 logo 换成生成的占位字标。仓库历史已重建为单次提交。`application.yml` 里的连接串是占位，直接跑连不上任何真实库；`ip2region_v4.xdb` 是保留的第三方离线库，用于 `IpCityResolver` 的测试。
