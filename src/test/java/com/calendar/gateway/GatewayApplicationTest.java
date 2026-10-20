package com.calendar.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers the one method left in {@link GatewayApplication}: {@code main}.
 *
 * <p>The routing table moved to {@code configuration.RouteConfig} and is tested in
 * {@code RouteConfigTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("the resilience beans are wired, not merely declared")
    void contextShouldExposeTheResilienceBeans() {
        // A circuit breaker whose factory bean is missing fails at the first request, not at
        // start-up: the route's filter is built lazily. This is what makes that a start-up
        // failure instead.
        assertThat(context.getBean(org.springframework.cloud.client.circuitbreaker
                .ReactiveCircuitBreakerFactory.class)).isNotNull();
        assertThat(context.getBean(org.springframework.cloud.gateway.filter.ratelimit
                .RateLimiter.class)).isNotNull();
        assertThat(context.getBean(org.springframework.cloud.gateway.filter.ratelimit
                .KeyResolver.class)).isNotNull();
    }

    @Test
    @DisplayName("the probe groups check the probe state only, not Redis, despite their extra paths")
    void contextShouldKeepTheProbeGroupsToTheirAvailabilityState() {
        // Naming a group in the properties (for its /livez or /readyz path) makes it a user group
        // that includes every indicator by default: Redis down would then fail liveness and get
        // every pod restarted. application.properties pins them back; this keeps it so.
        HealthEndpointGroups groups = context.getBean(HealthEndpointGroups.class);

        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("redis")).isFalse();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("redis")).isFalse();
    }

    @Test
    void main_shouldStartWithoutFailing() {
        // Deliberately shallow: it only guarantees the absence of a start-up failure.
        // The real application context is covered by contextLoads.
        System.setProperty("spring.profiles.active", "test");

        assertThatCode(() -> GatewayApplication.main(new String[] {"--server.port=0"}))
                .doesNotThrowAnyException();
    }
}
