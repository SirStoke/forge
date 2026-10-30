package forge.ai.llm;

import forge.ai.llm.tools.Mulligan;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ToolRepositoryShould {
    @Test
    public void compileBeforeConsumingAOneShotCallback() {
        final var pending = new CallbackOnce<Boolean, String>();
        final String requestId = pending.register(keep -> keep ? "Keep" : "Mulligan");
        final var repository = new ToolRepository();
        repository.register(Mulligan::schema, Mulligan::compile,
                mulligan -> pending.call(mulligan.requestId(), mulligan.keep()));
        final var handler = repository.specifications().get(0).callHandler();

        Assert.assertTrue(handler.apply(null, new CallToolRequest(Mulligan.NAME,
                Map.of("requestId", requestId, "keep", "false"))).isError());
        final var result = handler.apply(null, new CallToolRequest(Mulligan.NAME,
                Map.of("requestId", requestId, "keep", false)));
        Assert.assertFalse(result.isError());
        Assert.assertEquals(text(result), "Mulligan");
        Assert.assertTrue(handler.apply(null, new CallToolRequest(Mulligan.NAME,
                Map.of("requestId", requestId, "keep", true))).isError());
    }

    @DataProvider
    public Object[][] invalidMulliganArguments() {
        return new Object[][] {
                { null },
                { Map.of() },
                { Map.of("requestId", "request") },
                { Map.of("keep", true) },
                { Map.of("requestId", 123, "keep", true) },
                { Map.of("requestId", "request", "keep", "false") },
                { Map.of("requestId", "request", "keep", true, "extra", "unexpected") }
        };
    }

    @Test(dataProvider = "invalidMulliganArguments")
    public void rejectInvalidArgumentsWithoutInvokingTheCallback(Map<String, Object> arguments) {
        final var invocations = new AtomicInteger();
        final var repository = new ToolRepository();
        repository.register(Mulligan::schema, Mulligan::compile, mulligan -> invocations.incrementAndGet());
        final var result = repository.specifications().get(0).callHandler()
                .apply(null, new CallToolRequest(Mulligan.NAME, arguments));
        Assert.assertTrue(result.isError());
        Assert.assertTrue(text(result).contains("requestId"));
        Assert.assertEquals(invocations.get(), 0);
    }

    public record CardInfo(int id, String name) {
    }

    @Test
    public void supportRepeatedTypedCallbacksAndJsonResults() throws Exception {
        final var repository = new ToolRepository();
        final var invocations = new AtomicInteger();
        final Tool schema = Tool.builder().name("inspect_card").description("Inspect a card")
                .inputSchema(Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer")),
                        "required", List.of("id"))).build();
        repository.register(() -> schema, request -> (Integer) request.arguments().get("id"), id -> {
            invocations.incrementAndGet();
            return new CardInfo(id, "Fire & Ice");
        });
        final var handler = repository.specifications().get(0).callHandler();
        for (int id : List.of(1, 2)) {
            final var result = handler.apply(null, new CallToolRequest("inspect_card", Map.of("id", id)));
            Assert.assertFalse(result.isError());
            Assert.assertEquals(McpJsonDefaults.getMapper().readValue(text(result), CardInfo.class),
                    new CardInfo(id, "Fire & Ice"));
        }
        Assert.assertEquals(invocations.get(), 2);
        Assert.expectThrows(IllegalArgumentException.class,
                () -> repository.register(() -> schema, request -> 0, id -> "Duplicate"));
    }

    private String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}
