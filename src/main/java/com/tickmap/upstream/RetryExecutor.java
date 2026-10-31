package com.tickmap.upstream;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exponential backoff with jitter: delay n = min(max, initial * multiplier^(n-1)), randomised to
 * 50-100% of that so clients don't retry in lockstep. Retry-After is honoured up to the max.
 */
public class RetryExecutor {

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    private final UpstreamProperties.Retry config;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    public RetryExecutor(UpstreamProperties.Retry config) {
        this(config, d -> Thread.sleep(d.toMillis()), () -> ThreadLocalRandom.current().nextDouble());
    }

    public RetryExecutor(UpstreamProperties.Retry config, Sleeper sleeper, DoubleSupplier random) {
        this.config = config;
        this.sleeper = sleeper;
        this.random = random;
    }

    public <T> T execute(String operation, Supplier<T> call) {
        int attempt = 1;
        while (true) {
            try {
                return call.get();
            } catch (UpstreamApiException e) {
                if (!e.isRetryable() || attempt >= config.maxAttempts()) {
                    log.error("{} giving up on attempt {}/{}: {}", operation, attempt, config.maxAttempts(), e.getMessage());
                    throw UpstreamApiException.exhausted(operation, attempt, e);
                }
                Duration delay = delayBeforeRetry(attempt, e.getRetryAfter());
                log.warn("{} attempt {}/{} failed ({}); retrying in {} ms",
                        operation, attempt, config.maxAttempts(), e.getMessage(), delay.toMillis());
                sleep(delay);
                attempt++;
            }
        }
    }

    Duration delayBeforeRetry(int attempt, Duration retryAfter) {
        double base = config.initialBackoff().toMillis() * Math.pow(config.multiplier(), attempt - 1);
        long capped = (long) Math.min(base, config.maxBackoff().toMillis());
        long jittered = (long) (capped / 2.0 + random.getAsDouble() * capped / 2.0);
        if (retryAfter != null) {
            jittered = Math.max(jittered, retryAfter.toMillis());
        }
        return Duration.ofMillis(Math.min(jittered, config.maxBackoff().toMillis()));
    }

    private void sleep(Duration d) {
        try {
            sleeper.sleep(d);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new UpstreamApiException("Interrupted while waiting to retry", null, false, null, ie);
        }
    }
}
