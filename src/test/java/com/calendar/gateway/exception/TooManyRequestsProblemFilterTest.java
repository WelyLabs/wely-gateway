package com.calendar.gateway.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link TooManyRequestsProblemFilter#filter}, with a chain standing in for the rate
 * limiter: it does exactly what {@code RequestRateLimiterGatewayFilterFactory} does on a
 * refusal — set the status, add its headers, complete the response.
 */
class TooManyRequestsProblemFilterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final TooManyRequestsProblemFilter filter =
            new TooManyRequestsProblemFilter(new ProblemWriter(ServerCodecConfigurer.create()));

    private static GatewayFilterChain completingWith(HttpStatus status) {
        return exchange -> {
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
            return exchange.getResponse().setComplete();
        };
    }

    @Test
    @DisplayName("the limiter's 429 leaves with our problem, its headers kept")
    void filter_shouldWriteOurProblem_whenTheLimiterRefuses() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/user-service/me"));

        StepVerifier.create(filter.filter(exchange, completingWith(HttpStatus.TOO_MANY_REQUESTS)))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"GTW-REQ-003\"")
                        .contains("\"instance\":\"/api/v1/user-service/me\"")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\""))
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    @DisplayName("any other completion goes through untouched, with no body")
    void filter_shouldLeaveOtherCompletionsAlone() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        StepVerifier.create(filter.filter(exchange, completingWith(HttpStatus.NOT_FOUND)))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNull();
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .expectNext("")
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    @DisplayName("an allowed request passes through: the downstream writes its own response")
    void filter_shouldNotInterfere_whenTheRequestIsAllowed() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        GatewayFilterChain downstream = chained -> {
            chained.getResponse().setStatusCode(HttpStatus.OK);
            return chained.getResponse().writeWith(reactor.core.publisher.Mono.just(
                    chained.getResponse().bufferFactory().wrap("ok".getBytes())));
        };

        StepVerifier.create(filter.filter(exchange, downstream))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .expectNext("ok")
                .expectComplete()
                .verify(TIMEOUT);
    }
}
