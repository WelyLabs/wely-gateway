package com.calendar.gateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gives the rate limiter's 429 a body: our problem, {@value #CODE}.
 *
 * <p>{@code RequestRateLimiterGatewayFilterFactory} refuses a request by setting the status and
 * completing the response, with no hook for a body. Rather than rewrite that filter, this one
 * sits in front of it and hands it a response whose {@code setComplete()} writes the problem
 * when — and only when — the status is 429. Every other completion goes through untouched, and
 * the {@code X-RateLimit-*} headers the limiter set stay on the response.
 */
@Slf4j
@Component
public class TooManyRequestsProblemFilter implements GatewayFilter {

    /** Too many requests. Stable: a client backs off on it. */
    public static final String CODE = "GTW-REQ-003";

    private final ProblemWriter problemWriter;

    public TooManyRequestsProblemFilter(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                if (!HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode()) || isCommitted()) {
                    return super.setComplete();
                }
                // DEBUG: a looping client is exactly what produces these, one per request.
                log.debug("Too many requests {} on {} (request {})",
                        CODE, exchange.getRequest().getPath(), exchange.getRequest().getId());
                return problemWriter.write(exchange, Problems.of(HttpStatus.TOO_MANY_REQUESTS, CODE,
                        "Too many requests", "The request quota is spent. Retry in a moment.", exchange));
            }
        };

        return chain.filter(exchange.mutate().response(response).build());
    }
}
