package com.logsmanage.system.controller;

import com.logsmanage.system.service.IpBindRecordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 加绑 IP 登记接口：分页读取 139 库的 IpBindRecord，供异常情况页展示。
 */
@RestController
@RequestMapping("/api/ip-bind")
public class IpBindRecordController {

    private final IpBindRecordService ipBindRecordService;

    public IpBindRecordController(IpBindRecordService ipBindRecordService) {
        this.ipBindRecordService = ipBindRecordService;
    }

    @GetMapping("/page")
    public ResponseEntity<Map<String, Object>> getPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        if (pageNum < 1) {
            return ResponseEntity.badRequest().body(fail("pageNum 不能小于 1"));
        }
        if (pageSize < 1 || pageSize > 100) {
            return ResponseEntity.badRequest().body(fail("pageSize 必须在 1 到 100 之间"));
        }
        return ResponseEntity.ok(ipBindRecordService.getPage(pageNum, pageSize, keyword));
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
