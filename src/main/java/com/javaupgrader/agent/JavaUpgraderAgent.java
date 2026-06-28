package com.javaupgrader.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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
import java.util.stream.Stream;

/**
 * AI agent that drives the Java upgrade by reading, writing, and executing commands inside
 * a cloned repository. Tools are exposed to the model via Spring AI's {@link Tool} annotation;
 * Spring AI handles the multi-turn tool-call loop automatically.
 *
 * <p>Not safe for concurrent calls — upgrade runs are long-lived and serialised by the
 * single-threaded executor in {@link com.javaupgrader.service.UpgradeJobService}.
 */
@Component
public class JavaUpgraderAgent {

    private static final Logger log = LoggerFactory.getLogger(JavaUpgraderAgent.class);

    // Set at the start of each run() call. Volatile because UpgradeJobService submits to an
    // executor thread, though the single-threaded executor prevents actual concurrency.
    volatile String projectRoot;

    private final ChatClient chatClient;

    public JavaUpgraderAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Runs the upgrade agent against {@code projectPath} and returns the model's narrative
     * summary of every change it made.
     */
    public String run(String projectPath, int targetVersion) {
        projectRoot = Paths.get(projectPath).toAbsolutePath().normalize().toString();
        log.info("Agent starting — project: {}, target Java: {}", projectRoot, targetVersion);

        String response = chatClient.prompt()
            .system(buildSystemPrompt(targetVersion))
            .user("Upgrade the Java project at '%s' to Java %d. Start by reading pom.xml (and build.gradle if present), "
                + "then list and analyze source files, then apply the upgrades. Report each change you make."
                .formatted(projectRoot, targetVersion))
            .tools(this)
            .call()
            .content();

        String result = response != null ? response.strip() : "";
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
            """.formatted(projectRoot, targetVersion, targetVersion, projectRoot, targetVersion);
    }

    // ==================== TOOLS ====================

    Path resolvePath(String pathStr) {
        Path p = Paths.get(pathStr);
        return p.isAbsolute() ? p.normalize() : Paths.get(projectRoot).resolve(pathStr).normalize();
    }

    boolean isWithinProject(Path resolved) {
        return resolved.normalize().startsWith(Paths.get(projectRoot).normalize());
    }

    @Tool(description = "Read the full text contents of a file in the project")
    public String readFile(
            @ToolParam(description = "Path to the file — relative to project root (e.g. 'pom.xml', 'src/main/java/Foo.java') or absolute")
            String path) {
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

    @Tool(description = "Write (create or overwrite) a file in the project with new content")
    public String writeFile(
            @ToolParam(description = "Path to the file — relative to project root or absolute")
            String path,
            @ToolParam(description = "The complete new content to write to the file")
            String content) {
        try {
            Path resolved = resolvePath(path);
            if (!isWithinProject(resolved)) {
                return "Error: refusing to write outside the project directory: " + resolved;
            }
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return "Wrote " + content.length() + " chars to " + Paths.get(projectRoot).relativize(resolved);
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool(description = "List files in a directory, optionally filtered by a glob pattern")
    public String listFiles(
            @ToolParam(description = "Directory to list — relative to project root or absolute. Use '.' for the project root.")
            String directory,
            @ToolParam(description = "Optional glob pattern, e.g. '**/*.java', '*.xml'. Defaults to all files.", required = false)
            String pattern) {
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

    @Tool(description = "Execute a shell command in the project directory and return its output")
    public String executeCommand(
            @ToolParam(description = "Shell command to run, e.g. 'mvn dependency:tree -q', 'grep -r \"TODO\" src/'")
            String command,
            @ToolParam(description = "Working directory (absolute). Defaults to project root if blank.", required = false)
            String workingDirectory) {
        try {
            String workDir = (workingDirectory == null || workingDirectory.isBlank())
                ? projectRoot : workingDirectory;
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
