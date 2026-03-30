# Code Rush — Implementation Checklist

Work through each step in order. Each step should compile and pass its tests before moving on.

## Phase 1: Project Skeleton

- [x] **1.1 Maven multi-module setup**
  - Parent `pom.xml` with Spring Boot parent, declaring `common`, `server`, and `agent` modules.
  - Shared Java 25 compiler config and Spring Boot dependency management.
  - Module-level `pom.xml` files with correct dependencies (`server` → `common`, `agent` → `common`).
  - Verify: `mvn compile` compiles all three modules with no source yet (empty build succeeds).

- [x] **1.2 Common module — shared enums and DTOs**
  - `BuildStatus`, `AgentState`, `StepStatus` enums.
  - DTO records: `EnrollmentRequest`, `EnrollmentResponse`, `BuildAssignment`, `CancellationCommand`, `StepReport`, `BuildCompletionReport`, `HeartbeatRequest`.
  - Verify: `mvn -pl common compile` compiles.

- [x] **1.3 Common module — Ed25519 signing utilities**
  - `SigningUtil` with `generateKeyPair()`, `sign(payload, privateKey)`, `verify(payload, signature, publicKey)`.
  - Key serialization helpers (to/from base64).
  - Unit tests: generate key pair, sign a payload, verify succeeds, verify with wrong key fails, verify with tampered payload fails.
  - Verify: `mvn -pl common test`

## Phase 2: Server Foundation

- [x] **2.1 Server Spring Boot app with PostgreSQL and JPA entities**
  - `ServerApplication.java` main class.
  - JPA entities: `User`, `BuildConfig`, `Agent`, `Build` with column mappings matching the design schema.
  - Spring Data repositories for each entity.
  - `application.yml` with datasource config (use Testcontainers or H2 for tests).
  - Verify: server starts and Hibernate creates tables. `mvn -pl server test` with a Spring Boot context load test.

- [x] **2.2 Startup initialization — key pair generation and default admin**
  - On first startup (empty `users` table): generate Ed25519 key pair, persist to disk, create `admin:admin` user.
  - On subsequent startup: load existing key pair.
  - `SigningService` wraps the loaded key pair for signing operations.
  - Verify: start server twice — first creates keys + admin, second reuses them. Unit test the initialization logic.

- [x] **2.3 Spring Security configuration**
  - Stateless JWT authentication. No HTTP sessions.
  - `JwtAuthenticationFilter` extracts and validates the JWT from each request's `Authorization: Bearer <token>` header.
  - Role-based URL access rules matching the controller table in the design.
  - `BCryptPasswordEncoder` bean.
  - `/webhook/**` and `/api/agent/**` endpoints permitted without authentication.
  - CORS configured to allow requests from the UI dev server (`http://localhost:5173`) in development.
  - Verify: unauthenticated API requests return 401. Authenticated admin can access `/api/users`. Authenticated viewer cannot access `/api/users`. Webhook and agent API paths are open.

- [ ] **2.4 Auth controller — login, refresh, and current user**
  - `AuthController`: `POST /api/auth/login` (returns access + refresh tokens), `POST /api/auth/refresh` (issues new access token), `GET /api/auth/me` (returns current user info and role).
  - `JwtService`: generate and validate JWT access tokens (15 min) and refresh tokens (7 days).
  - Verify: login with valid credentials returns tokens. Access token grants API access. Expired access token is rejected. Refresh token obtains a new access token. Integration tests.

- [ ] **2.5 User management — service and REST controller**
  - `UserService`: create, list, delete users, change password.
  - `UserApiController` at `/api/users/**` returning JSON.
  - Admin-only access.
  - Verify: authenticated admin can create a developer user via API. Integration test for user CRUD.

## Phase 3: Build Configuration

- [ ] **3.1 SSH key encryption utilities**
  - AES-256-GCM encrypt/decrypt using a key derived from `CODE_RUSH_SECRET` via PBKDF2 or HKDF.
  - Unit tests: encrypt then decrypt round-trips, decrypt with wrong secret fails.

- [ ] **3.2 Build config — service and REST controller**
  - `BuildConfigService`: create, update, list, get. Encrypts SSH key on save, decrypts on read (for agent transfer).
  - `BuildConfigApiController` at `/api/build-configs/**` returning JSON.
  - Fields: name, repository URL, SSH key, webhook secret.
  - Accessible to developer and admin roles.
  - Verify: create a build config via API, confirm it persists. SSH key is encrypted in the database. Integration test for CRUD operations.

## Phase 4: Agent Enrollment and Heartbeats

- [ ] **4.1 Server-side agent API — enrollment and heartbeat endpoints**
  - `AgentApiController`: `POST /api/agent/enroll` (receives nonce + agent info, returns signed nonce), `POST /api/agent/heartbeat` (updates agent last_heartbeat and state).
  - `AgentService`: enroll agent (create record, sign nonce), process heartbeat, scheduled task to mark agents offline after 90s.
  - Verify: call enroll endpoint with a nonce, get back a valid signature. Call heartbeat, confirm agent stays IDLE. Stop heartbeats, confirm agent goes OFFLINE after 90s. Integration tests.

- [ ] **4.2 Server-side agent management REST controller**
  - `AgentManagementController` at `/api/agents/**` returning JSON: list agents with name, address, state, last heartbeat.
  - Admin-only access.
  - Verify: after enrolling an agent (via test or curl), the agent appears in the API response.

- [ ] **4.3 Agent Spring Boot app — enrollment and heartbeat**
  - `AgentApplication.java` main class.
  - Agent configuration properties (`code-rush.server.url`, `code-rush.server.public-key`, `code-rush.agent.name`, `code-rush.agent.work-dir`).
  - Startup sequence: send enrollment request, verify server signature on nonce, start heartbeat loop (30s interval).
  - On verification failure: log error and exit.
  - Verify: start server and agent together — agent enrolls and appears as IDLE on the server. Stop agent — server marks it OFFLINE after 90s.

## Phase 5: Webhook Integration

- [ ] **5.1 GitHub webhook receiver**
  - `WebhookController`: `POST /webhook/{buildConfigId}`.
  - `WebhookService`: validate HMAC-SHA256 signature using the build config's webhook secret, parse push event payload, extract branch + commits (SHA, author, message), queue one `Build` record per commit with status `QUEUED`.
  - Verify: send a sample GitHub push payload with valid signature — builds are queued. Invalid signature returns 401. Unknown buildConfigId returns 404. Integration tests with sample payloads.

## Phase 6: Build Execution

- [ ] **6.1 Build queue processor — server assigns builds to agents**
  - Scheduled task (5s interval): find `QUEUED` builds, find `IDLE` agents, assign build to agent.
  - On assignment: set build status to `RUNNING`, set agent state to `BUSY`, send signed `BuildAssignment` (repo URL, branch, commit SHA, SSH key, build ID) to agent's `/agent/build/assign` endpoint.
  - Verify: queue a build, have an idle agent — build gets assigned and agent receives the assignment. Integration test.

- [ ] **6.2 Agent — receive build assignment and clone repo**
  - `POST /agent/build/assign` endpoint: verify signature, store assignment.
  - Write SSH key to a temp file, configure `GIT_SSH_COMMAND`, clone repo into work dir, checkout branch/commit.
  - Clean up SSH key file after clone.
  - Verify: send a signed build assignment pointing to a test repo — agent clones it successfully. Invalid signature is rejected.

- [ ] **6.3 Agent — parse .code-rush.yml and report steps**
  - After clone: read and parse `.code-rush.yml` from repo root (use SnakeYAML or Jackson YAML).
  - If missing or unparsable: report build failure to server immediately.
  - On success: POST parsed steps to `POST /api/agent/builds/{id}/steps`.
  - Server stores the steps in the build's `execution_details` JSONB column (with status `PENDING`).
  - Verify: clone a repo with a valid `.code-rush.yml` — steps are reported. Clone a repo without the file — build fails immediately with descriptive error.

- [ ] **6.4 Agent — build step executor**
  - Execute each step sequentially using `ProcessBuilder` (shell: `/bin/sh -c <script>`).
  - Capture merged stdout/stderr as step log.
  - Per-step timeout (configurable, default 15 min).
  - After each step: POST result to `POST /api/agent/builds/{id}/step-result` with step number, status, and log.
  - On step failure (non-zero exit): stop execution, report build completion as `FAILED`.
  - On all steps success: report build completion as `SUCCESS`.
  - Clean up work directory after completion.
  - Verify: run a build with steps that succeed — all steps reported, build succeeds. Run a build where step 2 fails — step 1 success, step 2 failure, no step 3 executed, build fails. Timeout test.

- [ ] **6.5 Server — process agent build reports**
  - `POST /api/agent/builds/{id}/steps`: store parsed steps in `execution_details`.
  - `POST /api/agent/builds/{id}/step-result`: update the corresponding step in `execution_details` with status and log.
  - `POST /api/agent/builds/{id}/complete`: set final build status, `finished_at` timestamp, mark agent as `IDLE`.
  - Verify: simulate agent reports — build record updates correctly through the full lifecycle. Integration tests.

## Phase 7: Build Observability

- [ ] **7.1 Build REST API**
  - `BuildApiController` at `/api/builds/**` returning JSON: paginated build list with config name, commit SHA/author/message, branch, status, timestamp. Filters: by build configuration, status, branch. Build detail endpoint with per-step execution results.
  - Read access for all authenticated roles. Cancel endpoint for developer and admin roles.
  - Verify: create several builds with different statuses — API returns correct data. Filters work. Integration tests.

- [ ] **7.2 SPA controller for client-side routing**
  - `SpaController` at `/`, `/{path:[^api].*}`: forwards all non-API routes to `index.html` so React Router handles client-side navigation.
  - Verify: requesting `/builds/1` serves `index.html`. Requesting `/api/builds/1` hits the REST controller.

## Phase 8: Build Cancellation

- [ ] **8.1 Server-side cancellation**
  - Cancel endpoint on `BuildApiController` (admin and developer roles, only for `RUNNING` builds).
  - `BuildService.cancelBuild()`: send signed `CancellationCommand` to the agent's `/agent/build/cancel` endpoint.
  - Verify: cancel a running build via API — server sends cancellation to agent.

- [ ] **8.2 Agent-side cancellation**
  - `POST /agent/build/cancel`: verify signature, terminate running process (`destroyForcibly()`), clean up work directory.
  - Report build completion with status `CANCELLED` to server.
  - Agent transitions to `IDLE`.
  - Verify: cancel a running build — process is killed, work dir cleaned, agent returns to IDLE, build marked CANCELLED.

## Phase 9: Build Timeout

- [ ] **9.1 Build timeout monitor**
  - Server scheduled task (30s interval): find `RUNNING` builds that exceed the configured timeout (default 30 min).
  - For timed-out builds: send cancellation to agent, mark build as `FAILED` with error message "Build timed out after 30 minutes".
  - Verify: start a long-running build, set timeout to a short value — build is failed with timeout message.

## Phase 10: Web UI (code-rush-ui)

- [ ] **10.1 React SPA project setup**
  - Initialize `code-rush-ui/` with Vite, React 19, TypeScript, React Router, and Tailwind CSS.
  - Configure Vite dev server to proxy `/api` and `/webhook` requests to `http://localhost:8080`.
  - API client module with JWT token handling: attach access token to requests, auto-refresh on 401.
  - `AuthContext` provider: stores tokens, exposes login/logout, redirects unauthenticated users to `/login`.
  - Verify: `npm run dev` starts the dev server and proxies API requests to the backend.

- [ ] **10.2 Login page**
  - `LoginPage` at `/login`: credentials form, calls `POST /api/auth/login`, stores tokens, redirects to dashboard.
  - Verify: login with valid credentials redirects to dashboard. Invalid credentials show error.

- [ ] **10.3 Dashboard and build detail pages**
  - `DashboardPage` at `/`: build history list with filters (config, status, branch).
  - `BuildDetailPage` at `/builds/:id`: build metadata and per-step execution results with expandable log output. Cancel button for running builds (developer/admin).
  - Verify: builds display correctly. Filters work. Failed build steps are highlighted.

- [ ] **10.4 Build config pages**
  - `BuildConfigsPage` at `/build-configs`: list all build configurations.
  - `BuildConfigFormPage` at `/build-configs/new` and `/build-configs/:id/edit`: create/edit form with name, repository URL, SSH key, webhook secret.
  - Verify: create and edit build configs through the UI.

- [ ] **10.5 Admin pages**
  - `AgentsPage` at `/agents`: list agents with name, address, state, last heartbeat. Admin-only.
  - `UsersPage` at `/users`: user management (create, delete, change password). Admin-only.
  - `ServerInfoPage` at `/admin/server`: displays server's Ed25519 public key (base64) for copying. Admin-only.
  - Verify: admin can access all pages. Non-admin users are redirected away from admin pages.

- [ ] **10.6 Layout and navigation**
  - Shared `Layout` component with sidebar navigation: Dashboard, Build Configs, Agents (admin), Users (admin), Server Info (admin).
  - Active page highlighting. Role-based nav item visibility.
  - Tailwind CSS dark theme with Material Design 3 inspiration.
  - Verify: navigation works across all pages, role-restricted items are hidden for non-admin users.

- [ ] **10.7 Production build and serving**
  - Vite production build outputs to `dist/`. Copy into server's `src/main/resources/static/`.
  - `SpaController` serves `index.html` for all non-API routes.
  - Verify: `npm run build` produces static assets. Server serves the SPA and API requests work correctly.

- [ ] **10.8 End-to-end integration test**
  - Full flow: server starts → agent enrolls → create build config → simulate webhook → build queues → agent executes → steps report back → build succeeds → view in UI.
  - Verify: the entire pipeline works from webhook to build completion.
