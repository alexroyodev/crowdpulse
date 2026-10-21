package com.alexroyodev.crowdpulse.simulator;

/** Entry point: parses the options and runs the simulation. */
public final class Main {

	private Main() {
	}

	public static void main(String[] args) {
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
		// Sending is added in T15.
		System.out.println("Configuración: " + config);
	}

}
