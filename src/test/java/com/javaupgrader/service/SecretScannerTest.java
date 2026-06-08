package com.javaupgrader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretScannerTest {

    private final SecretScanner scanner = new SecretScanner();

    // ── clean cases ───────────────────────────────────────────────────────────

    @Test
    void cleanCode_isClean() {
        assertTrue(scanner.scanDiff("+String greeting = \"hello world\";").clean());
    }

    @Test
    void removedLine_withSecret_isClean() {
        // Removing a secret that was already there shouldn't block the push
        assertTrue(scanner.scanDiff("-String t = \"ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZaBcDeFgH12\";").clean());
    }

    @Test
    void diffFileHeader_isClean() {
        // +++ b/src/... lines are diff headers, not content
        assertTrue(scanner.scanDiff("+++ b/src/main/resources/config.properties").clean());
    }

    @Test
    void templateVariable_isClean() {
        // ${DB_PASSWORD} is a reference, not a literal secret
        assertTrue(scanner.scanDiff("+password=${DB_PASSWORD}").clean());
    }

    @Test
    void passwordMethodCall_isClean() {
        // password.equals(...) has no = assignment
        assertTrue(scanner.scanDiff("+if (password.equals(input)) {").clean());
    }

    // ── detected secrets ─────────────────────────────────────────────────────

    @Test
    void githubClassicPat_detected() {
        var result = scanner.scanDiff("+String t = \"ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZaBcDeFgH12\";");
        assertFalse(result.clean());
        assertFalse(result.findings().isEmpty());
    }

    @Test
    void githubFineGrainedPat_detected() {
        String fakePat = "github_pat_" + "A".repeat(82);
        assertFalse(scanner.scanDiff("+" + fakePat).clean());
    }

    @Test
    void awsAccessKeyId_detected() {
        assertFalse(scanner.scanDiff("+aws_access_key_id=AKIAIOSFODNN7EXAMPLE").clean());
    }

    @Test
    void pemPrivateKey_detected() {
        assertFalse(scanner.scanDiff("+-----BEGIN RSA PRIVATE KEY-----").clean());
    }

    @Test
    void opensshPrivateKey_detected() {
        assertFalse(scanner.scanDiff("+-----BEGIN OPENSSH PRIVATE KEY-----").clean());
    }

    @Test
    void passwordAssignment_detected() {
        assertFalse(scanner.scanDiff("+password=supersecretvalue123").clean());
    }

    @Test
    void apiKeyAssignment_detected() {
        assertFalse(scanner.scanDiff("+api_key = \"abcdefghijklmnopqrstu\"").clean());
    }

    @Test
    void multipleFindings_allReported() {
        String diff = "+AKIA1234567890ABCDEF\n+github_pat_" + "B".repeat(82);
        var result = scanner.scanDiff(diff);
        assertFalse(result.clean());
        assertEquals(2, result.findings().size());
    }
}
