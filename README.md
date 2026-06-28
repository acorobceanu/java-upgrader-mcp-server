# java-upgrader

An MCP server that automatically upgrades Java projects to modern Java versions using an AI agent. Connect it to Claude Desktop, Claude Code, or any MCP client, point it at a GitHub repository, and it clones the project, applies Java modernizations, and opens a pull request with the changes.

Supports **Anthropic Claude** and **OpenAI GPT** as the underlying LLM — switchable via a single configuration property.

## How It Works

1. **Call** `upgrade_java` with a GitHub repository URL — it returns a job ID immediately
2. **Poll** `get_upgrade_status` with that job ID until the status is `COMPLETE` or `FAILED`
3. **Agent runs** asynchronously in the background: clones the repo, inspects the project, applies upgrades
4. **Pull request** is opened on the target repository with all changes

The AI agent has four tools: read files, write files, list files, and execute shell commands. It uses these to update `pom.xml`/`build.gradle`, apply modern Java language features, and report every change it makes.

Upgrades run on a single background worker thread so concurrent calls queue rather than competing for disk and API quota.

## Features

- **MCP tool interface** — callable from Claude Desktop, Claude Code, or any MCP-compatible client
- **Multi-provider LLM support** — Anthropic Claude or OpenAI GPT, configured via a single property
- **Modern Java patterns** — applies `var`, text blocks, records, pattern matching, sealed classes, Stream API improvements, and more
- **Configurable target version** — defaults to Java 21, configurable per call
- **Automatic PRs and issues** — opens a PR on success, a GitHub issue with diagnostics on failure
- **Secret scanning** — scans the git diff before every push to prevent accidental credential leaks
- **Secure credential handling** — GitHub token stored in `.netrc` with `600` permissions; never passed as command-line args
- **Flexible token sourcing** — GitHub token from environment variable (local) or AWS SSM Parameter Store (deployed)

## Prerequisites

| Requirement | Details |
|---|---|
| Java | 17+ |
| Maven | 3.6+ |
| `GITHUB_TOKEN` | GitHub personal access token with `repo` and `issues` scope |
| `ANTHROPIC_API_KEY` | Required when `llm.provider=anthropic` (default) |
| `OPENAI_API_KEY` | Required when `llm.provider=openai` |

## Quick Start

```bash
# Clone and build (also installs the pre-push secret-scanning hook)
git clone https://github.com/acorobceanu/java-upgrader.git
cd java-upgrader
mvn clean install

# Set the GitHub token (always required)
export GITHUB_TOKEN=ghp_...

# Set the API key for your chosen LLM provider
export ANTHROPIC_API_KEY=sk-ant-...   # if using Anthropic (default)
# export OPENAI_API_KEY=sk-...        # if using OpenAI

# Start the MCP server on port 8080
mvn spring-boot:run
```

To switch providers, set `llm.provider` before starting:

```bash
# Use OpenAI instead of Anthropic
export OPENAI_API_KEY=sk-...
mvn spring-boot:run -Dspring-boot.run.arguments=--llm.provider=openai
```

The server starts and exposes the MCP SSE transport at `http://localhost:8080/sse`.

## Connecting to Claude Desktop

Add the following to your Claude Desktop `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "java-upgrader": {
      "url": "http://localhost:8080/sse"
    }
  }
}
```

Then ask Claude: *"Upgrade https://github.com/owner/my-app to Java 21"* and it will call the `upgrade_java` tool automatically.

## MCP Tool Reference

### `upgrade_java`

Enqueues an asynchronous upgrade job and returns a job ID immediately.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `githubUrl` | string | yes | GitHub repository URL (HTTPS or SSH) |
| `targetJavaVersion` | integer | no | Target Java version (default: `21`) |

**Returns:** a message containing the job ID and polling instructions:
```
Job started. ID: f3dcdb08-78bb-46ad-9ad0-e83661debead
Status: PENDING
Poll with: get_upgrade_status("f3dcdb08-78bb-46ad-9ad0-e83661debead")
```

### `get_upgrade_status`

Polls the status of a job submitted with `upgrade_java`.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `jobId` | string | yes | Job ID returned by `upgrade_java` |

**Returns** one of:

| Status | Response |
|---|---|
| `PENDING` | Job is queued and will start shortly |
| `RUNNING` | Upgrade is in progress — check again in a minute |
| `COMPLETE` | PR URL, or "No changes needed" message |
| `FAILED` | Error details (plus a GitHub issue URL if one was created) |

## Demo Client

`src/main/java/com/javaupgrader/demo/UpgradeClientDemo.java` is a standalone Java program that connects to the running MCP server and calls `upgrade_java` + `get_upgrade_status` programmatically — no MCP host (Claude Desktop, etc.) required. It uses the `io.modelcontextprotocol.sdk` client classes that are already on the classpath.

```java
var transport = HttpClientSseClientTransport.builder("http://localhost:8080")
    .requestBuilder(HttpRequest.newBuilder().timeout(Duration.ofMinutes(15)))
    .build();

try (McpSyncClient client = McpClient.sync(transport)
        .clientInfo(new Implementation("upgrade-client-demo", "1.0.0"))
        .requestTimeout(Duration.ofMinutes(15))
        .build()) {

    client.initialize();

    // Start the async job
    CallToolResult submit = client.callTool(new CallToolRequest(
        "upgrade_java",
        Map.of("githubUrl", "https://github.com/acme/legacy-java8-app",
               "targetJavaVersion", 21)
    ));
    String jobId = extractJobId(submit); // parse ID from the response text

    // Poll until done
    while (true) {
        CallToolResult status = client.callTool(
            new CallToolRequest("get_upgrade_status", Map.of("jobId", jobId)));
        String text = ((TextContent) status.content().get(0)).text();
        System.out.println(text);
        if (text.startsWith("Status: COMPLETE") || text.startsWith("Status: FAILED")) break;
        Thread.sleep(30_000);
    }
}
```

To run it, start the server first, then:

```bash
mvn exec:java -Dexec.mainClass=com.javaupgrader.demo.UpgradeClientDemo
```

Expected output:

```
Connected to: java-upgrader v1.0.0
Available tools: [upgrade_java, get_upgrade_status]

Calling upgrade_java: https://github.com/acme/legacy-java8-app → Java 21
Job started. ID: f3dcdb08-...
Status: PENDING

Status: RUNNING
Upgrade is in progress — check again in a minute.

Status: COMPLETE
Upgrade to Java 21 complete. Pull request: https://github.com/acme/legacy-java8-app/pull/7
```

## Configuration

All configuration lives in `src/main/resources/application.properties`.

### LLM Provider

| Property | Default | Description |
|---|---|---|
| `llm.provider` | `anthropic` | LLM backend: `anthropic` or `openai` |
| `spring.ai.anthropic.api-key` | `${ANTHROPIC_API_KEY:}` | Anthropic API key (used when `llm.provider=anthropic`) |
| `spring.ai.anthropic.chat.options.model` | `claude-opus-4-8` | Anthropic model name |
| `spring.ai.openai.api-key` | `${OPENAI_API_KEY:}` | OpenAI API key (used when `llm.provider=openai`) |
| `spring.ai.openai.chat.options.model` | `gpt-4o` | OpenAI model name |

Only set the API key for the provider you're using. Setting both is harmless but the inactive provider's key is ignored.

### Other

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `spring.ai.mcp.server.name` | `java-upgrader` | MCP server name advertised to clients |
| `spring.ai.mcp.server.version` | `1.0.0` | MCP server version |
| `github.token.ssm-path` | _(unset)_ | AWS SSM parameter path for GitHub token (production) |

When `github.token.ssm-path` is set, the service retrieves the token from SSM using the EC2 instance profile — no credentials need to be injected into the runtime environment. If unset, it falls back to the `GITHUB_TOKEN` environment variable.

## Security

### Secret scanning

A git pre-push hook (installed automatically by `mvn clean install`) and an in-process `SecretScanner` both run before every push. They detect:

- GitHub PATs (classic `ghp_…` and fine-grained `github_pat_…`)
- GitHub Actions tokens (`ghs_…`)
- AWS access key IDs (`AKIA…`)
- Anthropic API keys (`sk-ant-…`)
- PEM private keys (RSA, EC, DSA, OpenSSH, PGP)
- Generic `password=`, `secret=`, `api_key=` assignments

Template variables (`${...}`), XML/HTML placeholders (`<value>`), and masked values (`***`) are excluded from false-positive matching.

### Credential isolation

The GitHub token is written to a temporary `.netrc` file with `600` permissions before `git clone` / `git push`, then deleted immediately after. It is never passed as a URL fragment, command-line argument, or environment variable visible to child processes.

## Project Structure

```
java-upgrader/
├── pom.xml
├── .githooks/
│   └── pre-push                             # Secret-scanning gate (auto-installed)
└── src/
    ├── main/java/com/javaupgrader/
    │   ├── JavaUpgraderApplication.java
    │   ├── agent/
    │   │   └── JavaUpgraderAgent.java       # AI agent + file/shell tool definitions
    │   ├── mcp/
    │   │   └── JavaUpgraderTools.java       # MCP tools: upgrade_java, get_upgrade_status
    │   ├── service/
    │   │   ├── JobStore.java                # In-memory job state (PENDING→RUNNING→COMPLETE/FAILED)
    │   │   ├── UpgradeJobService.java       # Async submission + background worker thread
    │   │   ├── UpgradeOrchestrationService.java  # End-to-end upgrade workflow (synchronous)
    │   │   ├── GitHubService.java           # GitHub API + git operations
    │   │   └── SecretScanner.java           # Pre-push secret detection
    │   └── config/
    │       ├── LlmConfig.java               # ChatClient bean — selects Anthropic or OpenAI provider
    │       └── GitHubTokenConfig.java
    └── test/java/com/javaupgrader/
        ├── config/SecurityConfigTest.java
        ├── agent/JavaUpgraderAgentTest.java
        ├── mcp/JavaUpgraderToolsTest.java
        └── service/
            ├── JobStoreTest.java
            ├── UpgradeJobServiceTest.java
            ├── UpgradeOrchestrationServiceTest.java
            ├── GitHubServiceTest.java
            └── SecretScannerTest.java
```

## Development

```bash
# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=SecretScannerTest

# Build executable JAR
mvn clean package
java -jar target/java-upgrader-1.0-SNAPSHOT.jar
```

Tests use JUnit 5 and Mockito. Per project convention, every code change must be accompanied by a passing unit test before it is considered complete.

## Deployment

The service packages as a single executable JAR. For production use on AWS Lightsail (or any EC2-compatible instance):

1. Attach an IAM instance profile with `ssm:GetParameter` on your token path.
2. Set `github.token.ssm-path=/your/ssm/param` in `application.properties` or as a system property.
3. Set the API key for your chosen LLM provider (`ANTHROPIC_API_KEY` or `OPENAI_API_KEY`) in the environment.
4. Set `llm.provider=anthropic` or `llm.provider=openai` (or accept the default `anthropic`).
5. Run the JAR: `java -jar java-upgrader-1.0-SNAPSHOT.jar`

No `GITHUB_TOKEN` environment variable is needed when SSM is configured.

## License

MIT
