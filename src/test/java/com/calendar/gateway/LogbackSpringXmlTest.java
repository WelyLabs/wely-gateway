package com.calendar.gateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Covers {@code logback-spring.xml}: a URL password never reaches the console, whether it sits in
 * the message or in a stack trace.
 *
 * <p>The leak is real, not hypothetical: a WebClient error answer puts the request URL, userinfo
 * included, in its exception message and checkpoint, and the gateway's DEBUG routing line prints
 * the route's target URI. The first assertion of each test proves the secret is in the raw text
 * before the layout masks it.
 */
class LogbackSpringXmlTest {

    private static final String SECRET = "s3cr3t";

    private OutputStreamAppender<ILoggingEvent> console;
    private Logger logger;
    private HttpServer downstream;

    @BeforeEach
    void configure() throws Exception {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
        logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        OutputStreamAppender<ILoggingEvent> appender = (OutputStreamAppender<ILoggingEvent>) logger.getAppender("CONSOLE");
        console = appender;

        // A downstream that answers every request with a 401: the error-answer case, the one
        // that carries the URL. A refused connection only names the host.
        downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        downstream.start();
    }

    @AfterEach
    void stopDownstream() {
        downstream.stop(0);
    }

    private String format(String message, Throwable error) {
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, message, error, null);
        return new String(console.getEncoder().encode(event), StandardCharsets.UTF_8);
    }

    private static String trace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private String urlWithAPassword() {
        return "http://wely:" + SECRET + "@127.0.0.1:" + downstream.getAddress().getPort() + "/user-service/profile";
    }

    /** A real error answer, from a URL whose userinfo carries a password. */
    private WebClientResponseException errorAnswerFromAUrlWithAPassword() {
        Throwable failure = catchThrowable(() -> WebClient.create()
                .get().uri(urlWithAPassword())
                .retrieve().toBodilessEntity()
                .block(Duration.ofSeconds(10)));
        assertThat(failure).isInstanceOf(WebClientResponseException.class);
        return (WebClientResponseException) failure;
    }

    @Test
    void consolePattern_shouldMaskAUrlPassword_inTheMessageAndStackTraceOfAnErrorAnswer() {
        WebClientResponseException failure = errorAnswerFromAUrlWithAPassword();
        assertThat(failure.getMessage()).contains(SECRET);
        assertThat(trace(failure)).contains(SECRET);

        String line = format("Technical failure GTW-TEC-000 on /x (request 1): " + failure.getMessage(), failure);

        assertThat(line)
                .doesNotContain(SECRET)
                .contains("wely:****@127.0.0.1")
                .contains("401 Unauthorized");
    }

    @Test
    void consolePattern_shouldPrintTheStackTraceOnce() {
        String line = format("Technical failure", errorAnswerFromAUrlWithAPassword());

        // Without %nopex, logback appends its own, unmasked copy of the trace.
        assertThat(line.split("WebClientResponseException\\$Unauthorized: 401", -1)).hasSize(2);
    }

    @Test
    void consolePattern_shouldMaskAUrlPassword_inARouteTargetUri() {
        // What RoutePredicateHandlerMapping prints at DEBUG: "Mapping [...] to Route{... uri=...}".
        Route route = Route.async()
                .id("calendar-users-api")
                .uri(urlWithAPassword())
                .asyncPredicate(AsyncPredicate.from(exchange -> true))
                .build();
        assertThat(route.toString()).contains(SECRET);

        String line = format("Mapping [Exchange: GET http://gateway/api/v1/user-service/profile] to " + route, null);

        assertThat(line).doesNotContain(SECRET).contains("wely:****@127.0.0.1");
    }

    @Test
    void consolePattern_shouldLeaveAUrlWithoutPasswordAlone() {
        String line = format("Request to GET http://wely-users-service:8082/user-service/profile", null);

        assertThat(line).contains("http://wely-users-service:8082/user-service/profile");
    }
}
