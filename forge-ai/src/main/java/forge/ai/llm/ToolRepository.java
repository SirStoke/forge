package forge.ai.llm;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.tinylog.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Registers typed tools at startup and adapts their callbacks to MCP. */
public final class ToolRepository {
    private final List<SyncToolSpecification> tools = new ArrayList<>();

    public <I, O> void register(Supplier<Tool> schema, Function<CallToolRequest, I> compile,
                               Callback<I, O> callback) {
        final Tool tool = schema.get();
        if (tools.stream().anyMatch(existing -> existing.tool().name().equals(tool.name()))) {
            throw new IllegalArgumentException("Tool already registered: " + tool.name());
        }
        tools.add(SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> invoke(tool, compile, callback, request)).build());
    }

    public List<SyncToolSpecification> specifications() {
        return List.copyOf(tools);
    }

    private static <I, O> CallToolResult invoke(Tool tool, Function<CallToolRequest, I> compile,
                                               Callback<I, O> callback, CallToolRequest request) {
        final String invocationId = UUID.randomUUID().toString();
        Logger.tag("LLM_CONVERSATION").info("[MCP {}] TOOL CALL {}:\n{}",
                invocationId, tool.name(), request.arguments());
        try {
            final I input = compile.apply(request);
            final O output = callback.call(input);
            final String text = output instanceof String value ? value
                    : McpJsonDefaults.getMapper().writeValueAsString(output);
            Logger.tag("LLM_CONVERSATION").info("[MCP {}] TOOL RESULT {}:\n{}",
                    invocationId, tool.name(), text);
            return CallToolResult.builder().isError(false).addTextContent(text).build();
        } catch (RuntimeException | IOException e) {
            final String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Logger.tag("LLM_CONVERSATION").info("[MCP {}] TOOL ERROR {}:\n{}",
                    invocationId, tool.name(), message);
            return CallToolResult.builder().isError(true).addTextContent(message).build();
        }
    }
}
