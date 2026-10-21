package com.logsmanage.system.controller;

import com.logsmanage.system.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String SESSION_USER = "LOGS_MANAGE_AUTH_USER";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                     HttpSession session) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(fail("请输入用户名"));
        }
        if (password == null || password.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(fail("请输入密码"));
        }

        Map<String, Object> result = authService.login(username.trim(), password);
        if (Boolean.TRUE.equals(result.get("success"))) {
            session.setAttribute(SESSION_USER, result.get("user"));
            session.setMaxInactiveInterval(8 * 60 * 60);
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(401).body(result);
    }
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "已退出登录");
        return ResponseEntity.ok(body);
    }

    /** Map.of() 是 Java 9+ API，生产机是 JDK 8，只能自己拼。 */
    private static Map<String, Object> fail(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
