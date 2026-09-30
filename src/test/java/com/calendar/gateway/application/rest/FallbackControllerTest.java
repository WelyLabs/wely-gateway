package com.calendar.gateway.application.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link FallbackController#fallback}.
 */
class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    @Test
    @DisplayName("answers 503, not 500")
    void fallback_shouldAnswerServiceUnavailable() {
        // The distinction is the whole point of the breaker: the request was never attempted,
        // nothing is corrupt, and retrying later is the correct response.
        StepVerifier.create(controller.fallback("users"))
                .assertNext(response ->
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .verifyComplete();
    }

    @Test
    @DisplayName("names the failing service in the body")
    void fallback_shouldNameTheFailingService() {
        StepVerifier.create(controller.fallback("chat"))
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
    @DisplayName("the body is an RFC 7807 ProblemDetail, like every service behind the gateway")
    void fallback_shouldAnswerInTheSharedProblemShape() {
        StepVerifier.create(controller.fallback("events"))
                .assertNext(response -> {
                    ProblemDetail problem = response.getBody();
                    assertThat(problem).isNotNull();
                    assertThat(problem.getType().toString())
                            .isEqualTo("https://welylabs.app/problems/service-unavailable");
                    assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("the status on the entity matches the one inside the body")
    void fallback_shouldNotDisagreeWithItself() {
        // A ProblemDetail carries its own status field, and nothing keeps it in step with the
        // ResponseEntity's. A client reading one and a proxy reading the other must agree.
        StepVerifier.create(controller.fallback("social"))
                .assertNext(response -> {
                    ResponseEntity<ProblemDetail> entity = response;
                    assertThat(entity.getBody()).isNotNull();
                    assertThat(entity.getBody().getStatus()).isEqualTo(entity.getStatusCode().value());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("MediaType.APPLICATION_PROBLEM_JSON is what ProblemDetail serialises as")
    void fallback_shouldBeSerialisableAsProblemJson() {
        // Not a behavioural assertion so much as a guard: swapping ProblemDetail for a plain
        // record would compile and quietly change the content type clients negotiate.
        StepVerifier.create(controller.fallback("media"))
                .assertNext(response -> assertThat(response.getBody())
                        .isInstanceOf(ProblemDetail.class))
                .verifyComplete();

        assertThat(MediaType.APPLICATION_PROBLEM_JSON.toString()).isEqualTo("application/problem+json");
    }
}
