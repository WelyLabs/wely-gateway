package com.calendar.gateway.configuration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

// DEFINED_PORT with the management port fixed up front, rather than RANDOM_PORT: the gateway only
// skips routing on the management port when it knows that port's number, as in production (9081).
// A random management port (0) would hide that. The main port stays random (server.port=0).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
class WebFluxSecurityConfigTest {

    private static final int MANAGEMENT_PORT = freePort();

    @DynamicPropertySource
    static void managementPort(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> MANAGEMENT_PORT);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    private static WebTestClient onPort(int port) {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static final String CLIENT_REQUEST_ID = "client-chosen-request-id";

    private WebTestClient webTestClient;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    @Autowired
    private WebFluxSecurityConfig webFluxSecurityConfig;


    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    void testPublicEndpoints() {
        // /public/** is permitted
        webTestClient.get().uri("/public/test")
                .exchange()
                .expectStatus().isNotFound(); // No handler but it's permitted (not 401)
    }

    @Test
    void calendarFiles_shouldPassSecurityWithoutAToken() {
        // A calendar app carries no JWT: past security, whatever wely-events answers, never a 401.
        webTestClient.get().uri("/api/v1/events-service/public/calendar/tok.ics")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }

    @Test
    void calendarFiles_shouldOnlyBeOpenedForReading() {
        webTestClient.post().uri("/api/v1/events-service/public/calendar/tok.ics")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/events-service/events/e1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void testProtectedEndpoints() {
        // Any other endpoint should be unauthorized without token
        webTestClient.get().uri("/unmapped-path")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void testProtectedEndpointsWithUser() {
        // Using mockJwt instead of @WithMockUser for WebFlux JWT Resource Server
        webTestClient.mutateWith(mockJwt())
                .get().uri("/unmapped-path")
                .exchange()
                .expectStatus().isNotFound(); // Should be 404 since it's not unauthorized
    }

    @Test
    void testCorsConfiguration() {
        org.springframework.web.cors.reactive.CorsConfigurationSource source = context
                .getBean(org.springframework.web.cors.reactive.CorsConfigurationSource.class);
        org.springframework.web.server.ServerWebExchange exchange = org.springframework.mock.web.server.MockServerWebExchange
                .from(org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/").build());
        org.springframework.web.cors.CorsConfiguration config = source.getCorsConfiguration(exchange);
        assertThat(config.getAllowedOrigins()).contains("http://localhost:4200");
    }

    @Test
    void testJwtDecoderBean() {
        assertThat(webFluxSecurityConfig.jwtDecoder()).isNotNull();
    }

    @Test
    void testCorsConfigurationSourceBean() {
        assertThat(context.getBean(org.springframework.web.cors.reactive.CorsConfigurationSource.class)).isNotNull();
    }

    // --- 401, 403 and firewall rejections answer our problem ----------------------------------

    @Test
    void springSecurityFilterChain_shouldAnswer401AsOurProblem_keepingWwwAuthenticate() {
        webTestClient.get().uri("/api/v1/user-service/profile")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, value -> assertThat(value).startsWith("Bearer"))
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("GTW-REQ-001")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.type").isEqualTo("https://welylabs.app/problems/gtw-req-001")
                .jsonPath("$.instance").isEqualTo("/api/v1/user-service/profile")
                .jsonPath("$.requestId").isNotEmpty()
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void springSecurityFilterChain_shouldAnswer401WithTheGatewayRequestId() {
        assertOneRequestId(webTestClient.get().uri("/api/v1/user-service/profile")
                .header(RequestIdWebFilter.REQUEST_ID_HEADER, CLIENT_REQUEST_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().returnResult());
    }

    /** The id the client received in the header is the one in the body, and not the one it sent. */
    private static void assertOneRequestId(EntityExchangeResult<byte[]> result) {
        String header = result.getResponseHeaders().getFirst(RequestIdWebFilter.REQUEST_ID_HEADER);
        String body = JsonPath.read(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8), "$.requestId");
        assertThat(header).isNotBlank().isNotEqualTo(CLIENT_REQUEST_ID);
        assertThat(body).isEqualTo(header);
    }

    @Test
    void springSecurityFilterChain_shouldAnswer401AsOurProblem_whenTheTokenIsInvalid() {
        Mockito.when(reactiveJwtDecoder.decode("bad-token"))
                .thenReturn(Mono.error(new BadJwtException("expired")));

        webTestClient.get().uri("/api/v1/user-service/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer bad-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        value -> assertThat(value).startsWith("Bearer").contains("invalid_token"))
                .expectBody()
                .jsonPath("$.code").isEqualTo("GTW-REQ-001");
    }

    @Test
    void springSecurityFilterChain_shouldAnswerFirewallRejectionsAsOurProblem() {
        // TRACE never reaches a handler: the security firewall refuses it first.
        webTestClient.mutateWith(mockJwt())
                .method(HttpMethod.TRACE).uri("/fallback/users")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("GTW-REQ-000")
                .jsonPath("$.requestId").isNotEmpty();
    }

    // --- two ports: probes and API on 8081, actuator on the management port only ---------------

    @Test
    void probes_shouldAnswerOnTheMainPortWithoutAToken() {
        // Where the manifests probe: /livez and /readyz on the main port, not under /actuator.
        onPort(serverPort).get().uri("/livez").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        onPort(serverPort).get().uri("/readyz").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void probeAliases_shouldOnlyBeOpenedForReading() {
        onPort(serverPort).post().uri("/livez").exchange().expectStatus().isUnauthorized();
        onPort(serverPort).post().uri("/readyz").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void actuator_shouldNotBeServedOnTheMainPort() {
        // The port the tunnel reaches. Without a token, 401 like any path; with one, nothing there.
        for (String path : new String[] {"/actuator/prometheus", "/actuator/health", "/actuator/health/liveness",
                "/actuator/info", "/actuator/env"}) {
            onPort(serverPort).get().uri(path).exchange().expectStatus().isUnauthorized();
            webTestClient.mutateWith(mockJwt()).get().uri(path).exchange().expectStatus().isNotFound();
        }
    }

    @Test
    void prometheus_shouldAnswerOnTheManagementPortWithoutAToken() {
        // Count something first: a fallback through the real breaker (closed test port), and an
        // unexpected failure (a downstream outside the breaker refusing the connection).
        webTestClient.mutateWith(mockJwt()).get().uri("/api/v1/social-service/anything")
                .exchange().expectStatus().isEqualTo(503);
        webTestClient.mutateWith(mockJwt()).get().uri("/api/v1/notifications-service/notifications/stream")
                .exchange().expectStatus().isEqualTo(500);

        onPort(managementPort).get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("wely_gateway_fallbacks_total{service=\"social\"}")
                        .contains("wely_errors_technical_total{code=\"GTW-TEC-001\"}")
                        .contains("wely_errors_technical_total{code=\"GTW-TEC-000\"}"));
    }

    @Test
    void probes_shouldAlsoAnswerOnTheManagementPort() {
        onPort(managementPort).get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        onPort(managementPort).get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void managementPort_shouldDenyEverythingElse() {
        // Only GET on the scrape and the probes: the security chain of the management port
        // denies the rest, exposed or not — env and info alike, writes, and API paths.
        for (String path : new String[] {"/actuator/env", "/actuator/heapdump", "/actuator/info",
                "/actuator/health", "/api/v1/user-service/profile", "/fallback/users"}) {
            onPort(managementPort).get().uri(path).exchange().expectStatus().isUnauthorized();
        }
        onPort(managementPort).post().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void managementPort_shouldNeverBeSeenByTheRoutePredicates() {
        // Behind the security chain, a second guard: the gateway's own handler mapping returns no
        // route for a request received on the management port, so nothing there is ever proxied.
        RoutePredicateHandlerMapping routes = context.getBean(RoutePredicateHandlerMapping.class);
        MockServerWebExchange onManagement = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://localhost:" + managementPort + "/api/v1/user-service/profile")
                .localAddress(new InetSocketAddress("127.0.0.1", managementPort)));
        MockServerWebExchange onMain = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://localhost:" + serverPort + "/api/v1/user-service/profile")
                .localAddress(new InetSocketAddress("127.0.0.1", serverPort)));

        StepVerifier.create(routes.getHandler(onManagement)).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(routes.getHandler(onMain)).expectNextCount(1).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void onManagementPort_shouldMatchOnlyTheManagementPort() {
        ServerWebExchangeMatcher matcher = WebFluxSecurityConfig.onManagementPort(context.getEnvironment());

        StepVerifier.create(matcher.matches(MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/prometheus")
                        .localAddress(new InetSocketAddress("127.0.0.1", managementPort)))))
                .assertNext(result -> assertThat(result.isMatch()).isTrue())
                .expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(matcher.matches(MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/prometheus")
                        .localAddress(new InetSocketAddress("127.0.0.1", serverPort)))))
                .assertNext(result -> assertThat(result.isMatch()).isFalse())
                .expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(matcher.matches(MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/prometheus"))))
                .assertNext(result -> assertThat(result.isMatch()).isFalse())
                .expectComplete().verify(Duration.ofSeconds(10));
    }
}
