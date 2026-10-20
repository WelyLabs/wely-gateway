package com.calendar.gateway.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers both {@link Problems#of} builders.
 */
class ProblemsTest {

    private final MockServerWebExchange exchange =
            MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/user-service/profile?x=1"));

    @Test
    @DisplayName("builds the services' shape: type from the code, title, detail, code, timestamp, requestId")
    void of_shouldBuildTheSharedShape() {
        Instant before = Instant.now();

        ProblemDetail problem = Problems.of(HttpStatus.UNAUTHORIZED, "GTW-REQ-001", "Authentication required",
                "A valid bearer token is required.", exchange);

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getType()).isEqualTo(URI.create("https://welylabs.app/problems/gtw-req-001"));
        assertThat(problem.getTitle()).isEqualTo("Authentication required");
        assertThat(problem.getDetail()).isEqualTo("A valid bearer token is required.");
        assertThat(problem.getProperties())
                .containsEntry(Problems.CODE_PROPERTY, "GTW-REQ-001")
                .containsEntry(Problems.REQUEST_ID_PROPERTY, exchange.getRequest().getId());
        assertThat(Instant.parse((String) problem.getProperties().get(Problems.TIMESTAMP_PROPERTY)))
                .isBetween(before, Instant.now().plus(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("instance defaults to the request path, without the query string")
    void of_shouldUseTheRequestPath_whenNoInstanceIsGiven() {
        ProblemDetail problem = Problems.of(HttpStatus.NOT_FOUND, "GTW-REQ-000", "Not Found", "d", exchange);

        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/user-service/profile"));
    }

    @Test
    @DisplayName("an explicit instance wins over the request path")
    void of_shouldUseTheGivenInstance() {
        ProblemDetail problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "GTW-TEC-001", "t", "d",
                "/api/v1/chat-service/conversations", exchange);

        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/chat-service/conversations"));
        assertThat(problem.getProperties()).containsEntry(Problems.REQUEST_ID_PROPERTY, exchange.getRequest().getId());
    }

    @Test
    @DisplayName("the property names are the ones every service uses")
    void propertyNames_shouldMatchTheServices() {
        assertThat(Problems.CODE_PROPERTY).isEqualTo("code");
        assertThat(Problems.TIMESTAMP_PROPERTY).isEqualTo("timestamp");
        assertThat(Problems.REQUEST_ID_PROPERTY).isEqualTo("requestId");
    }
}
