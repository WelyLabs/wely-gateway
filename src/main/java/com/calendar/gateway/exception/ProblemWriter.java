package com.calendar.gateway.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Writes a {@link ProblemDetail} straight onto an exchange, as {@code application/problem+json}.
 *
 * <p>For the places that answer outside a controller — security handlers, a gateway filter, the
 * web exception handler — where no return value gets serialised for them. It goes through the
 * application's own codecs rather than a mapper of its own, so the body is serialised exactly as
 * a controller's {@code ProblemDetail} is: same Jackson configuration, same flattened properties.
 *
 * <p>Headers already on the response are kept: the 401 relies on that for
 * {@code WWW-Authenticate}, the 429 for the {@code X-RateLimit-*} headers.
 */
@Component
public class ProblemWriter {

    private final List<HttpMessageWriter<?>> messageWriters;

    public ProblemWriter(ServerCodecConfigurer codecs) {
        this.messageWriters = codecs.getWriters();
    }

    public Mono<Void> write(ServerWebExchange exchange, ProblemDetail problem) {
        ServerResponse.Context context = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return messageWriters;
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };

        return ServerResponse.status(HttpStatusCode.valueOf(problem.getStatus()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(problem)
                .flatMap(response -> response.writeTo(exchange, context));
    }

    /**
     * The same exchange, on a response whose {@code setComplete()} does nothing.
     *
     * <p>For handing to a Spring component that sets the status and headers we want — the
     * resource server's {@code WWW-Authenticate}, say — and then completes the response, which
     * would leave no room for the body. The headers land on the real response; the completion
     * is left to {@link #write}.
     */
    public static ServerWebExchange holdingCompletion(ServerWebExchange exchange) {
        return exchange.mutate()
                .response(new ServerHttpResponseDecorator(exchange.getResponse()) {
                    @Override
                    public Mono<Void> setComplete() {
                        return Mono.empty();
                    }
                })
                .build();
    }
}
