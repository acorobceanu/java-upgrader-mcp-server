package com.javaupgrader.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SecretScanner {

    private static final Logger log = LoggerFactory.getLogger(SecretScanner.class);

    static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("ghp_[A-Za-z0-9]{36}"),                                    // GitHub classic PAT
        Pattern.compile("github_pat_[A-Za-z0-9_]{82}"),                            // GitHub fine-grained PAT
        Pattern.compile("ghs_[A-Za-z0-9]{36}"),                                    // GitHub Actions token
        Pattern.compile("AKIA[0-9A-Z]{16}"),                                       // AWS access key ID
        Pattern.compile("sk-ant-api[0-9A-Za-z\\-_]{90,}"),                        // Anthropic API key
        Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY"), // PEM private keys
        // Keyword=value pairs — excludes template vars (${...}), placeholders (<...>), and masked values (***)
        Pattern.compile("(?i)(?:password|passwd|secret|api[_.\\-]?key|access[_.\\-]?key|auth[_.\\-]?token)" +
                        "\\s*[=:]\\s*[\"']?(?![${<*])[A-Za-z0-9+/=_\\-]{12,}[\"']?")
    );

    public record ScanResult(boolean clean, List<String> findings) {}

    /** Runs {@code git show HEAD} on the cloned repo and scans the diff. */
    public ScanResult scan(Path repoDir, Map<String, String> gitEnv) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "show", "HEAD");
            pb.directory(repoDir.toFile());
            pb.environment().putAll(gitEnv);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try {
                String diff = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                proc.waitFor();
                return scanDiff(diff);
            } finally {
                proc.destroyForcibly();
            }
        } catch (IOException | InterruptedException e) {
            log.error("Secret scan could not run: {}", e.getMessage());
            // Fail-safe: block the push if the scan itself errors
            return new ScanResult(false, List.of("Secret scan failed to execute: " + e.getMessage()));
        }
    }

    /** Package-private so tests can exercise pattern matching without spawning git. */
    ScanResult scanDiff(String diff) {
        List<String> findings = new ArrayList<>();
        int lineNum = 0;
        for (String line : diff.split("\n")) {
            lineNum++;
            // Only scan added lines; skip diff file headers (+++ b/...)
            if (!line.startsWith("+") || line.startsWith("+++")) continue;
            String content = line.substring(1);
            for (Pattern pattern : SECRET_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    findings.add("line " + lineNum + ": " + content.strip());
                    break;
                }
            }
        }
        if (!findings.isEmpty()) {
            log.warn("Secret scan found {} potential secret(s) in commit diff", findings.size());
        }
        return new ScanResult(findings.isEmpty(), findings);
    }
}
