# Code Rush — Technical Design

## Project Structure

Code Rush is a multi-module Gradle project producing two independent Spring Boot applications from a single repository.

```
code-rush/
├── build.gradle           # Root build file, shared dependencies and plugin config
├── settings.gradle        # Declares subprojects
├── server/                # Application server module
│   ├── build.gradle
│   └── src/main/java/dev/coderush/server/
│       ├── ServerApplication.java
│       ├── config/        # Spring Security, crypto, startup initialization
│       ├── controller/    # Thymeleaf UI controllers and webhook endpoint
│       ├── model/         # JPA entities
│       ├── repository/    # Spring Data JPA repositories
│       └── service/       # Business logic (builds, agents, users, signing)
├── agent/                 # Build agent module
│   ├── build.gradle
│   └── src/main/java/dev/coderush/agent/
│       ├── AgentApplication.java
│       ├── config/        # Agent configuration properties
│       ├── executor/      # Build step execution and process management
│       └── client/        # HTTP client for server communication
└── common/                # Shared module (DTOs, signing utilities, constants)
    ├── build.gradle
    └── src/main/java/dev/coderush/common/
        ├── dto/           # Request/response objects shared between server and agent
        ├── crypto/        # Signature generation and verification
        └── model/         # Shared enums (BuildStatus, AgentState, StepStatus)
```

Both `server` and `agent` depend on `common`. The server and agent have no direct dependency on each other.

## Build Tool

Gradle with the Kotlin DSL. Each module produces a Spring Boot executable JAR via the `org.springframework.boot` plugin.

```
./gradlew :server:bootJar   # Produces server/build/libs/server.jar
./gradlew :agent:bootJar    # Produces agent/build/libs/agent.jar
```

Java 25 is set as the toolchain version in the root build file.

## Database Schema

PostgreSQL. The server manages all tables via Spring Data JPA with Hibernate DDL auto-generation (`spring.jpa.hibernate.ddl-auto=update`) for v1. No migration tool (Flyway/Liquibase) in v1.

### Tables

#### `users`

| Column        | Type         | Constraints                        |
|---------------|--------------|------------------------------------|
| `id`          | `BIGSERIAL`  | PK                                 |
| `username`    | `VARCHAR`    | UNIQUE, NOT NULL                   |
| `password`    | `VARCHAR`    | NOT NULL (bcrypt hash)             |
| `role`        | `VARCHAR`    | NOT NULL (`ADMIN`, `DEVELOPER`, `VIEWER`) |
| `created_at`  | `TIMESTAMP`  | NOT NULL                           |

#### `build_configs`

| Column           | Type         | Constraints              |
|------------------|--------------|--------------------------|
| `id`             | `BIGSERIAL`  | PK                       |
| `name`           | `VARCHAR`    | NOT NULL                 |
| `repository_url` | `VARCHAR`    | NOT NULL                 |
| `ssh_key`        | `TEXT`       | NOT NULL (encrypted)     |
| `webhook_secret` | `VARCHAR`    | NOT NULL                 |
| `created_by`     | `BIGINT`     | FK → `users.id`          |
| `created_at`     | `TIMESTAMP`  | NOT NULL                 |
| `updated_at`     | `TIMESTAMP`  | NOT NULL                 |

#### `agents`

| Column           | Type         | Constraints              |
|------------------|--------------|--------------------------|
| `id`             | `BIGSERIAL`  | PK                       |
| `name`           | `VARCHAR`    | NOT NULL                 |
| `address`        | `VARCHAR`    | NOT NULL (host:port)     |
| `state`          | `VARCHAR`    | NOT NULL (`OFFLINE`, `IDLE`, `BUSY`) |
| `last_heartbeat` | `TIMESTAMP`  | NULLABLE                 |
| `registered_at`  | `TIMESTAMP`  | NOT NULL                 |

#### `builds`

| Column            | Type         | Constraints              |
|-------------------|--------------|--------------------------|
| `id`              | `BIGSERIAL`  | PK                       |
| `build_config_id` | `BIGINT`     | FK → `build_configs.id`  |
| `agent_id`        | `BIGINT`     | FK → `agents.id`, NULLABLE |
| `status`          | `VARCHAR`    | NOT NULL (`QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED`) |
| `branch`          | `VARCHAR`    | NOT NULL                 |
| `commit_sha`      | `VARCHAR`    | NOT NULL                 |
| `commit_author`   | `VARCHAR`    | NULLABLE                 |
| `commit_message`  | `TEXT`       | NULLABLE                 |
| `execution_details` | `JSONB`   | NULLABLE (step results snapshot) |
| `error_message`   | `TEXT`       | NULLABLE (config parse errors, timeouts, etc.) |
| `created_at`      | `TIMESTAMP`  | NOT NULL                 |
| `started_at`      | `TIMESTAMP`  | NULLABLE                 |
| `finished_at`     | `TIMESTAMP`  | NULLABLE                 |

The `execution_details` JSONB column stores the step results as reported by the agent:

```json
[
  {
    "step_number": 1,
    "name": "Install dependencies",
    "script": "npm install",
    "status": "SUCCESS",
    "log": "..."
  },
  {
    "step_number": 2,
    "name": "Run tests",
    "script": "npm test",
    "status": "FAILED",
    "log": "..."
  }
]
```

### SSH Key Storage

SSH private keys are encrypted at rest using AES-256-GCM. The encryption key is derived from a server secret configured via the `CODE_RUSH_SECRET` environment variable. The server decrypts the key only when transmitting it to an agent as part of a build assignment.

## Server Components

### Spring Security Configuration

- Session-based authentication with form login.
- Role-based access control using Spring Security's `@PreAuthorize` or URL-based rules.
- Three roles map directly to granted authorities: `ROLE_ADMIN`, `ROLE_DEVELOPER`, `ROLE_VIEWER`.
- Password encoding via `BCryptPasswordEncoder`.

### Startup Initialization

On first startup (detected by an empty `users` table), the server:
1. Generates an Ed25519 key pair and persists it to a configurable file path (default: `~/.code-rush/keys/`).
2. Creates the default admin user (`admin:admin`).

On subsequent startups, the server loads the existing key pair from disk.

### Controllers

| Controller             | Path                          | Role          | Purpose                              |
|------------------------|-------------------------------|---------------|--------------------------------------|
| `DashboardController`  | `/`                           | All           | Build history overview               |
| `BuildConfigController`| `/build-configs/**`           | Dev+          | CRUD for build configurations        |
| `BuildController`      | `/builds/**`                  | All (read), Dev+ (cancel) | Build detail and cancellation |
| `AgentController`      | `/agents/**`                  | Admin         | View and manage agents               |
| `UserController`       | `/users/**`                   | Admin         | User management                      |
| `WebhookController`    | `/webhook/{buildConfigId}`    | Anonymous     | GitHub webhook receiver              |
| `AgentApiController`   | `/api/agent/**`               | Anonymous*    | Agent enrollment, heartbeat, and build status reporting |

*Agent API endpoints are unauthenticated in the Spring Security sense but are protected by signature verification where applicable.

### Services

| Service               | Responsibility                                                   |
|-----------------------|------------------------------------------------------------------|
| `BuildService`        | Queues builds, assigns to agents, processes status updates from agents, handles cancellation |
| `AgentService`        | Tracks agent state, processes heartbeats, marks agents offline on missed heartbeats |
| `BuildConfigService`  | CRUD for build configurations, SSH key encryption/decryption     |
| `UserService`         | User CRUD and password management                                |
| `SigningService`      | Signs build assignments and cancellation commands with the server's private key |
| `WebhookService`      | Validates GitHub webhook signatures, parses push payloads, queues builds |

### Scheduled Tasks

| Task                     | Interval | Purpose                                           |
|--------------------------|----------|---------------------------------------------------|
| Agent heartbeat monitor  | 30s      | Marks agents `OFFLINE` if 3 consecutive heartbeats missed (90s) |
| Build queue processor    | 5s       | Assigns `QUEUED` builds to `IDLE` agents          |
| Build timeout monitor    | 30s      | Fails builds that exceed a configurable timeout (default: 30 min) |

## Agent Components

### Configuration Properties

Configured via `application.yml` or environment variables:

| Property                     | Description                           | Example                    |
|------------------------------|---------------------------------------|----------------------------|
| `code-rush.server.url`      | Server base URL                       | `http://192.168.1.10:8080` |
| `code-rush.server.public-key` | Server's Ed25519 public key (base64) | `MCowBQYDK2Vw...`         |
| `code-rush.agent.name`      | Agent display name                    | `agent-01`                 |
| `code-rush.agent.work-dir`  | Working directory for builds          | `/var/code-rush/work`      |
| `code-rush.agent.port`      | Port the agent listens on             | `8090`                     |

### Agent Startup Sequence

1. Load configuration.
2. Send enrollment request to server with a random nonce.
3. Verify the server's signature on the nonce using the configured public key.
4. On success, begin heartbeat loop and listen for build assignments.
5. On failure, log error and exit.

### Build Executor

The build executor runs each step in a separate OS process using `ProcessBuilder`:

- Working directory is set to the cloned repository root.
- stdout and stderr are merged and captured as the step log.
- Each process runs with a configurable per-step timeout (default: 15 minutes).
- A non-zero exit code marks the step as `FAILED`.

Process tree cleanup on cancellation or failure uses process group termination (`destroyForcibly()`).

### Agent HTTP Client

The agent communicates with the server using Spring's `RestClient`. All calls are outbound from agent to server:

| Endpoint                              | Method | Purpose                                      |
|---------------------------------------|--------|----------------------------------------------|
| `POST /api/agent/enroll`             | POST   | Agent enrollment with nonce                  |
| `POST /api/agent/heartbeat`          | POST   | Periodic heartbeat                           |
| `POST /api/agent/builds/{id}/steps`  | POST   | Report parsed steps from `.code-rush.yml`    |
| `POST /api/agent/builds/{id}/step-result` | POST | Report individual step completion        |
| `POST /api/agent/builds/{id}/complete` | POST | Report final build status                   |

### Agent REST Endpoints

The agent exposes a minimal REST API for server-initiated commands:

| Endpoint                     | Method | Purpose                                |
|------------------------------|--------|----------------------------------------|
| `POST /agent/build/assign`  | POST   | Receive a signed build assignment      |
| `POST /agent/build/cancel`  | POST   | Receive a signed cancellation command  |

The agent verifies the server's signature on both endpoints before acting.

## Common Module

### DTOs

Shared request/response types used by both server and agent:

- `EnrollmentRequest` / `EnrollmentResponse` — nonce exchange during enrollment.
- `BuildAssignment` — repository URL, branch, commit SHA, SSH key, build ID. Signed by server.
- `CancellationCommand` — build ID. Signed by server.
- `StepReport` — step number, name, script, status, log output.
- `BuildCompletionReport` — build ID, final status, error message (if any).
- `HeartbeatRequest` — agent name, current state, current build ID (if busy).

### Cryptography

Ed25519 is used for all signing operations. Java's built-in `java.security` API supports Ed25519 as of Java 15.

- `SigningUtil.sign(payload, privateKey)` → base64-encoded signature.
- `SigningUtil.verify(payload, signature, publicKey)` → boolean.

Payloads are serialized to a canonical JSON form before signing to ensure deterministic signatures.

### Shared Enums

- `BuildStatus`: `QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED`
- `AgentState`: `OFFLINE`, `IDLE`, `BUSY`
- `StepStatus`: `SUCCESS`, `FAILED`

## Web UI

Server-rendered HTML using Thymeleaf templates with the Spring Boot Thymeleaf starter. No JavaScript framework — interactivity is limited to standard HTML forms and page navigation.

### Layout

A shared Thymeleaf layout (`layout.html`) provides the navigation sidebar and page structure. Individual pages extend this layout.

### Pages

| Page                  | Path                         | Description                                 |
|-----------------------|------------------------------|---------------------------------------------|
| Login                 | `/login`                     | Form-based login                            |
| Dashboard             | `/`                          | Build history list with filters             |
| Build Detail          | `/builds/{id}`               | Per-step execution results and logs         |
| Build Configs List    | `/build-configs`             | List all build configurations               |
| Build Config Form     | `/build-configs/new`, `/build-configs/{id}/edit` | Create/edit a build configuration |
| Agents                | `/agents`                    | List agents with status                     |
| Users                 | `/users`                     | User management (admin only)                |
| Server Info           | `/admin/server`              | Displays server public key (admin only)     |

### Styling

Minimal CSS, either hand-written or using a classless CSS framework (e.g., Simple.css or Water.css) for baseline styling without build tooling. No CSS preprocessor.

## Configuration

### Server — `application.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/coderush
    username: coderush
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

code-rush:
  secret: ${CODE_RUSH_SECRET}       # Used to derive AES key for SSH key encryption
  keys-dir: ~/.code-rush/keys       # Ed25519 key pair storage
  build-timeout: 30m                # Max build duration before timeout
  step-timeout: 15m                 # Max individual step duration
```

### Agent — `application.yml`

```yaml
server:
  port: 8090

code-rush:
  server:
    url: http://192.168.1.10:8080
    public-key: MCowBQYDK2Vw...     # Base64-encoded Ed25519 public key
  agent:
    name: agent-01
    work-dir: /var/code-rush/work
```

## Communication Flow Diagrams

### Agent Enrollment

```
Agent                          Server
  |                              |
  |-- POST /api/agent/enroll -->|  (sends nonce + agent name/address)
  |                              |  signs nonce with private key
  |<-- 200 {signature} ---------|
  |                              |
  |  verifies signature          |
  |  starts heartbeat loop       |
  |                              |
  |-- POST /api/agent/heartbeat->| (every 30s)
```

### Build Execution

```
GitHub          Server                      Agent
  |               |                           |
  |-- webhook -->|                            |
  |               | queues build(s)           |
  |               |                           |
  |               | (queue processor runs)    |
  |               |                           |
  |               |-- POST /agent/build/assign -->|  (signed assignment + SSH key)
  |               |                           |
  |               |                           |  verifies signature
  |               |                           |  clones repo
  |               |                           |  parses .code-rush.yml
  |               |                           |
  |               |<-- POST /api/agent/builds/{id}/steps --|  (reports parsed steps)
  |               |                           |
  |               |                           |  executes step 1
  |               |<-- POST /api/agent/builds/{id}/step-result --| (step 1 result)
  |               |                           |
  |               |                           |  executes step 2
  |               |<-- POST /api/agent/builds/{id}/step-result --| (step 2 result)
  |               |                           |
  |               |<-- POST /api/agent/builds/{id}/complete -----| (final status)
  |               |                           |
  |               |                           |  cleanup work dir
```

### Build Cancellation

```
Server                          Agent
  |                               |
  |-- POST /agent/build/cancel ->|  (signed cancellation)
  |                               |
  |                               |  verifies signature
  |                               |  kills build process
  |                               |  cleans work dir
  |                               |
  |<-- POST /api/agent/builds/{id}/complete --| (status: CANCELLED)
```
