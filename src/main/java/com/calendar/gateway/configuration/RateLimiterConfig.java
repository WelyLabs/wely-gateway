package com.calendar.gateway.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Per-caller request quota, held in Redis.
 *
 * <p>Redis rather than an in-memory counter because the gateway is meant to scale: a counter
 * per pod means the real quota is the configured one multiplied by the number of replicas, and
 * it moves every time the deployment scales.
 *
 * <p>A Redis outage does not block traffic. {@code RedisRateLimiter} catches the failure and
 * answers {@code allowed}, logging the error — so the worst case is unmetered traffic for the
 * duration of the outage, not an unreachable platform. Which is the right trade for a quota,
 * and the reason this is worth alerting on rather than trusting silently.
 */
@Configuration
public class RateLimiterConfig {

    /** Sustained rate, in requests per second, granted to one caller. */
    private final int replenishRate;

    /** How large a burst that caller may spend at once. */
    private final int burstCapacity;

    public RateLimiterConfig(@Value("${gateway.rate-limit.replenish-rate:20}") int replenishRate,
                             @Value("${gateway.rate-limit.burst-capacity:40}") int burstCapacity) {
        this.replenishRate = replenishRate;
        this.burstCapacity = burstCapacity;
    }

    @Bean
    public RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(replenishRate, burstCapacity);
    }

    /**
     * Keys the quota on the authenticated user, falling back to the caller's address.
     *
     * <p>The JWT subject, not the IP: behind a Cloudflare tunnel every request arrives from the
     * same handful of addresses, so an IP quota would be shared by everyone and one heavy user
     * would throttle the rest. The address is only the fallback for a request that carries no
     * token — which, past the security filter chain, means little more than the actuator
     * endpoints.
     *
     * <p>Never returns an empty {@code Mono}: the gateway reads that as "no key" and answers
     * 403, which would turn an unresolvable key into a rejected request.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> token.getToken().getSubject())
                .defaultIfEmpty(callerAddress(exchange));
    }

    private static String callerAddress(ServerWebExchange exchange) {
        return Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(address -> address.getHostAddress())
                .orElse("unknown");
    }
}
