package com.logsmanage.system.controller;

import com.logsmanage.system.service.HolidayTipService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实用工具 → 节假日公告。
 *
 * <p>改动会写到两个生产 SQL Server（cas 的公告行 + 145 的热更新通知行），
 * 也就是说这个接口动的是<b>老版网站注册页上给用户看的内容</b>；
 * 界面负责在提交前把「改完用户会看到什么、什么时候能看到」讲清楚。
 *
 * <p>另外每次改动还会把公告整体覆盖一份到DemoText那台生产 Mongo
 * （{@code 203.0.113.12:30000/demotext} 的 {@code holiday_tip} 单行文档），那份纯粹是备份：
 * 老站与桌面端都不读它，写失败只提示、不回滚已经生效的公告。
 */
@RestController
@RequestMapping("/api/holiday-tip")
public class HolidayTipController {

    private final HolidayTipService holidayTipService;

    public HolidayTipController(HolidayTipService holidayTipService) {
        this.holidayTipService = holidayTipService;
    }

    /** 当前公告 + 状态 + 编辑历史，进页面一次拿全 */
    @GetMapping
    public Map<String, Object> current() {
        Map<String, Object> out = holidayTipService.current();
        out.put("history", holidayTipService.history());
        return out;
    }

    /** 实时预览：与落库同一段渲染代码，保证「看到什么就存什么」 */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("success", true);
        out.put("tip", holidayTipService.preview(text(body, "startDate"), text(body, "endDate"), text(body, "template")));
        return out;
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody(required = false) Map<String, Object> body) {
        // tip 是界面里那份「最终文案」（日期已替换好），优先用它；没有才退回按模板渲染
        return holidayTipService.save(text(body, "startDate"), text(body, "endDate"),
                text(body, "tip"), text(body, "template"), text(body, "operator"));
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(@RequestBody(required = false) Map<String, Object> body) {
        return holidayTipService.disable(text(body, "operator"));
    }

    /** 保存成功但热更新通知失败时重试；不改公告内容 */
    @PostMapping("/notify")
    public Map<String, Object> notifyAgain(@RequestBody(required = false) Map<String, Object> body) {
        return holidayTipService.notifyAgain(text(body, "operator"));
    }

    /** 保存成功但 demotext 备份失败时重试；不改公告内容，也不发热更新 */
    @PostMapping("/backup")
    public Map<String, Object> backupAgain(@RequestBody(required = false) Map<String, Object> body) {
        return holidayTipService.backupAgain(text(body, "operator"));
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
