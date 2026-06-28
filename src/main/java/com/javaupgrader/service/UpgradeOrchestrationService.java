package com.javaupgrader.service;

import com.javaupgrader.agent.JavaUpgraderAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class UpgradeOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeOrchestrationService.class);

    private final GitHubService gitHubService;
    private final JavaUpgraderAgent upgraderAgent;
    private final SecretScanner secretScanner;

    public UpgradeOrchestrationService(GitHubService gitHubService, JavaUpgraderAgent upgraderAgent,
                                       SecretScanner secretScanner) {
        this.gitHubService = gitHubService;
        this.upgraderAgent = upgraderAgent;
        this.secretScanner = secretScanner;
    }

    /**
     * Clones {@code githubUrl}, runs the AI upgrade agent, commits and pushes the changes,
     * and opens a pull request. Returns a human-readable result string.
     *
     * Throws {@link RuntimeException} on failure (MCP framework surfaces this as an isError response).
     */
    public String upgrade(String githubUrl, int targetJavaVersion) {
        GitHubService.RepoInfo repo = null;
        Path baseDir = null;
        Path credHome = null;  // temporary HOME containing .netrc — never appears in process listings

        try {
            repo = GitHubService.parseUrl(githubUrl);
            String defaultBranch = gitHubService.getDefaultBranch(repo);
            log.info("Upgrading {}/{} → Java {}", repo.owner(), repo.repo(), targetJavaVersion);

            // Write credentials to a temp dir used as HOME for all git commands.
            // The token lives in a 600-permission .netrc file, not in any command argument.
            credHome = Files.createTempDirectory("git-home-");
            gitHubService.writeNetrc(credHome);
            Map<String, String> gitEnv = Map.of("HOME", credHome.toString());

            baseDir = Files.createTempDirectory("java-upgrader-");
            String cloneUrl = gitHubService.getPlainCloneUrl(repo);
            runCommand(baseDir, List.of("git", "clone", "--depth", "1", cloneUrl, "repo"), gitEnv);

            Path cloneDir = baseDir.resolve("repo");
            runCommand(cloneDir, List.of("git", "config", "user.email", "java-upgrader@bot.local"), gitEnv);
            runCommand(cloneDir, List.of("git", "config", "user.name", "Java Upgrader Bot"), gitEnv);

            String branchName = "java-upgrade-to-" + targetJavaVersion;
            runCommand(cloneDir, List.of("git", "checkout", "-b", branchName), gitEnv);

            String agentSummary = upgraderAgent.run(cloneDir.toString(), targetJavaVersion);

            String gitStatus = runCommand(cloneDir, List.of("git", "status", "--porcelain"), gitEnv);
            if (gitStatus.isBlank()) {
                log.info("No changes for {}/{} — already at Java {}", repo.owner(), repo.repo(), targetJavaVersion);
                return "No changes needed — %s/%s is already at Java %d."
                    .formatted(repo.owner(), repo.repo(), targetJavaVersion);
            }

            runCommand(cloneDir, List.of("git", "add", "-A"), gitEnv);
            runCommand(cloneDir, List.of("git", "commit", "-m", "chore: upgrade to Java " + targetJavaVersion), gitEnv);

            SecretScanner.ScanResult scan = secretScanner.scan(cloneDir, gitEnv);
            if (!scan.clean()) {
                throw new RuntimeException(
                    "Push aborted — potential secrets detected in commit diff:\n" +
                    String.join("\n", scan.findings()));
            }

            runCommand(cloneDir, List.of("git", "push", "origin", branchName), gitEnv);

            String prUrl = gitHubService.createPullRequest(repo, branchName, defaultBranch, targetJavaVersion, agentSummary);
            log.info("PR created for {}/{}: {}", repo.owner(), repo.repo(), prUrl);
            return "Upgrade to Java %d complete. Pull request: %s".formatted(targetJavaVersion, prUrl);

        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Upgrade failed for {}: {}", githubUrl, error, e);
            String issueUrl = tryCreateFailureIssue(repo, githubUrl, targetJavaVersion, error);
            String message = "Upgrade to Java %d failed for %s: %s".formatted(targetJavaVersion, githubUrl, error);
            if (issueUrl != null) {
                message += " Failure issue created: " + issueUrl;
            }
            throw new RuntimeException(message, e);
        } finally {
            cleanupTempDir(baseDir);
            cleanupTempDir(credHome);  // .netrc deleted here — token no longer on disk
        }
    }

    private String tryCreateFailureIssue(GitHubService.RepoInfo repo, String githubUrl,
                                          int targetJavaVersion, String error) {
        if (repo == null) return null;
        try {
            String title = "[java-upgrader] Upgrade to Java " + targetJavaVersion + " failed";
            String body = """
                ## Java %d Upgrade Failed

                The **java-upgrader** agent could not complete the upgrade for this repository.

                ### Error

                ```
                %s
                ```

                ### What to check

                - `GITHUB_TOKEN` has `repo` and `issues` write scope
                - The repository is accessible and not archived
                - Retry via the `upgrade_java` MCP tool if the error looks transient

                ---
                *Generated by java-upgrader*
                """.formatted(targetJavaVersion, error);
            String issueUrl = gitHubService.createIssue(repo, title, body);
            log.info("Failure issue created: {}", issueUrl);
            return issueUrl;
        } catch (Exception ex) {
            log.error("Also failed to create GitHub issue for {}/{}: {}",
                repo.owner(), repo.repo(), ex.getMessage());
            return null;
        }
    }

    /**
     * Executes a git command as a typed argument list — no shell interpolation, no injection risk.
     * Package-private to allow spy-based unit testing without spawning real processes.
     */
    String runCommand(Path workDir, List<String> command, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process proc = pb.start();
        try {
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Command failed [exit " + exitCode + "]: "
                    + String.join(" ", command) + "\n" + output);
            }
            return output;
        } finally {
            proc.destroyForcibly();
        }
    }

    private void cleanupTempDir(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            log.debug("Cleaned up temp dir: {}", dir);
        } catch (IOException e) {
            log.warn("Failed to clean up temp dir {}: {}", dir, e.getMessage());
        }
    }
}
