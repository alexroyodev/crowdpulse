package com.alexroyodev.crowdpulse.processor.event;

/**
 * The message has no version header or one this processor does not understand. It is never
 * interpreted as version 1 by guessing: an incompatible change must not be misread silently.
 */
public class UnsupportedMessageVersionException extends RuntimeException {

	UnsupportedMessageVersionException(String version) {
		super("Unsupported message version: " + version);
	}

}
