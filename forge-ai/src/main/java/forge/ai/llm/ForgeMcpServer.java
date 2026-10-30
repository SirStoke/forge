package forge.ai.llm;

import forge.ai.llm.tools.Mulligan;
import forge.ai.llm.tools.CardDetails;
import forge.ai.llm.tools.ObserveStack;
import forge.game.player.Player;
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
    private final StackObserver stack;

    private ForgeMcpServer(Player viewer) throws IOException {
        directory = Files.createTempDirectory("forge-mcp-");
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        stack = new StackObserver(viewer);

        final ToolRepository tools = new ToolRepository();
        tools.register(Mulligan::schema, Mulligan::compile,
                mulligan -> mulliganDecision.call(mulligan.requestId(), mulligan.keep()));
        tools.register(CardDetails::schema, CardDetails::compile, CardCatalog::lookup);
        tools.register(ObserveStack::schema, ObserveStack::compile, request -> stack.read());

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
        return start(null);
    }

    static ForgeMcpServer start(Player viewer) {
        try {
            final ForgeMcpServer server = new ForgeMcpServer(viewer);
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

    void refreshStack() {
        stack.refresh();
    }

    @Override
    public void close() {
        stack.close();
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
