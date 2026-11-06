package forge.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Registry recipes plus local prerequisites; discovery never launches or installs agents. */
public final class AcpAgentRegistry {
    public static final String CODEX = "codex-acp";
    public static final String URL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry-for-jetbrains.json";
    private static final List<String> AGENTS = List.of("opencode", CODEX, "claude-acp", "github-copilot");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile List<Entry> entries = bundledEntries();
    private static CompletableFuture<Catalog> discovery;

    private AcpAgentRegistry() { }

    public record Entry(String id, String name, String version, List<String> command,
                        Map<String, String> environment, String unavailableReason) {
        public boolean available() { return unavailableReason.isEmpty(); }
        @Override public String toString() { return name; }
    }

    public record Catalog(List<Entry> entries, String notice) { }

    /** Share one background refresh between lobby panels and retain a disk copy for offline launches. */
    public static synchronized CompletableFuture<Catalog> discover(Path cache) {
        if (discovery == null) {
            discovery = CompletableFuture.supplyAsync(() -> refresh(cache));
        }
        return discovery;
    }

    public static Entry get(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown ACP agent: " + id));
    }

    public static List<Entry> entries() { return entries; }

    private static Catalog refresh(Path cache) {
        String notice = "";
        try {
            if (Files.isRegularFile(cache)) entries = parse(Files.readString(cache), AcpAgentRegistry::findExecutable, platform());
        } catch (IOException | RuntimeException e) {
            Logger.warn(e, "Could not read cached ACP registry");
        }
        try {
            final HttpURLConnection connection = (HttpURLConnection) URI.create(URL).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            final String body;
            try (var input = connection.getInputStream()) {
                body = new String(input.readNBytes(2 * 1024 * 1024), StandardCharsets.UTF_8);
            } finally {
                connection.disconnect();
            }
            entries = parse(body, AcpAgentRegistry::findExecutable, platform());
            try {
                Files.createDirectories(cache.toAbsolutePath().getParent());
                final Path temporary = Files.createTempFile(cache.toAbsolutePath().getParent(), "acp-registry-", ".json");
                try {
                    Files.writeString(temporary, body);
                    Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException e) {
                Logger.warn(e, "Could not cache ACP registry");
            }
        } catch (IOException | RuntimeException e) {
            notice = "Using saved agent catalog; could not refresh the ACP registry.";
            Logger.warn(e, "Could not refresh JetBrains ACP registry");
        }
        return new Catalog(entries, notice);
    }

    static List<Entry> parse(String body, Function<String, Path> executable, String platform) throws IOException {
        final JsonNode agents = JSON.readTree(body).path("agents");
        if (!agents.isArray()) throw new IOException("ACP registry has no agents array");
        final List<Entry> result = new ArrayList<>();
        for (String id : AGENTS) {
            JsonNode agent = null;
            for (JsonNode candidate : agents) {
                if (id.equals(candidate.path("id").asText())) { agent = candidate; break; }
            }
            if (agent == null) {
                result.add(new Entry(id, displayName(id), "", List.of(), Map.of(), "Not listed in the ACP registry"));
                continue;
            }
            final JsonNode distribution = agent.path("distribution");
            final List<String> command = new ArrayList<>();
            final var environment = new java.util.LinkedHashMap<String, String>();
            String unavailable = "";
            if (distribution.has("npx")) {
                final JsonNode recipe = distribution.path("npx");
                final String pkg = recipe.path("package").asText();
                final Path pnpm = executable.apply("pnpm");
                final Path npx = executable.apply("npx");
                final Path npm = executable.apply("npm");
                if (pkg.isBlank()) unavailable = "Registry entry has no npm package";
                else if (pnpm != null) command.addAll(List.of(pnpm.toString(), "dlx", pkg));
                else if (npx != null) command.addAll(List.of(npx.toString(), "--yes", pkg));
                else if (npm != null) command.addAll(List.of(npm.toString(), "exec", "--yes", "--", pkg));
                else unavailable = "Requires npm (or npx) or pnpm in PATH";
                recipe.path("args").forEach(arg -> command.add(arg.asText()));
            } else {
                final JsonNode recipe = distribution.path("binary").path(platform);
                final String relative = recipe.path("cmd").asText().replace('\\', '/');
                final String name = relative.substring(relative.lastIndexOf('/') + 1);
                final Path binary = name.isBlank() ? null : executable.apply(name);
                if (recipe.isMissingNode()) unavailable = "No distribution for this platform";
                else if (binary == null) unavailable = "Requires " + name + " in PATH";
                else command.add(binary.toString());
                recipe.path("args").forEach(arg -> command.add(arg.asText()));
                recipe.path("env").fields().forEachRemaining(field -> environment.put(field.getKey(), field.getValue().asText()));
            }
            result.add(new Entry(id, agent.path("name").asText(displayName(id)), agent.path("version").asText(),
                    List.copyOf(command), Map.copyOf(environment), unavailable));
        }
        if (result.stream().noneMatch(entry -> !entry.version().isBlank())) throw new IOException("No supported agents in registry");
        return List.copyOf(result);
    }

    private static String displayName(String id) {
        return switch (id) {
            case "opencode" -> "OpenCode";
            case CODEX -> "Codex";
            case "claude-acp" -> "Claude Agent";
            default -> "GitHub Copilot";
        };
    }

    static Path findExecutable(String name) {
        return findExecutable(name, System.getenv("PATH"), System.getenv("PATHEXT"), File.separatorChar == '\\');
    }

    static Path findExecutable(String name, String path, String pathExt, boolean windows) {
        if (path == null) return null;
        final List<String> extensions = new ArrayList<>(List.of(""));
        if (windows && !name.contains(".")) {
            extensions.addAll(List.of((pathExt == null ? ".COM;.EXE;.BAT;.CMD" : pathExt).split(";")));
        }
        for (String directory : path.split(Pattern.quote(windows ? ";" : ":"))) {
            if (directory.isBlank()) continue;
            for (String extension : extensions) {
                final Path candidate = Path.of(directory.replace("\"", ""), name + extension);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    private static String platform() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (os.contains("mac") ? "darwin" : os.contains("win") ? "windows" : "linux")
                + (architecture.equals("aarch64") || architecture.equals("arm64") ? "-aarch64" : "-x86_64");
    }

    private static List<Entry> bundledEntries() {
        try (var input = AcpAgentRegistry.class.getResourceAsStream("acp-registry.json")) {
            if (input == null) throw new IOException("Missing bundled ACP registry");
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), AcpAgentRegistry::findExecutable, platform());
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
