package com.javaupgrader.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;

@Configuration
public class GitHubTokenConfig {

    private static final Logger log = LoggerFactory.getLogger(GitHubTokenConfig.class);

    @Value("${github.token.ssm-path:}")
    private String ssmPath;

    @Value("${GITHUB_TOKEN:}")
    private String envToken;

    /**
     * Resolves the GitHub token from one of two sources, in priority order:
     *
     *  1. AWS SSM Parameter Store — when {@code github.token.ssm-path} is set.
     *     The Lightsail instance must have an IAM role with ssm:GetParameter on the path.
     *     No token ever appears in env vars, config files, or process listings.
     *
     *  2. GITHUB_TOKEN environment variable — fallback for local development.
     *
     * The bean value may be blank if neither is configured; GitHubService validates
     * at call time and logs a clear error.
     */
    @Bean("githubToken")
    public String githubToken() {
        if (!ssmPath.isBlank()) {
            return fetchFromSsm(ssmPath);
        }
        return envToken;
    }

    private String fetchFromSsm(String paramPath) {
        log.info("Fetching GitHub token from SSM Parameter Store: {}", paramPath);
        try (SsmClient ssm = SsmClient.create()) {  // uses instance profile credentials on Lightsail
            String token = ssm.getParameter(r -> r.name(paramPath).withDecryption(true))
                .parameter().value();
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("SSM parameter '" + paramPath + "' exists but is empty.");
            }
            log.info("GitHub token loaded from SSM.");
            return token;
        } catch (SsmException e) {
            throw new IllegalStateException(
                "Failed to fetch GitHub token from SSM '" + paramPath + "': " + e.getMessage() +
                " — ensure the Lightsail instance has an IAM role with ssm:GetParameter on this path.", e);
        }
    }
}
