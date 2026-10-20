package com.calendar.gateway.configuration;

import com.calendar.gateway.exception.TooManyRequestsProblemFilter;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
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

    private static final String CLIENT_REQUEST_ID = "client-chosen-request-id";

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
                .expectNext("internal-paths-blocked")
                .expectNext("calendar-users-api")
                .expectNext("calendar-social-api")
                .expectNext("calendar-chat-api")
                .expectNext("chat-rsocket-route")
                .expectNext("events-poll-stream")
                .expectNext("calendar-events-api")
                .expectNext("notifications-stream")
                .expectNext("wely-notifications")
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("every REST route carries the five proxy filters")
    void configureRoute_shouldApplyTheProxyFiltersToEveryRestRoute() {
        // 429 problem body, rate limiter, circuit breaker, stripPrefix, retry. Asserted on each route rather
        // than on one, because the point of extracting proxyFilters was that a route could no
        // longer be forgotten.
        for (String id : new String[] {"calendar-users-api", "calendar-social-api",
                "calendar-chat-api", "calendar-events-api", "wely-notifications"}) {
            StepVerifier.create(route(id).map(candidate -> candidate.getFilters().size()))
                    .assertNext(count -> assertThat(count).as("filters on %s", id).isEqualTo(5))
                    .expectComplete().verify(Duration.ofSeconds(10));
        }
    }

    @Test
    @DisplayName("the 429 problem filter comes first, in front of the rate limiter it dresses")
    void configureRoute_shouldPutTheTooManyRequestsFilterInFrontOfTheLimiter() {
        StepVerifier.create(route("calendar-users-api").map(candidate -> candidate.getFilters().getFirst()))
                .assertNext(first -> assertThat(first)
                        .isInstanceOfSatisfying(OrderedGatewayFilter.class, ordered ->
                                assertThat(ordered.getDelegate()).isInstanceOf(TooManyRequestsProblemFilter.class)))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the RSocket route carries no filter at all")
    void configureRoute_shouldLeaveTheRSocketRouteUnfiltered() {
        // A WebSocket is one request that lives as long as the tab, so all three of rate
        // limiting, retrying and the breaker measure the wrong thing — and the breaker's
        // filter reads the exchange in a way that breaks the upgrade.
        StepVerifier.create(route("chat-rsocket-route").map(candidate -> candidate.getFilters().size()))
                .expectNext(0)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the poll stream route only strips the prefix")
    void configureRoute_shouldLeaveThePollStreamOutOfTheBreaker() {
        // An SSE response lasts as long as the event page: the breaker's 10s time limiter
        // would cut it. One filter, stripPrefix.
        StepVerifier.create(route("events-poll-stream").map(candidate -> candidate.getFilters().size()))
                .expectNext(1)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the notifications stream route only strips the prefix")
    void configureRoute_shouldLeaveTheNotificationsStreamOutOfTheBreaker() {
        StepVerifier.create(route("notifications-stream").map(candidate -> candidate.getFilters().size()))
                .expectNext(1)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("routes point at the configured service URLs")
    void configureRoute_shouldTargetTheConfiguredUris() {
        StepVerifier.create(route("calendar-users-api").map(candidate -> candidate.getUri().toString()))
                .expectNext("http://localhost:9999")
                .expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(route("chat-rsocket-route").map(candidate -> candidate.getUri().toString()))
                .expectNext("ws://localhost:9999")
                .expectComplete().verify(Duration.ofSeconds(10));
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
                .jsonPath("$.code").isEqualTo("GTW-TEC-001")
                // The path the client called, not the internal /fallback/users forward.
                .jsonPath("$.instance").isEqualTo("/api/v1/user-service/profile")
                .jsonPath("$.requestId").isNotEmpty()
                .jsonPath("$.service").isEqualTo("users");
    }

    @Test
    @DisplayName("the fallback's requestId is the gateway's id, returned in X-Request-Id, not the client's")
    void configureRoute_shouldAnswerTheFallbackWithTheGatewayRequestId() {
        // The breaker forwards to /fallback with a new request: the id must survive the forward.
        assertOneRequestId(webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/chat-service/conversations")
                .header(RequestIdWebFilter.REQUEST_ID_HEADER, CLIENT_REQUEST_ID)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().returnResult());
    }

    /** The id the client received in the header is the one in the body, and not the one it sent. */
    private static void assertOneRequestId(EntityExchangeResult<byte[]> result) {
        String header = result.getResponseHeaders().getFirst(RequestIdWebFilter.REQUEST_ID_HEADER);
        String body = JsonPath.read(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8), "$.requestId");
        assertThat(header).isNotBlank().isNotEqualTo(CLIENT_REQUEST_ID);
        assertThat(body).isEqualTo(header);
    }

    // --- internal paths ---------------------------------------------------

    @Test
    @DisplayName("the internal block carries the status filter only: no quota, no breaker, no forwarding")
    void configureRoute_shouldGiveTheInternalBlockOnlyTheStatusFilter() {
        StepVerifier.create(route(RouteConfig.INTERNAL_PATHS_ROUTE).map(candidate -> candidate.getFilters().size()))
                .expectNext(1)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an internal path answers 404 at the gateway; forwarded, it would be 503 from the closed test port")
    void configureRoute_shouldAnswer404WithoutForwarding_whenThePathIsInternal() {
        webTestClient.mutateWith(mockJwt())
                .get()
                .uri("/api/v1/user-service/internal/deleted-users")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().isEmpty();
    }

    @Test
    @DisplayName("every service's internal paths are blocked, whatever the method")
    void configureRoute_shouldBlockInternalPaths_whateverTheServiceSegment() {
        for (String service : new String[] {"user-service", "social-service", "chat-service", "events-service",
                "notifications-service", "media-service"}) {
            webTestClient.mutateWith(mockJwt())
                    .get()
                    .uri("/api/v1/" + service + "/internal/anything/deeper?after=x")
                    .exchange()
                    .expectStatus().isNotFound();
        }
        webTestClient.mutateWith(mockJwt())
                .delete()
                .uri("/api/v1/user-service/internal/deleted-users")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("without a token the gateway answers 401 before it reveals anything about an internal path")
    void configureRoute_shouldRequireAToken_beforeAnsweringAnInternalPath() {
        webTestClient.get()
                .uri("/api/v1/user-service/internal/deleted-users")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("the account deletion goes through the users route and its breaker, like any write")
    void configureRoute_shouldForwardTheProfileDeletionThroughTheUsersBreaker_whenTheServiceIsDown() {
        webTestClient.mutateWith(mockJwt())
                .delete()
                .uri("/api/v1/user-service/profile")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.service").isEqualTo("users");
    }
}
