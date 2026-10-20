package com.calendar.gateway.application.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_PATH_CONTAINER_ATTR;

/**
 * Covers {@link FallbackController#fallback}.
 */
class FallbackControllerTest {

    private static final String ORIGINAL_PATH = "/api/v1/user-service/profile";

    private final FallbackController controller = new FallbackController();

    /** A forwarded exchange as the breaker leaves it: the request path is the fallback's. */
    private static MockServerWebExchange forwardedExchange(String service) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/fallback/" + service));
    }

    @Test
    @DisplayName("answers 503, not 500")
    void fallback_shouldAnswerServiceUnavailable() {
        // The distinction is the whole point of the breaker: the request was never attempted,
        // nothing is corrupt, and retrying later is the correct response.
        StepVerifier.create(controller.fallback("users", forwardedExchange("users")))
                .assertNext(response ->
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .verifyComplete();
    }

    @Test
    @DisplayName("names the failing service in the body")
    void fallback_shouldNameTheFailingService() {
        StepVerifier.create(controller.fallback("chat", forwardedExchange("chat")))
                .assertNext(response -> {
                    ProblemDetail problem = response.getBody();
                    assertThat(problem).isNotNull();
                    assertThat(problem.getTitle()).isEqualTo("Service unavailable");
                    assertThat(problem.getDetail()).contains("chat");
                    assertThat(problem.getProperties()).containsEntry("service", "chat");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("carries the stable code and the type derived from it, like the services' handlers")
    void fallback_shouldCarryTheStableCodeAndType() {
        // Clients branch on code: it is a contract, so the literal is asserted, not the constant.
        StepVerifier.create(controller.fallback("events", forwardedExchange("events")))
                .assertNext(response -> {
                    ProblemDetail problem = response.getBody();
                    assertThat(problem).isNotNull();
                    assertThat(problem.getProperties()).containsEntry("code", "GTW-TEC-001");
                    assertThat(problem.getType())
                            .isEqualTo(URI.create("https://welylabs.app/problems/gtw-tec-001"));
                    assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("stamps the response with an ISO-8601 timestamp, to correlate with the logs")
    void fallback_shouldStampTheResponseWithATimestamp() {
        Instant before = Instant.now();

        StepVerifier.create(controller.fallback("users", forwardedExchange("users")))
                .assertNext(response -> {
                    ProblemDetail problem = response.getBody();
                    assertThat(problem).isNotNull();
                    assertThat(problem.getProperties()).containsKey("timestamp");
                    Instant stamped = Instant.parse((String) problem.getProperties().get("timestamp"));
                    assertThat(stamped).isBetween(before, Instant.now().plus(Duration.ofSeconds(1)));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("carries the exchange's request id, the one the gateway's own logs use")
    void fallback_shouldCarryTheRequestId() {
        ServerWebExchange exchange = forwardedExchange("social");

        StepVerifier.create(controller.fallback("social", exchange))
                .assertNext(response -> {
                    ProblemDetail problem = response.getBody();
                    assertThat(problem).isNotNull();
                    assertThat(problem.getProperties()).containsKey("requestId");
                    assertThat((String) problem.getProperties().get("requestId"))
                            .isNotBlank()
                            .isEqualTo(exchange.getRequest().getId());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("instance is the client's original path when a filter recorded it (call attempted and failed)")
    void fallback_shouldUseTheOriginalRequestUrl_whenAFilterRecordedIt() {
        MockServerWebExchange exchange = forwardedExchange("users");
        LinkedHashSet<URI> originalUrls = new LinkedHashSet<>(List.of(
                URI.create("http://gateway" + ORIGINAL_PATH + "?x=1"),
                URI.create("http://gateway/user-service/profile")));
        exchange.getAttributes().put(GATEWAY_ORIGINAL_REQUEST_URL_ATTR, originalUrls);
        // Present too in real life; the recorded URL takes precedence.
        exchange.getAttributes().put(GATEWAY_PREDICATE_PATH_CONTAINER_ATTR,
                PathContainer.parsePath("/ignored"));

        StepVerifier.create(controller.fallback("users", exchange))
                .assertNext(response -> {
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().getInstance()).isEqualTo(URI.create(ORIGINAL_PATH));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("instance is the routed path when the circuit was open and no filter ran")
    void fallback_shouldUseTheRoutedPath_whenTheCircuitWasOpen() {
        MockServerWebExchange exchange = forwardedExchange("users");
        exchange.getAttributes().put(GATEWAY_PREDICATE_PATH_CONTAINER_ATTR,
                PathContainer.parsePath(ORIGINAL_PATH));

        StepVerifier.create(controller.fallback("users", exchange))
                .assertNext(response -> {
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().getInstance()).isEqualTo(URI.create(ORIGINAL_PATH));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("instance falls back to the fallback path when the exchange holds no original")
    void fallback_shouldUseTheFallbackPath_whenNoOriginalIsKnown() {
        MockServerWebExchange exchange = forwardedExchange("chat");
        exchange.getAttributes().put(GATEWAY_ORIGINAL_REQUEST_URL_ATTR, new LinkedHashSet<URI>());

        StepVerifier.create(controller.fallback("chat", exchange))
                .assertNext(response -> {
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().getInstance()).isEqualTo(URI.create("/fallback/chat"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("the status on the entity matches the one inside the body")
    void fallback_shouldNotDisagreeWithItself() {
        // A ProblemDetail carries its own status field, and nothing keeps it in step with the
        // ResponseEntity's. A client reading one and a proxy reading the other must agree.
        StepVerifier.create(controller.fallback("social", forwardedExchange("social")))
                .assertNext(response -> {
                    ResponseEntity<ProblemDetail> entity = response;
                    assertThat(entity.getBody()).isNotNull();
                    assertThat(entity.getBody().getStatus()).isEqualTo(entity.getStatusCode().value());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("the routed methods are declared, and only those")
    void fallback_shouldAcceptOnlyTheMethodsTheGatewayRoutes() throws NoSuchMethodException {
        // A bare @RequestMapping also accepts TRACE and HEAD. The five listed are what the
        // gateway proxies, and the breaker forwards the original method unchanged — so a failed
        // POST has to arrive here as a POST rather than meeting a 405.
        RequestMapping mapping = FallbackController.class
                .getMethod("fallback", String.class, ServerWebExchange.class)
                .getAnnotation(RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.method()).containsExactlyInAnyOrder(
                RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                RequestMethod.PATCH, RequestMethod.DELETE);
    }

    @Test
    @DisplayName("declares application/problem+json, whatever the client accepted")
    void fallback_shouldAnswerAsProblemJson() {
        StepVerifier.create(controller.fallback("events", forwardedExchange("events")))
                .assertNext(response -> {
                    assertThat(response.getHeaders().getContentType())
                            .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
                    assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
                })
                .verifyComplete();
    }
}
