package forge.ai.llm;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class PromptTemplates {
    private PromptTemplates() {
    }

    static Template load(String name) {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                PromptTemplates.class.getResourceAsStream(name), "Missing prompt: " + name), StandardCharsets.UTF_8)) {
            return Mustache.compiler().escapeHTML(false).compile(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load prompt: " + name, e);
        }
    }
}
