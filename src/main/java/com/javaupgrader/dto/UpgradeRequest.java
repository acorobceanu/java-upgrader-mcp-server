package com.javaupgrader.dto;

public record UpgradeRequest(String githubUrl, Integer targetJavaVersion) {

    public int effectiveTargetVersion() {
        return targetJavaVersion != null ? targetJavaVersion : 21;
    }
}
