package com.calendar.gateway.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers both beans of {@link RateLimiterConfig}.
 */
class RateLimiterConfigTest {

    private static final int REPLENISH_RATE = 20;
    private static final int BURST_CAPACITY = 40;

    private RateLimiterConfig config;

    @BeforeEach
    void setUp() {
        config = new RateLimiterConfig(REPLENISH_RATE, BURST_CAPACITY);
    }

    private static ServerWebExchange exchangeFrom(String address) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/user-service/profile")
                .remoteAddress(new InetSocketAddress(address, 45000))
                .build();
        return MockServerWebExchange.from(request);
    }

    private static Jwt jwtFor(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("businessId", "b-1")
                .build();
    }

    @Test
    @DisplayName("the limiter carries the configured rate and burst")
    void redisRateLimiter_shouldCarryTheConfiguredRates() {
        RedisRateLimiter limiter = config.redisRateLimiter();

        // Read reflectively because RedisRateLimiter.getDefaultConfig() is package-private and
        // there is no public accessor. Asserting the values matters more than avoiding the
        // reflection: they are what a caller's quota actually is, and a swapped pair of
        // constructor arguments would otherwise go unnoticed.
        RedisRateLimiter.Config defaults =
                (RedisRateLimiter.Config) ReflectionTestUtils.getField(limiter, "defaultConfig");

        assertThat(defaults).isNotNull();
        assertThat(defaults.getReplenishRate()).isEqualTo(REPLENISH_RATE);
        assertThat(defaults.getBurstCapacity()).isEqualTo(BURST_CAPACITY);
    }

    @Test
    @DisplayName("no per-route override is declared")
    void redisRateLimiter_shouldDeclareNoRouteSpecificQuota() {
        // Every route shares one quota on purpose: a per-route quota would let a caller spend
        // its full allowance on each of the five services at once.
        assertThat(config.redisRateLimiter().getConfig()).isEmpty();
    }

    @Test
    @DisplayName("an authenticated caller is keyed on the JWT subject")
    void userKeyResolver_shouldKeyOnTheJwtSubject() {
        // The subject and not the address: behind a Cloudflare tunnel every request arrives
        // from the same few addresses, so an IP quota would be shared by all users at once.
        KeyResolver resolver = config.userKeyResolver();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwtFor("user-42"), List.of());

        StepVerifier.create(resolver.resolve(exchangeFrom("10.0.0.7"))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .expectNext("user-42")
                .verifyComplete();
    }

    @Test
    @DisplayName("an unauthenticated caller falls back to its address")
    void userKeyResolver_shouldFallBackToTheCallerAddress() {
        KeyResolver resolver = config.userKeyResolver();

        StepVerifier.create(resolver.resolve(exchangeFrom("10.0.0.7")))
                .expectNext("10.0.0.7")
                .verifyComplete();
    }

    @Test
    @DisplayName("an authentication that is not a JWT falls back to the address too")
    void userKeyResolver_shouldFallBackWhenTheAuthenticationIsNotAJwt() {
        KeyResolver resolver = config.userKeyResolver();
        var authentication = new TestingAuthenticationToken("someone", "credentials");

        StepVerifier.create(resolver.resolve(exchangeFrom("10.0.0.8"))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .expectNext("10.0.0.8")
                .verifyComplete();
    }

    @Test
    @DisplayName("an unresolvable key never yields an empty Mono")
    void userKeyResolver_shouldNeverResolveToEmpty() {
        // The gateway reads an empty key as "deny" and answers 403. A request whose address
        // cannot be read is not a request that should be rejected.
        KeyResolver resolver = config.userKeyResolver();
        ServerWebExchange withoutAddress = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/user-service/profile").build());

        StepVerifier.create(resolver.resolve(withoutAddress))
                .expectNext("unknown")
                .verifyComplete();
    }
}
