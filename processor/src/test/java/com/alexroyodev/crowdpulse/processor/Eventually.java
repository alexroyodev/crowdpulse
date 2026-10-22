package com.alexroyodev.crowdpulse.processor;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Test helper for asynchronous processing: checks a condition every 100 ms until it holds or 10 s
 * have passed. A loop of a few lines instead of a library (plan 003, section 7).
 */
public final class Eventually {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final Duration INTERVAL = Duration.ofMillis(100);

	private Eventually() {
	}

	public static void await(String what, BooleanSupplier condition) {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Not true after " + TIMEOUT.toSeconds() + " s: " + what);
			}
			try {
				Thread.sleep(INTERVAL);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for: " + what, ex);
			}
		}
	}

}
