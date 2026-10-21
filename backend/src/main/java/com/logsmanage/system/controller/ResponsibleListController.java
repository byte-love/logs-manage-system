package com.logsmanage.system.controller;

import com.logsmanage.system.service.ResponsibleListService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责人列表接口：导入桌面 Excel 并分页读取新集合。
 */
@RestController
@RequestMapping("/api/school/responsible")
public class ResponsibleListController {

    private final ResponsibleListService responsibleListService;

    public ResponsibleListController(ResponsibleListService responsibleListService) {
        this.responsibleListService = responsibleListService;
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importWorkbook() {
        try {
            return ResponseEntity.ok(responsibleListService.importFromDesktopWorkbook());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(fail(e.getMessage()));
        }
    }

    @PostMapping("/update-owners")
    public ResponseEntity<Map<String, Object>> updateOwners(@RequestBody List<Map<String, String>> updates) {
        try {
            return ResponseEntity.ok(responsibleListService.updateOwnersBySchools(updates));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(fail(e.getMessage()));
        }
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
        return ResponseEntity.ok(responsibleListService.getPage(pageNum, pageSize, keyword));
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
