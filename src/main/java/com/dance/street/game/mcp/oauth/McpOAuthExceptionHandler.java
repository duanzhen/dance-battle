package com.dance.street.game.mcp.oauth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP OAuth 端点统一返回标准 OAuth error JSON。
 */
@RestControllerAdvice(assignableTypes = {
    McpOAuthController.class,
    McpOAuthApprovalController.class
})
public class McpOAuthExceptionHandler {

    @ExceptionHandler(McpOAuthException.class)
    public ResponseEntity<Map<String, Object>> handleOAuthError(McpOAuthException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.getError());
        body.put("error_description", error.getMessage());
        return ResponseEntity.status(error.getStatus())
            .cacheControl(CacheControl.noStore())
            .body(body);
    }
}
