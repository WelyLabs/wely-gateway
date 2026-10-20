package com.calendar.gateway.exception;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Covers {@link GlobalErrorHandler#handle}: directly for the mapping, and in the full context for
 * the one thing a unit test cannot show — that it answers before Spring does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GlobalErrorHandlerTest.ThrowingFilterConfig.class)
class GlobalErrorHandlerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String THROWING_PATH = "/test-only/throwing-filter";

    @TestConfiguration
    static class ThrowingFilterConfig {

        /** An exception raised before any handler is chosen, as a gateway filter would. */
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        WebFilter throwingFilter() {
            return (exchange, chain) -> THROWING_PATH.equals(exchange.getRequest().getPath().value())
                    ? Mono.error(new IllegalStateException("jdbc://user:s3cr3t@db failed"))
                    : chain.filter(exchange);
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry contextMeterRegistry;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    private WebTestClient webTestClient;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GlobalErrorHandler handler =
            new GlobalErrorHandler(new ProblemWriter(ServerCodecConfigurer.create()), meterRegistry);

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static double technicalErrors(MeterRegistry registry) {
        var counter = registry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC)
                .tag(GlobalErrorHandler.CODE_TAG, GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    // --- in the full context: our shape, not Spring's ---------------------------------------

    @Test
    @DisplayName("no route matched: 404 in our shape, not Spring's")
    void handle_shouldAnswerOurProblem_whenNoRouteMatches() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/no/such/route")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE)
                .jsonPath("$.type").isEqualTo("https://welylabs.app/problems/gtw-req-000")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.instance").isEqualTo("/no/such/route")
                .jsonPath("$.requestId").isNotEmpty()
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    @DisplayName("an exception raised in a web filter: 500 in our shape, counted, nothing leaked")
    void handle_shouldAnswerOurProblem_whenAWebFilterThrows() {
        double before = technicalErrors(contextMeterRegistry);

        webTestClient.mutateWith(mockJwt())
                .get().uri(THROWING_PATH)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .jsonPath("$.detail").isEqualTo("The request could not be completed.")
                .jsonPath("$.requestId").isNotEmpty()
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("s3cr3t"));

        assertThat(technicalErrors(contextMeterRegistry)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("a downstream the breaker does not cover refuses the connection: our shape, counted")
    void handle_shouldAnswerOurProblem_whenAnUnguardedRouteFails() {
        // The notifications stream is deliberately outside the breaker, and the test profile
        // points it at a closed port.
        double before = technicalErrors(contextMeterRegistry);

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/notifications-service/notifications/stream")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .jsonPath("$.instance").isEqualTo("/api/v1/notifications-service/notifications/stream")
                .jsonPath("$.requestId").isNotEmpty();

        assertThat(technicalErrors(contextMeterRegistry)).isEqualTo(before + 1);
    }

    // --- the mapping, directly ------------------------------------------------------------------

    @Test
    @DisplayName("a 4xx status exception keeps its status, answers REQ-000 and is not counted")
    void handle_shouldAnswerRequestRefused_whenTheStatusIsAClientError() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        StepVerifier.create(handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE)))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"GTW-REQ-000\"")
                        .contains("\"title\":\"Not Acceptable\"")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\""))
                .expectComplete()
                .verify(TIMEOUT);
        assertThat(technicalErrors(meterRegistry)).isZero();
    }

    @Test
    @DisplayName("a 5xx status exception keeps its status but is technical: TEC-000, counted")
    void handle_shouldAnswerTechnical_whenTheStatusIsAServerError() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        StepVerifier.create(handler.handle(exchange, new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT)))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body).contains("\"code\":\"GTW-TEC-000\""))
                .expectComplete()
                .verify(TIMEOUT);
        assertThat(technicalErrors(meterRegistry)).isEqualTo(1);
    }

    @Test
    @DisplayName("any other exception is a 500 TEC-000 with a generic detail, counted")
    void handle_shouldAnswerUnexpectedFailure_whenTheExceptionIsUnknown() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/y"));

        StepVerifier.create(handler.handle(exchange, new IllegalStateException("host db-1:5432 refused")))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"GTW-TEC-000\"")
                        .contains("\"instance\":\"/y\"")
                        .doesNotContain("db-1"))
                .expectComplete()
                .verify(TIMEOUT);
        assertThat(technicalErrors(meterRegistry)).isEqualTo(1);
    }

    @Test
    @DisplayName("a response already committed cannot be answered: the error is passed on")
    void handle_shouldPassTheErrorOn_whenTheResponseIsCommitted() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/z"));
        StepVerifier.create(exchange.getResponse().setComplete()).expectComplete().verify(TIMEOUT);
        IllegalStateException failure = new IllegalStateException("late");

        StepVerifier.create(handler.handle(exchange, failure))
                .expectErrorMatches(error -> error == failure)
                .verify(TIMEOUT);
    }

    @Test
    @DisplayName("the handler codes follow the convention and are distinct")
    void handlerCodes_shouldFollowTheConvention() {
        assertThat(GlobalErrorHandler.HANDLER_CODES)
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("GTW-(BUS|VAL|REQ|TEC)-\\d{3}"));
    }
}
