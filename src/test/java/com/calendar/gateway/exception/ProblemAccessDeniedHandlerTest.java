package com.calendar.gateway.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link ProblemAccessDeniedHandler#handle}. No rule of the gateway denies an
 * authenticated caller today, so there is no end-to-end path to drive it through.
 */
class ProblemAccessDeniedHandlerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ProblemAccessDeniedHandler handler =
            new ProblemAccessDeniedHandler(new ProblemWriter(ServerCodecConfigurer.create()));

    @Test
    @DisplayName("403, our problem, and the resource server's Bearer challenge")
    void handle_shouldAnswerOurProblem() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.delete("/api/v1/y"));

        StepVerifier.create(handler.handle(exchange, new AccessDeniedException("denied")))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"GTW-REQ-002\"")
                        .contains("\"status\":403")
                        .contains("\"instance\":\"/api/v1/y\"")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\""))
                .expectComplete()
                .verify(TIMEOUT);
    }
}
