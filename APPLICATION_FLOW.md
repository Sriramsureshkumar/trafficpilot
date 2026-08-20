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

Currently the app has **Phase 1 → Phase 7** of the roadmap partially built:
service registry, route management, dynamic gateway routing, admin API-key auth, gateway API-key auth, and Redis rate limiting.

---

## 2. Spring Boot in 5 Minutes (for a Struts developer)

You already know Struts — so let me map the concepts.

| Struts world | Spring Boot equivalent | Notes |
|---|---|---|
| `struts.xml` action mappings | `@RestController` + `@RequestMapping` / `@GetMapping` etc. | Routes are declared with annotations directly on the class/method, not in XML. |
| Action class extending `ActionSupport` | Any plain class annotated with `@RestController` or `@Service` or `@Component` | No inheritance required. |
| `execute()` method returning a String result (`"success"`, `"error"`) | Regular Java method returning an object (POJO / DTO / `Mono` / `Flux`) | Spring auto-converts the return value to JSON. |
| Form bean | DTO class (see `CreateRouteRequest`, `CreateServiceRequest`) | Uses `@Valid` + `jakarta.validation` annotations like `@NotBlank`. |
| Interceptors chain | **Filters** (`WebFilter`, `GlobalFilter`) — see `AdminApiKeyWebFilter`, `GatewayApiKeyFilter`, `RedisRateLimitFilter` | Same idea: intercept requests before they hit the controller. |
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
├── docker-compose.yml               # Just spins up Redis on port 6379
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
        └── data.sql                       # Seeds a demo service + route
```

---

## 4. What Happens When You Run `mvn spring-boot:run`

1. `TrafficpilotApplication.main()` calls `SpringApplication.run(...)`.
2. `@SpringBootApplication` triggers **component scanning** in the `com.sriram.trafficpilot` package.
   - Spring finds every class annotated with `@Component`, `@Service`, `@Repository`, `@RestController`.
   - It instantiates them once each (**singleton beans**) and wires their constructor arguments together.
3. It reads `application.properties` and creates auto-configured beans:
   - An **H2 in-memory database** via R2DBC (reactive JDBC).
   - A **ReactiveStringRedisTemplate** connected to `localhost:6379`.
   - A **Spring Cloud Gateway** (built on Netty, reactive).
4. Because `spring.sql.init.mode=always`, it runs `schema.sql` (creates tables) and `data.sql` (seeds one service + one route).
5. It starts a Netty server (default port **8080**).
6. Spring Cloud Gateway asks all `RouteDefinitionRepository` beans for their routes. Our `DatabaseRouteDefinitionRepository` reads from the DB and returns the seeded route.
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

The seed row in `data.sql`:
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

## 10. Security — Two Layers of API Keys

### Layer A — Admin API key (`AdminApiKeyWebFilter`)
- Runs as a **`WebFilter`** (WebFlux level, catches *every* HTTP request into the app).
- If the URL starts with `/admin/`, it demands header `X-Admin-Key: admin-secret`.
- Missing/wrong key → `401 Unauthorized`.
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
trafficpilot.security.admin-api-key=admin-secret
trafficpilot.security.gateway-api-key=user-secret
```
Read into `ApiKeyProperties` via `@Value` — a simple config-holder bean that the filters inject.

*(Later phases can swap API keys for JWT.)*

---

## 11. Redis Rate Limiting — `RedisRateLimitFilter`

Another `GlobalFilter`, order `0` (runs after auth).

For every proxied request:
1. Find the matched route in the DB (by `routeId`).
2. If it has no `rateLimitPerMinute`, skip.
3. Build a Redis key: `trafficpilot:rate:<routeId>:<clientId>`
   - `clientId` = `X-Api-Key` header, or client IP as fallback.
4. `INCR` that key in Redis (atomic counter).
5. If this is the first hit (`count == 1`), set a TTL of 60s (from `trafficpilot.rate-limit.window-seconds`).
6. If `count > limit` → respond `429 Too Many Requests` with a `Retry-After` header.
7. Otherwise add `X-RateLimit-Limit` / `X-RateLimit-Remaining` headers and continue.

This is a classic **fixed-window** rate limiter (simple and works well enough for MVP).

---

## 12. End-to-End Request Flow

### A) An admin creates a new route
```
Client (curl / Postman)
  |  POST /admin/routes
  |  Header: X-Admin-Key: admin-secret
  |  Body:   { "path": "/orders/**", "serviceName": "order-service", ... }
  v
[WebFilter]  AdminApiKeyWebFilter
  - path starts with /admin/  → check X-Admin-Key
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
# 1. Start Redis (needed for rate limiting)
docker compose up -d

# 2. Start the mock backend in a separate terminal
cd mock-services/payment-service
javac PaymentServiceMock.java
java PaymentServiceMock          # listens on 9001

# 3. Start the gateway
./mvnw spring-boot:run           # listens on 8080

# 4. Try it out
# admin: list seeded services
curl -H "X-Admin-Key: admin-secret" http://localhost:8080/admin/services

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
| 6. Auth policy | ✅ (API-key, not JWT yet) | `AdminApiKeyWebFilter`, `GatewayApiKeyFilter`, `ApiKeyProperties` |
| 7. Redis rate limiting | ✅ | `RedisRateLimitFilter` |
| 8. Canary / weighted routing | ✅ | `CanaryRoutingFilter`, `AdminCanaryController`, `CanaryTarget`, `CanaryTargetRepository` |
| 9. Config versioning + rollback | ❌ | not built yet |
| 10. Scheduled health checks + audit logs | ❌ | (empty `service/` package is reserved for this) |

---

## 15. Study Tips (What to Read in What Order)

If you want to internalize the codebase, read the files in this order:

1. `pom.xml` — see the ingredients.
2. `application.properties` — see the config knobs.
3. `TrafficpilotApplication.java` — the 10-line entrypoint.
4. `schema.sql` + `data.sql` — see the data.
5. `model/*.java` — DB entities.
6. `repository/*.java` — how you talk to the DB (magic method names!).
7. `dto/*.java` — API contract.
8. `controller/AdminServiceController.java` — simplest CRUD, learn the reactive style here.
9. `controller/AdminRouteController.java` — same idea + the `RefreshRoutesEvent` trick.
10. `gateway/DatabaseRouteDefinitionRepository.java` — the bridge to Spring Cloud Gateway.
11. `security/AdminApiKeyWebFilter.java` — simplest filter (WebFilter).
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

Runs at order `1` (after auth=-1 and rate-limit=0, before Netty actually forwards the request).

```
1. Get matched route string-id from the exchange
2. Look up gateway_routes.id (numeric PK) from DB
3. Load all enabled canary_targets for that route
4. IF canary targets exist:
     Pick one using weighted random selection
     Rewrite GATEWAY_REQUEST_URL_ATTR to the chosen service's base_url
     Add X-Canary-Service response header (which backend was chosen)
5. IF no canary targets: pass through normally (regular route still applies)
```

**Weighted random algorithm** (in `weightedRandom()`):
- Sum all weights.
- Pick a random integer in `[0, totalWeight)`.
- Walk the list, accumulating weights, until the running total exceeds the random number.
- That target wins.

This works even when weights don't sum to exactly 100 (graceful degradation).

### Admin API — `AdminCanaryController` → `/admin/routes/{routeId}/canary`

| method | path | what it does |
|---|---|---|
| GET | `/admin/routes/{id}/canary` | List current canary targets for a route |
| PUT | `/admin/routes/{id}/canary` | Set (replace) canary targets — weights must sum to 100 |
| DELETE | `/admin/routes/{id}/canary` | Remove all canary targets (revert to normal routing) |

All protected by `X-Admin-Key`.

### Demo Walkthrough

```bash
# 1. Start both mock backends
cd mock-services/payment-service && java PaymentServiceMock    # port 9001
cd mock-services/payment-service-v2 && javac PaymentServiceV2Mock.java && java PaymentServiceV2Mock  # port 9002

# 2. Start the gateway (Redis must be up)
docker compose up -d
./mvnw spring-boot:run

# 3. Find the route ID (numeric PK from gateway_routes table)
curl -H "X-Admin-Key: admin-secret" http://localhost:8080/admin/routes
# Note the "id" field (e.g. 1) in the response

# 4. Set 90/10 canary split on route id=1
curl -X PUT http://localhost:8080/admin/routes/1/canary \
  -H "X-Admin-Key: admin-secret" \
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
  -H "X-Admin-Key: admin-secret"
```

---

## 18. TL;DR

TrafficPilot is a Spring Boot app that:

1. **Stores** a list of backend services and routing rules in an H2 database.
2. **Exposes admin APIs** (`/admin/services`, `/admin/routes`) protected by an admin API key, so operators can create/list/delete services and routes.
3. **Feeds those DB rows to Spring Cloud Gateway** through a custom `RouteDefinitionRepository`, and refreshes the gateway live whenever routes change.
4. **Intercepts every proxied request** with two global filters:
   - a per-route API-key check, and
   - a Redis-backed fixed-window rate limiter.
5. **Proxies** matching requests to the real backend (currently a tiny `PaymentServiceMock` on port 9001).

Next planned phases are canary weighted routing, config versioning/rollback, scheduled health checks, and an audit log.
