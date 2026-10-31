package com.dance.street.game.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 Spring 容器收集全部 {@link IMcpTool} 实现，并生成 MCP 工具注册表。
 */
@Component
public class McpToolRegistry {

    private final List<McpStatelessServerFeatures.SyncToolSpecification> specifications;

    public McpToolRegistry(List<IMcpTool> toolBeans) {
        List<IMcpTool> orderedTools = new ArrayList<>(toolBeans);
        AnnotationAwareOrderComparator.sort(orderedTools);

        Set<String> names = new HashSet<>();
        List<McpStatelessServerFeatures.SyncToolSpecification> resolved = new ArrayList<>();
        for (IMcpTool toolBean : orderedTools) {
            var specification = toolBean.toSpecification();
            String name = specification.tool().name();
            if (!names.add(name)) {
                throw new IllegalStateException("Duplicate MCP tool name: " + name);
            }
            resolved.add(specification);
        }
        this.specifications = List.copyOf(resolved);
    }

    public List<McpStatelessServerFeatures.SyncToolSpecification> specifications() {
        return specifications;
    }
}
