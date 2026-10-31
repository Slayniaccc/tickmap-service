package com.tickmap.upstream;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** tickmap.upstream.* settings, validated at startup. */
@Validated
@ConfigurationProperties("tickmap.upstream")
public record UpstreamProperties(
        @NotBlank String baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull @Valid Retry retry) {

    public record Retry(
            @Min(1) @Max(10) int maxAttempts,
            @NotNull Duration initialBackoff,
            @DecimalMin("1.0") double multiplier,
            @NotNull Duration maxBackoff) {
    }
}
