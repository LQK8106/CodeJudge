package com.codejudge.common.interceptor;

import com.codejudge.common.constants.Constant;
import com.codejudge.common.logging.RequestIdContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求链路追踪：设置 requestId 到 MDC 与响应头
 */
public class RequestIdInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestId = request.getHeader(Constant.REQUEST_ID_HEADER);
        // 外部可任意构造该请求头：字符集不合法时重新生成，避免日志注入与后续 MQ 属性写入失败
        if (!RequestIdContext.isSafe(requestId)) {
            requestId = RequestIdContext.generate("");
        }
        MDC.put(Constant.REQUEST_ID_HEADER, requestId);
        response.setHeader(Constant.REQUEST_ID_HEADER, requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove(Constant.REQUEST_ID_HEADER);
    }
}
