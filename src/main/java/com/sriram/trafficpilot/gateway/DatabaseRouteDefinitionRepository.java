package com.sriram.trafficpilot.gateway;

import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Component
public class DatabaseRouteDefinitionRepository implements RouteDefinitionRepository {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DatabaseRouteDefinitionRepository.class);

	private final GatewayRouteRepository routeRepository;
	private final RegisteredServiceRepository serviceRepository;

	public DatabaseRouteDefinitionRepository(
			GatewayRouteRepository routeRepository,
			RegisteredServiceRepository serviceRepository
	) {
		this.routeRepository = routeRepository;
		this.serviceRepository = serviceRepository;
	}

	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {
		return routeRepository.findByEnabledTrue()
				.flatMap(route -> serviceRepository.findById(route.getServiceId())
						.filter(this::hasValidBaseUrl)
						.map(service -> toRouteDefinition(route, service)));
	}

	private boolean hasValidBaseUrl(RegisteredService service) {
		try {
			URI uri = URI.create(service.getBaseUrl());
			boolean valid = uri.getHost() != null && uri.getScheme() != null;
			if (!valid) {
				log.warn("Skipping routes for service '{}': invalid base URL '{}'", service.getName(), service.getBaseUrl());
			}
			return valid;
		}
		catch (IllegalArgumentException ex) {
			log.warn("Skipping routes for service '{}': invalid base URL '{}'", service.getName(), service.getBaseUrl());
			return false;
		}
	}

	@Override
	public Mono<Void> save(Mono<RouteDefinition> route) {
		return Mono.error(new UnsupportedOperationException("Use /admin/routes to create gateway routes"));
	}

	@Override
	public Mono<Void> delete(Mono<String> routeId) {
		return Mono.error(new UnsupportedOperationException("Use /admin/routes/{id} to delete gateway routes"));
	}

	private RouteDefinition toRouteDefinition(GatewayRoute gatewayRoute, RegisteredService service) {
		RouteDefinition routeDefinition = new RouteDefinition();
		routeDefinition.setId(gatewayRoute.getRouteId());
		routeDefinition.setUri(URI.create(service.getBaseUrl()));
		routeDefinition.setPredicates(List.of(pathPredicate(gatewayRoute.getPath())));
		routeDefinition.setFilters(filters(gatewayRoute));
		return routeDefinition;
	}

	private PredicateDefinition pathPredicate(String path) {
		PredicateDefinition predicate = new PredicateDefinition();
		predicate.setName("Path");
		predicate.addArg("_genkey_0", path);
		return predicate;
	}

	private List<FilterDefinition> filters(GatewayRoute gatewayRoute) {
		List<FilterDefinition> filters = new ArrayList<>();
		if (gatewayRoute.getStripPrefix() != null && gatewayRoute.getStripPrefix() > 0) {
			FilterDefinition filter = new FilterDefinition();
			filter.setName("StripPrefix");
			filter.addArg("_genkey_0", gatewayRoute.getStripPrefix().toString());
			filters.add(filter);
		}
		return filters;
	}
}