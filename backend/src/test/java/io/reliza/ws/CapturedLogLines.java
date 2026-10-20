/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * The formatted messages one class logs at WARN and above while open (SCORE-47 tests): an appender
 * on the logger configuration the class's events end at, removed on close. The application's
 * console appender is asynchronous and bound to the stream at start-up, so the console is no place
 * to look for a line.
 */
final class CapturedLogLines implements AutoCloseable {

	private final LoggerContext ctx;
	private final LoggerConfig config;
	private final AbstractAppender appender;
	private final String loggerName;
	private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

	CapturedLogLines(Class<?> source) {
		loggerName = source.getName();
		ctx = ((Logger) LogManager.getLogger(source)).getContext();
		config = ctx.getConfiguration().getLoggerConfig(loggerName);
		appender = new AbstractAppender("score47-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				if (loggerName.equals(event.getLoggerName())) {
					lines.add(event.getMessage().getFormattedMessage());
				}
			}
		};
		appender.start();
		config.addAppender(appender, Level.WARN, null);
		ctx.updateLoggers();
	}

	List<String> lines() {
		synchronized (lines) {
			return List.copyOf(lines);
		}
	}

	boolean anyContains(String... parts) {
		return lines().stream().anyMatch(l -> {
			for (String p : parts) if (!l.contains(p)) return false;
			return true;
		});
	}

	@Override
	public void close() {
		config.removeAppender(appender.getName());
		ctx.updateLoggers();
		appender.stop();
	}
}
