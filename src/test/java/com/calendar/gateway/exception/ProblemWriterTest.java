package com.calendar.gateway.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link ProblemWriter#write} and {@link ProblemWriter#holdingCompletion}.
 */
class ProblemWriterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ProblemWriter writer = new ProblemWriter(ServerCodecConfigurer.create());
    private final MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/p"));

    @Test
    @DisplayName("writes status, problem+json and the body with its properties flattened")
    void write_shouldWriteTheProblemAsProblemJson() {
        StepVerifier.create(writer.write(exchange, Problems.of(HttpStatus.TOO_MANY_REQUESTS, "GTW-REQ-003",
                        "Too many requests", "d", exchange)))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"GTW-REQ-003\"")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"")
                        .contains("\"status\":429")
                        .doesNotContain("\"properties\""))
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    @DisplayName("keeps headers already set on the response")
    void write_shouldKeepExistingHeaders() {
        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", "0");

        StepVerifier.create(writer.write(exchange, Problems.of(HttpStatus.TOO_MANY_REQUESTS, "GTW-REQ-003",
                        "t", "d", exchange)))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    @DisplayName("holdingCompletion: headers reach the real response, completion does not")
    void holdingCompletion_shouldPassHeadersButHoldTheCompletion() {
        ServerWebExchange held = ProblemWriter.holdingCompletion(exchange);
        held.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        held.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");

        StepVerifier.create(held.getResponse().setComplete()).expectComplete().verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(exchange.getResponse().isCommitted()).isFalse();
    }
}
