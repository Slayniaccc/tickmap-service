package com.tickmap.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetryExecutorTest {

    private final List<Duration> sleeps = new ArrayList<>();

    private RetryExecutor executor(int maxAttempts, double randomValue) {
        var config = new UpstreamProperties.Retry(maxAttempts, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5));
        return new RetryExecutor(config, sleeps::add, () -> randomValue);
    }

    private static UpstreamException retryable() {
        return UpstreamException.forStatus(503, "busy", null);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void returnsImmediatelyWhenFirstAttemptSucceeds() {
        assertThat(executor(4, 1.0).execute("op", () -> "ok")).isEqualTo("ok");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesTransientFailuresThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = executor(4, 1.0).execute("op", () -> {
            if (calls.incrementAndGet() < 3) {
                throw retryable();
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void backoffGrowsExponentiallyAndIsCappedAtMaxBackoff() {
        RetryExecutor ex = executor(10, 1.0);
        assertThat(ex.delayBeforeRetry(1, null)).isEqualTo(Duration.ofMillis(1000));
        assertThat(ex.delayBeforeRetry(2, null)).isEqualTo(Duration.ofMillis(2000));
        assertThat(ex.delayBeforeRetry(3, null)).isEqualTo(Duration.ofMillis(4000));
        assertThat(ex.delayBeforeRetry(4, null)).isEqualTo(Duration.ofMillis(5000)); // 8s capped to 5s
        assertThat(ex.delayBeforeRetry(9, null)).isEqualTo(Duration.ofMillis(5000));
    }

    @Test
    void jitterKeepsDelayBetweenHalfAndFullBackoff() {
        assertThat(executor(4, 0.0).delayBeforeRetry(2, null)).isEqualTo(Duration.ofMillis(1000));
        assertThat(executor(4, 0.5).delayBeforeRetry(2, null)).isEqualTo(Duration.ofMillis(1500));
        assertThat(executor(4, 1.0).delayBeforeRetry(2, null)).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void honoursRetryAfterButNeverBeyondMaxBackoff() {
        RetryExecutor ex = executor(4, 0.0);
        assertThat(ex.delayBeforeRetry(1, Duration.ofSeconds(3))).isEqualTo(Duration.ofSeconds(3));
        assertThat(ex.delayBeforeRetry(1, Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(5));
        // A shorter Retry-After doesn't shorten our own backoff.
        assertThat(executor(4, 1.0).delayBeforeRetry(3, Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void givesUpAfterMaxAttemptsAndKeepsLastStatus() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor(3, 1.0).execute("GET /x", () -> {
            calls.incrementAndGet();
            throw retryable();
        }))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("GET /x failed after 3 attempt(s)")
                .satisfies(e -> assertThat(((UpstreamException) e).getHttpStatus()).isEqualTo(503));
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void doesNotRetryNonRetryableFailures() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor(4, 1.0).execute("op", () -> {
            calls.incrementAndGet();
            throw UpstreamException.forStatus(404, "Not found", null);
        })).isInstanceOf(UpstreamException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void programmingErrorsAreNotRetriedOrWrapped() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor(4, 1.0).execute("op", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("bug");
        })).isExactlyInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void singleAttemptConfigMeansNoRetries() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor(1, 1.0).execute("op", () -> {
            calls.incrementAndGet();
            throw retryable();
        })).isInstanceOf(UpstreamException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void interruptionDuringBackoffStopsRetryingAndRestoresInterruptFlag() {
        var config = new UpstreamProperties.Retry(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5));
        RetryExecutor ex = new RetryExecutor(config, d -> { throw new InterruptedException(); }, () -> 1.0);
        assertThatThrownBy(() -> ex.execute("op", () -> { throw retryable(); }))
                .isInstanceOf(UpstreamException.class)
                .hasRootCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void classifiesHttpStatusesCorrectly() {
        assertThat(UpstreamException.forStatus(429, "", null).isRetryable()).isTrue();
        assertThat(UpstreamException.forStatus(408, "", null).isRetryable()).isTrue();
        assertThat(UpstreamException.forStatus(500, "", null).isRetryable()).isTrue();
        assertThat(UpstreamException.forStatus(504, "", null).isRetryable()).isTrue();
        assertThat(UpstreamException.forStatus(400, "", null).isRetryable()).isFalse();
        assertThat(UpstreamException.forStatus(401, "", null).isRetryable()).isFalse();
        assertThat(UpstreamException.forStatus(404, "", null).isRetryable()).isFalse();
    }
}
