/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;

/**
 * Pins the one switch that keeps {@code @Scheduled} work out of the test build
 * ({@link SchedulingConfig}), on a minimal context rather than on {@link App}:
 * an App context with scheduling on would be cached for the rest of the run and
 * tick against the shared database, which is the problem the switch exists for.
 *
 * <p>Also stands in for what the switch takes away. With scheduling off, no test
 * context registers the real schedules any more, so a bad cron, duration or
 * placeholder would pass the suite and fail the first production boot.
 * {@link #everyScheduledDeclarationResolves()} parses every one the way
 * Spring's ScheduledAnnotationBeanPostProcessor does.
 */
public class SchedulingConfigTest {

	private static final String CLASS_PATTERN = "classpath*:io/reliza/**/*.class";
	/** Where the backend's own config lives; surefire runs from the module directory. */
	private static final String APPLICATION_YAML = "application.yaml";
	/** A cron of "-" disables the job, which Spring accepts. */
	private static final String CRON_DISABLED = "-";

	// Deliberately NOT @Configuration: App's @ComponentScan over io.reliza.ws also scans test
	// classes, and a scanned taskScheduler bean here collides with App's in every App context.
	// The runner registers this class itself and processes its @Bean methods in lite mode.
	static class ProbeConfig {
		@Bean
		TaskScheduler taskScheduler() {
			return new ThreadPoolTaskScheduler();
		}

		@Bean
		TickProbe tickProbe() {
			return new TickProbe();
		}
	}

	static class TickProbe {
		@Scheduled(fixedDelayString = "PT1H")
		public void tick() {
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(SchedulingConfig.class, ProbeConfig.class);

	@Test
	public void schedulingIsOnWhenThePropertyIsAbsent() {
		// Production never sets the property, so this is the production case. The
		// runner's environment includes system properties, where surefire has set it
		// to false, so it is cleared for the duration of this run.
		String surefireValue = System.clearProperty(SchedulingConfig.SCHEDULING_ENABLED_PROPERTY);
		try {
			runner.run(context -> {
				ScheduledTaskHolder holder = context.getBean(ScheduledTaskHolder.class);
				assertEquals(1, holder.getScheduledTasks().size(),
						"with the property absent the @Scheduled probe must be registered");
			});
		} finally {
			if (null != surefireValue) System.setProperty(SchedulingConfig.SCHEDULING_ENABLED_PROPERTY, surefireValue);
		}
	}

	@Test
	public void schedulingIsOffWhenThePropertyIsFalse() {
		runner.withPropertyValues(SchedulingConfig.SCHEDULING_ENABLED_PROPERTY + "=false").run(context ->
				assertTrue(context.getBeansOfType(ScheduledTaskHolder.class).isEmpty(),
						"with the property false no @Scheduled method may be registered"));
	}

	@Test
	public void theTestBuildRunsWithSchedulingOff() {
		// Set by maven-surefire-plugin in pom.xml. Without it every cached
		// @SpringBootTest context ticks against the shared test database again.
		assertEquals("false", System.getProperty(SchedulingConfig.SCHEDULING_ENABLED_PROPERTY),
				"surefire must pass " + SchedulingConfig.SCHEDULING_ENABLED_PROPERTY + "=false to the test JVM");
	}

	@Test
	public void everyScheduledDeclarationResolves() throws IOException {
		StandardEnvironment env = new StandardEnvironment();
		FileSystemResource yaml = new FileSystemResource(APPLICATION_YAML);
		assertTrue(yaml.exists(), APPLICATION_YAML + " not found from " + yaml.getFile().getAbsolutePath());
		for (PropertySource<?> ps : new YamlPropertySourceLoader().load(APPLICATION_YAML, yaml)) {
			env.getPropertySources().addLast(ps);
		}

		List<String> problems = new ArrayList<>();
		int declarations = 0;
		CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory();
		for (Resource classFile : new PathMatchingResourcePatternResolver().getResources(CLASS_PATTERN)) {
			// Main classes only: test-only probes such as TickProbe above are not production schedules.
			if (classFile.getURL().toString().contains("/test-classes/")) continue;
			MetadataReader reader = readers.getMetadataReader(classFile);
			for (MethodMetadata method : reader.getAnnotationMetadata().getAnnotatedMethods(Scheduled.class.getName())) {
				declarations++;
				String where = method.getDeclaringClassName() + "#" + method.getMethodName();
				MergedAnnotation<Scheduled> scheduled = method.getAnnotations().get(Scheduled.class);
				try {
					checkScheduled(scheduled, env);
				} catch (RuntimeException e) {
					problems.add(where + ": " + e.getMessage());
				}
			}
		}
		assertTrue(declarations > 0, "found no @Scheduled methods -- the classpath scan is broken");
		if (!problems.isEmpty()) {
			fail(problems.size() + " @Scheduled declaration(s) would fail at startup:\n  "
					+ String.join("\n  ", problems));
		}
	}

	private static void checkScheduled(MergedAnnotation<Scheduled> scheduled, StandardEnvironment env) {
		TimeUnit unit = scheduled.getEnum("timeUnit", TimeUnit.class);
		DurationFormat.Unit durationUnit = DurationFormat.Unit.fromChronoUnit(unit.toChronoUnit());
		int triggers = 0;

		String cron = scheduled.getString("cron");
		if (!cron.isEmpty()) {
			triggers++;
			String resolved = env.resolveRequiredPlaceholders(cron);
			if (!CRON_DISABLED.equals(resolved)) CronExpression.parse(resolved);
			String zone = scheduled.getString("zone");
			if (!zone.isEmpty()) ZoneId.of(env.resolveRequiredPlaceholders(zone));
		}
		for (String attribute : List.of("fixedDelay", "fixedRate")) {
			boolean numeric = scheduled.getLong(attribute) >= 0;
			String text = scheduled.getString(attribute + "String");
			if (numeric || !text.isEmpty()) triggers++;
			if (!text.isEmpty()) {
				DurationFormatterUtils.detectAndParse(env.resolveRequiredPlaceholders(text), durationUnit);
			}
		}
		String initialDelay = scheduled.getString("initialDelayString");
		if (!initialDelay.isEmpty()) {
			DurationFormatterUtils.detectAndParse(env.resolveRequiredPlaceholders(initialDelay), durationUnit);
		}
		if (1 != triggers) {
			throw new IllegalStateException("exactly one of cron, fixedDelay(String), fixedRate(String) "
					+ "must be set, found " + triggers);
		}
	}
}
