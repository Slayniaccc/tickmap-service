package com.tickmap.upstream;

import java.time.Duration;
import java.util.Set;

/**
 * Retryable: timeouts, connection errors, 408/425/429, 5xx and corrupt JSON.
 * Anything else (e.g. 404, a response of the wrong shape) fails fast.
 */
public class UpstreamException extends RuntimeException {

    private static final Set<Integer> RETRYABLE_4XX = Set.of(408, 425, 429);

    private final Integer httpStatus;
    private final boolean retryable;
    private final Duration retryAfter;

    public UpstreamException(String message, Integer httpStatus, boolean retryable, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public static UpstreamException forStatus(int status, String body, Duration retryAfter) {
        boolean retryable = status >= 500 || RETRYABLE_4XX.contains(status);
        String snippet = body == null ? "" : body.substring(0, Math.min(body.length(), 200));
        return new UpstreamException("Upstream returned HTTP " + status + ": " + snippet,
                status, retryable, retryAfter, null);
    }

    public static UpstreamException network(Throwable cause) {
        return new UpstreamException("Upstream unreachable: " + cause.getMessage(), null, true, null, cause);
    }

    public static UpstreamException corruptBody(Throwable cause) {
        return new UpstreamException("Upstream returned invalid JSON: " + cause.getMessage(), null, true, null, cause);
    }

    public static UpstreamException unexpectedShape(String message) {
        return new UpstreamException(message, null, false, null, null);
    }

    public static UpstreamException exhausted(String operation, int attempts, UpstreamException last) {
        return new UpstreamException(operation + " failed after " + attempts + " attempt(s): " + last.getMessage(),
                last.httpStatus, false, null, last);
    }

    public Integer getHttpStatus() { return httpStatus; }
    public boolean isRetryable() { return retryable; }
    public Duration getRetryAfter() { return retryAfter; }
}
