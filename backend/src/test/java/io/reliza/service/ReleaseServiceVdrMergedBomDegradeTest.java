/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.cyclonedx.model.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.exceptions.MergedSbomUnavailableException;
import io.reliza.exceptions.MergedSbomUnavailableException.ChildBomOutcome;
import io.reliza.exceptions.MergedSbomUnavailableException.ChildBomStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import tools.jackson.databind.JsonNode;

/**
 * TEA-1: the VDR's component enrichment degrades to minimal components when the merged SBOM is
 * unavailable, and tells "no SBOM" (an ordinary answer, no ERROR) from a failed component
 * release (an ERROR with the message) by type, never by matching the message text.
 */
class ReleaseServiceVdrMergedBomDegradeTest {

	private CapturingAppender logs;

	/** In-memory appender on ReleaseService's own logger; only the level and the message matter. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	@BeforeEach
	void attach() {
		logs = new CapturingAppender();
		logs.start();
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseService.class)).addAppender(logs);
	}

	@AfterEach
	void detach() {
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseService.class)).removeAppender(logs);
		logs.stop();
	}

	private interface MergedBom {
		Optional<JsonNode> get(ReleaseData rd) throws RelizaException;
	}

	private static ReleaseService serviceWhoseMergedBom(MergedBom mergedBom) {
		return new ReleaseService(null) {
			@Override
			Optional<JsonNode> mergedBomForVdr(UUID releaseUuid, UUID org, WhoUpdated wu) throws RelizaException {
				ReleaseData rd = new ReleaseData();
				ReflectionTestUtils.setField(rd, "uuid", releaseUuid);
				return mergedBom.get(rd);
			}
		};
	}

	private static ReleaseData release() {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", UUID.randomUUID());
		rd.setVersion("9000.1.0");
		return rd;
	}

	private List<LogEvent> errors() {
		return logs.events.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
	}

	@Test
	void noSbomDegradesToMinimalComponentsWithoutAnError() {
		Map<String, Component> enriched = serviceWhoseMergedBom(rd -> Optional.empty())
				.mergedBomComponentsByPurl(release());

		assertTrue(enriched.isEmpty(), enriched.toString());
		assertEquals(List.of(), errors(), "no SBOM is an ordinary answer for the VDR");
	}

	@Test
	void aFailedComponentReleaseDegradesAndLogsOneErrorWithTheMessage() {
		ComponentData cd = new ComponentData();
		cd.setName("device");
		cd.setType(ComponentType.PRODUCT);
		ReleaseData product = release();
		ReflectionTestUtils.setField(product, "component", UUID.randomUUID());
		MergedSbomUnavailableException failure = MergedSbomUnavailableException.childMergeFailed(product, cd,
				List.of(new ChildBomOutcome(UUID.randomUUID(), "firmware", "1.0.0", ChildBomStatus.FAILED,
						null, "BOM not found: x")));

		Map<String, Component> enriched = serviceWhoseMergedBom(rd -> { throw failure; })
				.mergedBomComponentsByPurl(product);

		assertTrue(enriched.isEmpty(), enriched.toString());
		List<LogEvent> errors = errors();
		assertEquals(1, errors.size(), errors.toString());
		assertTrue(errors.get(0).getMessage().getFormattedMessage().contains(failure.getMessage()),
				errors.get(0).getMessage().getFormattedMessage());
	}

	@Test
	void aMergedBomEnrichesByMinimizedPurl() {
		JsonNode bom = Utils.OM.readTree("""
				{"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
				 "components": [{"type": "library", "name": "minimist", "version": "1.2.0",
				   "purl": "pkg:npm/minimist@1.2.0"}]}""");

		Map<String, Component> enriched = serviceWhoseMergedBom(rd -> Optional.of(bom))
				.mergedBomComponentsByPurl(release());

		assertEquals(1, enriched.size(), enriched.toString());
		assertEquals("1.2.0", enriched.values().iterator().next().getVersion());
		assertEquals(List.of(), errors());
	}
}
