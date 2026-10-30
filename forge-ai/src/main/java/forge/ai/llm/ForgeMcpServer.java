package forge.ai.llm;

import forge.util.BuildInfo;
import forge.util.FileUtil;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.tinylog.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** A localhost MCP endpoint for this agent's game tools. */
final class ForgeMcpServer implements AutoCloseable {
    static final String MULLIGAN_TOOL = "submit_mulligan_decision";
    private final Tomcat tomcat = new Tomcat();
    private final McpSyncServer mcp;
    private final Path directory;
    private final CallbackOnce<Boolean, String> mulliganDecision = new CallbackOnce<>();

    private ForgeMcpServer() throws IOException {
        directory = Files.createTempDirectory("forge-mcp-");
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");

        final var transport = HttpServletStreamableServerTransportProvider.builder().build();
        mcp = McpServer.sync(transport)
                .serverInfo("mtg-forge", BuildInfo.getVersionString())
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .toolCall(Tool.builder().name(MULLIGAN_TOOL)
                        .description("Submit Keep (true) or Mulligan (false) for the pending mulligan request.")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "requestId", Map.of("type", "string", "description", "Request ID from the prompt"),
                                        "keep", Map.of("type", "boolean", "description", "True to keep, false to mulligan")),
                                "required", List.of("requestId", "keep"),
                                "additionalProperties", false))
                        .build(), (exchange, request) -> submitMulligan(request))
                .build();

        final Context context = tomcat.addContext("", directory.toString());
        Tomcat.addServlet(context, "mcp", transport).setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
    }

    static ForgeMcpServer start() {
        try {
            final ForgeMcpServer server = new ForgeMcpServer();
            try {
                server.tomcat.start();
                return server;
            } catch (LifecycleException | RuntimeException e) {
                server.close();
                throw e;
            }
        } catch (IOException | LifecycleException e) {
            throw new IllegalStateException("Could not start Forge MCP server", e);
        }
    }

    String getUrl() {
        return "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/mcp";
    }

    CallbackOnce<Boolean, String> mulliganDecision() {
        return mulliganDecision;
    }

    private CallToolResult submitMulligan(CallToolRequest request) {
        final Map<String, Object> arguments = request.arguments();
        if (arguments == null || !(arguments.get("requestId") instanceof String requestId)
                || !(arguments.get("keep") instanceof Boolean keep)) {
            return CallToolResult.builder().isError(true)
                    .addTextContent("Expected a string requestId and a boolean keep.").build();
        }
        try {
            final String result = mulliganDecision.call(requestId, keep);
            Logger.tag("LLM_CONVERSATION").info("[MCP {}] {}: keep={}\n{}",
                    requestId, MULLIGAN_TOOL, keep, result);
            return CallToolResult.builder().isError(false)
                    .addTextContent(result).build();
        } catch (IllegalStateException e) {
            Logger.tag("LLM_CONVERSATION").info("[MCP {}] {}: keep={}\nERROR: {}",
                    requestId, MULLIGAN_TOOL, keep, e.getMessage());
            return CallToolResult.builder().isError(true).addTextContent(e.getMessage()).build();
        }
    }

    @Override
    public void close() {
        try {
            mcp.close();
        } finally {
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (LifecycleException e) {
                throw new IllegalStateException("Could not stop Forge MCP server", e);
            } finally {
                FileUtil.deleteDirectory(directory.toFile());
            }
        }
    }
}
