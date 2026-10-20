package com.calendar.gateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The 403: authenticated, but not allowed. Answers our problem, {@value #CODE}.
 *
 * <p>The gateway's rules only ask for authentication, so no rule denies an authenticated caller
 * today; this keeps the day one does from answering in a shape clients cannot read. As with the
 * 401, the resource server's handler still writes its {@code WWW-Authenticate} header.
 */
@Slf4j
@Component
public class ProblemAccessDeniedHandler implements ServerAccessDeniedHandler {

    /** Access denied. */
    public static final String CODE = "GTW-REQ-002";

    private final ServerAccessDeniedHandler bearerHandler = new BearerTokenServerAccessDeniedHandler();
    private final ProblemWriter problemWriter;

    public ProblemAccessDeniedHandler(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        // Rare by construction, and worth seeing when it happens.
        log.warn("Access denied {} on {} (request {})",
                CODE, exchange.getRequest().getPath(), RequestIds.of(exchange));

        ServerWebExchange headersOnly = ProblemWriter.holdingCompletion(exchange);

        return bearerHandler.handle(headersOnly, denied)
                .then(Mono.defer(() -> problemWriter.write(exchange, Problems.of(HttpStatus.FORBIDDEN, CODE,
                        "Access denied", "The request is not allowed for this caller.", exchange))));
    }
}
