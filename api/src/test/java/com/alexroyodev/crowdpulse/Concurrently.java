package com.alexroyodev.crowdpulse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Test helper: runs tasks on a fixed number of threads that all start at the same moment. */
public final class Concurrently {

	private Concurrently() {
	}

	/**
	 * Runs the tasks on {@code threads} threads. Every task waits on one gate that opens only
	 * after all are submitted, to maximise contention. Returns the results in submission order.
	 */
	public static <T> List<T> run(int threads, List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					startGate.await();
					return task.call();
				}));
			}
			startGate.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
