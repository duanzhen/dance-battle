package com.dance.street.game.mcp.oauth;

import org.springframework.http.HttpStatus;

/**
 * 简化 OAuth 流程中的标准协议错误。
 */
public class McpOAuthException extends RuntimeException {

    private final String error;
    private final HttpStatus status;

    public McpOAuthException(String error, String description, HttpStatus status) {
        super(description);
        this.error = error;
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static McpOAuthException invalidRequest(String description) {
        return new McpOAuthException("invalid_request", description, HttpStatus.BAD_REQUEST);
    }

    public static McpOAuthException invalidClient(String description) {
        return new McpOAuthException("invalid_client", description, HttpStatus.UNAUTHORIZED);
    }

    public static McpOAuthException invalidGrant(String description) {
        return new McpOAuthException("invalid_grant", description, HttpStatus.BAD_REQUEST);
    }
}
