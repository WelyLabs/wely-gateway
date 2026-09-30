package com.calendar.gateway.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.builder.UriSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/**
 * The routing table.
 *
 * <p>Extracted from {@code GatewayApplication}, which declared the routes and six
 * {@code @Value} fields inside the class annotated {@code @SpringBootApplication}. The other
 * five services keep their beans under {@code configuration/}; there was no reason for this
 * one to differ.
 *
 * <h2>Filter order on a REST route</h2>
 *
 * <ol>
 *   <li><b>Rate limiter</b> first, so a caller flooding the gateway is turned away before
 *       anything downstream is touched — including the circuit breaker, whose failure budget
 *       would otherwise be spent on traffic that should never have been forwarded.</li>
 *   <li><b>Circuit breaker</b>, which stops sending requests to a service that is failing and
 *       answers from {@code FallbackController} instead. Without it, a service that has
 *       stopped responding holds a gateway connection open for every waiting caller until
 *       each times out, and the gateway runs out of connections before the service recovers —
 *       one failing service taking the whole platform down with it.</li>
 *   <li><b>stripPrefix(2)</b>, removing {@code /api/v1} but keeping {@code /<service>}, which
 *       each service reattaches through its {@code WebConfig}. A service therefore answers on
 *       the same path whether it is reached through the gateway or directly.</li>
 *   <li><b>retry(3)</b>, which by default covers GET only and so cannot replay a write. The
 *       retries happen inside the breaker, so a route that is genuinely down trips it sooner
 *       rather than later — which is the behaviour to want.</li>
 * </ol>
 */
@Configuration
public class RouteConfig {

    private final String usersApiUrl;
    private final String socialApiUrl;
    private final String chatApiUrl;
    private final String chatRSocketUrl;
    private final String eventsApiUrl;

    private final RateLimiter<?> rateLimiter;
    private final KeyResolver keyResolver;

    public RouteConfig(RateLimiter<?> rateLimiter,
                       KeyResolver keyResolver,
                       @Value("${users.api.url}") String usersApiUrl,
                       @Value("${social.api.url}") String socialApiUrl,
                       @Value("${chat.api.url}") String chatApiUrl,
                       @Value("${chat.rsocket.url}") String chatRSocketUrl,
                       @Value("${events.api.url}") String eventsApiUrl) {
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.usersApiUrl = usersApiUrl;
        this.socialApiUrl = socialApiUrl;
        this.chatApiUrl = chatApiUrl;
        this.chatRSocketUrl = chatRSocketUrl;
        this.eventsApiUrl = eventsApiUrl;
    }

    @Bean
    public RouteLocator configureRoute(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("calendar-users-api", r -> r
                        .path("/api/v1/user-service/**")
                        .filters(proxyFilters("users"))
                        .uri(usersApiUrl))
                .route("calendar-social-api", r -> r
                        .path("/api/v1/social-service/**")
                        .filters(proxyFilters("social"))
                        .uri(socialApiUrl))
                .route("calendar-chat-api", r -> r
                        .path("/api/v1/chat-service/**")
                        .filters(proxyFilters("chat"))
                        .uri(chatApiUrl))
                // No breaker and no rate limiter on the RSocket route, and no retry either.
                // All three count requests, and a WebSocket is one request that then lives for
                // as long as the browser tab: rate limiting it would cap simultaneous chat
                // users rather than call volume, and the breaker's filter reads the exchange in
                // a way that breaks the upgrade handshake. Protecting a long-lived connection
                // is a different problem, and counting HTTP requests is not the answer to it.
                .route("chat-rsocket-route", r -> r
                        .path("/rsocket/**", "/rsocket")
                        .uri(chatRSocketUrl))
                .route("calendar-events-api", r -> r
                        .path("/api/v1/events-service/**")
                        .filters(proxyFilters("events"))
                        .uri(eventsApiUrl))
                .build();
    }

    /**
     * The filter chain every REST route shares. One method rather than six copies: the
     * previous version repeated {@code stripPrefix(2).retry(3)} on each route, so adding the
     * breaker would have meant editing five places and getting all five right.
     */
    private Function<GatewayFilterSpec, UriSpec> proxyFilters(String service) {
        return f -> f
                .requestRateLimiter(config -> config
                        .setRateLimiter(rateLimiter)
                        .setKeyResolver(keyResolver))
                .circuitBreaker(config -> config
                        .setName(service + "CircuitBreaker")
                        .setFallbackUri("forward:/fallback/" + service))
                .stripPrefix(2)
                .retry(3);
    }
}
