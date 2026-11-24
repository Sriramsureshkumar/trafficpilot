# TrafficPilot — Application Flow & Spring Boot Study Guide

> A walkthrough of the current codebase, written for someone who knows **Struts** but is new to **Spring Boot**. Read top-to-bottom; each section builds on the previous one.

---

## 1. What is TrafficPilot?

TrafficPilot is an **API Gateway Policy Manager**. Think of it as a smart traffic cop sitting in front of your backend microservices.

- Admins register backend services (e.g. `payment-service-v1` running at `http://localhost:9001`).
- Admins define **routes** — rules that say "any request coming to `/payments/**` should be forwarded to `payment-service-v1`".
- The gateway enforces policies on every incoming request:
  - Is the route enabled?
  - Does this route require an API key? (auth)
  - Has this client exceeded its rate limit? (Redis)
  - If ok, forward the request to the real backend service.

The app implements service registration, dynamic routes, team-scoped user login,
gateway API-key auth, Redis rate limiting, weighted canary routing, scheduled
backend health checks, and route-change audit history. Config versioning and
rollback remain unimplemented.

---

## 2. Spring Boot in 5 Minutes (for a Struts developer)

You already know Struts — so let me map the concepts.

| Struts world | Spring Boot equivalent | Notes |
|---|---|---|
| `struts.xml` action mappings | `@RestController` + `@RequestMapping` / `@GetMapping` etc. | Routes are declared with annotations directly on the class/method, not in XML. |
| Action class extending `ActionSupport` | Any plain class annotated with `@RestController` or `@Service` or `@Component` | No inheritance required. |
| `execute()` method returning a String result (`"success"`, `"error"`) | Regular Java method returning an object (POJO / DTO / `Mono` / `Flux`) | Spring auto-converts the return value to JSON. |
| Form bean | DTO class (see `CreateRouteRequest`, `CreateServiceRequest`) | Uses `@Valid` + `jakarta.validation` annotations like `@NotBlank`. |
| Interceptors chain | **Filters** (`WebFilter`, `GlobalFilter`) — see `AdminAuthWebFilter`, `GatewayApiKeyFilter`, `RedisRateLimitFilter` | Same idea: intercept requests before they hit the controller. |
| `struts.properties` | `application.properties` (or `.yml`) | Same idea: key/value config. |
| Manually wiring services in code | **Dependency Injection** via constructor | Spring creates one instance ("bean") of each `@Component`/`@Service`/`@RestController`/`@Repository` and passes it wherever it's needed. |

### The magic annotations you must remember

- `@SpringBootApplication` — put on the **main class**. It boots the whole framework, scans the package for beans, and starts an embedded web server.
- `@Component` / `@Service` / `@Repository` / `@RestController` — "please manage this class as a bean". Spring instantiates it once and hands it to anyone who asks for it.
- `@Autowired` — "inject a bean here". Modern style: just declare it in the **constructor**, Spring auto-injects (no annotation needed).
- `@Value("${some.key}")` — read a value from `application.properties`.
- `@RequestMapping("/admin/routes")` — base URL for a controller.
- `@GetMapping`, `@PostMapping`, `@DeleteMapping` — HTTP verb + path.
- `@PathVariable` — capture `{id}` from URL.
- `@RequestBody` — deserialize JSON body into a Java object.
- `@Valid` — trigger validation on the incoming DTO.
- `@Id`, `@Table(...)` — mark a class as a database row.

### Reactive (WebFlux) — this is new even for many Spring devs

TrafficPilot uses **Spring WebFlux**, not classic Spring MVC. WebFlux is *asynchronous* and *non-blocking*.

- Instead of returning `Route` you return `Mono<Route>` (a promise of one item).
- Instead of returning `List<Route>` you return `Flux<Route>` (a stream of many items).
- `Mono` and `Flux` are from **Project Reactor**. They are lazy: nothing runs until something (Spring) subscribes.
- Operators you'll see everywhere:
  - `.map(x -> ...)` — transform.
  - `.flatMap(x -> Mono/Flux)` — chain another async step.
  - `.switchIfEmpty(...)` — fallback if the stream is empty.
  - `.doOnSuccess(...)` — side effect after success.
  - `.onErrorResume(...)` — recover from an error.

Why reactive? Because a gateway must handle **many concurrent connections** with few threads. Blocking on a DB call would waste threads.

---

## 3. Project Layout

```
trafficpilot/
├── docker-compose.yml               # PostgreSQL (persistent volume) + Redis
├── pom.xml                          # Maven build + Spring Boot dependencies
├── Phases-doc                       # Original roadmap (10 phases)
├── mock-services/
│   └── payment-service/
│       └── PaymentServiceMock.java  # Tiny fake backend on port 9001 (/status, /health)
└── src/main/
    ├── java/com/sriram/trafficpilot/
    │   ├── TrafficpilotApplication.java   # main() — entrypoint
    │   ├── controller/                    # HTTP endpoints (admin APIs)
    │   ├── dto/                           # Request/response objects (JSON <-> Java)
    │   ├── model/                         # DB entities (table rows)
    │   ├── repository/                    # DB access (reactive CRUD)
    │   ├── gateway/                       # Bridges DB routes → Spring Cloud Gateway
    │   ├── ratelimit/                     # Redis rate-limit filter
    │   └── security/                      # API-key filters (admin + gateway)
    └── resources/
        ├── application.properties         # Config (ports, DB URL, keys)
        ├── schema.sql                     # Creates tables at startup
        └── data-demo.sql                  # Optional demo services + route
```

---

## 4. What Happens When You Run `mvn spring-boot:run`

1. `TrafficpilotApplication.main()` calls `SpringApplication.run(...)`.
2. `@SpringBootApplication` triggers **component scanning** in the `com.sriram.trafficpilot` package.
   - Spring finds every class annotated with `@Component`, `@Service`, `@Repository`, `@RestController`.
   - It instantiates them once each (**singleton beans**) and wires their constructor arguments together.
3. It reads `application.properties` and creates auto-configured beans:
   - A **PostgreSQL connection** via R2DBC (non-blocking SQL access, not JDBC).
   - A **ReactiveStringRedisTemplate** connected to `localhost:6379`.
   - A **Spring Cloud Gateway** (built on Netty, reactive).
4. Because `spring.sql.init.mode=always`, it runs `schema.sql` to create missing tables and indexes. With the optional `demo` profile, `data-demo.sql` inserts two mock services and a route only if their unique names/route ID do not exist; existing configuration is not overwritten.
5. It starts a Netty server (default port **8080**).
6. Spring Cloud Gateway asks all `RouteDefinitionRepository` beans for their routes. Our `DatabaseRouteDefinitionRepository` reads the saved routes from PostgreSQL.
7. Server is live. Requests to `/admin/**` hit the controllers; requests to `/payments/**` are proxied to the backend.

---

## 5. The Data Model

Two tables (see `schema.sql`):

### `registered_services` — a catalog of backend services
| column | meaning |
|---|---|
| id | PK |
| name | unique name, e.g. `payment-service-v1` |
| base_url | where it lives, e.g. `http://localhost:9001` |
| health_check_path | e.g. `/health` (used later in Phase 10) |
| owner_team | who owns it |
| status | UP / DOWN / UNKNOWN |
| created_at | timestamp |

Mapped by `model/RegisteredService.java` (`@Table("registered_services")`, `@Id` on `id`).

### `gateway_routes` — the routing rules
| column | meaning |
|---|---|
| id | PK |
| route_id | unique string id (e.g. `payment-service-route`) — used by Spring Cloud Gateway |
| path | ant-style path, e.g. `/payments/**` |
| service_id | FK → `registered_services.id` |
| strip_prefix | how many path segments to strip before forwarding (e.g. `1` turns `/payments/status` into `/status`) |
| auth_required | if true, the request must carry the correct `X-Api-Key` |
| rate_limit_per_minute | integer rate cap (used by Redis filter) |
| enabled | soft on/off switch |

Mapped by `model/GatewayRoute.java`.

The optional seed row in `data-demo.sql`:
- Service: `payment-service-v1` at `http://localhost:9001`.
- Route: `/payments/**` → that service, strip 1 prefix, auth required, 100 req/min.

---

## 6. Repositories — Zero-Code DB Access

Look at `GatewayRouteRepository.java`:

```java
public interface GatewayRouteRepository extends ReactiveCrudRepository<GatewayRoute, Long> {
    Flux<GatewayRoute> findByEnabledTrue();
    Mono<Boolean> existsByRouteId(String routeId);
    Mono<GatewayRoute> findByRouteId(String routeId);
}
```

That's it. No implementation. Spring Data reads the method names and **generates the SQL for you** at startup:
- `findByEnabledTrue()` → `SELECT * FROM gateway_routes WHERE enabled = true`
- `existsByRouteId(x)` → `SELECT COUNT(*) FROM gateway_routes WHERE route_id = ?`
- Inherited `findAll`, `findById`, `save`, `deleteById` from `ReactiveCrudRepository`.

Everything returns `Mono` / `Flux` because it's reactive.

Same story for `RegisteredServiceRepository`.

---

## 7. DTOs — What the JSON Looks Like

DTO = Data Transfer Object. Keeps the DB model separate from the JSON API contract.

- **`CreateServiceRequest`** — the JSON body when creating a service. Has `@NotBlank` on required fields.
- **`ServiceResponse`** (a Java `record`) — the JSON returned to the client. Immutable.
- **`CreateRouteRequest`** — validation like `@Min(1)` on rate limit.
- **`RouteResponse`** — includes joined service info (name + base URL) for convenience.

Spring uses **Jackson** to convert Java ↔ JSON automatically.

---

## 8. Admin API Endpoints

### `AdminServiceController` → `/admin/services`
| method | path | what it does |
|---|---|---|
| GET | `/admin/services` | list all registered services |
| GET | `/admin/services/{name}` | fetch one by name |
| POST | `/admin/services` | create (rejects duplicate names with 409) |
| DELETE | `/admin/services/{id}` | delete |

### `AdminRouteController` → `/admin/routes`
| method | path | what it does |
|---|---|---|
| GET | `/admin/routes` | list all routes (joined with service) |
| GET | `/admin/routes/{id}` | fetch one |
| POST | `/admin/routes` | create — validates service exists, then saves, then **publishes a `RefreshRoutesEvent`** |
| DELETE | `/admin/routes/{id}` | delete + refresh event |

### `HealthCheckController` → `/health-check`
Trivial `{ "status": "TrafficPilot is running" }`.

Actuator also adds `/actuator/health` and `/actuator/info` (see `application.properties`).

### 🔑 The `RefreshRoutesEvent` — the "dynamic" part
When you POST a new route:
1. Row is saved to DB.
2. `eventPublisher.publishEvent(new RefreshRoutesEvent(this))` fires.
3. Spring Cloud Gateway catches this event and re-asks all `RouteDefinitionRepository` beans for the latest routes.
4. `DatabaseRouteDefinitionRepository.getRouteDefinitions()` runs again and yields the new list.
5. The gateway's routing table is updated **live, without restarting the app**.

This is the "Phase 5: Dynamic Gateway Routing" — the heart of the project.

---

## 9. The Gateway Bridge — `DatabaseRouteDefinitionRepository`

Spring Cloud Gateway needs a `RouteDefinitionRepository` bean. Normally routes are defined in `application.yml`. We instead implement this interface and **read from the DB**.

```java
@Override
public Flux<RouteDefinition> getRouteDefinitions() {
    return routeRepository.findByEnabledTrue()
            .flatMap(route -> serviceRepository.findById(route.getServiceId())
                    .map(service -> toRouteDefinition(route, service)));
}
```

For each enabled `GatewayRoute` row, it looks up the target service, then builds a `RouteDefinition` that Spring Cloud Gateway understands:

- **id** = `route.routeId` (e.g. `payment-service-route`)
- **uri** = service base URL (e.g. `http://localhost:9001`)
- **predicates** = `Path=/payments/**` (this route matches when the incoming URL matches this path)
- **filters** = `StripPrefix=1` (chop the first segment before forwarding)

`save` and `delete` are intentionally blocked — those go through the admin controllers.

---

## 10. Security — User Login plus a Gateway API Key

### Layer A — User login and roles (`AdminAuthWebFilter`)
- Runs as a **`WebFilter`** (WebFlux level). `POST /auth/login` (public) returns an opaque bearer token; the SHA-256 hash is stored in `auth_tokens` (8h TTL).
- Everything under `/admin/` needs `Authorization: Bearer <token>`; missing/invalid/expired → `401`.
- Roles: `PLATFORM_ADMIN` (everything, plus `/admin/teams` and `/admin/users`), `TEAM_ADMIN` (read/write own team's services, routes, canaries), `VIEWER` (read-only on own team; writes → `403`).
- Services belong to a team (`team_id`); routes and canaries inherit it through their service. Resources of other teams return `404`, not `403`, so their existence isn't leaked.
- Passwords are BCrypt-hashed (`spring-security-crypto` only). 5 failed logins lock a username for 15 minutes (Redis key `login-fail:<username>`). Changing a password revokes existing sessions.
- On first start `SecurityBootstrap` creates the bootstrap admin (`TRAFFICPILOT_ADMIN_USERNAME`, default `admin`; `TRAFFICPILOT_ADMIN_PASSWORD`, required, min 10 chars) and backfills teams from legacy `owner_team` labels.
- Other paths (`/payments/**`, `/health-check`, etc.) pass through untouched.

### Layer B — Gateway API key (`GatewayApiKeyFilter`)
- Runs as a **`GlobalFilter`** (Spring Cloud Gateway level, only on proxied requests).
- Fetches the matched route from the DB. If `authRequired = true`, it demands header `X-Api-Key: user-secret`.
- Wrong/missing → `401`.
- If the route doesn't require auth, pass through.
- Ordered `-1` (runs before the rate limiter which is order `0`).

### The keys themselves
Live in `application.properties`:
```
trafficpilot.security.gateway-api-key=user-secret
trafficpilot.security.token-ttl-hours=8
```
The gateway key is read into `ApiKeyProperties` via `@Value`. It is still one shared, global key (per-team gateway keys are a future phase).

---

## 11. Redis Rate Limiting — `RedisRateLimitFilter`

Another `GlobalFilter`, order `0` (runs after auth).

For every proxied request:
1. Find the matched route in the DB (by `routeId`).
2. If it has no `rateLimitPerMinute`, skip.
3. Build a Redis key: `trafficpilot:rate:<routeId>:<clientId>`
   - `clientId` = `X-Api-Key` header, or client IP as fallback.
4. Execute one Redis Lua script: `INCR` the counter and, on the first hit, set its TTL (60s by default, from `trafficpilot.rate-limit.window-seconds`). Increment and expiry are atomic; the script also repairs old counters missing a TTL.
5. Requests share a window per route/client, not per backend (including canary backends).
6. If `count > limit` → respond `429 Too Many Requests` with a `Retry-After` header.
7. Otherwise add `X-RateLimit-Limit` / `X-RateLimit-Remaining` headers and continue.

This is a classic **fixed-window** rate limiter (simple and works well enough for MVP).
The configured window must be positive. `Retry-After` is a conservative full-window
delay, not the exact remaining TTL. Redis failures are logged and fail closed
with `503`; downstream errors propagate unchanged instead of being mistaken
for Redis outages.

---

## 12. End-to-End Request Flow

### A) An admin creates a new route
```
Client (curl / Postman)
  |  POST /admin/routes
  |  Header: Authorization: Bearer <token>
  |  Body:   { "path": "/orders/**", "serviceName": "order-service", ... }
  v
[WebFilter]  AdminAuthWebFilter
  - path starts with /admin/  → validate token, role and team
  - OK → continue
  v
[Dispatcher] Spring routes to AdminRouteController.createRoute(...)
  - @Valid triggers validation on CreateRouteRequest
  v
[Controller]
  1. Check routeId doesn't already exist    (routeRepository.existsByRouteId)
  2. Load service by name                   (serviceRepository.findByName)
  3. Save the GatewayRoute row              (routeRepository.save)
  4. Publish RefreshRoutesEvent             (Spring Cloud Gateway rebuilds routes)
  5. Return RouteResponse JSON              (201 Created)
```

### B) A client hits a proxied endpoint
```
Client
  |  GET /payments/status
  |  Header: X-Api-Key: user-secret
  v
[WebFilter]  AdminApiKeyWebFilter
  - path does NOT start with /admin/  → pass through
  v
[Spring Cloud Gateway]
  - Look up routes (from DatabaseRouteDefinitionRepository, cached)
  - Match: /payments/**  →  route "payment-service-route"
  - Attach the matched Route to the exchange
  v
[GlobalFilter -1]  GatewayApiKeyFilter
  - Look up route in DB; authRequired == true
  - Check X-Api-Key header matches gateway-api-key
  - OK → continue
  v
[GlobalFilter 0]  RedisRateLimitFilter
  - INCR trafficpilot:rate:payment-service-route:<key>
  - count <= 100 → add headers, continue
  - count > 100  → 429 Too Many Requests (stop here)
  v
[Built-in filter]  StripPrefix=1
  - /payments/status  →  /status
  v
[Netty HTTP client]
  - Forward to http://localhost:9001/status
  v
PaymentServiceMock
  - Returns { "service":"payment-service", "version":"v1", "status":"success" }
  v
Client gets the JSON, plus X-RateLimit-* response headers.
```

---

## 13. How to Run It Locally

```bash
# 1. Set TRAFFICPILOT_DB_PASSWORD in this terminal first (see PostgreSQL setup below).
# Start PostgreSQL and Redis.
docker compose up -d

# 2. Start the mock backend in a separate terminal
cd mock-services/payment-service
javac PaymentServiceMock.java
java PaymentServiceMock          # listens on 9001

# 3. Start the gateway
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo  # listens on 8080

# 4. Try it out
# set TRAFFICPILOT_ADMIN_PASSWORD (min 10 chars) before step 3, then log in
$token = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType application/json -Body '{"username":"admin","password":"<your password>"}').token
# admin: list seeded services
curl -H "Authorization: Bearer $token" http://localhost:8080/admin/services

# proxied call (needs the user key because the route has authRequired=true)
curl -H "X-Api-Key: user-secret" http://localhost:8080/payments/status

# rate-limit demo — fire 105 calls in a loop, last few should get HTTP 429
for i in $(seq 1 105); do
  curl -s -o /dev/null -w "%{http_code}\n" \
       -H "X-Api-Key: user-secret" \
       http://localhost:8080/payments/status
done
```

---

## 14. Where Each Roadmap Phase Lives (Current Progress)

| Phase | Status | Where in code |
|---|---|---|
| 1. Basic setup (Spring Boot, WebFlux, R2DBC, Redis) | ✅ | `pom.xml`, `application.properties` |
| 2. Mock services | ✅ (only payment-service) | `mock-services/payment-service/` |
| 3. Service registry APIs | ✅ | `AdminServiceController`, `RegisteredService`, `RegisteredServiceRepository` |
| 4. Route management APIs | ✅ | `AdminRouteController`, `GatewayRoute`, `GatewayRouteRepository` |
| 5. Dynamic gateway routing | ✅ | `DatabaseRouteDefinitionRepository` + `RefreshRoutesEvent` |
| 6. Auth policy | ✅ (user bearer tokens + gateway API key) | `AdminAuthWebFilter`, `GatewayApiKeyFilter`, `ApiKeyProperties` |
| 7. Redis rate limiting | ✅ | `RedisRateLimitFilter` |
| 8. Canary / weighted routing | ✅ | `CanaryRoutingFilter`, `AdminCanaryController`, `CanaryTarget`, `CanaryTargetRepository` |
| 9. Config versioning + rollback | ❌ | not built yet |
| 10. Scheduled health checks + audit logs | ✅ | `ServiceHealthPoller`, `AuditService`, `AdminAuditController` |

---

## 15. Study Tips (What to Read in What Order)

If you want to internalize the codebase, read the files in this order:

1. `pom.xml` — see the ingredients.
2. `application.properties` — see the config knobs.
3. `TrafficpilotApplication.java` — the 10-line entrypoint.
4. `schema.sql` + `data-demo.sql` — see the schema and optional demo data.
5. `model/*.java` — DB entities.
6. `repository/*.java` — how you talk to the DB (magic method names!).
7. `dto/*.java` — API contract.
8. `controller/AdminServiceController.java` — simplest CRUD, learn the reactive style here.
9. `controller/AdminRouteController.java` — same idea + the `RefreshRoutesEvent` trick.
10. `gateway/DatabaseRouteDefinitionRepository.java` — the bridge to Spring Cloud Gateway.
11. `security/AdminAuthWebFilter.java` — authentication and role filter (WebFilter).
12. `security/GatewayApiKeyFilter.java` — gateway-level filter (GlobalFilter).
13. `ratelimit/RedisRateLimitFilter.java` — hardest file; teaches you Reactor + Redis.

---

## 16. Cheat Sheet: Reactive Operators You'll See

| Code | Analogy in blocking Java |
|---|---|
| `Mono<X>` | `Future<X>` (0 or 1 result) |
| `Flux<X>` | `Stream<X>` (0..N results, async) |
| `.map(f)` | `list.stream().map(f)` |
| `.flatMap(f)` | flatten a nested async call |
| `.switchIfEmpty(other)` | if empty, use `other` |
| `.doOnSuccess(x -> ...)` | `try { ...; sideEffect(x); }` |
| `.onErrorResume(e -> other)` | `catch (Exception e) { return other; }` |
| `Mono.error(new X())` | `throw new X()` (in reactive form) |
| `.then(...)` | ignore previous value, do this next |
| `.thenReturn(v)` | do this, then return v |

---

## 17. Phase 8 — Canary Routing

### What is Canary Routing?

Instead of sending 100% of traffic to one backend, canary routing lets you **split traffic** across multiple service versions by weight. For example:

- 90% → `payment-service-v1` (stable, port 9001)
- 10% → `payment-service-v2` (new release, port 9002)

This lets you test a new version with real users on a small slice of traffic before a full rollout.

### The New Data Model — `canary_targets` table

```sql
canary_targets
- id
- route_id   (FK → gateway_routes.id)
- service_id (FK → registered_services.id)
- weight     (integer: 1–100, all weights for a route must sum to 100)
- enabled    (soft on/off)
```

Mapped by `model/CanaryTarget.java`.

### How the Filter Works — `CanaryRoutingFilter`

Runs at `RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1` (10001).
This is after API-key auth (-1), rate limiting (0), `StripPrefix`, and Gateway's
normal request-URL construction (10000), but before Netty forwards the request.
Running earlier would let Gateway overwrite the canary destination.

```
1. Get matched route string-id from the exchange
2. Look up gateway_routes.id (numeric PK) from DB
3. Load all enabled canary_targets for that route
4. IF canary targets exist:
     Pick one using weighted random selection
     Replace scheme/host/port in GATEWAY_REQUEST_URL_ATTR with the chosen backend
     Preserve the already stripped path and raw query without double encoding
     Add X-Canary-Service response header (which backend was chosen)
5. IF no canary targets: pass through normally (regular route still applies)
```

**Weighted random algorithm** (in `weightedTarget()`):
- Require positive enabled weights summing to 100.
- Pick a random integer in `[0, 100)`.
- Walk the list, accumulating weights, until the running total exceeds the random number.
- That target wins.

Invalid weights or a missing/invalid registered service produce an explicit
`503`, rather than silently selecting the first target. Requests without canary
targets follow the normal route exactly once. As with normal Gateway routes,
backend base-URL paths do not prefix the forwarded path; use route path filters.

Canary replacement validates all targets before writing, rejects duplicates, and
requires every target to belong to the route's owning team (even for platform
admins). Delete-and-insert plus its audit entry run in one reactive PostgreSQL
transaction, so failed writes preserve the old split. Route changes and canary
removal are transactional too. Refresh events are published after commit.

### Admin API — `AdminCanaryController` → `/admin/routes/{routeId}/canary`

| method | path | what it does |
|---|---|---|
| GET | `/admin/routes/{id}/canary` | List current canary targets for a route |
| PUT | `/admin/routes/{id}/canary` | Set (replace) canary targets — weights must sum to 100 |
| DELETE | `/admin/routes/{id}/canary` | Remove all canary targets (revert to normal routing) |

All require a bearer token and are scoped to the caller's team (write needs `TEAM_ADMIN` or `PLATFORM_ADMIN`).

### Demo Walkthrough

```bash
# 1. Start both mock backends
cd mock-services/payment-service && java PaymentServiceMock    # port 9001
cd mock-services/payment-service-v2 && javac PaymentServiceV2Mock.java && java PaymentServiceV2Mock  # port 9002

# 2. Start the gateway (set TRAFFICPILOT_DB_PASSWORD first; PostgreSQL and Redis must be up)
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo

# 3. Find the route ID (numeric PK from gateway_routes table)
curl -H "Authorization: Bearer $token" http://localhost:8080/admin/routes
# Note the "id" field (e.g. 1) in the response

# 4. Set 90/10 canary split on route id=1
curl -X PUT http://localhost:8080/admin/routes/1/canary \
  -H "Authorization: Bearer $token" \
  -H "Content-Type: application/json" \
  -d '{
    "targets": [
      { "serviceName": "payment-service-v1", "weight": 90 },
      { "serviceName": "payment-service-v2", "weight": 10 }
    ]
  }'

# 5. Fire 20 requests — ~18 should hit v1, ~2 should hit v2
for i in $(seq 1 20); do
  curl -s -H "X-Api-Key: user-secret" http://localhost:8080/payments/status | python3 -m json.tool | grep version
done

# 6. Remove canary → all traffic back to v1
curl -X DELETE http://localhost:8080/admin/routes/1/canary \
  -H "Authorization: Bearer $token"
```

---

## 18. TL;DR

TrafficPilot is a Spring Boot app that:

1. **Stores** a list of backend services and routing rules in a persistent PostgreSQL database.
2. **Exposes admin APIs** (`/admin/services`, `/admin/routes`) protected by user login with team-scoped roles, so operators can create/list/delete services and routes.
3. **Feeds those DB rows to Spring Cloud Gateway** through a custom `RouteDefinitionRepository`, and refreshes the gateway live whenever routes change.
4. **Intercepts every proxied request** with two global filters:
   - a per-route API-key check, and
   - a Redis-backed fixed-window rate limiter.
5. **Proxies** matching requests to the real backend (currently a tiny `PaymentServiceMock` on port 9001).

Weighted canary routing, health polling and route-change auditing are implemented.
Config versioning/rollback remains a future phase; self-signup, SSO and additional
role types are not included.

---

## 19. Admin Frontend

The admin console is served by Spring Boot from `src/main/resources/static/`; open the app's base URL (for example, `http://localhost:8080/`) to use it. It calls the existing admin APIs to list, register, and delete services; list, create, and delete routes; and configure or remove canary traffic splits. The overview also reads `/actuator/health`.

The console shows a sign-in dialog. After login it keeps the bearer token in the current tab's `sessionStorage` and sends it as `Authorization: Bearer`; a `401` returns you to the login dialog. The UI adapts to the role: viewers see no add/delete buttons, and only platform admins see the "Teams & users" panels for creating teams and users. Team admins and viewers only see their own team's data.

The audit panel shows route/canary changes with actor, timestamp and details
(50 entries per page, Newer/Older navigation). Service health and last-checked
timestamps refresh every 30 seconds in visible, signed-in tabs without resetting
the canary editor. Use Refresh to reload full configuration.

---

## 20. PostgreSQL Setup and Persistence

Requires Java 21, Docker Desktop running with Linux containers, and an available local port 5432.
In PowerShell, from the project root:

```powershell
# Enter a local database password without writing it into this repository.
$credential = Get-Credential -UserName trafficpilot -Message "Choose a local PostgreSQL password"
$env:TRAFFICPILOT_DB_PASSWORD = $credential.GetNetworkCredential().Password
# On first startup, choose the admin login password (at least 10 characters).
$adminCredential = Get-Credential -UserName admin -Message "Choose the first admin password"
$env:TRAFFICPILOT_ADMIN_PASSWORD = $adminCredential.GetNetworkCredential().Password
docker compose up -d --wait
.\mvnw.cmd spring-boot:run
```

Run Maven in the same terminal so it inherits the password. A fresh database starts with no registered services or routes. For optional mock data instead, use:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Database settings:

| Environment variable | Default / purpose |
|---|---|
| `TRAFFICPILOT_DB_PASSWORD` | Required by both Compose and Spring Boot |
| `TRAFFICPILOT_DB_USERNAME` | `trafficpilot` |
| `TRAFFICPILOT_DB_URL` | `r2dbc:postgresql://localhost:5432/trafficpilot` (application connection) |
| `TRAFFICPILOT_DB_PORT` | `5432` (Compose host port; if changed, set the application URL to the same port) |

PostgreSQL data lives in the Compose `postgres-data` named volume. Ordinary app restarts, container restarts, and `docker compose down` followed by `up` preserve it. Do not use `docker compose down -v`: that deletes the database volume. Keep database backups for important configuration. PostgreSQL initialization credentials apply only when the volume is first created; changing environment variables alone does not change an existing database user's password.

The old H2 database was in memory; its data is not automatically migrated. Export any configuration needed from the still-running old application's admin APIs before shutting it down, then recreate it against PostgreSQL.

Run `.\mvnw.cmd clean test` against a **dedicated test PostgreSQL database and Redis**,
with `TRAFFICPILOT_DB_URL`, `TRAFFICPILOT_DB_USERNAME`, `TRAFFICPILOT_DB_PASSWORD` and
`SPRING_DATA_REDIS_PORT` set appropriately. Tests initialize the schema and create a
test bootstrap admin; integration fixtures use unique names and clean up their own
rows and Redis keys. Do not point tests at a production database. `clean` removes
stale compiled resources from the previous H2 setup. SQL initialization creates
missing schema objects and applies the explicit `ADD COLUMN IF NOT EXISTS`
statements; use versioned migrations before evolving a deployed database.

## 21. Route Audit History

`audit_entries` stores immutable snapshots of the actor ID/username, owning team,
numeric route ID, route name, action, details and UTC timestamp. Supported actions:
`ROUTE_CREATED`, `ROUTE_DELETED`, `CANARY_REPLACED`, `CANARY_REMOVED`.
Only successful changes are recorded, in the same transaction as the change.
Audit failure rolls back the configuration change. Audit records deliberately
have no foreign keys to routes or users, so deleting either does not erase history.
There is no audit edit/delete API.

`GET /admin/audit?limit=50&offset=0` requires a bearer token. Platform admins can
read all teams; team admins and viewers can read only their own team's history,
including deleted routes. `limit` is 1-100; `offset` must be non-negative. History
is newest-first by monotonically increasing ID. This is application-level
auditing, not a tamper-proof compliance store; DB admins can still modify it.
Service/user management and failed requests are not audited.

## 22. Scheduled Backend Health Checks

`ServiceHealthPoller` starts after 5 seconds and polls registered backends every
30 seconds (fixed delay after completion). Up to four checks run concurrently,
with a two-second total timeout per check. Spring subscribes to the reactive
scheduled method; each subscription re-reads current services.
The WebClient starter provides Boot's configured HTTP client builder.

The poller requests the configured `healthCheckPath` on the backend's HTTP(S)
origin (for example `http://localhost:9001/health`). Paths must begin with a single
`/` and cannot contain a query or fragment; they cannot redirect checks to another
host. HTTP 2xx means `UP`; other status codes, connection failures, invalid legacy
URLs and timeouts mean `DOWN`. Response bodies are released. Results update only
`status` and `last_checked_at`, without overwriting service configuration.
Errors are logged; the next poll can recover a backend to `UP`.

These are **observational checks**, not circuit breaking, failover or automatic
route disablement. A DOWN backend may still receive traffic. Gateway
`/actuator/health` remains a separate check of TrafficPilot and its infrastructure.

| Property | Default |
|---|---|
| `trafficpilot.health.enabled` | `true` (set `false` to disable polling) |
| `trafficpilot.health.interval` | `30s` |
| `trafficpilot.health.initial-delay` | `5s` |
| `trafficpilot.health.timeout` | `2s` |

Focused, infrastructure-free regression tests:

```powershell
.\mvnw.cmd "-Dtest=CanaryRoutingFilterTest,RedisRateLimitFilterTest,ServiceHealthPollerTest" test
```

The full suite also verifies real HTTP routing/stripping/query preservation,
disabled routes, gateway auth, atomic Redis limits under concurrent traffic,
expiry/TTL repair, PostgreSQL audit persistence/isolation/deletion retention,
transaction rollback when auditing fails, and persisted backend health.
