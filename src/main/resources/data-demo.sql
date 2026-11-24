-- Phase 2: Seed mock backend services
INSERT INTO "registered_services" (name, base_url, health_check_path, owner_team, status, created_at)
VALUES ('payment-service-v1', 'http://localhost:9001', '/health', 'payments-team', 'UNKNOWN', CURRENT_TIMESTAMP)
ON CONFLICT (name) DO NOTHING;

INSERT INTO "registered_services" (name, base_url, health_check_path, owner_team, status, created_at)
VALUES ('payment-service-v2', 'http://localhost:9002', '/health', 'payments-team', 'UNKNOWN', CURRENT_TIMESTAMP)
ON CONFLICT (name) DO NOTHING;

-- Phase 4: Seed route (points to v1 as default; canary can split traffic)
INSERT INTO "gateway_routes" (route_id, path, service_id, strip_prefix, auth_required, rate_limit_per_minute, enabled)
VALUES ('payment-service-route', '/payments/**', (SELECT id FROM "registered_services" WHERE name = 'payment-service-v1'), 1, TRUE, 100, TRUE)
ON CONFLICT (route_id) DO NOTHING;
