package forge.ai.llm;

import forge.ai.llm.tools.RuleText;
import forge.ai.llm.tools.RulesIndex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Immutable, shared index of the bundled official rules. Never fetches data during a game. */
final class ComprehensiveRules {
    private static final String SOURCE = "https://media.wizards.com/2026/downloads/MagicCompRules%2020261106.txt";
    private static final Pattern HEADING = Pattern.compile("^([1-9]|[1-9][0-9]{2})(?:\\.([0-9]+)([a-z]+)?)?\\.?\\s+(.+)$");
    private static ComprehensiveRules bundled;
    private final String edition;
    private final Map<String, Entry> entries;
    private final Map<String, List<String>> children;

    private ComprehensiveRules(String edition, Map<String, Entry> entries) {
        this.edition = edition;
        this.entries = Map.copyOf(entries);
        final Map<String, List<String>> hierarchy = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            if (!entry.parent().isEmpty() && !entries.containsKey(entry.parent())) {
                throw new IllegalArgumentException("Missing rule parent: " + entry.parent());
            }
            hierarchy.computeIfAbsent(entry.parent(), ignored -> new ArrayList<>()).add(entry.id());
        }
        hierarchy.replaceAll((id, ids) -> List.copyOf(ids));
        children = Map.copyOf(hierarchy);
    }

    static synchronized ComprehensiveRules bundled() {
        if (bundled == null) {
            final var stream = ComprehensiveRules.class.getResourceAsStream("comprehensive-rules.txt");
            if (stream == null) throw new IllegalStateException("Bundled Comprehensive Rules are missing");
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                bundled = parse(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read bundled Comprehensive Rules", e);
            }
        }
        return bundled;
    }

    static ComprehensiveRules parse(Reader source) throws IOException {
        final var reader = new BufferedReader(source);
        final Map<String, Entry> entries = new LinkedHashMap<>();
        String edition = null;
        boolean body = false;
        String id = null;
        final var text = new StringBuilder();
        for (String line; (line = reader.readLine()) != null;) {
            line = line.strip();
            if (!body) {
                if (line.startsWith("These rules are effective as of ")) edition = line;
                // The table of contents ends at its Credits entry, before chapter 1 repeats.
                if (line.equals("Credits")) body = true;
                continue;
            }
            if (line.equals("Glossary")) break;
            final var heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (id != null) add(entries, id, text.toString().strip());
                id = heading.group(1) + (heading.group(2) == null ? "" : "." + heading.group(2))
                        + (heading.group(3) == null ? "" : heading.group(3));
                text.setLength(0);
                text.append(heading.group(4));
            } else if (id != null) {
                text.append('\n').append(line);
            }
        }
        if (id != null) add(entries, id, text.toString().strip());
        if (edition == null || !entries.containsKey("100.1") || !entries.containsKey("905")) {
            throw new IllegalArgumentException("Incomplete Comprehensive Rules document");
        }
        return new ComprehensiveRules(edition, entries);
    }

    private static void add(Map<String, Entry> entries, String id, String text) {
        final int dot = id.indexOf('.');
        final String parent = dot < 0 ? (id.length() == 1 ? "" : id.substring(0, 1))
                : Character.isLetter(id.charAt(id.length() - 1)) ? id.replaceFirst("[a-z]+$", "") : id.substring(0, dot);
        if (entries.putIfAbsent(id, new Entry(id, parent, text)) != null) {
            throw new IllegalArgumentException("Duplicate rule: " + id);
        }
    }

    Index index(RulesIndex request) {
        if (!request.parent().isEmpty()) entry(request.parent());
        final var ids = children.getOrDefault(request.parent(), List.of());
        if (request.offset() < 0 || request.offset() > ids.size()) {
            throw new IllegalArgumentException("offset must be between 0 and " + ids.size());
        }
        final int end = (int) Math.min(ids.size(), (long) request.offset() + RulesIndex.PAGE_SIZE);
        final List<Summary> page = ids.subList(request.offset(), end).stream().map(id -> {
            final Entry entry = entry(id);
            final String preview = entry.text().replaceAll("\\s+", " ");
            return new Summary(id, preview.length() > 160 ? preview.substring(0, 157) + "..." : preview,
                    children.getOrDefault(id, List.of()).size());
        }).toList();
        return new Index(edition, SOURCE, request.parent(), page, ids.size(), end < ids.size() ? end : null);
    }

    Paragraph read(RuleText request) {
        final Entry entry = entry(request.id());
        return new Paragraph(edition, SOURCE, entry.id(), entry.parent(), entry.text(),
                children.getOrDefault(entry.id(), List.of()).size());
    }

    private Entry entry(String id) {
        final Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Rule not found: " + id + ". Use get_rules_index to browse valid ids.");
        return entry;
    }

    private record Entry(String id, String parent, String text) { }
    public record Summary(String id, String preview, int childCount) { }
    public record Index(String edition, String source, String parent, List<Summary> entries, int totalEntries,
                        Integer nextOffset) { }
    public record Paragraph(String edition, String source, String id, String parent, String text, int childCount) { }
}
