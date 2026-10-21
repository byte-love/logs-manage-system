package com.logsmanage.system.controller;

import com.logsmanage.system.dto.CreateAccountRequest;
import com.logsmanage.system.model.UserDataUpdateTime;
import com.logsmanage.system.service.AccountCreationService;
import com.logsmanage.system.service.RegisterFileService;
import com.logsmanage.system.service.UserDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user-data")
public class UserDataController {

    private static final Logger log = LoggerFactory.getLogger(UserDataController.class);

    private final UserDataService userDataService;
    private final RegisterFileService registerFileService;
    private final AccountCreationService accountCreationService;

    public UserDataController(UserDataService userDataService,
                              RegisterFileService registerFileService,
                              AccountCreationService accountCreationService) {
        this.userDataService = userDataService;
        this.registerFileService = registerFileService;
        this.accountCreationService = accountCreationService;
    }

    @GetMapping("/page")
    public ResponseEntity<Map<String, Object>> getPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String nickName,
            @RequestParam(required = false) String mail,
            @RequestParam(required = false) String institution,
            @RequestParam(required = false) String college,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String xuezhi,
            @RequestParam(required = false) String ruxueYear,
            @RequestParam(required = false) String accountType,
            @RequestParam(required = false) String accountStatus,
            @RequestParam(required = false) String applyChannel) {
        ResponseEntity<Map<String, Object>> invalid = validatePaging(pageNum, pageSize);
        if (invalid != null) {
            return invalid;
        }
        Map<String, String> filters = new HashMap<>();
        filters.put("keyword", keyword);
        filters.put("userName", userName);
        filters.put("nickName", nickName);
        filters.put("mail", mail);
        filters.put("institution", institution);
        filters.put("college", college);
        filters.put("position", position);
        filters.put("xuezhi", xuezhi);
        filters.put("ruxueYear", ruxueYear);
        filters.put("accountType", accountType);
        filters.put("accountStatus", accountStatus);
        filters.put("applyChannel", applyChannel);
        return ResponseEntity.ok(userDataService.getPage(pageNum, pageSize, filters));
    }

    @GetMapping("/register/page")
    public ResponseEntity<Map<String, Object>> getRegisterPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String registerEntry) {
        ResponseEntity<Map<String, Object>> invalid = validatePaging(pageNum, pageSize);
        if (invalid != null) {
            return invalid;
        }
        return ResponseEntity.ok(userDataService.getRegisterPage(pageNum, pageSize, keyword, registerEntry));
    }

    @GetMapping("/new-log/page")
    public ResponseEntity<Map<String, Object>> getNewLogPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String nickName,
            @RequestParam(required = false) String institution,
            @RequestParam(required = false) String mail,
            @RequestParam(required = false) String srcIP,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String system,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String dateStart,
            @RequestParam(required = false) String dateEnd) {
        ResponseEntity<Map<String, Object>> invalid = validatePaging(pageNum, pageSize);
        if (invalid != null) {
            return invalid;
        }
        Map<String, String> filters = new HashMap<>();
        filters.put("userName", userName);
        filters.put("nickName", nickName);
        filters.put("institution", institution);
        filters.put("mail", mail);
        filters.put("srcIP", srcIP);
        filters.put("city", city);
        filters.put("system", system);
        filters.put("database", database);
        filters.put("table", table);
        filters.put("operationType", operationType);
        filters.put("dateStart", dateStart);
        filters.put("dateEnd", dateEnd);
        return ResponseEntity.ok(userDataService.getNewLogPage(pageNum, pageSize, filters));
    }

    /**
     * 按机构查操作日志（实用工具 → 学校日志导出）。
     *
     * <p>不走 {@code /new-log/page} 是因为那边按机构筛只给封顶 10000 的总数，
     * 学校报表要真实条数。查询 NewLog + 已同步的 En_Log；Log_Login 没有 Institution 列，不参与。
     *
     * @param excludeAnonymous 排除匿名访问，默认 true（对齐业务方手工 SQL 的
     *                         {@code NickName != 'anonymous'}）。导出接口要传同一个值。
     */
    @GetMapping("/school-logs")
    public ResponseEntity<Map<String, Object>> getSchoolLogs(
            @RequestParam String institution,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String dateStart,
            @RequestParam(required = false) String dateEnd,
            @RequestParam(defaultValue = "true") boolean excludeAnonymous) {
        ResponseEntity<Map<String, Object>> invalid = validatePaging(pageNum, pageSize);
        if (invalid != null) {
            return invalid;
        }
        if (institution == null || institution.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(fail("机构名称不能为空"));
        }
        return ResponseEntity.ok(userDataService.getSchoolLogPage(
                institution, pageNum, pageSize, dateStart, dateEnd, excludeAnonymous));
    }

    @GetMapping("/new-log/by-user")
    public ResponseEntity<Map<String, Object>> getNewLogByUser(
            @RequestParam String userName,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String operationType) {
        ResponseEntity<Map<String, Object>> invalid = validatePaging(pageNum, pageSize);
        if (invalid != null) {
            return invalid;
        }
        return ResponseEntity.ok(
                userDataService.getNewLogByUser(userName, pageNum, pageSize, operationType));
    }

    // 异常情况：被封禁账号（IsDisabled=1）
    @GetMapping("/disabled")
    public ResponseEntity<Map<String, Object>> getDisabledUsers(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String userName) {
        ResponseEntity<Map<String, Object>> invalid = validatePaging(pageNum, pageSize);
        if (invalid != null) {
            return invalid;
        }
        return ResponseEntity.ok(userDataService.getDisabledUsers(pageNum, pageSize, userName));
    }

    @PostMapping("/disabled/unban")
    public ResponseEntity<Map<String, Object>> unbanUser(
            @RequestBody Map<String, String> body,
            HttpSession session) {
        ResponseEntity<Map<String, Object>> denied = requireAccountAdmin(session);
        if (denied != null) {
            return denied;
        }
        try {
            return ResponseEntity.ok(accountCreationService.unbanUser(
                    body == null ? null : body.get("userName")));
        } catch (AccountCreationService.UnbanConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    fail(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    fail(e.getMessage()));
        } catch (SQLException e) {
            log.error("线上账号解禁失败", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    fail("解禁失败，线上账号库暂不可用"));
        } catch (RuntimeException e) {
            log.error("账号解禁时 MongoDB 同步失败", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    fail("解禁失败，数据同步未完成"));
        }
    }

    // 异常操作排行（原登录次数排行，现统计全部操作类型）
    // limit 传 0 或负数表示「全部」；withinDays 限定最近 N 天，不传为历史全部
    // refresh=true 跳过服务端缓存强制重算（聚合较慢，默认走缓存）
    @GetMapping("/login-ranking")
    public ResponseEntity<Map<String, Object>> getOperationRanking(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Integer withinDays,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String accountStatus,
            @RequestParam(required = false) String applyChannel,
            @RequestParam(defaultValue = "false") boolean refresh) {
        if (limit > 500) {
            return ResponseEntity.badRequest().body(
                    fail("limit 不能超过 500，如需全部请传 0"));
        }
        if (withinDays != null && withinDays <= 0) {
            return ResponseEntity.badRequest().body(
                    fail("withinDays 必须大于 0，如需全部时间请不传"));
        }
        return ResponseEntity.ok(userDataService.getOperationRanking(
                limit, withinDays, position, accountStatus, applyChannel, refresh));
    }

    @GetMapping("/options")
    public ResponseEntity<Map<String, Object>> getOptions() {
        return ResponseEntity.ok(userDataService.getOptions());
    }

    @GetMapping("/create-options")
    public ResponseEntity<Map<String, Object>> getCreateOptions(HttpSession session) {
        ResponseEntity<Map<String, Object>> denied = requireAccountAdmin(session);
        if (denied != null) {
            return denied;
        }
        try {
            return ResponseEntity.ok(accountCreationService.getCreateOptions());
        } catch (SQLException e) {
            log.error("加载创建账号选项失败", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    fail("账号配置加载失败，数据库暂不可用"));
        }
    }

    @GetMapping("/school-permission-template")
    public ResponseEntity<Map<String, Object>> getSchoolPermissionTemplate(
            @RequestParam String school,
            HttpSession session) {
        ResponseEntity<Map<String, Object>> denied = requireAccountAdmin(session);
        if (denied != null) {
            return denied;
        }
        try {
            return ResponseEntity.ok(accountCreationService.getSchoolPermissionTemplate(school));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    fail(e.getMessage()));
        } catch (SQLException e) {
            log.error("加载学校权限模板失败：{}", school, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    fail("学校权限配置加载失败，数据库暂不可用"));
        }
    }

    @PostMapping("/colleges")
    public ResponseEntity<Map<String, Object>> addCollege(
            @RequestBody Map<String, String> body,
            HttpSession session) {
        ResponseEntity<Map<String, Object>> denied = requireAccountAdmin(session);
        if (denied != null) {
            return denied;
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    accountCreationService.addCollege(body == null ? null : body.get("name")));
        } catch (AccountCreationService.CollegeAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    fail(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    fail(e.getMessage()));
        } catch (SQLException e) {
            log.error("新增学院复核项失败", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    fail("学院添加失败，数据库暂不可用"));
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createAccount(
            @RequestBody CreateAccountRequest request,
            HttpSession session) {
        ResponseEntity<Map<String, Object>> denied = requireAccountAdmin(session);
        if (denied != null) {
            return denied;
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(accountCreationService.create(request));
        } catch (AccountCreationService.AccountCreationConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    fail(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    fail(e.getMessage()));
        } catch (SQLException e) {
            log.error("添加正式账号失败", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    fail("账号添加失败，数据库暂不可用"));
        }
    }

    // 某邮箱在 register 里的全部申请材料。
    // 同一邮箱可能有多次申请（实测最多 187 条），列表页只展示最新一条，
    // 需要看历史材料时调这里。
    @GetMapping("/register-files")
    public ResponseEntity<Map<String, Object>> getRegisterFiles(
            @RequestParam String mail) {
        if (mail == null || mail.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(
                    fail("mail 不能为空"));
        }
        List<Map<String, Object>> list = registerFileService.listAllByMail(mail);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("records", list);
        body.put("total", list.size());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> validatePaging(int pageNum, int pageSize) {
        if (pageNum < 1) {
            return ResponseEntity.badRequest().body(fail("pageNum 不能小于 1"));
        }
        if (pageSize < 1 || pageSize > 100) {
            return ResponseEntity.badRequest().body(fail("pageSize 必须在 1 到 100 之间"));
        }
        return null;
    }

    /** Map.of() 是 Java 9+ API，生产机是 JDK 8，只能自己拼。 */
    private static Map<String, Object> fail(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }

    private boolean isAuthenticated(HttpSession session) {
        return session != null && session.getAttribute(AuthController.SESSION_USER) != null;
    }

    private boolean canCreateAccount(HttpSession session) {
        if (!isAuthenticated(session)) {
            return false;
        }
        Object value = session.getAttribute(AuthController.SESSION_USER);
        if (!(value instanceof Map)) {
            return false;
        }
        return Boolean.TRUE.equals(((Map<?, ?>) value).get("canCreateAccount"));
    }

    private ResponseEntity<Map<String, Object>> requireAccountAdmin(HttpSession session) {
        if (!isAuthenticated(session)) {
            return unauthorized();
        }
        if (!canCreateAccount(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    fail("当前账号没有创建正式账号的权限"));
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                fail("登录已失效，请重新登录"));
    }

    // 日志同步断点诊断（只读）。增量同步靠 _sync_state 的 lastId，
    // 书签与集合实际内容脱节时会重拉巨量数据，动手前先用这个看真实数字。
    @GetMapping("/sync-diagnostics")
    public ResponseEntity<Map<String, Object>> syncDiagnostics(
            @RequestParam(defaultValue = "false") boolean probeSource,
            @RequestParam(defaultValue = "false") boolean probeSchema,
            @RequestParam(defaultValue = "false") boolean probeLogin,
            @RequestParam(defaultValue = "false") boolean probeRegister,
            @RequestParam(defaultValue = "0") int probeUsernames) {
        Map<String, Object> body = userDataService.getSyncDiagnostics();
        // 探源库要连公网 SQL Server，默认不连，需要量「这次要拉多少行」时才显式开。
        // 探源库要连公网 SQL Server。列结构（WHERE 1=0，只取元数据）很快，
        // 但 COUNT(*) 在 2250 万行的 NewLog 上很慢 —— 两者分开，否则一起超时。
        if (probeSchema) {
            try {
                body.put("schema", userDataService.probeSyncSchema());
            } catch (Exception e) {
                body.put("schemaError", e.getMessage());
            }
        }
        if (probeSource) {
            try {
                body.put("source", userDataService.probeSyncSource());
            } catch (Exception e) {
                body.put("sourceError", e.getMessage());
            }
        }
        if (probeLogin) {
            try {
                body.put("loginCoverage", userDataService.probeLoginCoverage());
            } catch (Exception e) {
                body.put("loginCoverageError", e.getMessage());
            }
        }
        if (probeRegister) {
            try {
                body.put("registerSource", userDataService.probeRegisterSource());
            } catch (Exception e) {
                body.put("registerSourceError", e.getMessage());
            }
        }
        if (probeUsernames > 0) {
            try {
                body.put("usernameConsistency",
                        userDataService.probeUsernameConsistency(Math.min(probeUsernames, 200)));
            } catch (Exception e) {
                body.put("usernameConsistencyError", e.getMessage());
            }
        }
        return ResponseEntity.ok(body);
    }

    // 重算某个 _id 区间的 O_city（只改这个字段，不回源库）。
    // 默认 dryRun，只报会改多少行；真正写库要显式传 dryRun=false。
    @PostMapping("/recompute-city")
    public ResponseEntity<Map<String, Object>> recomputeCity(
            @RequestParam int fromId,
            @RequestParam int toId,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        if (fromId > toId) {
            return ResponseEntity.badRequest().body(fail("fromId 不能大于 toId"));
        }
        try {
            return ResponseEntity.ok(userDataService.recomputeCityRange(fromId, toId, dryRun));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(fail("重算失败: " + e.getMessage()));
        }
    }

    // 同步面板：登录日志表现有条数
    @GetMapping("/log-login-total")
    public ResponseEntity<Map<String, Object>> logLoginTotal() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("total", userDataService.getLogLoginTotal());
        return ResponseEntity.ok(body);
    }

    // 同步面板：智能体目标集合（GptReplyHistory）现有条数。
    // exists 用来区分「空集合」和「还没同步过」——集合是首次同步写入才建的。
    @GetMapping("/agent-total")
    public ResponseEntity<Map<String, Object>> agentTotal() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.putAll(userDataService.getAgentSyncTotal());
        return ResponseEntity.ok(body);
    }

    // 同步面板：中英文版日志目标集合（En_Log）现有条数，exists 语义同 agent-total
    @GetMapping("/en-log-total")
    public ResponseEntity<Map<String, Object>> enLogTotal() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.putAll(userDataService.getEnLogTotal());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/lastUpdateTime")
    public ResponseEntity<Map<String, Object>> lastUpdateTime() {
        return lastUpdateTimeBody(userDataService.getLastUpdateTime());
    }

    @GetMapping("/lastUpdateTimeByType")
    public ResponseEntity<Map<String, Object>> lastUpdateTimeByType(@RequestParam String type) {
        if (type == null || type.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(fail("type 不能为空"));
        }
        return lastUpdateTimeBody(userDataService.getLastUpdateTime(type.trim()));
    }

    private ResponseEntity<Map<String, Object>> lastUpdateTimeBody(UserDataUpdateTime lastUpdateTime) {
        Map<String, Object> hashMap = new HashMap<>();
        if (lastUpdateTime == null) {
            hashMap.put("lastUpdateTime", null);
        } else {
            hashMap.put("lastUpdateTime", lastUpdateTime.getUpdateTime());
        }
        return ResponseEntity.ok(hashMap);
    }
}
