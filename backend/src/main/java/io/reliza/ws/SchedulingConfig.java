/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Background work switch: {@code @Scheduled} processing for the whole application,
 * and the startup duplicate-component census, run unless
 * {@code relizaprops.schedulingEnabled=false} (application.yaml, env SCHEDULING_ENABLED).
 *
 * <p>Production leaves it on. The test build sets it to false (maven-surefire-plugin
 * {@code systemPropertyVariables} in pom.xml): the TestContext cache keeps every
 * {@code @SpringBootTest} context alive for the whole run, and each one ran its own
 * copy of all twenty-odd ticks against the one shared test database. Those ticks
 * acted on other classes' fixtures between their saves, held the advisory locks
 * other classes assert on, and walked every organization ever created in that
 * database on the shared connection pool. Tests that exercise a scheduled job call
 * its method directly, which this switch does not affect;
 * SchedulingConfigTest.everyScheduledDeclarationResolves parses every schedule,
 * so a bad cron, duration or placeholder still fails the build.
 *
 * <p>Only the annotation processing is switched off. The {@code taskScheduler}
 * pool in {@link App} is still created (SchedulerPoolWiringTest pins it), and the
 * per-job properties such as {@code relizaprops.autoIntegrateDrainEnabled} stay the
 * operational kill switches for a single job.
 */
@Slf4j
@Configuration
public class SchedulingConfig {

	public static final String SCHEDULING_ENABLED_PROPERTY = "relizaprops.schedulingEnabled";

	@Configuration
	@EnableScheduling
	@ConditionalOnProperty(name = SCHEDULING_ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
	static class ScheduledJobs {
	}

	@Value("${" + SCHEDULING_ENABLED_PROPERTY + ":true}")
	private String schedulingEnabled;

	/**
	 * Says so when nothing will run. The condition above matches only "true", so any
	 * other value -- including a typo -- stops every scheduled job, and without this
	 * line that would be silent.
	 */
	@PostConstruct
	void reportDisabled() {
		if (!Boolean.TRUE.toString().equalsIgnoreCase(schedulingEnabled.trim())) {
			log.error("{}={} -- NO @Scheduled job will run in this process (metrics, drains, "
					+ "notification delivery, KEV sync, retention) and the startup duplicate-component "
					+ "census is skipped. Expected only in the test build.",
					SCHEDULING_ENABLED_PROPERTY, schedulingEnabled);
		}
	}
}
