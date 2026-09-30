package com.calendar.gateway.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Covers {@link RouteConfig#configureRoute}, the whole routing table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RouteConfigTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private ApplicationContext context;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private Mono<Route> route(String id) {
        return routeLocator.getRoutes().filter(candidate -> candidate.getId().equals(id)).next();
    }

    @Test
    @DisplayName("one route per service, in order")
    void configureRoute_shouldDeclareOneRoutePerService() {
        StepVerifier.create(routeLocator.getRoutes().map(Route::getId))
                .expectNext("calendar-users-api")
                .expectNext("calendar-social-api")
                .expectNext("calendar-chat-api")
                .expectNext("chat-rsocket-route")
                // Kept although the media service was removed from the project. Dropping it
                // also touches wely-gitops-infra, which injects MEDIA_API_URL.
                .expectNext("calendar-media-api")
                .expectNext("calendar-events-api")
                .verifyComplete();
    }

    @Test
    @DisplayName("every REST route carries the four proxy filters")
    void configureRoute_shouldApplyTheProxyFiltersToEveryRestRoute() {
        // Rate limiter, circuit breaker, stripPrefix, retry. Asserted on each route rather
        // than on one, because the point of extracting proxyFilters was that a route could no
        // longer be forgotten.
        for (String id : new String[] {"calendar-users-api", "calendar-social-api",
                "calendar-chat-api", "calendar-media-api", "calendar-events-api"}) {
            StepVerifier.create(route(id).map(candidate -> candidate.getFilters().size()))
                    .assertNext(count -> assertThat(count).as("filters on %s", id).isEqualTo(4))
                    .verifyComplete();
        }
    }

    @Test
    @DisplayName("the RSocket route carries no filter at all")
    void configureRoute_shouldLeaveTheRSocketRouteUnfiltered() {
        // A WebSocket is one request that lives as long as the tab, so all three of rate
        // limiting, retrying and the breaker measure the wrong thing — and the breaker's
        // filter reads the exchange in a way that breaks the upgrade.
        StepVerifier.create(route("chat-rsocket-route").map(candidate -> candidate.getFilters().size()))
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    @DisplayName("routes point at the configured service URLs")
    void configureRoute_shouldTargetTheConfiguredUris() {
        StepVerifier.create(route("calendar-users-api").map(candidate -> candidate.getUri().toString()))
                .expectNext("http://localhost:9999")
                .verifyComplete();
        StepVerifier.create(route("chat-rsocket-route").map(candidate -> candidate.getUri().toString()))
                .expectNext("ws://localhost:9999")
                .verifyComplete();
    }

    @Test
    @DisplayName("an unreachable service answers 503 through the breaker, not a hanging request")
    void configureRoute_shouldFallBackWhenTheServiceIsUnreachable() {
        // The test profile points every route at a closed port, so this exercises the real
        // path: connection refused → circuit breaker → forward:/fallback/users →
        // FallbackController. Without the breaker the caller would wait for a timeout while
        // holding a gateway connection, which is how one failing service takes down the rest.
        webTestClient.mutateWith(mockJwt())
                .get()
                .uri("/api/v1/user-service/profile")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Service unavailable")
                .jsonPath("$.service").isEqualTo("users");
    }
}
