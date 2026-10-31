package com.tickmap;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    // Upstream timestamps have no zone; they're UK sightings, so treat them as UK local time.
    public static final ZoneId UK = ZoneId.of("Europe/London");

    @Bean
    Clock clock() {
        return Clock.system(UK);
    }
}
