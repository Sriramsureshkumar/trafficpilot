-- Phase 2: Seed mock backend services
MERGE INTO "registered_services" (name, base_url, health_check_path, owner_team, status, created_at)
KEY(name)
VALUES ('payment-service-v1', 'http://localhost:9001', '/health', 'payments-team', 'UP', CURRENT_TIMESTAMP);

MERGE INTO "registered_services" (name, base_url, health_check_path, owner_team, status, created_at)
KEY(name)
VALUES ('payment-service-v2', 'http://localhost:9002', '/health', 'payments-team', 'UP', CURRENT_TIMESTAMP);

-- Phase 4: Seed route (points to v1 as default; canary can split traffic)
MERGE INTO "gateway_routes" (route_id, path, service_id, strip_prefix, auth_required, rate_limit_per_minute, enabled)
KEY(route_id)
VALUES ('payment-service-route', '/payments/**', (SELECT id FROM "registered_services" WHERE name = 'payment-service-v1'), 1, TRUE, 100, TRUE);
