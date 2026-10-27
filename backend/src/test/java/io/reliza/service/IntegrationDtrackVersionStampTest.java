/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.testsupport.StampAssertions.assertContentSave;
import static io.reliza.testsupport.StampAssertions.assertJsonType;
import static io.reliza.testsupport.StampAssertions.assertStampedOnly;
import static io.reliza.testsupport.StampAssertions.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.DependencyTrackVersion;
import io.reliza.model.IntegrationData.IntegrationCapability;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.testsupport.StampAssertions.RowSnapshot;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The Dependency-Track version a probe detected is stored in place through
 * IntegrationRepository.stampDtrackVersion: no revision, no audit row, the sweep's re-read-and-compare guard
 * as the WHERE (TPF-71 / TPF-107, architecture round 2 section 5.2). A capabilities change stays one revision.
 */
@SpringBootTest(classes = {App.class})
public class IntegrationDtrackVersionStampTest {

	@MockitoSpyBean private IntegrationService integrationService;
	@Autowired private IntegrationRepository repository;
	@Autowired private AuditService auditService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TestInitializer testInitializer;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private record Row(UUID uuid, String uri) {}

	/** A base DEPENDENCYTRACK integration on a fresh org, its dtrackVersion removed (not detected). */
	private Row dtrack() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		URI uri = URI.create("https://dt-" + UUID.randomUUID() + ".example");
		UUID uuid = integrationService.createIntegration(CommonVariables.BASE_INTEGRATION_IDENTIFIER, org,
				IntegrationType.DEPENDENCYTRACK, uri, "k", null, null, WU).getUuid();
		jdbc.update("update rearm.integrations set record_data = record_data - 'dtrackVersion' where uuid = ?", uuid);
		return new Row(uuid, uri.toString());
	}

	private RowSnapshot snap(Row r) {
		return snapshot(jdbc, TableName.INTEGRATIONS, r.uuid());
	}

	private IntegrationData data(Row r) {
		return IntegrationData.dataFromRecord(repository.findById(r.uuid()).orElseThrow());
	}

	// row 1
	@Test
	public void theStampStoresTheDetectedVersionWithoutARevisionOrAnAuditRow() {
		Row r = dtrack();
		RowSnapshot before = snap(r);
		assertNull(data(r).getDtrackVersion());

		assertEquals(1, repository.stampDtrackVersion(r.uuid(), "V4", null, r.uri()));

		RowSnapshot after = snap(r);
		assertStampedOnly(before, after, Set.of("dtrackVersion"));
		assertJsonType(jdbc, TableName.INTEGRATIONS, r.uuid(), "dtrackVersion", "string");
		assertEquals(DependencyTrackVersion.V4, data(r).getDtrackVersion());
		assertEquals(DependencyTrackVersion.V4, data(r).getEffectiveDtrackVersion());

		assertEquals(1, repository.stampDtrackVersion(r.uuid(), "V5", "V4", r.uri()));
		assertStampedOnly(after, snap(r), Set.of("dtrackVersion"));
		assertEquals(DependencyTrackVersion.V5, data(r).getDtrackVersion());
	}

	// the scope of the statement: its own row only
	@Test
	public void theStampChangesItsOwnRowOnlyEvenWhenAnotherRowHoldsTheSameVersionAndUri() {
		Row a = dtrack();
		Row b = dtrack();            // another integration, another org: made to hold the same URI and the same (absent) version
		jdbc.update("update rearm.integrations set record_data = jsonb_set(record_data, '{uri}', to_jsonb(cast(? as text))) where uuid = ?",
				a.uri(), b.uuid());
		RowSnapshot otherBefore = snap(b);
		assertNull(data(b).getDtrackVersion());

		assertEquals(1, repository.stampDtrackVersion(a.uuid(), "V4", null, a.uri()));

		assertEquals(DependencyTrackVersion.V4, data(a).getDtrackVersion());
		assertEquals(otherBefore, snap(b), "another row with the same version and URI was stamped");
		assertEquals(0, repository.stampDtrackVersion(UUID.randomUUID(), "V4", null, a.uri()),
				"an unknown uuid with a matching version and URI stamps nothing");
		assertEquals(otherBefore, snap(b));
	}

	// row 2
	@Test
	public void theGuardHoldsWhenTheVersionMovedMeanwhile() {
		Row r = dtrack();
		assertEquals(1, repository.stampDtrackVersion(r.uuid(), "V4", null, r.uri()));
		RowSnapshot before = snap(r);

		assertEquals(0, repository.stampDtrackVersion(r.uuid(), "V5", null, r.uri()),
				"the probe started from not detected, the row holds V4 now");

		assertStampedOnly(before, snap(r), Set.of());
		assertEquals(DependencyTrackVersion.V4, data(r).getDtrackVersion());
	}

	// row 3
	@Test
	public void theGuardHoldsWhenTheUriChangedMeanwhile() {
		Row r = dtrack();
		assertEquals(1, repository.stampDtrackVersion(r.uuid(), "V4", null, r.uri()));
		RowSnapshot before = snap(r);

		assertEquals(0, repository.stampDtrackVersion(r.uuid(), "V5", "V4", "https://elsewhere.example"));
		assertStampedOnly(before, snap(r), Set.of());

		jdbc.update("update rearm.integrations set record_data = jsonb_set(record_data, '{uri}', "
				+ "to_jsonb(cast('https://moved.example' as text))) where uuid = ?", r.uuid());
		RowSnapshot moved = snap(r);
		assertEquals(0, repository.stampDtrackVersion(r.uuid(), "V5", "V4", r.uri()), "the old URI no longer matches");
		assertStampedOnly(moved, snap(r), Set.of());
		assertEquals(1, repository.stampDtrackVersion(r.uuid(), "V5", "V4", "https://moved.example"));
		assertEquals(DependencyTrackVersion.V5, data(r).getDtrackVersion());
	}

	// row 4
	@Test
	public void aJsonNullAndAnAbsentKeyBothReadAsNotDetected() {
		Row jsonNull = dtrack();
		jdbc.update("update rearm.integrations set record_data = jsonb_set(record_data, '{dtrackVersion}', 'null') "
				+ "where uuid = ?", jsonNull.uuid());
		assertJsonType(jdbc, TableName.INTEGRATIONS, jsonNull.uuid(), "dtrackVersion", "null");
		Row absent = dtrack();

		assertEquals(1, repository.stampDtrackVersion(jsonNull.uuid(), "V4", null, jsonNull.uri()));
		assertEquals(1, repository.stampDtrackVersion(absent.uuid(), "V4", null, absent.uri()));
		assertEquals(DependencyTrackVersion.V4, data(jsonNull).getDtrackVersion());
		assertEquals(DependencyTrackVersion.V4, data(absent).getDtrackVersion());
	}

	// row 5: the probe is stubbed per URI, so no other class's base DEPENDENCYTRACK row is stamped, and the
	// assertions are on this row only (the sweep reads every org's rows; a blanket stub is forbidden, 5.4)
	@Test
	public void theSweepStoresInPlaceOnARealRow() throws Exception {
		Row r = dtrack();
		URI ownUri = URI.create(r.uri());
		doReturn(DependencyTrackVersion.V4).when(integrationService).probeDtrackVersion(eq(ownUri), any());
		doThrow(new RelizaException("no answer")).when(integrationService)
				.probeDtrackVersion(argThat(u -> !ownUri.equals(u)), any());
		RowSnapshot before = snap(r);

		int stored = integrationService.redetectDtrackVersions();

		assertTrue(stored >= 1, "the own row is stored: " + stored);
		RowSnapshot after = snap(r);
		assertStampedOnly(before, after, Set.of("dtrackVersion"));
		assertEquals(DependencyTrackVersion.V4, data(r).getDtrackVersion());

		integrationService.redetectDtrackVersions();
		assertStampedOnly(after, snap(r), Set.of());
	}

	// row 6
	@Test
	public void aCapabilitiesChangeIsStillOneRevision() throws Exception {
		Row r = dtrack();
		assertEquals(1, repository.stampDtrackVersion(r.uuid(), "V4", null, r.uri()));
		RowSnapshot before = snap(r);

		integrationService.updateCapabilities(r.uuid(), List.of(IntegrationCapability.WORKFLOW_DISPATCH), WU);

		assertContentSave(before, snap(r));
		IntegrationData auditCopy = Utils.OM.convertValue(auditService.getAuditForEntity(r.uuid(),
				TableName.INTEGRATIONS, 1000, 0).get(0).getRevisionRecordData(), IntegrationData.class);
		assertEquals(DependencyTrackVersion.V4, auditCopy.getDtrackVersion(),
				"the stamped value rides into the trail with the next content change");
		assertEquals(DependencyTrackVersion.V4, data(r).getDtrackVersion());
	}
}
