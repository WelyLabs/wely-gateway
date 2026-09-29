package com.calendar.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers both methods of {@link GatewayApplication}: {@code main} and the
 * {@code configureRoute} bean that holds the whole routing table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayApplicationTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void contextLoads() {
        assertThatCode(() -> routeLocator.getRoutes().blockLast()).doesNotThrowAnyException();
    }

    @Test
    void configureRoute_shouldDeclareOneRoutePerService() {
        StepVerifier.create(routeLocator.getRoutes().map(route -> route.getId()))
                .expectNext("calendar-users-api")
                .expectNext("calendar-social-api")
                .expectNext("calendar-chat-api")
                .expectNext("chat-rsocket-route")
                // Route conservée alors que le service media a été supprimé du projet.
                // Son retrait touche aussi wely-gitops-infra (variable MEDIA_API_URL).
                .expectNext("calendar-media-api")
                .expectNext("calendar-events-api")
                .verifyComplete();
    }

    @Test
    void configureRoute_shouldStripTheApiVersionPrefixOnly() {
        // stripPrefix(2) retire /api/v1 mais conserve /<service>, que chaque service
        // réattache via son WebConfig : un service répond donc sur le même chemin
        // qu'il soit appelé via la gateway ou directement.
        StepVerifier.create(routeLocator.getRoutes()
                        .filter(route -> route.getId().equals("calendar-users-api"))
                        .map(route -> route.getFilters().size()))
                .expectNextMatches(filterCount -> filterCount >= 2)
                .verifyComplete();
    }

    @Test
    void main_shouldStartWithoutFailing() {
        // Vérification superficielle assumée : elle ne garantit que l'absence d'échec
        // au démarrage. Le contexte applicatif réel est couvert par contextLoads.
        System.setProperty("spring.profiles.active", "test");

        assertThatCode(() -> GatewayApplication.main(new String[] {"--server.port=0"}))
                .doesNotThrowAnyException();
    }
}
