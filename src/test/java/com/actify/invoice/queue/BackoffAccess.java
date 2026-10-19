package com.actify.invoice.queue;

/** Lets tests in other packages check ExtractionWorker's package-private back-off formula. */
public final class BackoffAccess {

    private BackoffAccess() {
    }

    public static long seconds(int attemptNumber, double random) {
        return ExtractionWorker.backoffSeconds(attemptNumber, random);
    }
}
