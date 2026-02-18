package com.calendar.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RouteConfigurationTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void testRoutesConfiguration() {
        StepVerifier.create(routeLocator.getRoutes())
                .expectNextMatches(route -> route.getId().equals("calendar-users-api"))
                .expectNextMatches(route -> route.getId().equals("calendar-social-api"))
                .expectNextMatches(route -> route.getId().equals("calendar-chat-api"))
                .expectNextMatches(route -> route.getId().equals("chat-rsocket-route"))
                .expectNextMatches(route -> route.getId().equals("calendar-media-api"))
                .expectNextMatches(route -> route.getId().equals("calendar-events-api"))
                .verifyComplete();
    }
}
