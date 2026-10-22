package com.alexroyodev.crowdpulse.simulator;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Entry point: one sender thread per zone, a wait until the API shows the changes, then the
 * report. Exit code 0 when the CA10 check passes, 1 when it does not or the API is unreachable, 2
 * for invalid options.
 */
public final class Main {

	private Main() {
	}

	public static void main(String[] args) throws InterruptedException {
		if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
			System.out.print(SimulatorConfig.USAGE);
			return;
		}
		SimulatorConfig config;
		try {
			config = SimulatorConfig.parse(args);
		}
		catch (IllegalArgumentException ex) {
			System.err.println("Error: " + ex.getMessage());
			System.err.print(SimulatorConfig.USAGE);
			System.exit(2);
			return;
		}

		ApiClient api = new ApiClient(config.url());
		Map<String, Integer> before;
		try {
			before = api.occupancies();
		}
		catch (IOException ex) {
			System.err.println("Error: no se puede consultar la API en " + config.url() + " (" + ex.getMessage() + ")");
			System.exit(1);
			return;
		}

		System.out.printf("Enviando %d ev/s durante %d s a %s (zonas %s, %d %% duplicados)...%n", config.rate(),
				config.duration().toSeconds(), config.url(), String.join(",", config.zones()), config.duplicatesPct());
		long start = System.nanoTime();
		List<ZoneResult> results = runZones(config, api);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		// Spec 003 (D4): the API processes asynchronously, so wait until it shows the expected changes.
		SettlingWait.Result settling;
		try {
			settling = new SettlingWait(api::occupancies, Clock.systemUTC(), duration -> Thread.sleep(duration))
					.await(after -> new SimulationReport(results, before, after, elapsed, Duration.ZERO).changesMatch());
		}
		catch (IOException ex) {
			System.err.println("Error: no se puede consultar la API al terminar (" + ex.getMessage() + ")");
			System.exit(1);
			return;
		}

		SimulationReport report = new SimulationReport(results, before, settling.after(), elapsed, settling.waited());
		System.out.print(report.format());
		System.exit(report.ok() ? 0 : 1);
	}

	private static List<ZoneResult> runZones(SimulatorConfig config, ApiClient api) throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(config.zones().size());
		try {
			List<Future<ZoneResult>> futures = new ArrayList<>();
			for (String zoneId : config.zones()) {
				TrafficGenerator generator = new TrafficGenerator(zoneId, config.duplicatesPct(), new Random(),
						Clock.systemUTC());
				futures.add(pool.submit(new ZoneRunner(generator, api::send, config.ratePerZone(), config.duration())));
			}
			List<ZoneResult> results = new ArrayList<>();
			for (Future<ZoneResult> future : futures) {
				results.add(future.get());
			}
			return results;
		}
		catch (ExecutionException ex) {
			throw new IllegalStateException("Fallo en un hilo de envío", ex.getCause());
		}
		finally {
			pool.shutdownNow();
		}
	}

}
