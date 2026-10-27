/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

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
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * Task TEA-21: the events one logger, or the root configuration, receives while a test runs, from an in-memory
 * appender. {@link #of(Class, Level)} attaches to one class's logger and then opens that logger at the level (the
 * first appender gives the logger its own configuration, so the level is set after it); {@link #root()} attaches to
 * the root configuration without changing any level, so it sees what reaches root (the non-additive {@code io.reliza}
 * and Spring configurations never do; the container's own loggers do).
 */
public final class LogEventCapture implements AutoCloseable {

	private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
	private final AbstractAppender appender;
	private final Logger logger;
	private final Level restoreLevel;

	private LogEventCapture(Logger logger, Level level) {
		this.logger = logger;
		this.restoreLevel = null == logger ? null : logger.getLevel();
		this.appender = new AbstractAppender("tea21-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				events.add(event.toImmutable());
			}
		};
		appender.start();
		if (null != logger) {
			logger.addAppender(appender);
			if (!logger.isEnabled(level)) Configurator.setLevel(logger.getName(), level);
		} else {
			LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			ctx.getConfiguration().getRootLogger().addAppender(appender, null, null);
			ctx.updateLoggers();
		}
	}

	/** A capture on {@code source}'s logger, opened at {@code level} when it is not already enabled there. */
	public static LogEventCapture of(Class<?> source, Level level) {
		return new LogEventCapture((Logger) LogManager.getLogger(source), level);
	}

	/** A capture on the root configuration, levels unchanged. */
	public static LogEventCapture root() {
		return new LogEventCapture(null, null);
	}

	/** Every event so far, in order. */
	public List<LogEvent> events() {
		synchronized (events) {
			return List.copyOf(events);
		}
	}

	/** The events at exactly {@code level}. */
	public List<LogEvent> at(Level level) {
		return events().stream().filter(e -> level.equals(e.getLevel())).toList();
	}

	/** The formatted messages of the events at exactly {@code level}, in order. */
	public List<String> messages(Level level) {
		return at(level).stream().map(e -> e.getMessage().getFormattedMessage()).toList();
	}

	/** The events that carry a throwable (a stack trace in the log). */
	public List<LogEvent> withThrowable() {
		return events().stream().filter(e -> null != e.getThrown()).toList();
	}

	public void clear() {
		events.clear();
	}

	@Override
	public void close() {
		if (null != logger) {
			logger.removeAppender(appender);
			if (null != restoreLevel && !restoreLevel.equals(logger.getLevel())) Configurator.setLevel(logger.getName(), restoreLevel);
		} else {
			LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			LoggerConfig root = ctx.getConfiguration().getRootLogger();
			root.removeAppender(appender.getName());
			ctx.updateLoggers();
		}
		appender.stop();
	}
}
