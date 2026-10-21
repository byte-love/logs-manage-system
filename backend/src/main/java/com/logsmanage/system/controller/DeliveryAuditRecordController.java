package com.logsmanage.system.controller;

import com.logsmanage.system.service.DeliveryAuditRecordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/apply/delivery")
public class DeliveryAuditRecordController {
    private final DeliveryAuditRecordService service;
    public DeliveryAuditRecordController(DeliveryAuditRecordService service) { this.service = service; }
    @GetMapping("/page")
    public ResponseEntity<Map<String,Object>> page(@RequestParam(defaultValue="1") int pageNum,
            @RequestParam(defaultValue="10") int pageSize,
            @RequestParam(required=false) String keyword) {
        if (pageNum < 1) return ResponseEntity.badRequest().body(fail("pageNum 不能小于 1"));
        if (pageSize < 1 || pageSize > 100) return ResponseEntity.badRequest().body(fail("pageSize 必须在 1 到 100 之间"));
        return ResponseEntity.ok(service.page(pageNum, pageSize, keyword));
    }
    private Map<String,Object> fail(String message) {
        Map<String,Object> result = new java.util.LinkedHashMap<String,Object>();
        result.put("success", false); result.put("message", message); return result;
    }
}
