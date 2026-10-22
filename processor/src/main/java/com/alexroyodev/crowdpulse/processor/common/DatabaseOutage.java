package com.alexroyodev.crowdpulse.processor.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

/**
 * Remembers whether the database is down, so that an outage is logged twice (when it starts and
 * when it ends) instead of once per retry (spec 004, CA2). Processing itself waits in the Kafka
 * error handler; this class only reports.
 */
@Component
public class DatabaseOutage {

	private static final Logger log = LoggerFactory.getLogger(DatabaseOutage.class);

	private final Clock clock;
	private Instant since;

	DatabaseOutage(Clock clock) {
		this.clock = clock;
	}

	/** An event could not be processed because the database is not available. */
	public synchronized void failed(Throwable cause) {
		if (since == null) {
			since = clock.instant();
			log.atWarn().setMessage("Database unavailable: processing paused until it is back")
					.addKeyValue("cause", NestedExceptionUtils.getMostSpecificCause(cause).toString())
					.log();
		}
	}

	/** An event was processed, so the database is available. */
	public synchronized void succeeded() {
		if (since != null) {
			log.atWarn().setMessage("Database available again: processing resumed")
					.addKeyValue("downtimeMs", Duration.between(since, clock.instant()).toMillis())
					.log();
			since = null;
		}
	}

}
