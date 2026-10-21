package com.logsmanage.system.config;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;

/**
 * SPA 回退：Vue Router history 模式下，刷新非首页路径（如 /home、/login）时返回 index.html
 */
@Controller
public class SpaForwardController implements ErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status != null && Integer.parseInt(status.toString()) == HttpStatus.NOT_FOUND.value()) {
            return "forward:/index.html";
        }
        return null;
    }
}
