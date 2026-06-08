# java-upgrader

A Spring Boot service that automatically upgrades Java projects to modern Java versions using an AI agent powered by Claude. Submit a GitHub repository URL, and the service clones it, applies Java modernizations, and opens a pull request with the changes.

## How It Works

1. **Submit** a GitHub repository URL via REST API
2. **Agent runs** asynchronously: clones the repo, inspects the project, applies upgrades
3. **Pull request** is opened on the target repository with all changes
4. **Poll** the job status endpoint until the job succeeds, fails, or reports no changes needed

The AI agent (Claude claude-opus-4-8) has four tools available: read files, write files, list files, and execute shell commands. It uses these to update `pom.xml`/`build.gradle`, apply modern Java language features, and report every change it makes.

## Features

- **Async job processing** — HTTP responses return immediately with a job ID; upgrades run in the background
- **Modern Java patterns** — applies `var`, text blocks, records, pattern matching, sealed classes, Stream API improvements, and more
- **Configurable target version** — defaults to Java 21, configurable per request
- **Automatic PRs and issues** — opens a PR on success, a GitHub issue with diagnostics on failure
- **Secret scanning** — scans the git diff before every push to prevent accidental credential leaks
- **Secure credential handling** — GitHub token stored in `.netrc` with `600` permissions; never passed as command-line args or environment variables visible to subprocesses
- **Production-ready token sourcing** — GitHub token from environment variable (local) or AWS SSM Parameter Store (deployed)

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 17+ |
| Maven | 3.6+ |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `GITHUB_TOKEN` | GitHub personal access token with `repo` scope |

## Quick Start

```bash
# Clone and build (also installs the pre-push secret-scanning hook)
git clone https://github.com/acorobceanu/java-upgrader.git
cd java-upgrader
mvn clean install

# Set required environment variables
export ANTHROPIC_API_KEY=sk-ant-...
export GITHUB_TOKEN=ghp_...

# Start the service on port 8080
mvn spring-boot:run
```

## API Reference

### Submit an upgrade job

```
POST /api/upgrade
Content-Type: application/json
```

**Request body:**

```json
{
  "githubUrl": "https://github.com/owner/repo",
  "targetJavaVersion": 21
}
```

`targetJavaVersion` is optional and defaults to `21`.

**Response — 202 Accepted:**

```json
{
  "jobId": "a1b2c3d4-...",
  "message": "Upgrade job accepted"
}
```

### Poll job status

```
GET /api/upgrade/{jobId}
```

**Response:**

```json
{
  "id": "a1b2c3d4-...",
  "state": "succeeded",
  "prUrl": "https://github.com/owner/repo/pull/42",
  "errorMessage": null,
  "issueUrl": null
}
```

| `state` | Meaning |
|---|---|
| `pending` | Job is queued or running |
| `succeeded` | PR was opened successfully |
| `no_changes` | Project is already up to date |
| `failed` | Agent or infrastructure error; see `issueUrl` for details |

### Example: curl workflow

```bash
# Submit a job
JOB_ID=$(curl -s -X POST http://localhost:8080/api/upgrade \
  -H "Content-Type: application/json" \
  -d '{"githubUrl":"https://github.com/owner/my-java-app","targetJavaVersion":21}' \
  | jq -r '.jobId')

# Poll until done
while true; do
  STATUS=$(curl -s http://localhost:8080/api/upgrade/$JOB_ID | jq -r '.state')
  echo "State: $STATUS"
  [ "$STATUS" != "pending" ] && break
  sleep 10
done

# Get the PR URL
curl -s http://localhost:8080/api/upgrade/$JOB_ID | jq '.prUrl'
```

## Configuration

All configuration lives in `src/main/resources/application.properties`.

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `spring.mvc.async.request-timeout` | `600000` | Max async request timeout (ms) |
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
│   └── pre-push                     # Secret-scanning gate (auto-installed)
└── src/
    ├── main/java/com/javaupgrader/
    │   ├── JavaUpgraderApplication.java
    │   ├── agent/
    │   │   └── JavaUpgraderAgent.java          # Claude agent + tool definitions
    │   ├── controller/
    │   │   └── UpgradeController.java           # POST /api/upgrade, GET /api/upgrade/{id}
    │   ├── service/
    │   │   ├── UpgradeOrchestrationService.java # End-to-end upgrade workflow
    │   │   ├── GitHubService.java               # GitHub API + git operations
    │   │   ├── SecretScanner.java               # Pre-push secret detection
    │   │   └── JobStore.java                    # In-memory job state
    │   ├── config/
    │   │   ├── AnthropicConfig.java
    │   │   ├── AsyncConfig.java
    │   │   └── GitHubTokenConfig.java
    │   └── dto/
    │       ├── UpgradeRequest.java
    │       ├── JobStatus.java
    │       └── UpgradeAcceptedResponse.java
    └── test/java/com/javaupgrader/
        ├── controller/UpgradeControllerTest.java
        └── service/
            ├── GitHubServiceTest.java
            ├── SecretScannerTest.java
            └── JobStoreTest.java
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

The service is stateless (job state is in-memory) and packages as a single executable JAR. For production use on AWS Lightsail (or any EC2-compatible instance):

1. Attach an IAM instance profile with `ssm:GetParameter` on your token path.
2. Set `github.token.ssm-path=/your/ssm/param` in `application.properties` or as a system property.
3. Set `ANTHROPIC_API_KEY` in the environment.
4. Run the JAR: `java -jar java-upgrader-1.0-SNAPSHOT.jar`

No `GITHUB_TOKEN` environment variable is needed when SSM is configured.

## License

MIT
