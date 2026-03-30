# Code Rush — Technical Design

## Project Structure

Code Rush is a multi-module Maven project producing two independent Spring Boot applications from a single repository.

```
code-rush/
├── pom.xml                # Parent POM, shared dependency management and plugin config
├── server/                # Application server module
│   ├── pom.xml
│   └── src/main/java/dev/coderush/server/
│       ├── ServerApplication.java
│       ├── config/        # Spring Security (JWT), crypto, startup initialization
│       ├── controller/    # REST API controllers and webhook endpoint
│       ├── model/         # JPA entities
│       ├── repository/    # Spring Data JPA repositories
│       └── service/       # Business logic (builds, agents, users, signing, auth)
├── code-rush-ui/          # React SPA module
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   └── src/
│       ├── main.tsx       # Entry point, React Router setup
│       ├── App.tsx        # Root component with route definitions
│       ├── pages/         # Page components (Login, Dashboard, BuildDetail, etc.)
│       ├── components/    # Shared UI components (Layout, Sidebar, etc.)
│       ├── api/           # API client functions with JWT token handling
│       └── context/       # React context providers (AuthContext, etc.)
├── agent/                 # Build agent module
│   ├── pom.xml
│   └── src/main/java/dev/coderush/agent/
│       ├── AgentApplication.java
│       ├── config/        # Agent configuration properties
│       ├── executor/      # Build step execution and process management
│       └── client/        # HTTP client for server communication
└── common/                # Shared module (DTOs, signing utilities, constants)
    ├── pom.xml
    └── src/main/java/dev/coderush/common/
        ├── dto/           # Request/response objects shared between server and agent
        ├── crypto/        # Signature generation and verification
        └── model/         # Shared enums (BuildStatus, AgentState, StepStatus)
```

Both `server` and `agent` depend on `common`. The server and agent have no direct dependency on each other. The `code-rush-ui` module is a standalone Vite/React project — not a Maven module. Its production build output is copied into the server's `src/main/resources/static/` directory for serving.

## Build Tool

Maven with the Spring Boot parent POM. Each application module uses the `spring-boot-maven-plugin` to produce executable JARs.

```
mvn -pl server spring-boot:repackage   # Produces server/target/server-0.1.0.jar
mvn -pl agent spring-boot:repackage    # Produces agent/target/agent-0.1.0.jar
```

Java 25 is set via `java.version` and `maven.compiler.source/target` properties in the parent POM.

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

- Stateless authentication using JWT tokens. No HTTP sessions.
- JWT tokens are issued by the `/api/auth/login` endpoint and must be included in the `Authorization: Bearer <token>` header on all subsequent API requests.
- Access and refresh token flow:
  - **Access token**: Short-lived (15 minutes). Included in every API request.
  - **Refresh token**: Longer-lived (7 days). Used to obtain a new access token via `/api/auth/refresh`.
- Role-based access control using Spring Security's `@PreAuthorize` or URL-based rules.
- Three roles map directly to granted authorities: `ROLE_ADMIN`, `ROLE_DEVELOPER`, `ROLE_VIEWER`.
- Password encoding via `BCryptPasswordEncoder`.
- A `JwtAuthenticationFilter` (extends `OncePerRequestFilter`) extracts and validates the JWT from each request, setting the `SecurityContext`.
- CORS is configured to allow requests from the UI dev server (`http://localhost:5173`) in development.

### Startup Initialization

On first startup (detected by an empty `users` table), the server:
1. Generates an Ed25519 key pair and persists it to a configurable file path (default: `~/.code-rush/keys/`).
2. Creates the default admin user (`admin:admin`).

On subsequent startups, the server loads the existing key pair from disk.

### Controllers

All UI-facing controllers are REST APIs returning JSON. The SPA is served as a static resource.

| Controller             | Path                              | Role          | Purpose                              |
|------------------------|-----------------------------------|---------------|--------------------------------------|
| `SpaController`        | `/`, `/{path:[^api].*}`           | Anonymous     | Forwards non-API routes to `index.html` for client-side routing |
| `AuthController`       | `/api/auth/login`                 | Anonymous     | Authenticates user, returns JWT access + refresh tokens |
|                        | `/api/auth/refresh`               | Anonymous     | Issues new access token from valid refresh token |
|                        | `/api/auth/me`                    | Authenticated | Returns current user info and role   |
| `BuildApiController`   | `/api/builds/**`                  | All (read), Dev+ (cancel) | Build list, detail, and cancellation |
| `BuildConfigApiController` | `/api/build-configs/**`       | Dev+          | CRUD for build configurations        |
| `AgentManagementController` | `/api/agents/**`             | Admin         | View and manage agents               |
| `UserApiController`    | `/api/users/**`                   | Admin         | User management                      |
| `WebhookController`    | `/webhook/{buildConfigId}`        | Anonymous     | GitHub webhook receiver              |
| `AgentApiController`   | `/api/agent/**`                   | Anonymous*    | Agent enrollment, heartbeat, and build status reporting |

*Agent API endpoints are unauthenticated in the Spring Security sense but are protected by signature verification where applicable.

### Services

| Service               | Responsibility                                                   |
|-----------------------|------------------------------------------------------------------|
| `BuildService`        | Queues builds, assigns to agents, processes status updates from agents, handles cancellation |
| `AgentService`        | Tracks agent state, processes heartbeats, marks agents offline on missed heartbeats |
| `BuildConfigService`  | CRUD for build configurations, SSH key encryption/decryption     |
| `UserService`         | User CRUD and password management                                |
| `SigningService`      | Signs build assignments and cancellation commands with the server's private key |
| `JwtService`          | Generates and validates JWT access/refresh tokens                |
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

The UI is a single-page application (SPA) built with React, TypeScript, and Vite, located in the `code-rush-ui/` module. It communicates with the server exclusively through REST APIs and handles routing client-side with React Router.

### Tech Stack

- **React 19** with TypeScript
- **Vite** for dev server and production builds
- **React Router** for client-side routing
- **Tailwind CSS** for styling
- JWT tokens stored in memory (access token) and `httpOnly` cookie (refresh token) for authentication

### SPA Serving

In production, the Vite build output (`dist/`) is copied into the server's `src/main/resources/static/` directory. The server's `SpaController` forwards all non-API routes to `index.html`, allowing React Router to handle client-side navigation.

During development, the Vite dev server runs on `http://localhost:5173` and proxies API requests to the Spring Boot server.

### Pages / Routes

| Route                        | Component            | Description                                 |
|------------------------------|----------------------|---------------------------------------------|
| `/login`                     | `LoginPage`          | JWT-based login form                        |
| `/`                          | `DashboardPage`      | Build history list with filters             |
| `/builds/:id`               | `BuildDetailPage`    | Per-step execution results and logs         |
| `/build-configs`            | `BuildConfigsPage`   | List all build configurations               |
| `/build-configs/new`        | `BuildConfigFormPage`| Create a build configuration                |
| `/build-configs/:id/edit`   | `BuildConfigFormPage`| Edit a build configuration                  |
| `/agents`                   | `AgentsPage`         | List agents with status                     |
| `/users`                    | `UsersPage`          | User management (admin only)                |
| `/admin/server`             | `ServerInfoPage`     | Displays server public key (admin only)     |

### Authentication Flow (Client-Side)

1. User submits credentials on the login page.
2. The UI calls `POST /api/auth/login` and receives access + refresh tokens.
3. The access token is stored in memory and attached to every API request via an `Authorization` header.
4. When the access token expires, the API client automatically calls `POST /api/auth/refresh` to obtain a new one.
5. Protected routes are guarded by an `AuthContext` provider that redirects unauthenticated users to `/login`.

### Styling

Tailwind CSS loaded via CDN (`cdn.tailwindcss.com`) with a custom Material Design 3–inspired dark theme. Custom utility classes for scanlines, grid patterns, and neon glow effects are defined in the app's base styles.

## Configuration

### Server — `application.yml`

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
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
  jwt:
    secret: ${JWT_SECRET}           # HMAC key for signing JWTs
    access-token-expiry: 15m        # Access token lifetime
    refresh-token-expiry: 7d        # Refresh token lifetime
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
