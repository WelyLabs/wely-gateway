package com.calendar.gateway.configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Logs each circuit breaker's state changes: the outage, not every request it rejects.
 *
 * <p>The fallback used to log one ERROR per request it answered. While a breaker is open that is
 * one line per caller per click, and the line that matters — the service went down, at this time
 * — drowns in its own repetitions. A transition happens once per outage:
 * <ul>
 *   <li><b>→ OPEN</b> at ERROR: a service stopped answering, someone has to act;</li>
 *   <li><b>→ HALF_OPEN</b> at WARN: the wait is over, a few trial calls are let through;</li>
 *   <li><b>→ CLOSED</b> at INFO: it answers again, the incident is over.</li>
 * </ul>
 * How many requests fell back in between is counted by {@code FallbackController}, not logged.
 *
 * <p>The breakers are created lazily, one per route on its first request, so listening to the
 * ones that exist at start-up would hear none: this listens to the registry for each one added.
 */
@Slf4j
@Component
public class CircuitBreakerTransitionLogger {

    public CircuitBreakerTransitionLogger(CircuitBreakerRegistry registry) {
        registry.getAllCircuitBreakers().forEach(this::listenTo);
        registry.getEventPublisher()
                .onEntryAdded(event -> listenTo(event.getAddedEntry()))
                .onEntryReplaced(event -> listenTo(event.getNewEntry()));
    }

    private void listenTo(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(this::onStateTransition);
    }

    void onStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        String breaker = event.getCircuitBreakerName();
        CircuitBreaker.State from = event.getStateTransition().getFromState();
        CircuitBreaker.State to = event.getStateTransition().getToState();

        switch (to) {
            case OPEN, FORCED_OPEN -> log.error("Circuit breaker {} {} -> {}: calls are now short-circuited to the fallback",
                    breaker, from, to);
            case HALF_OPEN -> log.warn("Circuit breaker {} {} -> {}: trial calls let through", breaker, from, to);
            default -> log.info("Circuit breaker {} {} -> {}", breaker, from, to);
        }
    }
}
