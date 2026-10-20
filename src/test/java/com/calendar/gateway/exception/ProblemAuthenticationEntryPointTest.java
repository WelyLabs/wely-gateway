package com.calendar.gateway.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link ProblemAuthenticationEntryPoint#commence}. The wiring into the security chain is
 * covered end to end in {@code WebFluxSecurityConfigTest}.
 */
class ProblemAuthenticationEntryPointTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ProblemAuthenticationEntryPoint entryPoint =
            new ProblemAuthenticationEntryPoint(new ProblemWriter(ServerCodecConfigurer.create()));

    @Test
    @DisplayName("no token: 401, our problem, and a bare Bearer challenge")
    void commence_shouldAnswerOurProblem_whenNoTokenWasSent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/x"));

        StepVerifier.create(entryPoint.commence(exchange, new AuthenticationCredentialsNotFoundException("none")))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"GTW-REQ-001\"")
                        .contains("\"type\":\"https://welylabs.app/problems/gtw-req-001\"")
                        .contains("\"instance\":\"/api/v1/x\"")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\""))
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    @DisplayName("invalid token: the challenge keeps the resource server's error parameters")
    void commence_shouldKeepTheBearerErrorParameters_whenTheTokenIsInvalid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/x"));
        OAuth2AuthenticationException invalid =
                new OAuth2AuthenticationException(BearerTokenErrors.invalidToken("Jwt expired"));

        StepVerifier.create(entryPoint.commence(exchange, invalid))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer")
                .contains("error=\"invalid_token\"");
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"GTW-REQ-001\"")
                        .doesNotContain("Jwt expired"))
                .expectComplete()
                .verify(TIMEOUT);
    }
}
