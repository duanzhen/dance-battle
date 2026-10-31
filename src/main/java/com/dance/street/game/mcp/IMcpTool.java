package com.dance.street.game.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP 工具扩展点。
 *
 * <p>实现类声明为 Spring Bean（{@code @Component}/{@code @Service}/{@code @Bean}）后，
 * 会自动进入 tools/list。{@link #definition()} 定义工具名和 JSON Schema，
 * {@link #execute(McpTransportContext, McpSchema.CallToolRequest)} 处理调用。</p>
 *
 * <p>写工具必须从 {@code transportContext} 读取 MCP claims 并执行业务权限校验，
 * 不能只依赖工具是否在列表中出现。</p>
 */
public interface IMcpTool {

    McpSchema.Tool definition();

    McpSchema.CallToolResult execute(McpTransportContext transportContext,
                                     McpSchema.CallToolRequest request);

    default McpStatelessServerFeatures.SyncToolSpecification toSpecification() {
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
            .tool(definition())
            .callHandler(this::execute)
            .build();
    }
}
