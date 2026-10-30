/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;

/**
 * Task TEA-10: the lines one class logs while a test runs, read from an appender attached to that class's
 * logger (not from captured output, which log4j2 caches at start). {@link #of(Class)} opens the logger at
 * WARN; {@link #of(Class, Level)} at any level, so a case can pin that nothing above DEBUG was written
 * (round 4, T-9).
 */
public final class TeaLogCapture implements AutoCloseable {

	private final Logger logger;
	private final AbstractAppender appender;
	private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
	private final Level restoreLevel;

	private TeaLogCapture(Class<?> source, Level level) {
		this.logger = (Logger) LogManager.getLogger(source);
		this.restoreLevel = logger.getLevel();
		this.appender = new AbstractAppender("tea-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				events.add(event.toImmutable());
			}
		};
		appender.start();
		// the first appender on a logger gives it its own LoggerConfig and resets every logger's level, so the
		// level is set after it, on the LoggerConfig (Configurator), never on the Logger alone: a plain unit test
		// runs log4j2's default configuration (ERROR), the Spring tests io.reliza at INFO
		logger.addAppender(appender);
		if (!logger.isEnabled(level)) Configurator.setLevel(logger.getName(), level);
	}

	public static TeaLogCapture of(Class<?> source) {
		return new TeaLogCapture(source, Level.WARN);
	}

	/** A capture with the logger opened at {@code level} (when it is not already enabled there). */
	public static TeaLogCapture of(Class<?> source, Level level) {
		return new TeaLogCapture(source, level);
	}

	/** The formatted ERROR messages, in order. */
	public List<String> errors() {
		return lines(Level.ERROR);
	}

	/** The formatted WARN messages, in order. */
	public List<String> warnings() {
		return lines(Level.WARN);
	}

	/** Every captured line at INFO or more specific, as {@code <LEVEL>: <formatted message>}, in order. */
	public List<String> aboveDebug() {
		synchronized (events) {
			return events.stream().filter(e -> e.getLevel().isMoreSpecificThan(Level.INFO))
					.map(e -> e.getLevel().name() + ": " + e.getMessage().getFormattedMessage()).toList();
		}
	}

	/** How many captured lines, at any level, carry an exception object (a stack trace). */
	public long withThrowable() {
		synchronized (events) {
			return events.stream().filter(e -> null != e.getThrown()).count();
		}
	}

	/** The formatted messages at exactly {@code level}, in order. */
	public List<String> lines(Level level) {
		synchronized (events) {
			return events.stream().filter(e -> level.equals(e.getLevel()))
					.map(e -> e.getMessage().getFormattedMessage()).toList();
		}
	}

	@Override
	public void close() {
		logger.removeAppender(appender);
		appender.stop();
		if (!restoreLevel.equals(logger.getLevel())) Configurator.setLevel(logger.getName(), restoreLevel);
	}
}
