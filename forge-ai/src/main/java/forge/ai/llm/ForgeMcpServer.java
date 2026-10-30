package forge.ai.llm;

import forge.ai.llm.tools.Mulligan;
import forge.util.BuildInfo;
import forge.util.FileUtil;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A localhost MCP endpoint for this agent's game tools. */
final class ForgeMcpServer implements AutoCloseable {
    private final Tomcat tomcat = new Tomcat();
    private final McpSyncServer mcp;
    private final Path directory;
    private final CallbackOnce<Boolean, String> mulliganDecision = new CallbackOnce<>();

    private ForgeMcpServer() throws IOException {
        directory = Files.createTempDirectory("forge-mcp-");
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");

        final ToolRepository tools = new ToolRepository();
        tools.register(Mulligan::schema, Mulligan::compile,
                mulligan -> mulliganDecision.call(mulligan.requestId(), mulligan.keep()));

        final var transport = HttpServletStreamableServerTransportProvider.builder().build();
        mcp = McpServer.sync(transport)
                .serverInfo("mtg-forge", BuildInfo.getVersionString())
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools.specifications())
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
