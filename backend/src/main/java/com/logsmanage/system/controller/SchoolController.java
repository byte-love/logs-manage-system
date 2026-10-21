package com.logsmanage.system.controller;

import com.logsmanage.system.service.SchoolService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 学校列表（Ip_Adr 集合）。独立于 /api/user-data，这张表与账号/日志业务无关联。
 */
@RestController
@RequestMapping("/api/school")
public class SchoolController {

    private final SchoolService schoolService;

    public SchoolController(SchoolService schoolService) {
        this.schoolService = schoolService;
    }

    /**
     * @param base 正式库筛选：{@code "formal"} = 正式库（源表 base=false），{@code "base"} = 基础库（源表 base=true），不传或空表示全部。
     *             用 String 而非 Boolean 接收，是为了能区分「没传」和「传了非法值」——
     *             Boolean 遇到非法值会在绑定阶段抛类型错误，返回的报文不受这里控制。
     */
    @GetMapping("/page")
    public ResponseEntity<Map<String, Object>> getPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String base) {
        if (pageNum < 1) {
            return ResponseEntity.badRequest().body(fail("pageNum 不能小于 1"));
        }
        if (pageSize < 1 || pageSize > 100) {
            return ResponseEntity.badRequest().body(fail("pageSize 必须在 1 到 100 之间"));
        }
        Boolean baseFilter = null;
        if (base != null && !base.trim().isEmpty()) {
            String normalized = base.trim().toLowerCase();
            if ("formal".equals(normalized) || "false".equals(normalized)) {
                baseFilter = Boolean.FALSE;
            } else if ("base".equals(normalized) || "true".equals(normalized)) {
                baseFilter = Boolean.TRUE;
            } else {
                return ResponseEntity.badRequest().body(fail("base 只能是 formal / base"));
            }
        }
        return ResponseEntity.ok(schoolService.getPage(pageNum, pageSize, keyword, baseFilter));
    }

    // ===== 协议 PDF 代理 =====

    /** 协议对象所在的 OSS 主机。实测 287 行 570 个 URL 全部是这一个 host。 */
    private static final String ALLOWED_HOST = "datahub-statics.oss-cn-shanghai.aliyuncs.com";

    /** 上游取流超时（毫秒）。单文件约 107KB，给足余量。 */
    private static final int UPSTREAM_TIMEOUT_MS = 20000;

    private static final String KIND_AGREE = "agree";

    /**
     * 代理协议 PDF，使其可在浏览器内预览。
     *
     * <p><b>为什么要代理：</b>OSS 上这些对象带着上传时写死的
     * {@code Content-Disposition: attachment}，且 {@code Content-Type} 是
     * {@code application/octet-stream}（内容实际是 {@code %PDF-1.7}），浏览器一律触发下载、
     * iframe 里也不预览；响应又没有 {@code Access-Control-Allow-Origin}，前端 fetch 拿不到字节。
     * 加 {@code ?response-content-disposition=inline} 会被 OSS 以 400 拒绝（未签名的 override 不允许）。
     * 所以由后端取流、换成 {@code application/pdf} + {@code inline} 再吐给前端。
     *
     * <p>没有走预签名 URL：那要用全权限 AK/SK 现签，等于把凭证派生物交给浏览器，
     * 且签名 URL 一旦泄露即可直取。对象本身是公共读，代理请求连凭证都不需要。
     *
     * @param id   单位编号（{@code Ip_Adr._id}）
     * @param kind {@code agree} = 使用协议，{@code student} = 学生协议
     * @param mode {@code inline} = 浏览器内预览（默认），{@code download} = 另存为
     */
    @GetMapping("/agreement")
    public void getAgreement(
            @RequestParam int id,
            @RequestParam(defaultValue = KIND_AGREE) String kind,
            @RequestParam(defaultValue = "inline") String mode,
            HttpServletResponse response) throws IOException {

        // kind 非法与「文件不存在」要分开：前者是调用方写错了参数，回 400
        if (!KIND_AGREE.equals(kind) && !"student".equals(kind)) {
            writeJsonError(response, 400, "kind 只能是 agree 或 student");
            return;
        }

        Map<String, String> resolved = schoolService.resolveAgreement(id, kind);
        if (resolved == null) {
            writeJsonError(response, 404, "该单位没有对应的协议文件");
            return;
        }

        String url = resolved.get("url");
        // 库里的值也要防一手：只允许 https + 白名单 host，避免把接口变成任意地址代理
        URL parsed;
        try {
            parsed = new URL(url);
        } catch (Exception e) {
            writeJsonError(response, 400, "协议地址格式不正确");
            return;
        }
        if (!"https".equalsIgnoreCase(parsed.getProtocol())
                || !ALLOWED_HOST.equalsIgnoreCase(parsed.getHost())) {
            writeJsonError(response, 400, "协议地址不在允许的范围内");
            return;
        }

        HttpURLConnection conn = (HttpURLConnection) parsed.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(UPSTREAM_TIMEOUT_MS);
        conn.setReadTimeout(UPSTREAM_TIMEOUT_MS);
        // 不跟随跳转到别的 host（防御性：当前 OSS 直接 200，不会跳）
        conn.setInstanceFollowRedirects(false);

        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                writeJsonError(response, 404, "协议文件在存储上不存在");
                return;
            }

            try (InputStream upstream = conn.getInputStream()) {
                // 先读前 5 字节判类型：OSS 一律回 octet-stream，不能信它的 Content-Type
                byte[] head = new byte[5];
                int headLen = 0;
                while (headLen < head.length) {
                    int n = upstream.read(head, headLen, head.length - headLen);
                    if (n < 0) {
                        break;
                    }
                    headLen += n;
                }
                if (headLen == 0) {
                    // register 那边有 0 字节对象的先例，这里同样兜一下
                    writeJsonError(response, 404, "协议文件是空文件");
                    return;
                }

                boolean isPdf = headLen >= 4
                        && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
                response.setStatus(200);
                response.setContentType(isPdf ? "application/pdf" : "application/octet-stream");
                int length = conn.getContentLength();
                if (length > 0) {
                    response.setContentLength(length);
                }
                // OSS 上每个学校的文件名都叫「DataHub平台数据使用协议.pdf」，
                // 这里换成带单位名和协议类型的可读名，下载时更好认
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        buildDisposition(mode, resolved.get("adress"), id, kind));
                response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=300");

                try (OutputStream out = response.getOutputStream()) {
                    out.write(head, 0, headLen);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = upstream.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 拼 Content-Disposition。文件名按 RFC 5987 双写：{@code filename=} 给老浏览器兜底、
     * {@code filename*=UTF-8''} 承载中文。
     */
    private static String buildDisposition(String mode, String adress, int id, String kind) {
        String type = "download".equals(mode) ? "attachment" : "inline";
        String who = adress == null || adress.trim().isEmpty() ? String.valueOf(id) : adress.trim();
        String label = KIND_AGREE.equals(kind) ? "使用协议" : "学生协议";
        // 文件名里不能出现路径分隔符和引号
        String raw = (who + "_" + label + ".pdf").replaceAll("[\\\\/\"\\r\\n]", "_");
        String encoded;
        try {
            encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");
        } catch (IOException e) {
            encoded = "agreement.pdf";
        }
        return type + "; filename=" + encoded + "; filename*=UTF-8''" + encoded;
    }

    /** 出错时回 JSON。必须在写字节之前调用，否则响应已提交、改不了。 */
    private static void writeJsonError(HttpServletResponse response, int status, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message.replace("\"", "'") + "\"}");
        response.getWriter().flush();
    }

    /**
     * 全部学校名称，不分页。给前端下拉联想用（数据量小，一次取回本地过滤）。
     * 返回的是单位名 Adress，不是 mangerName（那是客户经理姓名）。
     */
    @GetMapping("/all-names")
    public ResponseEntity<Map<String, Object>> getAllSchoolNames() {
        return ResponseEntity.ok(schoolService.getAllSchoolNames());
    }

    /** Map.of() 是 Java 9+ API，生产机是 JDK 8，只能自己拼。 */
    private static Map<String, Object> fail(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
