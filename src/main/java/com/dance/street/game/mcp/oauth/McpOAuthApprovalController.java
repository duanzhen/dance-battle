package com.dance.street.game.mcp.oauth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.servlet.http.HttpServletRequest;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已登录管理员确认 MCP 客户端授权并签发 authorization code。
 */
@RestController
public class McpOAuthApprovalController {

    private final McpOAuthService oauthService;
    private final McpPublicUrlResolver urlResolver;

    public McpOAuthApprovalController(McpOAuthService oauthService,
                                      McpPublicUrlResolver urlResolver) {
        this.oauthService = oauthService;
        this.urlResolver = urlResolver;
    }

    @SaCheckLogin
    @PostMapping("/mcp/authorize/approve")
    public R<Map<String, Object>> approve(@RequestBody Map<String, String> params,
                                          HttpServletRequest request) {
        String code = oauthService.authorize(request, params);
        UriComponentsBuilder redirect = UriComponentsBuilder.fromUriString(params.get("redirect_uri"))
            .queryParam("code", code)
            .queryParam("iss", urlResolver.baseUrl(request));
        if (params.get("state") != null) {
            redirect.queryParam("state", params.get("state"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("redirectUrl", redirect.build().encode().toUriString());
        return R.ok(data);
    }
}
