package com.javaupgrader.demo;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.net.http.HttpRequest;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Demo MCP client — shows how to connect to the java-upgrader server and invoke
 * the upgrade_java tool programmatically.
 *
 * Prerequisites:
 *   1. Start the server: mvn spring-boot:run
 *   2. Set ANTHROPIC_API_KEY and GITHUB_TOKEN in the server environment.
 *   3. Run this class's main() from the IDE or: mvn exec:java -Dexec.mainClass=com.javaupgrader.demo.UpgradeClientDemo
 */
public class UpgradeClientDemo {

    private static final String SERVER_URL  = "http://localhost:8080";
    private static final String GITHUB_URL  = "https://github.com/acme/legacy-java8-app";
    private static final int    TARGET_JAVA = 21;

    public static void main(String[] args) throws InterruptedException {
        var transport = HttpClientSseClientTransport.builder(SERVER_URL)
            .requestBuilder(HttpRequest.newBuilder().timeout(Duration.ofMinutes(15)))  // upgrades can take several minutes
            .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new Implementation("upgrade-client-demo", "1.0.0"))
                .requestTimeout(Duration.ofMinutes(15))
                .build()) {

            client.initialize();

            System.out.println("Connected to: " + client.getServerInfo().name()
                + " v" + client.getServerInfo().version());

            System.out.println("Available tools: " + client.listTools().tools()
                .stream().map(t -> t.name()).toList());

            System.out.println();
            System.out.printf("Calling upgrade_java: %s → Java %d%n", GITHUB_URL, TARGET_JAVA);
            System.out.println("(upgrade_java returns immediately — polling for the async result...)");
            System.out.println();

            // Step 1: submit the upgrade job and extract the job ID from the response.
            CallToolResult submitResult = client.callTool(new CallToolRequest(
                "upgrade_java",
                Map.of("githubUrl", GITHUB_URL, "targetJavaVersion", TARGET_JAVA)
            ));

            if (Boolean.TRUE.equals(submitResult.isError())) {
                System.err.println("upgrade_java returned an error:");
                submitResult.content().forEach(c -> {
                    if (c instanceof TextContent t) System.err.println(t.text());
                });
                System.exit(1);
            }

            String submitText = submitResult.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .findFirst()
                .orElse("");

            System.out.println(submitText);

            // Parse "Job started. ID: <uuid>" from the first line of the response.
            String jobId = null;
            for (String line : submitText.split("\n")) {
                if (line.startsWith("Job started. ID: ")) {
                    jobId = line.substring("Job started. ID: ".length()).trim();
                    break;
                }
            }
            if (jobId == null) {
                System.err.println("Could not extract job ID from response — exiting.");
                System.exit(1);
            }

            // Step 2: poll get_upgrade_status every 30 seconds until COMPLETE or FAILED.
            System.out.println();
            System.out.println("Polling for job result (every 30 s)...");
            while (true) {
                CallToolResult statusResult = client.callTool(new CallToolRequest(
                    "get_upgrade_status",
                    Map.of("jobId", jobId)
                ));

                String statusText = statusResult.content().stream()
                    .filter(c -> c instanceof TextContent)
                    .map(c -> ((TextContent) c).text())
                    .findFirst()
                    .orElse("");

                if (statusText.contains("COMPLETE") || statusText.contains("FAILED")) {
                    System.out.println();
                    System.out.println("=== Final result ===");
                    System.out.println(statusText);
                    if (statusText.contains("FAILED")) {
                        System.exit(1);
                    }
                    break;
                }

                System.out.printf("  [%s] Still running — retrying in 30 s...%n",
                    java.time.LocalTime.now().withNano(0));
                TimeUnit.SECONDS.sleep(30);
            }
        }
    }
}
