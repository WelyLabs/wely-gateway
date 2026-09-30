package com.calendar.gateway.application.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * What a caller gets when a service's circuit breaker is open.
 *
 * <p>Answers {@link HttpStatus#SERVICE_UNAVAILABLE} as a {@link ProblemDetail}, in the same
 * RFC 7807 shape as every service behind the gateway, so a client has one error format to
 * parse rather than a special case for outages.
 *
 * <p>503 and not 500: the distinction is the whole point of the breaker. The request was never
 * attempted, nothing is corrupt, and retrying later is the correct response — which is what a
 * client, and the browser, can act on. A 500 would say the opposite.
 *
 * <p>The methods are listed rather than left open, because the breaker forwards the original
 * request unchanged: a failed POST arrives here as a POST, and answering it with 405 would hide
 * the outage behind a nonsense status. These five are what the gateway routes — a bare
 * {@code @RequestMapping} would also accept TRACE and HEAD, which nothing sends and which
 * Sonar's java:S3752 rightly flags as a wider surface than intended.
 */
@RestController
public class FallbackController {

    private static final URI TYPE = URI.create("https://welylabs.app/problems/service-unavailable");

    @RequestMapping(
            value = "/fallback/{service}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                      RequestMethod.PATCH, RequestMethod.DELETE})
    public Mono<ResponseEntity<ProblemDetail>> fallback(@PathVariable String service) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(TYPE);
        problem.setTitle("Service unavailable");
        problem.setDetail("The %s service is not responding. Try again shortly.".formatted(service));
        problem.setProperty("service", service);

        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem));
    }
}
