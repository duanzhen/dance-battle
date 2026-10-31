package com.dance.street.game.mcp;

import com.dance.street.game.mcp.oauth.McpBearerAuthFilter;
import com.dance.street.game.mcp.oauth.McpJwtService;
import com.dance.street.game.mcp.oauth.McpPublicUrlResolver;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/**
 * MCP Streamable HTTP 端点与认证过滤器。
 */
@Configuration
public class McpServerConfig implements WebMvcConfigurer {

    @Bean
    McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Bean
    JsonSchemaValidator mcpJsonSchemaValidator(McpJsonMapper mapper) {
        var objectMapper = ((JacksonMcpJsonMapper) mapper).getObjectMapper();
        return new DefaultJsonSchemaValidator(objectMapper);
    }

    @Bean
    HttpServletStatelessServerTransport mcpTransport(McpJsonMapper mapper,
                                                     McpJwtService jwtService,
                                                     McpPublicUrlResolver urlResolver) {
        return HttpServletStatelessServerTransport.builder()
            .jsonMapper(mapper)
            .messageEndpoint("/mcp")
            .contextExtractor(request -> {
                Object claims = request.getAttribute(McpBearerAuthFilter.TOKEN_CLAIMS_ATTR);
                return claims == null
                    ? McpTransportContext.EMPTY
                    : McpTransportContext.create(Map.of(
                        "claims", claims,
                        "resource", urlResolver.mcpResource(request)
                    ));
            })
            .build();
    }

    @Bean
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport,
                                     McpJsonMapper mapper,
                                     JsonSchemaValidator schemaValidator,
                                     McpToolRegistry toolRegistry,
                                     McpBusinessResources businessResources,
                                     McpBusinessPrompt businessPrompt) {
        return McpServer.sync(transport)
            .jsonMapper(mapper)
            .jsonSchemaValidator(schemaValidator)
            .serverInfo("dance-battle-mcp", "1.0.0")
            .instructions("Use the dance_battle_operator prompt to load the business model before complex "
                + "tournament operations. Read the dance:// resource guide and live overviews when useful, "
                + "then use the domain tools to inspect current state and execute changes. After a successful "
                + "create, retain the returned context ids and continue using that object until the user "
                + "explicitly switches context.")
            .tools(toolRegistry.specifications())
            .resources(businessResources.resources())
            .resourceTemplates(businessResources.resourceTemplates())
            .prompts(businessPrompt.toSpecification())
            .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
        HttpServletStatelessServerTransport transport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
            new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("mcpStreamableHttpServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    FilterRegistrationBean<McpBearerAuthFilter> mcpBearerAuthFilter(
        McpJwtService jwtService, McpPublicUrlResolver urlResolver) {
        FilterRegistrationBean<McpBearerAuthFilter> registration =
            new FilterRegistrationBean<>(new McpBearerAuthFilter(jwtService, urlResolver));
        registration.addUrlPatterns("/mcp");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/.well-known/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "OPTIONS")
            .maxAge(3600);
        registry.addMapping("/mcp")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "Accept", "Mcp-Session-Id", "MCP-Protocol-Version")
            .exposedHeaders("Mcp-Session-Id", "WWW-Authenticate")
            .maxAge(3600);
        registry.addMapping("/register")
            .allowedOriginPatterns("*")
            .allowedMethods("POST", "OPTIONS")
            .allowedHeaders("Content-Type");
        registry.addMapping("/token")
            .allowedOriginPatterns("*")
            .allowedMethods("POST", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type");
    }
}
