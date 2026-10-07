/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import io.reliza.common.Utils;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaCleDefinitions;
import io.reliza.model.tea.TeaCleEvent;
import io.reliza.model.tea.TeaCleEventType;
import io.reliza.model.tea.TeaCleSupportDefinition;
import io.reliza.model.tea.TeaCleVersionSpecifier;
import io.reliza.service.SharedReleaseService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every CLE document {@link TeaTransformerService#wrapAsCleDocument} emits validates
 * against the TEA 0.4.0 {@code cle} schema at
 * {@code src/test/resources/schema/tea-0.4.0-cle.schema.json}, with date-time formats
 * asserted. Until the CLE models dropped absent fields, every export failed it: an
 * optional field written as {@code null} where the schema says string or integer.
 */
class TeaCleDocumentSchemaTest {

	private static final String SCHEMA_PATH = "/schema/tea-0.4.0-cle.schema.json";
	private static final JsonSchema SCHEMA = loadSchema();

	private static JsonSchema loadSchema() {
		JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
		SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
		try (InputStream in = TeaCleDocumentSchemaTest.class.getResourceAsStream(SCHEMA_PATH)) {
			if (in == null) throw new IllegalStateException("CLE schema fixture not found at " + SCHEMA_PATH);
			return factory.getSchema(in, config);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load the CLE schema fixture", e);
		}
	}

	private static Set<ValidationMessage> errorsOf(JsonNode doc) {
		return SCHEMA.validate(doc.toString(), InputFormat.JSON);
	}

	private static void assertSchemaValid(JsonNode doc) {
		Set<ValidationMessage> errors = errorsOf(doc);
		assertTrue(errors.isEmpty(), () -> "TEA 0.4.0 cle schema validation failed:\n" + errors + "\n---\n" + doc);
	}

	private TeaTransformerService service;
	private SharedReleaseService sharedReleaseService;

	@BeforeEach
	void wire() {
		service = new TeaTransformerService();
		sharedReleaseService = mock(SharedReleaseService.class);
		ReflectionTestUtils.setField(service, "sharedReleaseService", sharedReleaseService);
	}

	private static final ZonedDateTime GA_AT = ZonedDateTime.parse("2026-01-01T00:00:00Z");
	private static final ZonedDateTime EOS_AT = ZonedDateTime.parse("2026-09-01T10:00:00Z");
	private static final ZonedDateTime EOL_AT = ZonedDateTime.parse("2026-09-15T10:00:00Z");

	private static ReleaseData release(UUID org, String version, ReleaseLifecycle... after) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "org", org);
		ReflectionTestUtils.setField(rd, "version", version);
		ReflectionTestUtils.setField(rd, "createdDate", GA_AT.minusDays(1));
		rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED,
				ReleaseLifecycle.ASSEMBLED.name(), ReleaseLifecycle.GENERAL_AVAILABILITY.name(),
				null, GA_AT, mock(WhoUpdated.class)));
		ReleaseLifecycle from = ReleaseLifecycle.GENERAL_AVAILABILITY;
		for (ReleaseLifecycle to : after) {
			rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED,
					from.name(), to.name(), null, to == ReleaseLifecycle.END_OF_LIFE ? EOL_AT : EOS_AT,
					mock(WhoUpdated.class)));
			from = to;
		}
		return rd;
	}

	@Test
	void aComponentCle_releasedSupportEndedAndARange_isSchemaValid() {
		UUID org = UUID.randomUUID();
		ComponentData cd = new ComponentData();
		ReflectionTestUtils.setField(cd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(cd, "versionSchema", "semver");
		when(sharedReleaseService.listReleaseDatasOfComponent(any(UUID.class), any(Integer.class), any(Integer.class)))
				.thenReturn(List.of(release(org, "1.0.0", ReleaseLifecycle.END_OF_SUPPORT),
						release(org, "1.0.1", ReleaseLifecycle.END_OF_SUPPORT),
						release(org, "1.1.0", ReleaseLifecycle.END_OF_SUPPORT, ReleaseLifecycle.END_OF_LIFE),
						release(org, "2.0.0")));

		JsonNode doc = service.wrapAsCleDocument(service.transformComponentToCle(cd), null);

		assertTrue(doc.get("events").size() >= 3, () -> "sanity: released, support and life events expected: " + doc);
		assertFalse(doc.toString().contains(":null"), () -> "no field may be written as null: " + doc);
		assertSchemaValid(doc);
	}

	@Test
	void aReleaseCle_withADeclaredSupportWindow_isSchemaValid() {
		ReleaseData rd = release(UUID.randomUUID(), "1.0.0");
		rd.setEos(LocalDate.parse("2033-06-15"));
		rd.setEol(LocalDate.parse("2035-12-31"));
		rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.SUPPORT_WINDOW, ReleaseUpdateAction.ADDED,
				null, ReleaseData.supportWindowValueString(rd.getEos(), rd.getEol()), null, GA_AT,
				mock(WhoUpdated.class)));

		assertSchemaValid(service.wrapAsCleDocument(service.transformReleaseToCle(rd), null));
	}

	@Test
	void everyOptionalEventFieldAndDefinitions_areSchemaValidWhenSet() {
		TeaCleEvent ev = new TeaCleEvent(0, TeaCleEventType.SUPERSEDED_BY,
				OffsetDateTime.parse("2027-06-30T00:00:00Z"), OffsetDateTime.parse("2026-09-02T10:15:00Z"));
		ev.setVersions(List.of(new TeaCleVersionSpecifier().range("vers:semver/<2.0.0")));
		ev.setSupersededByVersion("2.0.0");
		ev.setSupportId("standard");
		ev.setDescription("Superseded by the 2.x line.");
		ev.setReferences(List.of(URI.create("https://example.com/notes/2.0.0")));
		TeaCle cle = new TeaCle(List.of(ev));
		TeaCleSupportDefinition support = new TeaCleSupportDefinition("standard", "Standard support");
		cle.setDefinitions(new TeaCleDefinitions().support(List.of(support)));

		JsonNode doc = service.wrapAsCleDocument(cle, null);

		assertFalse(doc.get("definitions").get("support").get(0).has("url"), "an absent url is left out");
		assertSchemaValid(doc);
	}

	@Test
	void aCleSerialisedWhole_asTheTeaApiReturnsIt_isSchemaValid() {
		// the TEA API returns the TeaCle itself, not wrapAsCleDocument's tree: no "definitions": null
		TeaCleEvent ev = new TeaCleEvent(0, TeaCleEventType.END_OF_SUPPORT,
				OffsetDateTime.parse("2027-06-30T00:00:00Z"), OffsetDateTime.parse("2026-09-02T10:15:00Z"));
		JsonNode cle = Utils.OM.valueToTree(new TeaCle(List.of(ev)));
		assertFalse(cle.has("definitions"), () -> "no definitions key when there are none: " + cle);
		assertSchemaValid(cle);
	}

	@Test
	void anOptionalFieldWrittenAsNull_isSchemaInvalid_whichIsWhyTheModelsLeaveItOut() {
		TeaCleEvent ev = new TeaCleEvent(0, TeaCleEventType.END_OF_SUPPORT,
				OffsetDateTime.parse("2027-06-30T00:00:00Z"), OffsetDateTime.parse("2026-09-02T10:15:00Z"));
		JsonNode doc = service.wrapAsCleDocument(new TeaCle(List.of(ev)), null);
		assertSchemaValid(doc);
		assertFalse(doc.get("events").get(0).has("description"), "an absent description is left out");

		// the shape every export had before: explicit nulls
		((ObjectNode) doc.get("events").get(0)).putNull("description");
		((ObjectNode) doc.get("events").get(0)).putNull("eventId");
		assertEquals(2, errorsOf(doc).size(), () -> "expected one error per null field: " + errorsOf(doc));
	}
}
