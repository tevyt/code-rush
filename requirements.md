# Code Rush

Code Rush is a self-hosted CI/CD platform for use to build and application code.

## Components

- Code Rush Application Server: This is the component users will interact with. Here users will be able to view and edit jobs, review build history, configure build agents, etc.
- Build agents: build agents execute build triggered by the code rush application server.

## Build Configuration

Build configurations are defined through the application server UI. A build config consists of:

- **Name**: A human-readable name for the build config.
- **Repository URL**: An SSH clone URL for the project repository (e.g., `git@github.com:org/repo.git`).
- **SSH Key**: An SSH private key used to clone the repository. Stored on the server and transferred to the agent at the start of each build. This centralizes credential management — users configure keys in one place on the server rather than on each agent.

### Repository Build File

Build steps are defined in a `.code-rush.yml` file at the root of the project repository. This keeps build definitions versioned alongside the code they build. The file format is:

```yaml
steps:
  - name: Install dependencies
    script: npm install
  - name: Run tests
    script: npm test
  - name: Build
    script: npm run build
```

Each step has a **name** (human-readable label) and a **script** (shell script executed in the cloned project directory). Steps execute sequentially in the order listed.

If the `.code-rush.yml` file is missing or cannot be parsed, the build fails immediately with a descriptive error message.

### Build Execution Flow

1. The server assigns a queued build to an available agent.
2. The server transfers the SSH key and build assignment (signed) to the agent. The build assignment includes the repository URL, branch, and commit SHA.
3. The agent clones the repository into its configured working directory using the provided SSH key.
4. The agent checks out the branch and commit that triggered the build.
5. The agent reads and parses `.code-rush.yml` from the repository root. If the file is missing or unparsable, the build fails immediately.
6. The agent reports the parsed steps to the server, so the server knows what to expect and can display them in the UI.
7. The agent executes each step sequentially as a shell script within the cloned project directory.
8. As each step completes, the agent pushes the step result (status and log output) to the server.
9. On step failure: the build fails immediately — remaining steps are not executed.
10. On build completion (success or failure): the agent reports the final build status to the server, deletes the cloned source code and SSH key from the working directory, and transitions to IDLE.

### Agent Working Directory

Each agent has a single configured working directory. There is no per-build directory — the working directory is reused across builds. After each build completes (regardless of outcome), the source code and SSH keys are cleaned from the working directory before the agent returns to IDLE status.

## Build Observability

### Build Statuses

A build progresses through the following statuses: `QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED`. Timeouts are not a distinct status — a timed-out build is marked `FAILED` with a descriptive message.

### Logging

Logs are captured per build step. As each step completes, the agent pushes the step result (including log output) to the server, which stores it immediately.

Build execution details are stored as a JSON column on the build record. This captures a snapshot of the steps as they were defined in the repository's `.code-rush.yml` at the time of execution. The structure is an ordered list of step results, each containing:

- **step_number**: Position in the execution order.
- **name**: The step name at the time of execution.
- **script**: The script that was executed.
- **status**: `SUCCESS` or `FAILED`.
- **log**: The full log output for the step.

Users can view the final log output for each step of a completed build through the UI. Real-time log streaming is out of scope for v1.

## Tech Stack

- **Language**: Java 25
- **Server Framework**: Spring Boot
- **Agent Framework**: Spring Boot (same as server for simplicity; the agent protocol is language-agnostic, so the agent can be rewritten in a lighter language later if needed)
- **Web UI**: Server-rendered with Thymeleaf (avoids introducing JavaScript build tooling)
- **Database**: PostgreSQL (required for initial release)
- **ORM**: Spring Data JPA
- **Packaging**: Single executable JARs for both server and agent (users can containerize on their own if desired)

## Security & Authentication

### User Authentication

- Session-based authentication using Spring Security.
- Passwords are hashed with bcrypt.
- On initial installation, a default admin account is created (`admin:admin`). No forced password change on first login.
- No programmatic API for v1 — the UI is the only user-facing interface.

### Server-Agent Authentication

The server generates a public/private key pair on first startup. The server's public key is displayed in the admin UI so it can be copied during agent setup.

**Agent Enrollment Flow:**

1. An admin copies the server's public key from the UI.
2. The admin installs the agent on a machine and configures it with the server's address and the server's public key.
3. On startup, the agent reaches out to the server to register itself, sending a nonce.
4. The server signs the nonce with its private key and returns the signature.
5. The agent verifies the signature using the server's public key. If verification fails, the agent reports an error and does not begin operations.
6. On successful verification, the agent is enrolled and marked as available on the server.

This ensures the agent can confirm it is communicating with the intended server. The server trusts any agent that initiates enrollment and completes the handshake.

### Signed Build Assignments

Build assignments sent from the server to an agent are signed with the server's private key. The agent verifies the signature using the server's public key before executing. The signed payload covers the build assignment (repository URL, branch, commit SHA) — the build steps themselves come from the repository content after cloning, not from the server. This prevents an attacker from sending malicious build assignments to an agent.

### TLS

TLS is not enforced by the application for v1. Users may terminate TLS themselves (e.g., via a reverse proxy) if desired.

## Agent Lifecycle & Communication

### Protocol

All communication between the server and agents is over REST/HTTP.

### Agent States

- **OFFLINE**: The agent has not registered or has missed 3 consecutive heartbeats.
- **IDLE**: The agent is registered, healthy, and available to accept builds.
- **BUSY**: The agent is currently executing a build.

### Heartbeats

The agent pushes a heartbeat to the server every 30 seconds. If the server misses 3 consecutive heartbeats (90 seconds), the agent is marked `OFFLINE`. Heartbeats are the single source of truth for agent availability — build status and agent status are tracked independently.

### Agent Selection

When a build is queued and multiple agents are `IDLE`, the server selects an available agent to execute the build. Selection strategy is unspecified for v1 — any idle agent may be chosen.

## GitHub Integration

### Webhook Configuration

Webhooks are configured manually by the user in GitHub. Each build configuration has a unique webhook endpoint on the server at `/webhook/{buildConfigId}`. This path-based routing eliminates the need for payload inspection to determine which build to trigger.

### Webhook Secret

Each build configuration requires a webhook secret, configured by the user during build setup. The server validates the signature on incoming webhook payloads using this secret to ensure the request is genuinely from GitHub.

### Trigger Behavior

- The server listens for **push events** only (v1).
- Builds trigger on pushes to **any branch** — no branch filtering for v1.
- Each commit in a push triggers a **separate build**. If a push contains 5 commits, 5 builds are queued.
- The build checks out the specific commit's branch as described in the Build Execution Flow.

### Payload Handling

From the GitHub push event payload, the server extracts:
- The branch that was pushed to.
- The list of commit SHAs.
- Relevant metadata (commit author, message) for display in the build history UI.

## User Roles & Permissions

Users are managed by admins through the server UI. Three roles are supported:

- **Admin**: Full access — create and manage users, create and edit build configs, configure agents, cancel builds, restart the application server.
- **Developer**: Create and edit build configs, trigger and cancel builds, change their own password.
- **Viewer**: Read-only access to build configs and build history.

## Build Cancellation

Users with admin or developer roles can cancel a running build through the UI. When a build is cancelled:

1. The server sends a signed cancellation command to the agent.
2. The agent terminates the currently executing build process.
3. The agent performs cleanup (deletes source code and SSH keys from the working directory).
4. The agent transitions from `BUSY` to `IDLE`.
5. The build is marked as `CANCELLED` on the server.

## Build History UI

The build history page displays a list of builds with the following information per entry:

- Build config name
- Commit SHA, author, and message
- Branch
- Status (`QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED`)
- Timestamp

Users can filter the build list by:
- Build configuration
- Status
- Branch

Selecting a build shows the detail view with the per-step execution results (name, script, status, and log output).

## Typical Use Case

1. A user installs the Code Rush Application Server and configures it with a PostgreSQL database.
2. On first startup, the server generates its key pair and creates a default admin account (`admin:admin`).
3. The admin logs in, updates credentials, and creates additional users as needed.
4. The admin copies the server's public key from the UI, installs a build agent on another machine, and configures it with the server's address and public key.
5. The agent starts, completes the enrollment handshake with the server, and becomes available (`IDLE`).
6. A developer creates a build configuration — providing the repo SSH URL, SSH key, and webhook secret. Build steps are defined in a `.code-rush.yml` file in the repository.
7. The developer configures a webhook in GitHub pointing to `/webhook/{buildConfigId}` with the matching secret.
8. When code is pushed to the repository, GitHub sends a webhook event, and the server queues a build per commit.
9. The server assigns the build to an idle agent, which clones the repo, reads `.code-rush.yml`, reports the steps to the server, executes each step, and reports results back.
10. The developer reviews the build outcome and per-step logs in the UI.