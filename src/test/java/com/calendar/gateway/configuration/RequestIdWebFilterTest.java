package com.calendar.gateway.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link RequestIdWebFilter#filter}. The full path — the id a client receives is the one
 * in the problem body — is covered by the context tests of the error writers.
 */
class RequestIdWebFilterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RequestIdWebFilter filter = new RequestIdWebFilter();

    /** Runs the filter, then completes the response as the end of a chain would. */
    private static ServerWebExchange run(RequestIdWebFilter filter, MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        WebFilterChain chain = chained -> {
            seen.set(chained);
            return chained.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify(TIMEOUT);
        return seen.get();
    }

    @Test
    @DisplayName("generates an id and puts it on the forwarded request, the attribute and the response")
    void filter_shouldPutOneGeneratedIdEverywhere() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/x"));

        ServerWebExchange forwarded = run(filter, exchange);

        String requestId = forwarded.getRequest().getHeaders().getFirst(RequestIdWebFilter.REQUEST_ID_HEADER);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat((String) forwarded.getAttribute(RequestIdWebFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo(requestId);
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIdWebFilter.REQUEST_ID_HEADER))
                .isEqualTo(requestId);
    }

    @Test
    @DisplayName("ignores an id the client sent: it cannot be trusted for correlation")
    void filter_shouldReplaceAClientSuppliedId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/x")
                .header(RequestIdWebFilter.REQUEST_ID_HEADER, "client-chosen-id"));

        ServerWebExchange forwarded = run(filter, exchange);

        assertThat(forwarded.getRequest().getHeaders().get(RequestIdWebFilter.REQUEST_ID_HEADER))
                .singleElement()
                .isNotEqualTo("client-chosen-id");
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIdWebFilter.REQUEST_ID_HEADER))
                .isNotEqualTo("client-chosen-id");
    }

    @Test
    @DisplayName("the response carries the id once, even when the proxied service echoed it")
    void filter_shouldNotDuplicateTheResponseHeader_whenTheServiceEchoesIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/x"));
        WebFilterChain proxying = chained -> {
            // What NettyRoutingFilter does with the service's headers: addAll.
            chained.getResponse().getHeaders().add(RequestIdWebFilter.REQUEST_ID_HEADER,
                    chained.getRequest().getHeaders().getFirst(RequestIdWebFilter.REQUEST_ID_HEADER));
            return chained.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, proxying)).expectComplete().verify(TIMEOUT);

        assertThat(exchange.getResponse().getHeaders().get(RequestIdWebFilter.REQUEST_ID_HEADER)).hasSize(1);
    }

    @Test
    @DisplayName("each request gets its own id")
    void filter_shouldGiveEachRequestItsOwnId() {
        ServerWebExchange first = run(filter, MockServerWebExchange.from(MockServerHttpRequest.get("/a")));
        ServerWebExchange second = run(filter, MockServerWebExchange.from(MockServerHttpRequest.get("/a")));

        assertThat((String) first.getAttribute(RequestIdWebFilter.REQUEST_ID_ATTRIBUTE))
                .isNotEqualTo(second.getAttribute(RequestIdWebFilter.REQUEST_ID_ATTRIBUTE));
    }

    @Test
    @DisplayName("an error further down the chain still leaves with the id")
    void filter_shouldPassErrorsThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a"));
        IllegalStateException failure = new IllegalStateException("boom");

        StepVerifier.create(filter.filter(exchange, chained -> Mono.error(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify(TIMEOUT);
    }
}
