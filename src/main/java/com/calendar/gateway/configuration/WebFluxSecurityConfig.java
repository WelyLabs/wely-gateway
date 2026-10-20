package com.calendar.gateway.configuration;

import com.calendar.gateway.exception.ProblemAccessDeniedHandler;
import com.calendar.gateway.exception.ProblemAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class WebFluxSecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String publicIssuer;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String internalJwkSetUri;

    @Value("${cors.allowed.origin}")
    private String corsAllowedOrigin;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                           ProblemAuthenticationEntryPoint authenticationEntryPoint,
                                                           ProblemAccessDeniedHandler accessDeniedHandler) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        // Kubernetes probes: the kubelet carries no token. Only the two
                        // health groups are opened, not /actuator as a whole.
                        .pathMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        // The same probes outside /actuator, served on this port by Boot's
                        // additional-path; reading only.
                        .pathMatchers(HttpMethod.GET, "/livez", "/readyz").permitAll()
                        .pathMatchers("/rsocket/**").permitAll()
                        .pathMatchers("/public/**").permitAll()
                        // An event's .ics, opened by a calendar app that carries no token: the
                        // short-lived link in the path is checked by wely-events. Reading only.
                        .pathMatchers(HttpMethod.GET, "/api/v1/events-service/public/calendar/*").permitAll()
                        // OpenAPI. These paths are not routed by the gateway — it forwards
                        // /api/v1/<service>/** and /rsocket only — so opening them here makes
                        // the documentation reachable in-cluster and in dev, and nowhere else.
                        .pathMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**",
                                      "/swagger-ui.html", "/webjars/swagger-ui/**").permitAll()
                        .anyExchange().authenticated())
                // 401 and 403 answer our problem (GTW-REQ-001, GTW-REQ-002) rather than an empty
                // body. Set in both places: the resource server uses its own for a token that
                // fails validation, exceptionHandling covers a request that carries none.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        NimbusReactiveJwtDecoder jwtDecoder = NimbusReactiveJwtDecoder.withJwkSetUri(internalJwkSetUri).build();

        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(publicIssuer);
        OAuth2TokenValidator<Jwt> withTimestamp = new JwtTimestampValidator();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(withTimestamp, issuerValidator);

        jwtDecoder.setJwtValidator(validator);
        return jwtDecoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(corsAllowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
