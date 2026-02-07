package com.calendar.gateway;

import com.calendar.gateway.infrastructure.filters.IdentityTranslatorGatewayFilterFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayApplication {

        @Value("${users.api.url}")
        private String USERS_API_URL;

        @Value("${social.api.url}")
        private String SOCIAL_API_URL;

        @Value("${chat.api.url}")
        private String CHAT_API_URL;

        @Value("${chat.rsocket.url}")
        private String CHAT_RSOCKET_URL;

        public static void main(String[] args) {
                SpringApplication.run(GatewayApplication.class, args);
        }

        @Bean
        public RouteLocator configureRoute(RouteLocatorBuilder builder) {
                return builder.routes()
                                .route("calendar-users-api", r -> r
                                                .path("/api/v1/user-service/**")
                                                .filters(f -> f
                                                                .stripPrefix(2)
                                                                .retry(3))
                                                .uri(USERS_API_URL))
                                .route("calendar-social-api", r -> r
                                                .path("/api/v1/social-service/**")
                                                .filters(f -> f
                                                                .stripPrefix(2)
                                                                .retry(3))
                                                .uri(SOCIAL_API_URL))
                                .route("calendar-chat-api", r -> r
                                                .path("/api/v1/chat-service/**")
                                                .filters(f -> f
                                                                .stripPrefix(2)
                                                                .retry(3))
                                                .uri(CHAT_API_URL))
                                .route("chat-rsocket-route", r -> r
                                                .path("/rsocket/**", "/rsocket")
                                                .filters(f -> f
                                                                .retry(3))
                                                .uri(CHAT_RSOCKET_URL))
                                .route("calendar-media-api", r -> r
                                                .path("/api/v1/media-service/**")
                                                .filters(f -> f
                                                                .stripPrefix(2)
                                                                .retry(3)
                                        )
                                        .uri("http://localhost:8085"))
                                .build();
        }
}
