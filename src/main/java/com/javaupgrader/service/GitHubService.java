package com.javaupgrader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);
    private static final Pattern GITHUB_URL_PATTERN =
        Pattern.compile("github\\.com[:/]([^/]+)/([^/]+?)(\\.git)?$");

    private final String githubToken;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public GitHubService(@Qualifier("githubToken") String githubToken) {
        this.githubToken = githubToken;
        this.httpClient = HttpClient.newHttpClient();
    }

    public record RepoInfo(String owner, String repo) {}

    /** Parses https://github.com/owner/repo[.git] and git@github.com:owner/repo[.git] */
    public static RepoInfo parseUrl(String githubUrl) {
        Matcher m = GITHUB_URL_PATTERN.matcher(githubUrl.strip());
        if (!m.find()) {
            throw new IllegalArgumentException("Cannot parse GitHub URL: " + githubUrl);
        }
        return new RepoInfo(m.group(1), m.group(2));
    }

    /** Plain HTTPS clone URL — authentication is handled via the .netrc written by writeNetrc(). */
    public String getPlainCloneUrl(RepoInfo repo) {
        return "https://github.com/" + repo.owner() + "/" + repo.repo() + ".git";
    }

    /**
     * Writes a .netrc file into {@code homeDir} so git can authenticate without
     * the token appearing in the process listing or git command arguments.
     *
     * File is created with 600 permissions (owner read/write only).
     */
    public void writeNetrc(Path homeDir) throws IOException {
        requireToken();
        Path netrc = homeDir.resolve(".netrc");
        String content = "machine github.com\nlogin x-access-token\npassword " + githubToken + "\n";
        Files.writeString(netrc, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(netrc, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        ));
        log.debug("Wrote .netrc to {}", homeDir);
    }

    public String getDefaultBranch(RepoInfo repo) throws IOException, InterruptedException {
        HttpRequest request = newAuthRequest("repos/" + repo.owner() + "/" + repo.repo()).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkStatus(response, "get default branch");
        JsonNode node = objectMapper.readTree(response.body());
        return node.path("default_branch").asText("main");
    }

    public String createPullRequest(RepoInfo repo, String head, String base, int targetVersion, String agentSummary)
            throws IOException, InterruptedException {
        String body = buildPrBody(targetVersion, agentSummary);
        String requestBody = objectMapper.writeValueAsString(Map.of(
            "title", "chore: upgrade to Java " + targetVersion,
            "body", body,
            "head", head,
            "base", base
        ));
        HttpRequest request = newAuthRequest("repos/" + repo.owner() + "/" + repo.repo() + "/pulls")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkStatus(response, "create pull request");
        JsonNode node = objectMapper.readTree(response.body());
        return node.path("html_url").asText();
    }

    public String createIssue(RepoInfo repo, String title, String body) throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(Map.of(
            "title", title,
            "body", body,
            "labels", java.util.List.of("java-upgrader")
        ));
        HttpRequest request = newAuthRequest("repos/" + repo.owner() + "/" + repo.repo() + "/issues")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkStatus(response, "create issue");
        JsonNode node = objectMapper.readTree(response.body());
        return node.path("html_url").asText();
    }

    private HttpRequest.Builder newAuthRequest(String path) {
        requireToken();
        return HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/" + path))
            .header("Authorization", "Bearer " + githubToken)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private void requireToken() {
        if (githubToken == null || githubToken.isBlank()) {
            throw new IllegalStateException(
                "No GitHub token configured. Set GITHUB_TOKEN env var, " +
                "or set github.token.ssm-path to an SSM Parameter Store path.");
        }
    }

    private void checkStatus(HttpResponse<String> response, String operation) {
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            String detail = response.body().length() > 500
                ? response.body().substring(0, 500) + "..." : response.body();
            throw new RuntimeException(
                "GitHub API error during '" + operation + "' [HTTP " + code + "]: " + detail);
        }
    }

    private String buildPrBody(int targetVersion, String summary) {
        String changes = (summary == null || summary.isBlank())
            ? "See file-level diffs for details." : summary;
        return """
            ## Upgrade to Java %d

            This PR was automatically generated by the **Java Upgrader Agent**.

            ### What changed

            %s

            ---
            *Generated by java-upgrader*
            """.formatted(targetVersion, changes);
    }
}
