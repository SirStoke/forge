package forge.ai.llm;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ForgeMcpServerShould {
    @Test
    public void serveAnEmptyMcpEndpointAndReleaseThePortOnClose() {
        final String url;
        try (ForgeMcpServer server = ForgeMcpServer.start()) {
            url = server.getUrl();
            final var transport = HttpClientStreamableHttpTransport
                    .builder(URI.create(url).resolve("/").toString()).endpoint("/mcp").build();
            try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
                Assert.assertEquals(client.initialize().serverInfo().name(), "mtg-forge");
                Assert.assertTrue(client.listTools().tools().isEmpty());
            }
        }

        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(2)).build();
        Assert.expectThrows(IOException.class,
                () -> HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()));
    }
}
