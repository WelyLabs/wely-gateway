package com.calendar.gateway.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class SecurityConfigTest {

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
}
