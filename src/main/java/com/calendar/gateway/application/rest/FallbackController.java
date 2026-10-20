package com.calendar.gateway.application.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_PATH_CONTAINER_ATTR;

/**
 * What a caller gets when a service's circuit breaker is open.
 *
 * <p>Answers {@link HttpStatus#SERVICE_UNAVAILABLE} as a {@link ProblemDetail}, in the same
 * RFC 7807 shape as every service behind the gateway — same {@code type} namespace, same
 * {@code code}, {@code timestamp} and {@code requestId} properties — so a client has one error
 * format to parse and branches on {@code code} rather than special-casing outages.
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
@Slf4j
@RestController
public class FallbackController {

    /** The stable identifier clients branch on. Part of the API contract: never renumber it. */
    static final String CODE = "GTW-TEC-001";

    /** Same namespace as the services' handlers: the code, lowercased. Identifies, not served. */
    private static final URI TYPE = URI.create("https://welylabs.app/problems/" + CODE.toLowerCase());

    @RequestMapping(
            value = "/fallback/{service}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                      RequestMethod.PATCH, RequestMethod.DELETE})
    public Mono<ResponseEntity<ProblemDetail>> fallback(@PathVariable String service,
                                                        ServerWebExchange exchange) {
        String requestId = exchange.getRequest().getId();
        String path = originalPath(exchange);

        // A downstream service unreachable is an incident someone has to act on: ERROR, as the
        // services' technical handler does. No stack trace — the breaker already decided, and
        // the cause (refused connection, timeout, open circuit) adds noise, not information.
        log.error("Fallback {} for service {} on {} (request {})", CODE, service, path, requestId);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(TYPE);
        problem.setTitle("Service unavailable");
        problem.setDetail("The %s service is not responding. Try again shortly.".formatted(service));
        problem.setInstance(URI.create(path));
        problem.setProperty("code", CODE);
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("requestId", requestId);
        problem.setProperty("service", service);

        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem));
    }

    /**
     * The path the client actually called, which is what {@code instance} should name: the
     * caller never sent {@code /fallback/users} and cannot correlate it with anything.
     *
     * <p>The breaker forwards with a rewritten URI, so the exchange's own request path is the
     * fallback's. Two gateway attributes still hold the original, and each covers a case the
     * other misses:
     * <ul>
     *   <li>{@code GATEWAY_ORIGINAL_REQUEST_URL_ATTR} — the client URL before any filter rewrote
     *       it. Set by {@code stripPrefix}, which runs inside the breaker: present when the call
     *       was attempted and failed, absent when the circuit was already open.</li>
     *   <li>{@code GATEWAY_PREDICATE_PATH_CONTAINER_ATTR} — the raw path the {@code Path}
     *       predicate matched during routing, so present even with the circuit open.</li>
     * </ul>
     * The fallback path is the last resort, for a request reaching this controller directly.
     */
    private static String originalPath(ServerWebExchange exchange) {
        Set<URI> originalUrls = exchange.getAttribute(GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (originalUrls != null && !originalUrls.isEmpty()) {
            return originalUrls.iterator().next().getRawPath();
        }
        PathContainer matchedPath = exchange.getAttribute(GATEWAY_PREDICATE_PATH_CONTAINER_ATTR);
        if (matchedPath != null) {
            return matchedPath.value();
        }
        return exchange.getRequest().getPath().value();
    }
}
