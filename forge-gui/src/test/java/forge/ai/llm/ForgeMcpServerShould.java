package forge.ai.llm;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ForgeMcpServerShould {
    @Test
    public void advertiseTheMulliganToolAndReleaseThePortOnClose() {
        final String url;
        try (ForgeMcpServer server = ForgeMcpServer.start()) {
            url = server.getUrl();
            final var transport = HttpClientStreamableHttpTransport
                    .builder(URI.create(url).resolve("/").toString()).endpoint("/mcp").build();
            try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
                Assert.assertEquals(client.initialize().serverInfo().name(), "mtg-forge");
                Assert.assertEquals(client.listTools().tools().size(), 1);
                Assert.assertEquals(client.listTools().tools().get(0).name(), ForgeMcpServer.MULLIGAN_TOOL);
            }
        }

        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(2)).build();
        Assert.expectThrows(IOException.class,
                () -> HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()));
    }

    @Test
    public void validateAndConsumeMulliganSubmissionsWithoutConsumingANewerRequest() {
        try (ForgeMcpServer server = ForgeMcpServer.start()) {
            final var transport = HttpClientStreamableHttpTransport
                    .builder(URI.create(server.getUrl()).resolve("/").toString()).endpoint("/mcp").build();
            try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
                client.initialize();
                Assert.assertTrue(client.callTool(new CallToolRequest(ForgeMcpServer.MULLIGAN_TOOL,
                        Map.of("requestId", "missing", "keep", true))).isError());

                final AtomicInteger invocations = new AtomicInteger();
                final String first = server.mulliganDecision().register(keep -> {
                    Assert.assertFalse(keep);
                    invocations.incrementAndGet();
                    return "Accepted";
                });
                Assert.assertTrue(client.callTool(new CallToolRequest(ForgeMcpServer.MULLIGAN_TOOL,
                        Map.of("requestId", first, "keep", "false"))).isError());
                Assert.assertTrue(client.callTool(new CallToolRequest(ForgeMcpServer.MULLIGAN_TOOL,
                        Map.of("requestId", "stale", "keep", true))).isError());
                Assert.assertFalse(client.callTool(new CallToolRequest(ForgeMcpServer.MULLIGAN_TOOL,
                        Map.of("requestId", first, "keep", false))).isError());
                Assert.assertEquals(invocations.get(), 1);

                final String second = server.mulliganDecision().register(keep -> "Accepted again");
                Assert.assertTrue(client.callTool(new CallToolRequest(ForgeMcpServer.MULLIGAN_TOOL,
                        Map.of("requestId", first, "keep", true))).isError());
                Assert.assertFalse(client.callTool(new CallToolRequest(ForgeMcpServer.MULLIGAN_TOOL,
                        Map.of("requestId", second, "keep", true))).isError());
                Assert.assertTrue(client.callTool(new CallToolRequest(ForgeMcpServer.MULLIGAN_TOOL,
                        Map.of("requestId", second, "keep", true))).isError());
            }
        }
    }
}
