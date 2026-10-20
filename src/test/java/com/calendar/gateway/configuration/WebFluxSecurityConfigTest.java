package com.calendar.gateway.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebFluxSecurityConfigTest {

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

    // --- actuator: the probes are open, nothing else is served -------------------------------

    @Test
    void probes_shouldAnswerWithoutAToken() {
        webTestClient.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        webTestClient.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void actuator_shouldServeNeitherMetricsNorInternals() {
        // This port is the one the tunnel reaches: no prometheus, no env, even with a token.
        for (String path : new String[] {"/actuator/prometheus", "/actuator/env", "/actuator/heapdump"}) {
            webTestClient.get().uri(path)
                    .exchange()
                    .expectStatus().isUnauthorized();
            webTestClient.mutateWith(mockJwt())
                    .get().uri(path)
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }
}
