package com.calendar.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point.
 *
 * <p>Holds nothing but {@code main}. The routing table it used to declare, along with six
 * {@code @Value} fields, now lives in {@code configuration.RouteConfig} — where the other
 * five services keep their beans.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
