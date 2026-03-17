package com.yihen.exceptionhandlers;


import com.yihen.common.Result;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler { // 全局异常处理

    @ExceptionHandler(WebClientResponseException.class)
    public Result handleWebClientResponseException(WebClientResponseException e) {
        HttpServletRequest request = currentRequest();

        int statusCode = e.getStatusCode().value();
        String statusText = e.getStatusText();
        String responseBody = e.getResponseBodyAsString();
        String requestId = e.getHeaders().getFirst("x-request-id");

        String errorCode = null;
        String errorMessage = null;
        try {
            JSONObject body = JSON.parseObject(responseBody);
            if (body != null) {
                if (body.containsKey("error")) {
                    JSONObject err = body.getJSONObject("error");
                    errorCode = err.getString("code");
                    errorMessage = err.getString("message");
                } else {
                    errorCode = body.getString("code");
                    errorMessage = body.getString("message");
                }
            }
        } catch (Exception ignore) {
            // 忽略解析异常，尽量保证不中断
        }

        log.error("HTTP {} {} | Type={} | Code={} | Message={} | RequestId={} | URL={} | Body={}",
                statusCode,
                statusText,
                e.getClass().getSimpleName(),
                errorCode,
                errorMessage,
                requestId,
                request != null ? request.getRequestURI() : "",
                responseBody);

        String friendlyMsg = String.format("HTTP %d %s | code=%s | message=%s | requestId=%s", statusCode, statusText, errorCode, errorMessage, requestId);
        return Result.error(friendlyMsg);
    }

    // 定义需要捕获的异常
    @ExceptionHandler(Exception.class)
    public Result exceptionHandler(Exception e) {
        HttpServletRequest request = currentRequest();

        // 打印日志
        e.printStackTrace();


        log.error("代码出现异常，异常信息为：{} ",e.getMessage());
        return Result.error(e.getMessage());
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }

}
