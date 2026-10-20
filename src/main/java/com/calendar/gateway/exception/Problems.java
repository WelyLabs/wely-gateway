package com.calendar.gateway.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.time.Instant;

/**
 * Builds every RFC 7807 problem the gateway answers, in the shape the services use.
 *
 * <p>The services build theirs in their {@code GlobalErrorHandler}. The gateway answers errors
 * from five places — the fallback controller, the two security handlers, the rate limit and the
 * web exception handler — so the builder is shared here instead, and none of them can drift
 * from the others: same {@code type} namespace, same {@code code}, {@code timestamp} and
 * {@code requestId} properties.
 */
public final class Problems {

    /** Namespace for the {@code type} URI. Not dereferenced; it identifies, it does not serve. */
    public static final String PROBLEM_TYPE_BASE = "https://welylabs.app/problems/";

    /** The stable identifier a client branches on. */
    public static final String CODE_PROPERTY = "code";

    /** When the problem was built, to correlate with the logs. */
    public static final String TIMESTAMP_PROPERTY = "timestamp";

    /** The exchange's request id, the one the gateway's own log lines carry. */
    public static final String REQUEST_ID_PROPERTY = "requestId";

    private Problems() {
    }

    /** A problem whose {@code instance} is the path of the request being answered. */
    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail,
                                   ServerWebExchange exchange) {
        return of(status, code, title, detail, exchange.getRequest().getPath().value(), exchange);
    }

    /**
     * A problem whose {@code instance} is given: the fallback names the path the client called,
     * not the internal forward it is answering.
     */
    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail,
                                   String instance, ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.toLowerCase()));
        problem.setTitle(title);
        problem.setInstance(URI.create(instance));
        problem.setProperty(CODE_PROPERTY, code);
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now().toString());
        problem.setProperty(REQUEST_ID_PROPERTY, exchange.getRequest().getId());

        return problem;
    }
}
