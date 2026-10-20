package com.calendar.gateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The 401: no token, or one that does not validate. Answers our problem,
 * {@value #CODE}, instead of Spring Security's empty body.
 *
 * <p>The {@code WWW-Authenticate: Bearer …} header is kept, and is not rebuilt here: the
 * resource server's own entry point writes it — with the {@code error} and
 * {@code error_description} of an invalid token when there is one — and this class lets it,
 * on a response whose {@code setComplete()} is held back so the body can still be written.
 */
@Slf4j
@Component
public class ProblemAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    /** Authentication required. Stable: clients branch on it to send the user to sign in. */
    public static final String CODE = "GTW-REQ-001";

    private final ServerAuthenticationEntryPoint bearerEntryPoint = new BearerTokenServerAuthenticationEntryPoint();
    private final ProblemWriter problemWriter;

    public ProblemAuthenticationEntryPoint(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        // An expired token is routine, and a client retrying with one would flood WARN: DEBUG.
        log.debug("Authentication required {} on {} (request {})",
                CODE, exchange.getRequest().getPath(), RequestIds.of(exchange));

        ServerWebExchange headersOnly = ProblemWriter.holdingCompletion(exchange);

        return bearerEntryPoint.commence(headersOnly, ex)
                .then(Mono.defer(() -> problemWriter.write(exchange, Problems.of(HttpStatus.UNAUTHORIZED, CODE,
                        "Authentication required", "A valid bearer token is required.", exchange))));
    }
}
