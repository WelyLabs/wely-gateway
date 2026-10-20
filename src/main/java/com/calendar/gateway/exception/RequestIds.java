package com.calendar.gateway.exception;

import com.calendar.gateway.configuration.RequestIdWebFilter;
import org.springframework.web.server.ServerWebExchange;

/**
 * The one place that reads a request's id, for problems and log lines alike.
 */
public final class RequestIds {

    private RequestIds() {
    }

    /**
     * The id {@link RequestIdWebFilter} gave the request — the one forwarded to the services and
     * returned in {@value RequestIdWebFilter#REQUEST_ID_HEADER}. Falls back to the exchange's own
     * id for an exchange the filter never saw (a unit test, a request answered before it ran).
     */
    public static String of(ServerWebExchange exchange) {
        String requestId = exchange.getAttribute(RequestIdWebFilter.REQUEST_ID_ATTRIBUTE);
        return requestId != null ? requestId : exchange.getRequest().getId();
    }
}
