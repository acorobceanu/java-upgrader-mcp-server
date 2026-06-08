package com.javaupgrader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitHubServiceTest {

    @Test
    void parseUrl_https() {
        var info = GitHubService.parseUrl("https://github.com/owner/repo");
        assertEquals("owner", info.owner());
        assertEquals("repo", info.repo());
    }

    @Test
    void parseUrl_httpsWithGitSuffix() {
        var info = GitHubService.parseUrl("https://github.com/owner/repo.git");
        assertEquals("owner", info.owner());
        assertEquals("repo", info.repo());
    }

    @Test
    void parseUrl_ssh() {
        var info = GitHubService.parseUrl("git@github.com:owner/repo.git");
        assertEquals("owner", info.owner());
        assertEquals("repo", info.repo());
    }

    @Test
    void parseUrl_trailingWhitespace() {
        var info = GitHubService.parseUrl("  https://github.com/owner/repo  ");
        assertEquals("owner", info.owner());
        assertEquals("repo", info.repo());
    }

    @Test
    void parseUrl_invalidUrl_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> GitHubService.parseUrl("https://gitlab.com/owner/repo"));
    }

    @Test
    void parseUrl_emptyUrl_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> GitHubService.parseUrl("not-a-url"));
    }
}
