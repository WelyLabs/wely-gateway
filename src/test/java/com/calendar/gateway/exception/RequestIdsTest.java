package com.calendar.gateway.exception;

import com.calendar.gateway.configuration.RequestIdWebFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link RequestIds#of}.
 */
class RequestIdsTest {

    @Test
    @DisplayName("reads the id the request-id filter stored")
    void of_shouldReadTheFilterAttribute() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a"));
        exchange.getAttributes().put(RequestIdWebFilter.REQUEST_ID_ATTRIBUTE, "0f3c9a52-1b7e-4d1c-9a4e-2b6f8c1d3e5a");

        assertThat(RequestIds.of(exchange)).isEqualTo("0f3c9a52-1b7e-4d1c-9a4e-2b6f8c1d3e5a");
    }

    @Test
    @DisplayName("falls back to the exchange's own id when the filter never ran")
    void of_shouldFallBackToTheExchangeId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a"));

        assertThat(RequestIds.of(exchange)).isEqualTo(exchange.getRequest().getId());
    }

    @Test
    @DisplayName("never reads the header: a client-sent X-Request-Id is not an id the gateway gave")
    void of_shouldIgnoreTheHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a")
                .header(RequestIdWebFilter.REQUEST_ID_HEADER, "client-chosen-id"));

        assertThat(RequestIds.of(exchange)).isNotEqualTo("client-chosen-id");
    }
}
