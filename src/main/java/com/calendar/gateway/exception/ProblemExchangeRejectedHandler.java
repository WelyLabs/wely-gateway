package com.calendar.gateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.firewall.ServerExchangeRejectedException;
import org.springframework.security.web.server.firewall.ServerExchangeRejectedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The 400 Spring Security's firewall answers before anything else runs — a TRACE, an encoded
 * slash, a path with {@code ..} — in our problem shape, as {@value GlobalErrorHandler#REQUEST_REFUSED_CODE}.
 *
 * <p>The firewall rejects in the security filter itself, so the rejection never reaches
 * {@link GlobalErrorHandler}: without this bean it is an empty 400.
 */
@Slf4j
@Component
public class ProblemExchangeRejectedHandler implements ServerExchangeRejectedHandler {

    private final ProblemWriter problemWriter;

    public ProblemExchangeRejectedHandler(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, ServerExchangeRejectedException rejected) {
        // The firewall's message names what it rejected; it is for the logs, not the response.
        log.debug("Request rejected by the firewall {} on {} (request {}): {}", GlobalErrorHandler.REQUEST_REFUSED_CODE,
                exchange.getRequest().getPath(), RequestIds.of(exchange), rejected.getMessage());

        return problemWriter.write(exchange, Problems.of(HttpStatus.BAD_REQUEST, GlobalErrorHandler.REQUEST_REFUSED_CODE,
                HttpStatus.BAD_REQUEST.getReasonPhrase(), "The request could not be served as sent.", exchange));
    }
}
