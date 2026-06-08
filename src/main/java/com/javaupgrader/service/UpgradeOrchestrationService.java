package com.javaupgrader.service;

import com.javaupgrader.agent.JavaUpgraderAgent;
import com.javaupgrader.dto.JobStatus;
import com.javaupgrader.dto.UpgradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

@Service
public class UpgradeOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeOrchestrationService.class);

    private final GitHubService gitHubService;
    private final JavaUpgraderAgent upgraderAgent;
    private final JobStore jobStore;
    private final SecretScanner secretScanner;

    public UpgradeOrchestrationService(GitHubService gitHubService, JavaUpgraderAgent upgraderAgent,
                                       JobStore jobStore, SecretScanner secretScanner) {
        this.gitHubService = gitHubService;
        this.upgraderAgent = upgraderAgent;
        this.jobStore = jobStore;
        this.secretScanner = secretScanner;
    }

    @Async("upgradeExecutor")
    public void upgrade(UpgradeRequest request, String jobId) {
        int targetVersion = request.effectiveTargetVersion();
        GitHubService.RepoInfo repo = null;
        Path baseDir = null;
        Path credHome = null;  // temporary HOME containing .netrc — never appears in process listings

        try {
            repo = GitHubService.parseUrl(request.githubUrl());
            String defaultBranch = gitHubService.getDefaultBranch(repo);
            log.info("Upgrading {}/{} → Java {} [job={}]", repo.owner(), repo.repo(), targetVersion, jobId);

            // Write credentials to a temp dir used as HOME for all git commands.
            // The token lives in a 600-permission .netrc file, not in any command argument.
            credHome = Files.createTempDirectory("git-home-");
            gitHubService.writeNetrc(credHome);
            Map<String, String> gitEnv = Map.of("HOME", credHome.toString());

            baseDir = Files.createTempDirectory("java-upgrader-");
            runCommand(baseDir, "git clone --depth 1 " + gitHubService.getPlainCloneUrl(repo) + " repo", gitEnv);

            Path cloneDir = baseDir.resolve("repo");
            runCommand(cloneDir, "git config user.email 'java-upgrader@bot.local'", gitEnv);
            runCommand(cloneDir, "git config user.name 'Java Upgrader Bot'", gitEnv);

            String branchName = "java-upgrade-to-" + targetVersion;
            runCommand(cloneDir, "git checkout -b " + branchName, gitEnv);

            String agentSummary = upgraderAgent.run(cloneDir.toString(), targetVersion);

            String gitStatus = runCommand(cloneDir, "git status --porcelain", gitEnv);
            if (gitStatus.isBlank()) {
                log.info("No changes for {}/{} — already up to date. [job={}]", repo.owner(), repo.repo(), jobId);
                jobStore.put(jobId, JobStatus.noChanges(jobId, request.githubUrl(), targetVersion));
                return;
            }

            runCommand(cloneDir, "git add -A", gitEnv);
            runCommand(cloneDir, "git commit -m 'chore: upgrade to Java " + targetVersion + "'", gitEnv);

            SecretScanner.ScanResult scan = secretScanner.scan(cloneDir, gitEnv);
            if (!scan.clean()) {
                throw new RuntimeException(
                    "Push aborted — potential secrets detected in commit diff:\n" +
                    String.join("\n", scan.findings()));
            }

            runCommand(cloneDir, "git push origin " + branchName, gitEnv);

            String prUrl = gitHubService.createPullRequest(repo, branchName, defaultBranch, targetVersion, agentSummary);
            log.info("PR created for {}/{}: {} [job={}]", repo.owner(), repo.repo(), prUrl, jobId);
            jobStore.put(jobId, JobStatus.succeeded(jobId, request.githubUrl(), targetVersion, prUrl));

        } catch (Exception e) {
            String error = e.getMessage();
            log.error("Upgrade failed for {} [job={}]: {}", request.githubUrl(), jobId, error, e);
            String issueUrl = tryCreateFailureIssue(repo, request.githubUrl(), jobId, targetVersion, error);
            jobStore.put(jobId, JobStatus.failed(jobId, request.githubUrl(), targetVersion, error, issueUrl));
        } finally {
            cleanupTempDir(baseDir);
            cleanupTempDir(credHome);  // .netrc deleted here — token no longer on disk
        }
    }

    private String tryCreateFailureIssue(GitHubService.RepoInfo repo, String githubUrl, String jobId,
                                          int targetVersion, String error) {
        if (repo == null) return null;
        try {
            String title = "[java-upgrader] Upgrade to Java " + targetVersion + " failed";
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
                - Retry via `POST /api/upgrade` if the error looks transient

                ---
                *job id: `%s` — generated by java-upgrader*
                """.formatted(targetVersion, error, jobId);
            String issueUrl = gitHubService.createIssue(repo, title, body);
            log.info("Failure issue created: {} [job={}]", issueUrl, jobId);
            return issueUrl;
        } catch (Exception ex) {
            log.error("Also failed to create GitHub issue for {}/{} [job={}]: {}",
                repo.owner(), repo.repo(), jobId, ex.getMessage());
            return null;
        }
    }

    private String runCommand(Path workDir, String command) throws IOException, InterruptedException {
        return runCommand(workDir, command, Map.of());
    }

    private String runCommand(Path workDir, String command, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed [exit " + exitCode + "]: " + command + "\n" + output);
        }
        return output;
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
