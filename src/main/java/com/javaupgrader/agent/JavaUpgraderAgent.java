package com.javaupgrader.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Component
public class JavaUpgraderAgent {

    private static final Logger log = LoggerFactory.getLogger(JavaUpgraderAgent.class);

    // Not safe for concurrent requests — upgrade runs are long-lived and single-user by design.
    static volatile String PROJECT_ROOT;

    private final AnthropicClient client;

    public JavaUpgraderAgent(AnthropicClient client) {
        this.client = client;
    }

    /**
     * Runs the upgrade agent against {@code projectPath} and returns Claude's narrative summary
     * of every change it made.
     */
    public String run(String projectPath, int targetVersion) {
        PROJECT_ROOT = Paths.get(projectPath).toAbsolutePath().normalize().toString();
        log.info("Agent starting — project: {}, target Java: {}", PROJECT_ROOT, targetVersion);

        MessageCreateParams params = MessageCreateParams.builder()
            .model("claude-opus-4-8")
            .maxTokens(16000L)
            .putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13")
            .system(buildSystemPrompt(targetVersion))
            .addTool(ReadFileTool.class)
            .addTool(WriteFileTool.class)
            .addTool(ListFilesTool.class)
            .addTool(ExecuteCommandTool.class)
            .addUserMessage(
                "Upgrade the Java project at '%s' to Java %d. Start by reading pom.xml (and build.gradle if present), "
                + "then list and analyze source files, then apply the upgrades. Report each change you make."
                .formatted(PROJECT_ROOT, targetVersion))
            .build();

        BetaToolRunner toolRunner = client.beta().messages().toolRunner(params);

        StringBuilder summary = new StringBuilder();
        for (BetaMessage message : toolRunner) {
            for (BetaContentBlock block : message.content()) {
                block.text().ifPresent(tb -> {
                    String text = tb.text().strip();
                    if (!text.isEmpty()) {
                        summary.append(text).append("\n\n");
                    }
                });
            }
        }

        String result = summary.toString().strip();
        log.info("Agent finished. Summary: {} chars", result.length());
        return result;
    }

    private String buildSystemPrompt(int targetVersion) {
        return """
            You are an expert Java upgrade agent. Upgrade the project at '%s' to Java %d.

            WHAT TO UPGRADE:

            1. BUILD CONFIG (pom.xml / build.gradle):
               - Set java source/target version to %d
               - Update <maven.compiler.source>, <maven.compiler.target> or <java.version> property
               - Update maven-compiler-plugin if present
               - Update Spring Boot / testing / plugin versions to current stable

            2. SOURCE CODE (.java files) — apply where safe:
               - var for local variable type inference (Java 10+)
               - Text blocks for multiline strings (Java 15+)
               - Switch expressions (Java 14+)
               - Records for simple data carriers (Java 16+)
               - Pattern matching for instanceof (Java 16+)
               - Sealed classes where inheritance is controlled (Java 17+)
               - Modern Stream API: toList() instead of collect(Collectors.toList()) etc.
               - Enhanced switch with patterns (Java 21+) if target ≥ 21

            RULES:
            - NEVER change business logic, method signatures, class/field names, or public APIs
            - Only make changes that preserve identical runtime behaviour
            - Skip files that don't need changes
            - Report each modified file and what you changed

            Project root: %s
            Target Java: %d
            """.formatted(PROJECT_ROOT, targetVersion, targetVersion, PROJECT_ROOT, targetVersion);
    }

    // ==================== TOOLS ====================

    static Path resolvePath(String pathStr) {
        Path p = Paths.get(pathStr);
        return p.isAbsolute() ? p.normalize() : Paths.get(PROJECT_ROOT).resolve(pathStr).normalize();
    }

    static boolean isWithinProject(Path resolved) {
        return resolved.normalize().startsWith(Paths.get(PROJECT_ROOT).normalize());
    }

    @JsonClassDescription("Read the full text contents of a file in the project")
    public static class ReadFileTool implements Supplier<String> {
        @JsonPropertyDescription("Path to the file — relative to project root (e.g. 'pom.xml', 'src/main/java/Foo.java') or absolute")
        public String path;

        @Override
        public String get() {
            try {
                Path resolved = resolvePath(path);
                if (!Files.exists(resolved)) return "File not found: " + resolved;
                if (Files.isDirectory(resolved)) return "Path is a directory: " + resolved;
                String content = Files.readString(resolved, StandardCharsets.UTF_8);
                return content.length() > 60_000
                    ? content.substring(0, 60_000) + "\n\n... [truncated at 60 000 chars]"
                    : content;
            } catch (IOException e) {
                return "Error reading file: " + e.getMessage();
            }
        }
    }

    @JsonClassDescription("Write (create or overwrite) a file in the project with new content")
    public static class WriteFileTool implements Supplier<String> {
        @JsonPropertyDescription("Path to the file — relative to project root or absolute")
        public String path;

        @JsonPropertyDescription("The complete new content to write to the file")
        public String content;

        @Override
        public String get() {
            try {
                Path resolved = resolvePath(path);
                if (!isWithinProject(resolved)) {
                    return "Error: refusing to write outside the project directory: " + resolved;
                }
                Files.createDirectories(resolved.getParent());
                Files.writeString(resolved, content, StandardCharsets.UTF_8);
                return "Wrote " + content.length() + " chars to " + Paths.get(PROJECT_ROOT).relativize(resolved);
            } catch (IOException e) {
                return "Error writing file: " + e.getMessage();
            }
        }
    }

    @JsonClassDescription("List files in a directory, optionally filtered by a glob pattern")
    public static class ListFilesTool implements Supplier<String> {
        @JsonPropertyDescription("Directory to list — relative to project root or absolute. Use '.' for the project root.")
        public String directory;

        @JsonPropertyDescription("Optional glob pattern, e.g. '**/*.java', '*.xml'. Defaults to all files.")
        public String pattern;

        @Override
        public String get() {
            try {
                Path dir = resolvePath(directory == null || directory.isBlank() ? "." : directory);
                if (!Files.isDirectory(dir)) return "Not a directory: " + dir;

                String glob = (pattern == null || pattern.isBlank()) ? "**/*" : pattern;
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);

                List<String> results = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(dir.relativize(p)))
                        .sorted()
                        .forEach(p -> results.add(dir.relativize(p).toString()));
                }
                return results.isEmpty() ? "No files found" : String.join("\n", results);
            } catch (IOException e) {
                return "Error listing files: " + e.getMessage();
            }
        }
    }

    @JsonClassDescription("Execute a shell command in the project directory and return its output")
    public static class ExecuteCommandTool implements Supplier<String> {
        @JsonPropertyDescription("Shell command to run, e.g. 'mvn dependency:tree -q', 'grep -r \"TODO\" src/'")
        public String command;

        @JsonPropertyDescription("Working directory (absolute). Defaults to project root if blank.")
        public String workingDirectory;

        @Override
        public String get() {
            try {
                String workDir = (workingDirectory == null || workingDirectory.isBlank())
                    ? PROJECT_ROOT : workingDirectory;
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                pb.directory(new File(workDir));
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder out = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) out.append(line).append('\n');
                    int exitCode = proc.waitFor();
                    String result = out.toString();
                    if (result.length() > 12_000) result = result.substring(0, 12_000) + "\n... [truncated]";
                    return (exitCode != 0 ? "[exit " + exitCode + "]\n" : "") + (result.isBlank() ? "[no output]" : result);
                }
            } catch (IOException | InterruptedException e) {
                return "Error executing command: " + e.getMessage();
            }
        }
    }
}
