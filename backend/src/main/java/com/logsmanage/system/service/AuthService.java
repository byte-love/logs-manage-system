 package com.logsmanage.system.service;

import com.logsmanage.system.model.SysUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {

    private final MongoTemplate mongoTemplate;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.init-admin.enabled:true}")
    private boolean initAdminEnabled;

    @Value("${app.init-admin.username:admin}")
    private String initAdminUsername;

    @Value("${app.init-admin.password:123456}")
    private String initAdminPassword;

    @Value("${app.init-admin.display-name:系统管理员}")
    private String initAdminDisplayName;

    public AuthService(MongoTemplate mongoTemplate, PasswordEncoder passwordEncoder) {
        this.mongoTemplate = mongoTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void initAdminIfNeeded() {
        if (!initAdminEnabled) {
            return;
        }

        long count = mongoTemplate.getCollection("sys_users").countDocuments();
        if (count > 0) {
            return;
        }

        SysUser admin = new SysUser();
        admin.setUsername(initAdminUsername);
        admin.setPassword(passwordEncoder.encode(initAdminPassword));
        admin.setDisplayName(initAdminDisplayName);
        admin.setEnabled(true);
        admin.setCanCreateAccount(true);
        admin.setCreatedAt(LocalDateTime.now());
        mongoTemplate.insert(admin);
    }

    public Map<String, Object> login(String username, String password) {
        Map<String, Object> result = new LinkedHashMap<>();

        Query query = new Query(Criteria.where("username").is(username));
        SysUser user = mongoTemplate.findOne(query, SysUser.class);

        if (user == null) {
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            return result;
        }

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            result.put("success", false);
            result.put("message", "账号已被禁用");
            return result;
        }

        String storedPassword = user.getPassword();
        if (storedPassword == null || storedPassword.isEmpty()) {
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            return result;
        }

        boolean loginSuccess;
        if (isBcryptHash(storedPassword)) {
            loginSuccess = passwordEncoder.matches(password, storedPassword);
        } else {
            loginSuccess = storedPassword.equals(password);
            if (loginSuccess) {
                migratePassword(user.getId(), password);
            }
        }

        if (!loginSuccess) {
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            return result;
        }

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("displayName", user.getDisplayName());
        userInfo.put("enabled", user.getEnabled());
        // 兼容升级前已创建、尚未带 canCreateAccount 字段的初始化管理员。
        userInfo.put("canCreateAccount",
                Boolean.TRUE.equals(user.getCanCreateAccount()) ||
                        initAdminUsername.equals(user.getUsername()));

        result.put("success", true);
        result.put("message", "登录成功");
        result.put("user", userInfo);
        return result;
    }

    private boolean isBcryptHash(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }

    private void migratePassword(String userId, String rawPassword) {
        Query query = new Query(Criteria.where("_id").is(userId));
        Update update = new Update().set("password", passwordEncoder.encode(rawPassword));
        mongoTemplate.updateFirst(query, update, SysUser.class);
    }
}
