package com.calendar.gateway.configuration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Covers {@link CircuitBreakerTransitionLogger}: one line per state change, at the level the
 * change deserves, for breakers created before and after it.
 */
class CircuitBreakerTransitionLoggerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(CircuitBreakerTransitionLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("OPEN at ERROR, HALF_OPEN at WARN, CLOSED at INFO, each naming the breaker")
    void onStateTransition_shouldLogEachTransitionAtItsLevel() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        new CircuitBreakerTransitionLogger(registry);
        // Created after the logger, as the gateway's are: lazily, on a route's first request.
        CircuitBreaker breaker = registry.circuitBreaker("usersCircuitBreaker");

        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        breaker.transitionToClosedState();

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, event -> event.getFormattedMessage().startsWith(
                        "Circuit breaker usersCircuitBreaker"))
                .containsExactly(
                        tuple(Level.ERROR, true),
                        tuple(Level.WARN, true),
                        tuple(Level.INFO, true));
        assertThat(appender.list.get(0).getFormattedMessage()).contains("CLOSED -> OPEN");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("OPEN -> HALF_OPEN");
        assertThat(appender.list.get(2).getFormattedMessage()).contains("HALF_OPEN -> CLOSED");
    }

    @Test
    @DisplayName("a breaker that existed before the logger is heard too")
    void constructor_shouldListenToBreakersThatAlreadyExist() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker breaker = registry.circuitBreaker("chatCircuitBreaker");
        new CircuitBreakerTransitionLogger(registry);

        breaker.transitionToForcedOpenState();

        assertThat(appender.list).singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("chatCircuitBreaker", "FORCED_OPEN");
                });
    }

    @Test
    @DisplayName("a breaker replaced in the registry is listened to in its new form")
    void constructor_shouldListenToReplacedBreakers() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        new CircuitBreakerTransitionLogger(registry);
        registry.circuitBreaker("eventsCircuitBreaker");
        CircuitBreaker replacement = CircuitBreaker.ofDefaults("eventsCircuitBreaker");
        registry.replace("eventsCircuitBreaker", replacement);

        replacement.transitionToOpenState();

        assertThat(appender.list).singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("a request a breaker merely records logs nothing: only transitions do")
    void onStateTransition_shouldStaySilentWithoutATransition() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        new CircuitBreakerTransitionLogger(registry);
        CircuitBreaker breaker = registry.circuitBreaker("socialCircuitBreaker");

        breaker.onSuccess(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        breaker.onError(1, java.util.concurrent.TimeUnit.MILLISECONDS, new IllegalStateException("x"));

        assertThat(appender.list).isEmpty();
    }
}
