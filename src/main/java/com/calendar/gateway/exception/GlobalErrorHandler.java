package com.calendar.gateway.exception;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Answers every error that reaches the web layer as our RFC 7807 problem.
 *
 * <p>The services translate their errors in a {@code @RestControllerAdvice}. The gateway has
 * almost no controller to advise: its errors come from the dispatcher (no route matched, a
 * method the fallback does not take) and from the gateway filters (a downstream that refuses
 * the connection on a route the breaker does not cover). All of those reach the
 * {@link WebExceptionHandler} chain, where Boot's {@code DefaultErrorWebExceptionHandler}, at
 * order -1, would answer them in its own JSON — without {@code code} or {@code requestId}, so a
 * client could not branch on them. Ordered at -2, this one answers first.
 *
 * <p>Mapping, as in the services' handler:
 * <ul>
 *   <li>a {@link ResponseStatusException} the framework raised keeps its status. A 4xx is the
 *       request's fault: {@value #REQUEST_REFUSED_CODE}, logged at DEBUG. A 5xx — the gateway
 *       timing out on a downstream, say — is ours: {@value #UNEXPECTED_FAILURE_CODE}, logged at
 *       ERROR and counted. The services answer every status exception with their REQ code; the
 *       gateway differs because it is the one place a 5xx status exception is raised for a
 *       failure rather than for a bad request;</li>
 *   <li>anything else is a 500 {@value #UNEXPECTED_FAILURE_CODE}, logged at ERROR with its
 *       cause and counted. The detail stays generic: an exception message can carry a host or a
 *       URL, and that belongs in the logs, not in a response.</li>
 * </ul>
 */
@Slf4j
@Component
@Order(-2)
public class GlobalErrorHandler implements WebExceptionHandler {

    /** A request the framework refused as sent: unknown path, method not taken, and so on. */
    public static final String REQUEST_REFUSED_CODE = "GTW-REQ-000";

    /** A failure the gateway did not anticipate, or a 5xx status the framework raised. */
    public static final String UNEXPECTED_FAILURE_CODE = "GTW-TEC-000";

    /**
     * The codes this handler answers with. The services list three; the gateway validates no
     * request body, so it has no {@code VALIDATION_FAILED_CODE}.
     */
    public static final List<String> HANDLER_CODES = List.of(REQUEST_REFUSED_CODE, UNEXPECTED_FAILURE_CODE);

    /** Counter of technical failures, tagged by code. Same name in every service. */
    public static final String TECHNICAL_ERRORS_METRIC = "wely.errors.technical";

    /** The tag carrying the problem code on {@value #TECHNICAL_ERRORS_METRIC}. */
    public static final String CODE_TAG = "code";

    private final ProblemWriter problemWriter;
    private final MeterRegistry meterRegistry;

    public GlobalErrorHandler(ProblemWriter problemWriter, MeterRegistry meterRegistry) {
        this.problemWriter = problemWriter;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            // Headers are on the wire: nothing can be answered any more. Let the server close it.
            return Mono.error(ex);
        }

        String path = exchange.getRequest().getPath().value();
        String requestId = exchange.getRequest().getId();
        ProblemDetail problem;

        if (ex instanceof ResponseStatusException statusException
                && statusException.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = statusException.getStatusCode();
            log.debug("Request refused {} on {} (request {})", status.value(), path, requestId);
            problem = Problems.of(status, REQUEST_REFUSED_CODE, reasonPhrase(status),
                    "The request could not be served as sent.", exchange);
        } else {
            HttpStatusCode status = ex instanceof ResponseStatusException statusException
                    ? statusException.getStatusCode()
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            // Someone has to act on it: ERROR with the cause, and counted.
            log.error("Technical failure {} on {} (request {})", UNEXPECTED_FAILURE_CODE, path, requestId, ex);
            meterRegistry.counter(TECHNICAL_ERRORS_METRIC, CODE_TAG, UNEXPECTED_FAILURE_CODE).increment();
            problem = Problems.of(status, UNEXPECTED_FAILURE_CODE, "Unexpected error",
                    "The request could not be completed.", exchange);
        }

        return problemWriter.write(exchange, problem);
    }

    private static String reasonPhrase(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Request refused";
    }
}
