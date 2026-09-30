package com.calendar.gateway.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link OpenApiConfig#openApi} and the one thing that makes the documentation useful:
 * that it answers without a token.
 *
 * <p>The gateway serves its own specification and does not aggregate the services': each of them
 * serves its own, in-cluster. Aggregating would mean the gateway had to know every service's
 * documentation paths, which is the coupling that routing by prefix exists to avoid.
 */
// A running server, not bindToApplicationContext: springdoc registers its routes on the web
// handler, which the context-bound client does not see.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiConfigTest {

    @Autowired
    private ApplicationContext context;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("the generated document names the service rather than the application class")
    void openApi_shouldDescribeTheService() {
        var info = context.getBean(OpenAPI.class).getInfo();

        assertThat(info.getTitle()).isEqualTo("wely-gateway");
        assertThat(info.getVersion()).isEqualTo("v1");
        assertThat(info.getDescription()).isNotBlank();
    }

    @Test
    @DisplayName("the specification answers without a token")
    void apiDocs_shouldBeReachableUnauthenticated() {
        client().get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.info.title").isEqualTo("wely-gateway");
    }

    @Test
    @DisplayName("the Swagger UI and its assets load without a token")
    void swaggerUi_shouldBeReachableUnauthenticated() {
        client().get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk();

        client().get().uri("/webjars/swagger-ui/index.html")
                .exchange()
                .expectStatus().isOk();
    }
}
