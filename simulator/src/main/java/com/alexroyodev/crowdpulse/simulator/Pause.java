package com.alexroyodev.crowdpulse.simulator;

import java.time.Duration;

/** Waiting, injected so that tests do not really wait (SettlingWait, ZoneRunner). */
@FunctionalInterface
interface Pause {

	void sleep(Duration duration) throws InterruptedException;

}
