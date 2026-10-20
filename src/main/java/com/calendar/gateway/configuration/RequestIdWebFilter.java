package com.calendar.gateway.configuration;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Gives every request one id, from the gateway to the last service's logs.
 *
 * <p>The id is generated here, always: a client-supplied {@value #REQUEST_ID_HEADER} is
 * overwritten, because an id the client chooses can collide with another request's or be
 * crafted to muddle an investigation, and the point of the id is to be trusted when correlating
 * logs. It goes:
 * <ul>
 *   <li>on the forwarded request, so each service logs and answers with the same id (they keep
 *       a well-formed incoming id rather than making their own);</li>
 *   <li>on the response, so a user reporting an error can quote it;</li>
 *   <li>into the exchange attribute {@value #REQUEST_ID_ATTRIBUTE}, where every problem and log
 *       line of the gateway reads it — see {@code exception.RequestIds}.</li>
 * </ul>
 *
 * <p>A {@link WebFilter} rather than a gateway {@code GlobalFilter}: a global filter only runs on
 * a request that matched a route, and the 401s, the "no route" 404s and the firewall's 400s are
 * answered before or without one. At the highest precedence, it runs before Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    /** The header carrying the id, on the forwarded request and on the response. Same name in every service. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** The exchange attribute holding the id. Same key in every service. */
    public static final String REQUEST_ID_ATTRIBUTE = "wely.requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
                .build();
        ServerWebExchange identified = exchange.mutate().request(request).build();
        identified.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);

        // Set at commit time rather than now: the proxied response's headers are added to ours
        // (addAll), so a service echoing the id would otherwise leave it there twice.
        identified.getResponse().beforeCommit(() -> {
            identified.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
            return Mono.empty();
        });

        return chain.filter(identified);
    }
}
