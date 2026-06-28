package com.javaupgrader.service;

import com.javaupgrader.agent.JavaUpgraderAgent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UpgradeOrchestrationService}.
 *
 * Git command execution is stubbed via a Mockito spy on the package-private
 * {@code runCommand} method, so no real git binary or network access is needed.
 */
class UpgradeOrchestrationServiceTest {

    private static final String GITHUB_URL = "https://github.com/owner/repo";
    private static final int TARGET_VERSION = 21;

    // ---------------------------------------------------------------------------
    // Happy path — changes detected, secret scan passes, PR created
    // ---------------------------------------------------------------------------

    @Test
    void upgrade_happyPath_returnsPrUrl() throws Exception {
        GitHubService gh = mock(GitHubService.class);
        JavaUpgraderAgent agent = mock(JavaUpgraderAgent.class);
        SecretScanner scanner = mock(SecretScanner.class);

        when(gh.getDefaultBranch(any())).thenReturn("main");
        when(gh.getPlainCloneUrl(any())).thenReturn("https://github.com/owner/repo.git");
        when(agent.run(anyString(), eq(TARGET_VERSION))).thenReturn("Upgraded pom.xml to Java 21");
        when(scanner.scan(any(), anyMap()))
                .thenReturn(new SecretScanner.ScanResult(true, List.of()));
        when(gh.createPullRequest(any(), any(), any(), eq(TARGET_VERSION), any()))
                .thenReturn("https://github.com/owner/repo/pull/1");

        UpgradeOrchestrationService service = spy(new UpgradeOrchestrationService(gh, agent, scanner));

        // Stub all git commands; return a non-blank status so the upgrade proceeds past
        // the no-changes check.
        doAnswer(inv -> {
            List<?> cmd = inv.getArgument(1);
            if (cmd.size() > 1 && "status".equals(cmd.get(1))) return "M pom.xml\n";
            return "";
        }).when(service).runCommand(any(Path.class), anyList(), anyMap());

        String result = service.upgrade(GITHUB_URL, TARGET_VERSION);

        assertTrue(result.contains("https://github.com/owner/repo/pull/1"),
                "Expected PR URL in result, got: " + result);
        verify(gh).createPullRequest(any(), eq("java-upgrade-to-21"), eq("main"), eq(21), any());
    }

    // ---------------------------------------------------------------------------
    // No-changes path — git status returns blank, short-circuit before PR
    // ---------------------------------------------------------------------------

    @Test
    void upgrade_noChanges_returnsNoChangesMessage() throws Exception {
        GitHubService gh = mock(GitHubService.class);
        JavaUpgraderAgent agent = mock(JavaUpgraderAgent.class);
        SecretScanner scanner = mock(SecretScanner.class);

        when(gh.getDefaultBranch(any())).thenReturn("main");
        when(gh.getPlainCloneUrl(any())).thenReturn("https://github.com/owner/repo.git");
        when(agent.run(anyString(), eq(TARGET_VERSION))).thenReturn("No changes required");

        UpgradeOrchestrationService service = spy(new UpgradeOrchestrationService(gh, agent, scanner));

        // All git commands return blank output — git status is blank so no changes detected.
        doReturn("").when(service).runCommand(any(Path.class), anyList(), anyMap());

        String result = service.upgrade(GITHUB_URL, TARGET_VERSION);

        assertTrue(result.contains("No changes needed"),
                "Expected no-changes message, got: " + result);
        verify(gh, never()).createPullRequest(any(), any(), any(), anyInt(), any());
        verify(scanner, never()).scan(any(), anyMap());
    }

    // ---------------------------------------------------------------------------
    // Secret-detected path — scan blocks the push, throws RuntimeException
    // ---------------------------------------------------------------------------

    @Test
    void upgrade_secretsDetected_throwsRuntimeException() throws Exception {
        GitHubService gh = mock(GitHubService.class);
        JavaUpgraderAgent agent = mock(JavaUpgraderAgent.class);
        SecretScanner scanner = mock(SecretScanner.class);

        when(gh.getDefaultBranch(any())).thenReturn("main");
        when(gh.getPlainCloneUrl(any())).thenReturn("https://github.com/owner/repo.git");
        when(agent.run(anyString(), eq(TARGET_VERSION))).thenReturn("summary");
        when(scanner.scan(any(), anyMap()))
                .thenReturn(new SecretScanner.ScanResult(
                        false, List.of("line 5: [REDACTED_TOKEN_FOR_TESTING]")));

        UpgradeOrchestrationService service = spy(new UpgradeOrchestrationService(gh, agent, scanner));

        doAnswer(inv -> {
            List<?> cmd = inv.getArgument(1);
            if (cmd.size() > 1 && "status".equals(cmd.get(1))) return "M pom.xml\n";
            return "";
        }).when(service).runCommand(any(Path.class), anyList(), anyMap());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.upgrade(GITHUB_URL, TARGET_VERSION));

        assertTrue(ex.getMessage().toLowerCase().contains("secret"),
                "Expected secrets error, got: " + ex.getMessage());
        verify(gh, never()).createPullRequest(any(), any(), any(), anyInt(), any());
    }

    // ---------------------------------------------------------------------------
    // Failure path — underlying call throws, exception is wrapped and rethrown
    // ---------------------------------------------------------------------------

    @Test
    void upgrade_gitHubServiceThrows_wrapsExceptionWithUpgradeMessage() throws Exception {
        GitHubService gh = mock(GitHubService.class);
        JavaUpgraderAgent agent = mock(JavaUpgraderAgent.class);
        SecretScanner scanner = mock(SecretScanner.class);

        when(gh.getDefaultBranch(any())).thenThrow(new RuntimeException("Network error"));

        UpgradeOrchestrationService service = new UpgradeOrchestrationService(gh, agent, scanner);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.upgrade(GITHUB_URL, TARGET_VERSION));

        assertTrue(ex.getMessage().contains("Upgrade to Java"),
                "Expected wrapped upgrade-failure message, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Network error"),
                "Expected original error message preserved, got: " + ex.getMessage());
    }

    // ---------------------------------------------------------------------------
    // Null getMessage() safety — exception with null message uses class name
    // ---------------------------------------------------------------------------

    @Test
    void upgrade_exceptionWithNullMessage_usesClassNameAsError() throws Exception {
        GitHubService gh = mock(GitHubService.class);
        JavaUpgraderAgent agent = mock(JavaUpgraderAgent.class);
        SecretScanner scanner = mock(SecretScanner.class);

        // NPE with no message has getMessage() == null
        when(gh.getDefaultBranch(any())).thenThrow(new NullPointerException());

        UpgradeOrchestrationService service = new UpgradeOrchestrationService(gh, agent, scanner);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.upgrade(GITHUB_URL, TARGET_VERSION));

        // The wrapped message must not contain "null" as a literal string for the error detail
        assertTrue(ex.getMessage().contains("NullPointerException"),
                "Expected class name fallback in message, got: " + ex.getMessage());
    }
}
