package com.calendar.gateway.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.firewall.ServerExchangeRejectedException;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link ProblemExchangeRejectedHandler#handle}. That the security chain uses it is
 * covered end to end in {@code WebFluxSecurityConfigTest}.
 */
class ProblemExchangeRejectedHandlerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ProblemExchangeRejectedHandler handler =
            new ProblemExchangeRejectedHandler(new ProblemWriter(ServerCodecConfigurer.create()));

    @Test
    @DisplayName("400, REQ-000, and the firewall's reason kept out of the body")
    void handle_shouldAnswerOurProblem() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a"));

        StepVerifier.create(handler.handle(exchange,
                        new ServerExchangeRejectedException("The request was rejected because the URL contained \"..\"")))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"GTW-REQ-000\"")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"")
                        .doesNotContain("rejected because"))
                .expectComplete()
                .verify(TIMEOUT);
    }
}
