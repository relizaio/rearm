/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import java.util.ArrayList;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.LevelOfSupport;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentSupport;
import io.reliza.model.SbomComponentSupportAudit;
import io.reliza.model.SbomComponentPage;
import io.reliza.model.SupportAttestationFilter;
import java.util.HashSet;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.model.SupportBulkOutcome;
import io.reliza.model.SupportBulkResult;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.model.SupportStatus;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.service.SbomComponentService.SupportCoverage;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import tools.jackson.databind.JsonNode;
import io.reliza.ws.oss.TestInitializer;

/**
 * Behavioral validation for the V83 support-attestation shape: ONE JSONB row per assessed
 * component, replacing V901's per-milestone child rows. Boots the app against the shared
 * rearm-test-pg so the migration actually applies, then exercises the write path end to end
 * -- milestones persist with independent provenance, the append-only history captures one
 * after-image per CALL, an omitted milestone survives a later partial edit, and coverage
 * counts move.
 *
 * <p>Three capabilities here did not exist before V83 and are the point of the change:
 * "assessed, nothing published" is storable and COUNTS as coverage; {@code assessedAt} is
 * caller-supplied so it can precede the filing; and an attestation can be WITHDRAWN without
 * destroying its history.
 *
 * <p>Uses a fresh per-test org so the shared DB cannot contaminate the assertions.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SbomComponentSupportServiceIntegrationTest {

	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private SbomComponentSupportRepository supportRepository;
	@Autowired private SbomComponentSupportAuditRepository auditRepository;
	@Autowired private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Autowired private ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private SupportInjectionService supportInjectionService;
	@Autowired private DataSource dataSource;

	private SbomComponent newComponent(UUID org, String canonicalPurl) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonicalPurl);
		return sbomComponentRepository.save(sc);
	}

	/** Dates-only attestation, the common case. */
	private static SupportAttestationRequest dates(LocalDate eogs, LocalDate eos, LocalDate eol, String notes) {
		return new SupportAttestationRequest(null, null, null, null, null, eogs, eos, eol,
				notes, Set.of(), null);
	}

	private SupportData supportOf(UUID componentUuid) {
		return supportRepository.findBySbomComponentUuid(componentUuid).orElseThrow().getSupportData();
	}

	@Test
	void attestationPersistsDerivesAuditsAndCountsCoverage() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		String purl = "pkg:maven/org.example/lib@1.2.3?t=" + UUID.randomUUID();
		SbomComponent sc = newComponent(orgUuid, purl);

		UUID attester = UUID.randomUUID();
		LocalDate pastEos = LocalDate.now().minusDays(30);
		sbomComponentService.setSbomComponentSupport(
				sc.getUuid(), dates(null, pastEos, null, "vendor announced EOS"), attester);

		// Persisted facts: one END_OF_SUPPORT milestone in the payload, no others.
		SupportData data = supportOf(sc.getUuid());
		SupportMilestoneFact eos = data.milestones().get(SupportMilestoneType.END_OF_SUPPORT);
		assertNotNull(eos);
		assertEquals(pastEos, eos.dateValue());
		assertEquals(SupportSource.MANUAL, eos.source());
		assertEquals(attester, eos.assertedBy());
		assertNotNull(eos.lastAssessed());
		assertEquals("vendor announced EOS", eos.notes());
		assertNull(data.milestones().get(SupportMilestoneType.END_OF_LIFE));
		assertEquals(attester, data.assertedBy());
		assertEquals(SupportState.ATTESTED, data.state());

		// Derived status: past EOS, no EOGS/EOL -> END_OF_SUPPORT.
		assertEquals(SupportStatus.END_OF_SUPPORT,
				SupportStatus.derive(null, eos.dateValue(), LocalDate.now()));

		// Append-only history: ONE after-image row per CALL (not per milestone, as V901 did).
		List<SbomComponentSupportAudit> hist = auditRepository
				.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid());
		assertEquals(1, hist.size());
		assertEquals(pastEos,
				hist.get(0).getSupportData().milestoneDate(SupportMilestoneType.END_OF_SUPPORT));
		assertEquals(attester, hist.get(0).getAssertedBy());
		assertNotNull(hist.get(0).getAssertedDate());

		// A second attestation on the SAME milestone APPENDS to history and moves the dates.
		LocalDate futureEos = LocalDate.now().plusYears(1);
		sbomComponentService.setSbomComponentSupport(
				sc.getUuid(), dates(null, futureEos, null, null), attester);
		assertEquals(2, auditRepository.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid()).size());
		assertEquals(futureEos, supportOf(sc.getUuid()).milestoneDate(SupportMilestoneType.END_OF_SUPPORT));

		// A FUTURE EOS derives UNKNOWN, never ACTIVELY_SUPPORTED. A published horizon says
		// nothing about whether anyone is maintaining the component today; claiming otherwise
		// was ReARM asserting upstream maintenance on the manufacturer's behalf.
		assertEquals(SupportStatus.UNKNOWN,
				SupportStatus.derive(null, futureEos, LocalDate.now()));

		// Coverage counts the (only, non-root) MANUAL-attested component in this fresh org.
		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid);
		assertEquals(1, cov.total(), "the one non-root component is counted in total");
		assertEquals(1, cov.attested(), "the MANUAL attestation is counted");

		// Discriminating: a SUPPLIER-sourced attestation is NOT a manufacturer disclosure, so it
		// counts toward total but MUST NOT count as attested (pins the MANUAL-only numerator).
		SbomComponent supplierComp = newComponent(orgUuid,
				"pkg:maven/org.example/other@2.0.0?t=" + UUID.randomUUID());
		SbomComponentSupport supplierRow = new SbomComponentSupport();
		supplierRow.setSbomComponentUuid(supplierComp.getUuid());
		supplierRow.setOrg(orgUuid);
		supplierRow.setCanonicalPurl(supplierComp.getCanonicalPurl());
		supplierRow.setSupportData(new SupportData(null, SupportState.ATTESTED, null,
				SupportSource.SUPPLIER, ZonedDateTime.now().toInstant().toString(), null, null,
				Map.of(SupportMilestoneType.END_OF_SUPPORT, new SupportMilestoneFact(
						LocalDate.now().plusMonths(6).toString(), SupportSource.SUPPLIER,
						ZonedDateTime.now().toInstant().toString(), null, null))));
		supportRepository.save(supplierRow);
		SupportCoverage cov2 = sbomComponentService.getSupportCoverage(orgUuid);
		assertEquals(2, cov2.total(), "both non-root components counted in total");
		assertEquals(1, cov2.attested(), "SUPPLIER provenance excluded; only MANUAL attested");
	}

	/**
	 * Per-date provenance: EOS asserted from one source, then EOL asserted from a DIFFERENT
	 * source in a later call, must leave EOS's own provenance untouched -- the case a shared
	 * (source, lastAssessed, assertedBy) triple could not represent.
	 */
	@Test
	void mixedSourceMilestonesPreserveIndependentProvenance() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent sc = newComponent(orgUuid,
				"pkg:maven/org.example/mixed@1.0.0?t=" + UUID.randomUUID());

		LocalDate eosDate = LocalDate.now().plusMonths(3);
		sbomComponentService.setSbomComponentSupport(
				sc.getUuid(), dates(null, eosDate, null, "catalog suggestion"), null);
		// The MANUAL entry point always writes SupportSource.MANUAL -- rewrite the stored EOS
		// milestone directly to exercise a genuinely different source without depending on the
		// (separately gated) suggestion-acceptance service.
		SbomComponentSupport row = supportRepository.findBySbomComponentUuid(sc.getUuid()).orElseThrow();
		SupportData before = row.getSupportData();
		SupportMilestoneFact enrichedEos = new SupportMilestoneFact(
				eosDate.toString(), SupportSource.ENRICHED, before.assessedAt(), null, "catalog suggestion");
		row.setSupportData(new SupportData(before.levelOfSupport(), before.state(), before.party(),
				before.assessmentSource(), before.assessedAt(), before.assertedBy(),
				before.justification(),
				Map.of(SupportMilestoneType.END_OF_SUPPORT, enrichedEos)));
		supportRepository.save(row);

		// EOL asserted later, MANUAL, by a human.
		UUID manualAttester = UUID.randomUUID();
		LocalDate eolDate = LocalDate.now().plusYears(2);
		sbomComponentService.setSbomComponentSupport(
				sc.getUuid(), dates(null, null, eolDate, "manufacturer confirmed EOL"), manualAttester);

		SupportData after = supportOf(sc.getUuid());
		SupportMilestoneFact eosAfter = after.milestones().get(SupportMilestoneType.END_OF_SUPPORT);
		SupportMilestoneFact eolAfter = after.milestones().get(SupportMilestoneType.END_OF_LIFE);

		// EOS's own provenance survived the EOL-only edit untouched.
		assertEquals(SupportSource.ENRICHED, eosAfter.source());
		assertEquals(eosDate, eosAfter.dateValue());
		assertNull(eosAfter.assertedBy());
		// EOL carries its own, independent MANUAL provenance.
		assertEquals(SupportSource.MANUAL, eolAfter.source());
		assertEquals(eolDate, eolAfter.dateValue());
		assertEquals(manualAttester, eolAfter.assertedBy());
	}

	/** The third milestone date makes SECURITY_ONLY reachable end-to-end. */
	@Test
	void endOfGuaranteedSupportMilestoneReachesSecurityOnly() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/eogs@1.0.0?t=" + UUID.randomUUID());
		LocalDate pastEogs = LocalDate.now().minusDays(1);
		LocalDate futureEos = LocalDate.now().plusYears(1);
		sbomComponentService.setSbomComponentSupport(
				sc.getUuid(), dates(pastEogs, futureEos, null, null), UUID.randomUUID());

		SupportData data = supportOf(sc.getUuid());
		assertEquals(SupportStatus.SECURITY_ONLY, SupportStatus.derive(
				data.milestoneDate(SupportMilestoneType.END_OF_GUARANTEED_SUPPORT),
				data.milestoneDate(SupportMilestoneType.END_OF_SUPPORT), LocalDate.now()));
	}

	/**
	 * Party is independent OF MILESTONES -- not of the attestation. It is a single
	 * record-level fact that survives with or without any date, which is what this asserts.
	 *
	 * <p>It is deliberately NOT independent of the attestation, and the paired justification
	 * here is the proof rather than an inconvenience: party alone no longer opens a record.
	 * Party classifies WHO OWNS the component; it is not an assessment of its support, and it
	 * sits in the same category as {@code notes} and {@code state} -- attributes OF an
	 * attestation, with nothing to attach to on their own. A row conjured from party alone
	 * would still carry assessmentSource=MANUAL and assessedAt=now and count toward the
	 * coverage gauge, asserting that support was assessed when only ownership was recorded.
	 * See the fresh-row guard in applySupport.
	 */
	@Test
	void supportPartyPersistsIndependentlyOfMilestones() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/party@1.0.0?t=" + UUID.randomUUID());
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, null, SupportParty.THIRD_PARTY,
						"third-party dependency; upstream publishes no EOS", null,
						null, null, null, null, Set.of(), null),
				UUID.randomUUID());

		SupportData data = supportOf(sc.getUuid());
		assertEquals(SupportParty.THIRD_PARTY, data.party());
		// No dates were supplied -> no milestones, but the attestation row still exists.
		assertTrue(data.milestones().isEmpty());
	}

	/**
	 * THE CASE V901 COULD NOT STORE. "I looked and the upstream publishes nothing" is a
	 * diligence record in its own right, not a lesser record than a dated one. Its milestone
	 * map is empty, and it MUST still count as coverage -- otherwise the readiness signal
	 * punishes the manufacturer for honestly reporting an absence.
	 */
	@Test
	void assessedButNothingPublishedIsStoredAndCountsAsCoverage() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent sc = newComponent(orgUuid,
				"pkg:maven/org.example/nodates@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null, null,
						"checked upstream releases and the security policy; no EOS is published",
						null, null, null, null, null, Set.of(), null),
				attester);

		SupportData data = supportOf(sc.getUuid());
		assertTrue(data.milestones().isEmpty(), "no dates were found, and that is the record");
		assertEquals(LevelOfSupport.ACTIVELY_MAINTAINED, data.levelOfSupport(),
				"a positive maintenance claim is attestable with no dates on record at all");
		assertNotNull(data.assessedAt(), "a level is never stored bare -- it carries its date");
		assertEquals(SupportSource.MANUAL, data.assessmentSource());
		assertTrue(data.hasManualAttestation());

		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid);
		assertEquals(1, cov.attested(), "an honest 'nothing published' is still an attestation");
	}

	/**
	 * assessedAt is CALLER-SUPPLIED and must survive verbatim: "I read the vendor advisory on
	 * the 12th and recorded it on the 30th". Forcing it equal to the write time makes ALCOA
	 * Contemporaneous unsatisfiable. The system's own record time is the audit assertedDate,
	 * which is a DIFFERENT value and must not be conflated.
	 */
	@Test
	void callerSuppliedAssessedAtIsPreservedAndDistinctFromRecordTime() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/backdated@1.0.0?t=" + UUID.randomUUID());
		ZonedDateTime assessedEarlier = ZonedDateTime.now(ZoneOffset.UTC).minusDays(18);

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, null, null, null, assessedEarlier,
						null, LocalDate.now().plusYears(1), null, null, Set.of(), null),
				UUID.randomUUID());

		SupportData data = supportOf(sc.getUuid());
		assertEquals(assessedEarlier.toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
				data.assessedAt(), "the human's assessment instant, stored as given");
		assertTrue(data.assessedAt().endsWith("Z"), "stored as a UTC RFC-3339 instant");

		SbomComponentSupportAudit audit = auditRepository
				.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid()).get(0);
		assertTrue(audit.getAssertedDate().toInstant().isAfter(assessedEarlier.toInstant()),
				"the system's record time is when it was FILED, not when it was assessed");
	}

	/**
	 * REGRESSION: a later PARTIAL write must not un-retract a WITHDRAWN attestation.
	 *
	 * <p>Omitting {@code state} used to hard-default it to ATTESTED while every sibling
	 * field preserved its stored value. So correcting a date on a withdrawn row -- or any
	 * future SUPPLIER/ENRICHED writer, which supplies no state at all -- silently
	 * republished a claim the manufacturer had taken back, into every exported BOM.
	 */
	@Test
	void aLaterPartialWriteDoesNotUnretractAWithdrawnAttestation() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/stayswithdrawn@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, "initial"), attester);
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, SupportState.WITHDRAWN, null,
						"asserted against the wrong component", null, null, null, null, null, Set.of(), null),
				attester);
		assertEquals(SupportState.WITHDRAWN, supportOf(sc.getUuid()).state());

		// A correcting write that says nothing about state.
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(2), null, null), attester);

		SupportData after = supportOf(sc.getUuid());
		assertEquals(SupportState.WITHDRAWN, after.state(),
				"omitting state must PRESERVE the retraction, never silently re-assert it");
		assertEquals(LocalDate.now().plusYears(2),
				after.milestoneDate(SupportMilestoneType.END_OF_SUPPORT),
				"the date edit itself still applies");
	}

	/**
	 * REGRESSION: a WITHDRAWN attestation must not be counted as coverage. It is not
	 * injected into any export, so counting it would have the pre-submission readiness
	 * gauge report coverage the served BOM does not contain -- the one number whose job
	 * is to be trustworthy, disagreeing with the artifact it describes.
	 */
	@Test
	void withdrawnAttestationsAreNotCountedAsCoverage() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent sc = newComponent(orgUuid,
				"pkg:maven/org.example/withdrawncoverage@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), attester);
		assertEquals(1, sbomComponentService.getSupportCoverage(orgUuid).attested());

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, SupportState.WITHDRAWN, null, "retracted",
						null, null, null, null, null, Set.of(), null),
				attester);

		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid);
		assertEquals(1, cov.total(), "the component still exists and still needs attesting");
		assertEquals(0, cov.attested(), "a retracted attestation is not coverage");
	}

	/**
	 * Pins the UNIQUE constraint NAME that {@code SbomComponentService} matches on to tell
	 * "another writer inserted the first attestation for this component concurrently"
	 * (retryable) from any other integrity error (not retryable).
	 *
	 * <p>Without this, renaming the constraint in a later migration would silently break
	 * that classification and the race would go back to surfacing as a raw database error
	 * to the user, with nothing failing to warn anyone.
	 *
	 * <p>NOTE this pins the CLASSIFICATION, not the concurrent behaviour itself. A genuine
	 * two-writer race test is not written -- see the PR discussion.
	 */
	@Test
	void duplicateAttestationRowIsRejectedByTheNamedConstraint() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/dup@1.0.0?t=" + UUID.randomUUID());
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), UUID.randomUUID());

		SbomComponentSupport duplicate = new SbomComponentSupport();
		duplicate.setSbomComponentUuid(sc.getUuid());
		duplicate.setOrg(org.getUuid());
		duplicate.setCanonicalPurl(sc.getCanonicalPurl());
		duplicate.setSupportData(new SupportData(null, SupportState.ATTESTED, null,
				SupportSource.MANUAL, ZonedDateTime.now().toInstant().toString(), null, null, Map.of()));

		Exception thrown = assertThrows(Exception.class, () -> {
			supportRepository.save(duplicate);
			supportRepository.count();
		});
		String chain = "";
		for (Throwable t = thrown; t != null && t.getCause() != t; t = t.getCause()) {
			chain += t.getMessage() + " | ";
		}
		assertTrue(chain.contains("sbom_component_support_component_unique"),
				"the violation must name the constraint the service classifies on; got: " + chain);
	}

	/**
	 * REGRESSION (#1): editing an unrelated date must NOT re-date an existing level-of-support
	 * attestation. assessedAt dates the LEVEL; re-stamping it on every write silently moved a
	 * months-old "abandoned" claim to today, so a reviewer judging the claim's age was judging
	 * a date nobody had assessed on. P2's entire staleness answer depends on that date being
	 * true.
	 */
	@Test
	void editingADateDoesNotReDateAnExistingLevelAttestation() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/redate@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();
		ZonedDateTime assessedLongAgo = ZonedDateTime.now(ZoneOffset.UTC).minusDays(45);

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ABANDONED, null, null,
						"upstream archived the repository", assessedLongAgo,
						null, null, null, null, Set.of(), null),
				attester);
		String originalAssessedAt = supportOf(sc.getUuid()).assessedAt();

		// A later, unrelated edit: a date, no level, no assessedAt.
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), attester);

		SupportData after = supportOf(sc.getUuid());
		assertEquals(LevelOfSupport.ABANDONED, after.levelOfSupport(), "the level itself is preserved");
		assertEquals(originalAssessedAt, after.assessedAt(),
				"the level's assessment date must NOT move when nobody re-assessed the level");
		// NOT assertNotNull: that passes under the bug too. The milestone was created by the
		// SECOND call, so stamping it with the preserved instant would assert a human assessed
		// it 45 days before it existed.
		assertNotEquals(originalAssessedAt,
				after.milestones().get(SupportMilestoneType.END_OF_SUPPORT).lastAssessed(),
				"the milestone's OWN lastAssessed moves -- it genuinely was assessed on this call");
	}

	/**
	 * The other half of the assessedAt rule: RE-ASSERTING the level DOES re-date it. Without
	 * this, "always preserve when the caller supplies no instant" passes every other test
	 * here while making a freshly re-assessed claim carry its original date.
	 */
	@Test
	void reAssertingTheLevelReDatesTheClaim() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/reassert@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ABANDONED, null, null, "archived",
						ZonedDateTime.now(ZoneOffset.UTC).minusDays(60), null, null, null, null, Set.of(), null),
				attester);
		String original = supportOf(sc.getUuid()).assessedAt();

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ABANDONED, null, null,
						"re-checked; still archived", null, null, null, null, null, Set.of(), null),
				attester);

		assertNotEquals(original, supportOf(sc.getUuid()).assessedAt(),
				"re-asserting the level is a fresh assessment and must carry today's date");
	}

	/**
	 * Pins the milestone-shape CHECK constraint. No Java path can produce a violating value,
	 * so without this the constraint could be renamed or dropped and nothing would notice --
	 * and it is the only thing standing between a hand-edited date and an export that reads
	 * as an affirmative "no date is published".
	 */
	@Test
	void malformedMilestoneDatesAreRejectedByTheDatabase() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/badshape@1.0.0?t=" + UUID.randomUUID());
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), UUID.randomUUID());

		for (String bad : List.of("\"31/01/2027\"", "20270101", "\"2026-13-45\"", "\"9999-99-99\"")) {
			try (Connection c = dataSource.getConnection();
					PreparedStatement ps = c.prepareStatement(
							"UPDATE rearm.sbom_component_support SET support_data ="
							+ " jsonb_set(support_data, '{milestones,END_OF_SUPPORT,date}', ?::jsonb)"
							+ " WHERE sbom_component_uuid = ?")) {
				ps.setString(1, bad);
				ps.setObject(2, sc.getUuid());
				ps.executeUpdate();
				throw new AssertionError("database accepted a malformed milestone date: " + bad);
			} catch (SQLException expected) {
				assertTrue(expected.getMessage().contains("sbom_component_support_milestone_shape"),
						"rejected by the shape constraint, got: " + expected.getMessage());
			}
		}
	}

	/**
	 * D5, and the headline behaviour change: END-OF-SUPPORT AFTER END-OF-LIFE IS NOW VALID.
	 *
	 * <p>CycloneDX defines end-of-life as end of SALE, which routinely precedes end of support
	 * by years, so the old EOS &lt;= EOL rule REJECTED conformant data -- and the SUPPLIER
	 * writer slice calls this same path, so it would have been blocked outright. Nothing
	 * tested this before; the change was only visible in the guard's absence.
	 */
	@Test
	void endOfSupportMayFollowEndOfLife() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/offsale@1.0.0?t=" + UUID.randomUUID());
		LocalDate endOfSale = LocalDate.now().plusYears(1);
		LocalDate supportEnds = LocalDate.now().plusYears(6);

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, supportEnds, endOfSale, "vendor sells until 2027, patches until 2032"),
				UUID.randomUUID());

		SupportData data = supportOf(sc.getUuid());
		assertEquals(endOfSale, data.milestoneDate(SupportMilestoneType.END_OF_LIFE));
		assertEquals(supportEnds, data.milestoneDate(SupportMilestoneType.END_OF_SUPPORT),
				"end of sale before end of support is the normal vendor shape, not an error");
	}

	/** The one ordering rule that survives D5 is still enforced. */
	@Test
	void endOfGuaranteedSupportStillMayNotFollowEndOfSupport() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/badorder@1.0.0?t=" + UUID.randomUUID());
		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(sc.getUuid(),
						dates(LocalDate.now().plusYears(5), LocalDate.now().plusYears(1), null, null),
						UUID.randomUUID()));
	}

	/**
	 * REGRESSION: a CHANGE to a negative level needs its OWN basis. The result-level invariant
	 * only checks that a justification is present, so without this a flip from
	 * ACTIVELY_MAINTAINED to ABANDONED inherits the previous justification -- dating a fresh
	 * abandonment claim to now while citing text that asserts active maintenance.
	 */
	@Test
	void changingToANegativeLevelRequiresANewJustification() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/flip@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null, null,
						"upstream cut 4.2 last week, CI green", null, null, null, null, null, Set.of(), null),
				attester);

		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(sc.getUuid(),
						new SupportAttestationRequest(LevelOfSupport.ABANDONED, null, null, null,
								null, null, null, null, null, Set.of(), null),
						attester),
				"the previous claim's basis must not silently become the abandonment basis");

		assertEquals(LevelOfSupport.ACTIVELY_MAINTAINED, supportOf(sc.getUuid()).levelOfSupport());
	}

	/**
	 * REGRESSION: changing the level to a POSITIVE one must not inherit the old basis either.
	 *
	 * <p>The earlier guard only fired when the NEW level required a justification, so
	 * ABANDONED -> ACTIVELY_MAINTAINED skipped every check and the old text survived by
	 * supplied-wins merge. Once this PR started exporting the field, that shipped
	 * "actively maintained" next to "upstream archived the repository" in the same BOM.
	 */
	@Test
	void changingToAPositiveLevelDoesNotInheritTheOldBasis() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/revived@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ABANDONED, null, null,
						"upstream archived the repository", null, null, null, null, null,
						Set.of(), null),
				attester);

		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(sc.getUuid(),
						new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null,
								null, null, null, null, null, null, null, Set.of(), null),
						attester),
				"a changed claim cannot silently keep the previous claim's basis");

		// Supplying an EMPTY justification is the explicit way to drop it, and it stores
		// null rather than whitespace so nothing later reads as a basis.
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null, null,
						"", null, null, null, null, null, Set.of(), null),
				attester);
		SupportData after = supportOf(sc.getUuid());
		assertEquals(LevelOfSupport.ACTIVELY_MAINTAINED, after.levelOfSupport());
		assertNull(after.justification(), "the stale basis is gone, not blanked to whitespace");
	}

	/**
	 * REGRESSION: the justification invariant holds on the STORED RESULT, not the request.
	 *
	 * <p>An earlier revision checked only the incoming request, which failed in the direction
	 * that matters: a partial write omitting {@code levelOfSupport} but sending a blank
	 * justification passed the check, then the supplied-wins merge blanked the stored
	 * justification on an ABANDONED row -- leaving the unsupported negative claim about a
	 * named third party that the rule exists to prevent.
	 */
	@Test
	void aPartialWriteCannotBlankTheJustificationOffANegativeClaim() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/blankjust@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ABANDONED, null, null,
						"upstream archived the repository on 2026-04-02", null,
						null, null, null, null, Set.of(), null),
				attester);

		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(sc.getUuid(),
						new SupportAttestationRequest(null, null, null, "  ", null,
								null, LocalDate.now().plusYears(1), null, null, Set.of(), null),
						attester),
				"blanking the basis out from under a stored ABANDONED must be refused");

		SupportData after = supportOf(sc.getUuid());
		assertEquals(LevelOfSupport.ABANDONED, after.levelOfSupport());
		assertEquals("upstream archived the repository on 2026-04-02", after.justification(),
				"and the original basis survives the refused write");
	}

	/** A negative claim cannot be recorded without a basis in the first place either. */
	@Test
	void aNegativeLevelRequiresAJustification() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/nobasis@1.0.0?t=" + UUID.randomUUID());
		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(sc.getUuid(),
						new SupportAttestationRequest(LevelOfSupport.NO_LONGER_MAINTAINED, null,
								null, null, null, null, null, null, null, Set.of(), null),
						UUID.randomUUID()));
		// ACTIVELY_MAINTAINED is a positive claim and carries no such requirement.
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null, null,
						null, null, null, null, null, null, Set.of(), null),
				UUID.randomUUID());
		assertEquals(LevelOfSupport.ACTIVELY_MAINTAINED, supportOf(sc.getUuid()).levelOfSupport());
	}

	/**
	 * REGRESSION: an unrecognised PER-MILESTONE source is as fatal to the parse as a
	 * record-level one, so coverage must exclude it too.
	 *
	 * <p>This is the sixth enum domain, and it was missed twice: once when the degrade-to-null
	 * annotations came off, and again when the coverage query grew validity predicates for
	 * the record-level fields and the milestone KEYS but not the value inside each milestone.
	 * Both times the gap survived because the test poisoned a field that was already guarded.
	 */
	@Test
	void anUnrecognisedMilestoneSourceIsExcludedFromExportAndCoverage() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent sc = newComponent(orgUuid,
				"pkg:maven/org.example/badsource@1.0.0?t=" + UUID.randomUUID());
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), UUID.randomUUID());
		assertEquals(1, sbomComponentService.getSupportCoverage(orgUuid).attested());

		try (Connection c = dataSource.getConnection();
				PreparedStatement ps = c.prepareStatement(
						"UPDATE rearm.sbom_component_support SET support_data ="
						+ " jsonb_set(support_data, '{milestones,END_OF_SUPPORT,source}',"
						+ " '\"REGISTRY\"') WHERE sbom_component_uuid = ?")) {
			ps.setObject(1, sc.getUuid());
			assertEquals(1, ps.executeUpdate());
		} catch (SQLException e) {
			throw new IllegalStateException("could not plant the poison source", e);
		}

		assertNull(sbomComponentService.findSupportByComponentIds(orgUuid, Set.of(sc.getUuid()))
				.get(sc.getUuid()), "the row is unreadable, so it is excluded from the export");
		assertEquals(0, sbomComponentService.getSupportCoverage(orgUuid).attested(),
				"and the gauge must not count what the BOM omits");
	}

	/**
	 * REGRESSION: a milestone removal must NOT overwrite the basis of a negative level claim.
	 *
	 * <p>They are different facts and now live in different places -- the removal reason on
	 * the audit row, the claim's basis in the payload. An earlier revision required a
	 * justification when clearing and wrote it into the payload, so removing a typo'd date
	 * from an ABANDONED component replaced "upstream archived the repository" with "removed a
	 * typo'd EOL". PR 3 exports that field, so this would have shipped a removal note as the
	 * stated reason a third party's project was called abandoned.
	 */
	@Test
	void clearingAMilestoneDoesNotOverwriteTheLevelJustification() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/basissurvives@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();
		String basis = "upstream archived the repository on 2026-04-02";

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ABANDONED, null, null, basis, null,
						null, null, LocalDate.now().plusYears(1), null, Set.of(), null),
				attester);

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, null, null, null, null,
						null, null, null, null, Set.of(SupportMilestoneType.END_OF_LIFE),
						"removed a typo'd EOL per the vendor advisory"),
				attester);

		SupportData after = supportOf(sc.getUuid());
		assertNull(after.milestoneDate(SupportMilestoneType.END_OF_LIFE), "the date is gone");
		assertEquals(basis, after.justification(),
				"the abandonment basis is untouched -- it is what gets exported");

		SbomComponentSupportAudit newest = auditRepository
				.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid()).get(0);
		assertEquals("removed a typo'd EOL per the vendor advisory", newest.getReason(),
				"and the removal reason is on the audit row, where it belongs");
	}

	/**
	 * BOTH DIRECTIONS, deliberately in one test so neither can be written without the other.
	 *
	 * <p>An entirely empty first write must be REFUSED: it used to create an attestation
	 * (MANUAL, assessedAt=now) that counted toward coverage and exported as "a human looked",
	 * from a caller who sent nothing.
	 *
	 * <p>A justification-only first write must be ACCEPTED: "I looked, upstream publishes
	 * nothing" is the case this storage shape exists for. The tempting way to write the guard
	 * -- require a level or a date -- refuses precisely that, and a test asserting only the
	 * refusal direction passes just as happily against that wrong rule.
	 */
	@Test
	void aFirstAttestationMustRecordSomethingButAJustificationAloneIsEnough() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		UUID attester = UUID.randomUUID();

		SbomComponent empty = newComponent(orgUuid,
				"pkg:maven/org.example/emptyfirst@1.0.0?t=" + UUID.randomUUID());
		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(empty.getUuid(),
						new SupportAttestationRequest(null, null, null, null, null,
								null, null, null, null, Set.of(), null),
						attester),
				"an empty first write must not conjure an assessment");
		assertTrue(supportRepository.findBySbomComponentUuid(empty.getUuid()).isEmpty(),
				"and no row is left behind");

		// THE FLAGSHIP CASE. No level, no dates -- just the basis.
		SbomComponent assessed = newComponent(orgUuid,
				"pkg:maven/org.example/nothingpublished@1.0.0?t=" + UUID.randomUUID());
		sbomComponentService.setSbomComponentSupport(assessed.getUuid(),
				new SupportAttestationRequest(null, null, null,
						"checked upstream releases and the security policy; no EOS is published",
						null, null, null, null, null, Set.of(), null),
				attester);

		SupportData data = supportOf(assessed.getUuid());
		assertTrue(data.milestones().isEmpty(), "no dates were found, and that is the record");
		assertNull(data.levelOfSupport(), "no level was claimed either");
		assertEquals("checked upstream releases and the security policy; no EOS is published",
				data.justification());
		assertEquals(SupportSource.MANUAL, data.assessmentSource());
		assertNotNull(data.assessedAt(), "the assessment is dated");
		assertEquals(1, sbomComponentService.getSupportCoverage(orgUuid).attested(),
				"and it counts as coverage -- refusing to count it would penalise honestly"
						+ " reporting an absence");
	}

	/**
	 * END-TO-END PIN for "assessed, nothing published": written through the SERVICE, read
	 * back through the real bulk read, matched by canonical purl, and emitted by the real
	 * injector into a real BOM.
	 *
	 * <p>Every existing test of this case stops short of one of those seams.
	 * {@code emitsAssessedAtForAnAttestationWithNoDatesAndNoLevel} builds {@code SupportData}
	 * by hand, so it cannot catch a write path that stores something the injector would not
	 * emit; the storage tests never reach the injector at all. Hand-built fixtures have
	 * hidden exactly that kind of divergence in this feature before -- a row that was
	 * countable and invisible passed both halves for weeks.
	 */
	@Test
	void assessedNothingPublishedSurvivesFromTheServiceIntoAServedBom() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		// Uniqueness goes in the NAME, not in a "?t=" qualifier as elsewhere in this class.
		// newComponent() sets canonical_purl directly, bypassing the canonicalisation that
		// produces it in production, while the injector derives its lookup key from the BOM
		// node THROUGH that canonicalisation. A synthetic qualifier therefore survives on one
		// side and not the other, and the match silently misses -- which cost a debugging
		// round here. Only this test spans both sides, so only this test is exposed to it.
		String purl = "pkg:maven/org.example/e2e" + UUID.randomUUID().toString().substring(0, 8) + "@3.1.0";
		SbomComponent sc = newComponent(orgUuid, purl);

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, null, null,
						"monitored upstream for 12 months; no end-of-support date is published",
						null, null, null, null, null, Set.of(), null),
				UUID.randomUUID());

		JsonNode bom = Utils.OM.readTree("{\"components\":[{\"name\":\"e2e\",\"purl\":\""
				+ purl + "\"}]}");
		JsonNode injected = supportInjectionService.injectCurrentSupport(bom, orgUuid);

		Map<String, String> props = new HashMap<>();
		JsonNode arr = injected.get("components").get(0).get("properties");
		assertNotNull(arr, "the component must carry support properties -- an assessed"
				+ " component that exports nothing is countable and invisible");
		arr.forEach(prop -> props.put(prop.get("name").asText(), prop.get("value").asText()));

		assertEquals("monitored upstream for 12 months; no end-of-support date is published",
				props.get("reliza:support:justification"),
				"the basis is the whole disclosure for this case");
		assertNotNull(props.get("reliza:support:assessedAt"), "and it is dated");
		assertNull(props.get("reliza:support:levelOfSupport"), "no level was claimed");
		assertNull(props.get("cdx:lifecycle:milestone:endOfSupport"), "no date was found");
		assertEquals(1, sbomComponentService.getSupportCoverage(orgUuid).attested(),
				"the gauge and the served BOM agree");
	}

	/**
	 * THE GAUGE AND THE LIST BENEATH IT MUST AGREE.
	 *
	 * <p>Release-scoped coverage resolves its components through
	 * {@code findReleaseComponentUuids}, the same path {@code listReleaseSbomComponents}
	 * walks. This asserts the equality rather than trusting the shared call, because the two
	 * could still drift through their filters -- the denominator excludes roots and the list
	 * does not, so "same source" is necessary and not sufficient.
	 *
	 * <p>This feature has hit in-step failures twice by other routes (coverage counting rows
	 * the export omitted; the injector emitting what coverage did not count). A gauge that
	 * disagrees with the table under it is the same defect a third time.
	 */
	@Test
	void releaseScopedCoverageDenominatorMatchesTheReleaseComponentList() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();

		SbomComponent root = newComponent(orgUuid, "pkg:maven/org.example/app@1.0.0");
		Map<String, Object> rootRd = new HashMap<>();
		rootRd.put("name", "app");
		rootRd.put("isRoot", Boolean.TRUE);
		root.setRecordData(rootRd);
		sbomComponentRepository.save(root);

		SbomComponent depA = newComponent(orgUuid, "pkg:maven/org.example/depA@2.0.0");
		SbomComponent depB = newComponent(orgUuid, "pkg:maven/org.example/depB@3.0.0");

		// A REAL release: resolveReleaseArtifactComponents starts from getReleaseData and
		// returns empty for an unknown uuid, so a synthetic id would make this test pass
		// vacuously by resolving nothing.
		String slug = "it-coverage-" + UUID.randomUUID().toString().substring(0, 8);
		UUID componentUuid = componentService.createComponent(CreateComponentDto.builder()
				.organization(orgUuid).name(slug).type(ComponentType.COMPONENT)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(),
				WhoUpdated.getTestWhoUpdated()).getUuid();
		Branch branch = branchService.findBranchByName(componentUuid, "main", true,
				WhoUpdated.getTestWhoUpdated()).get();
		UUID releaseUuid = ossReleaseService.createRelease(ReleaseDto.builder()
				.component(componentUuid).branch(branch.getUuid()).org(orgUuid)
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0").build(), WhoUpdated.getTestWhoUpdated()).getUuid();

		UUID canonicalArtifact = UUID.randomUUID();
		ReleaseArtifactIndex idx = new ReleaseArtifactIndex();
		idx.setOrg(orgUuid);
		idx.setReleaseUuid(releaseUuid);
		idx.setCanonicalArtifactUuid(canonicalArtifact);
		releaseArtifactIndexRepository.save(idx);
		for (SbomComponent c : List.of(root, depA, depB)) {
			ArtifactSbomComponent asc = new ArtifactSbomComponent();
			asc.setOrg(orgUuid);
			asc.setCanonicalArtifactUuid(canonicalArtifact);
			asc.setSbomComponentUuid(c.getUuid());
			asc.setExactPurl(c.getCanonicalPurl());
			asc.setParents(new ArrayList<>());
			artifactSbomComponentRepository.save(asc);
		}

		sbomComponentService.setSbomComponentSupport(depA.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), UUID.randomUUID());

		Set<UUID> resolved = sbomComponentService.findReleaseComponentUuids(releaseUuid);
		assertEquals(3, resolved.size(), "all three components resolve through the release path");

		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid, releaseUuid);
		assertEquals(2, cov.total(),
				"the denominator is the release's NON-ROOT components -- the root is the app"
						+ " itself, not a third-party dependency to attest");
		assertEquals(1, cov.attested());

		// And the org-wide scope still answers the other question, unchanged.
		SupportCoverage orgWide = sbomComponentService.getSupportCoverage(orgUuid);
		assertEquals(2, orgWide.total(), "org-wide sees the same two non-root components here");
	}

	/** A real release carrying the given components, one canonical artifact. */
	private UUID newReleaseWith(UUID orgUuid, List<SbomComponent> components) throws Exception {
		String slug = "it-page-" + UUID.randomUUID().toString().substring(0, 8);
		UUID componentUuid = componentService.createComponent(CreateComponentDto.builder()
				.organization(orgUuid).name(slug).type(ComponentType.COMPONENT)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(),
				WhoUpdated.getTestWhoUpdated()).getUuid();
		Branch branch = branchService.findBranchByName(componentUuid, "main", true,
				WhoUpdated.getTestWhoUpdated()).get();
		UUID releaseUuid = ossReleaseService.createRelease(ReleaseDto.builder()
				.component(componentUuid).branch(branch.getUuid()).org(orgUuid)
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0").build(), WhoUpdated.getTestWhoUpdated()).getUuid();
		UUID canonicalArtifact = UUID.randomUUID();
		ReleaseArtifactIndex idx = new ReleaseArtifactIndex();
		idx.setOrg(orgUuid);
		idx.setReleaseUuid(releaseUuid);
		idx.setCanonicalArtifactUuid(canonicalArtifact);
		releaseArtifactIndexRepository.save(idx);
		for (SbomComponent c : components) {
			ArtifactSbomComponent asc = new ArtifactSbomComponent();
			asc.setOrg(orgUuid);
			asc.setCanonicalArtifactUuid(canonicalArtifact);
			asc.setSbomComponentUuid(c.getUuid());
			asc.setExactPurl(c.getCanonicalPurl());
			asc.setParents(new ArrayList<>());
			artifactSbomComponentRepository.save(asc);
		}
		return releaseUuid;
	}

	private SbomComponent newRootComponent(UUID orgUuid, String purl) {
		SbomComponent root = newComponent(orgUuid, purl);
		Map<String, Object> rd = new HashMap<>();
		rd.put("name", "app");
		rd.put("isRoot", Boolean.TRUE);
		root.setRecordData(rd);
		return sbomComponentRepository.save(root);
	}

	/**
	 * THE invariant of the page: its filter and the coverage gauge must be the same
	 * question asked twice.
	 *
	 * <p>The gauge tells the operator how many components are undisclosed; the UNATTESTED
	 * page is how they then select those components to attest. If the two definitions drift
	 * the list will not contain what the number is counting, and the gap can never be closed
	 * -- the in-step failure this feature has already hit three times by other routes.
	 *
	 * <p>Asserted four ways, because "same source" has already proven necessary and not
	 * sufficient here: ALL equals the denominator, ATTESTED equals the numerator, the two
	 * filters partition ALL exactly, and the root is in neither.
	 */
	@Test
	void thePageFilterAndTheCoverageGaugeAnswerTheSameQuestion() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent root = newRootComponent(orgUuid, "pkg:maven/org.example/pageapp@1.0.0");
		SbomComponent attested = newComponent(orgUuid, "pkg:maven/org.example/pgA@1.0.0");
		SbomComponent bare = newComponent(orgUuid, "pkg:maven/org.example/pgB@1.0.0");
		SbomComponent withdrawn = newComponent(orgUuid, "pkg:maven/org.example/pgC@1.0.0");
		UUID releaseUuid = newReleaseWith(orgUuid, List.of(root, attested, bare, withdrawn));

		UUID who = UUID.randomUUID();
		sbomComponentService.setSbomComponentSupport(attested.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), who);
		sbomComponentService.setSbomComponentSupport(withdrawn.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), who);
		sbomComponentService.setSbomComponentSupport(withdrawn.getUuid(),
				new SupportAttestationRequest(null, SupportState.WITHDRAWN, null,
						"asserted against the wrong component", null, null, null, null, null,
						Set.of(), null),
				who);

		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid, releaseUuid);
		SbomComponentPage all = page(orgUuid, releaseUuid, SupportAttestationFilter.ALL, null);
		SbomComponentPage yes = page(orgUuid, releaseUuid, SupportAttestationFilter.ATTESTED, null);
		SbomComponentPage no = page(orgUuid, releaseUuid, SupportAttestationFilter.UNATTESTED, null);

		assertEquals(cov.total(), all.totalCount(),
				"ALL is the gauge's denominator -- root excluded from both");
		assertEquals(cov.attested(), yes.totalCount(), "ATTESTED is the gauge's numerator");
		assertEquals(all.totalCount(), yes.totalCount() + no.totalCount(),
				"UNATTESTED must be the exact complement, not an independent 'no row' test");
		assertFalse(all.componentUuids().contains(root.getUuid()),
				"the root is the release's own coordinate, never a dependency to disclose");
		assertTrue(no.componentUuids().contains(withdrawn.getUuid()),
				"a WITHDRAWN row reads as undisclosed on the gauge, so it belongs in the queue"
						+ " the operator works from -- a row-existence filter would hide it");
		assertTrue(yes.componentUuids().contains(attested.getUuid()));
		assertTrue(no.componentUuids().contains(bare.getUuid()));
	}

	/** The UNATTESTED page is exactly the set bulk can act on: select it, sweep it, gauge closes. */
	@Test
	void sweepingTheUnattestedPageClosesTheGaugeExactly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		List<SbomComponent> deps = new ArrayList<>();
		for (int i = 0; i < 5; i++) deps.add(newComponent(orgUuid, "pkg:maven/org.example/sweep" + i + "@1.0.0"));
		UUID releaseUuid = newReleaseWith(orgUuid, List.of(
				newRootComponent(orgUuid, "pkg:maven/org.example/sweepapp@1.0.0"),
				deps.get(0), deps.get(1), deps.get(2), deps.get(3), deps.get(4)));

		SbomComponentPage todo = page(orgUuid, releaseUuid, SupportAttestationFilter.UNATTESTED, null);
		assertEquals(5, todo.totalCount());
		List<SupportBulkResult> res = sbomComponentService.bulkSetSbomComponentSupport(orgUuid,
				todo.componentUuids(),
				new SupportAttestationRequest(null, null, null, "swept", null, null, null, null,
						null, Set.of(), null),
				UUID.randomUUID(), UUID.randomUUID());
		assertTrue(res.stream().allMatch(r -> SupportBulkOutcome.APPLIED == r.outcome()),
				"every row the page offered must be writable -- the page IS the work queue");
		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid, releaseUuid);
		assertEquals(cov.total(), cov.attested(), "the gap the gauge showed is now closed");
		assertEquals(0, page(orgUuid, releaseUuid, SupportAttestationFilter.UNATTESTED, null).totalCount());
	}

	/**
	 * The naive walk -- follow the cursor, attest as you go -- visits every component
	 * exactly once. This is the property the "select all unattested" action needs.
	 *
	 * <p>It is the inverse of the test this replaces. Under offset pagination the same loop
	 * SKIPPED components, because UNATTESTED is a predicate over mutable state and attesting
	 * a page shifted the set the offset indexed into; the old test asserted that loss and the
	 * schema carried a client protocol to work around it. A keyset cursor is anchored to the
	 * sort key, so rows vanishing BEHIND it change nothing, and there is no protocol left to
	 * get wrong.
	 *
	 * <p>Asserted as "exactly once", not merely "all": a cursor that failed to advance past a
	 * tied sort key would re-return rows forever, and a sweep that re-attests is a sweep that
	 * overwrites someone's considered judgement.
	 */
	@Test
	void aCursorWalkVisitsEveryComponentExactlyOnceWhileAttestingAsItGoes() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		List<SbomComponent> deps = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			deps.add(newComponent(orgUuid, String.format("pkg:maven/org.example/mp%02d@1.0.0", i)));
		}
		UUID releaseUuid = newReleaseWith(orgUuid, deps);
		SupportAttestationRequest req = new SupportAttestationRequest(null, null, null, "swept",
				null, null, null, null, null, Set.of(), null);

		List<UUID> visited = new ArrayList<>();
		UUID cursor = null;
		int guard = 0;
		while (guard++ < 20) {
			SbomComponentPage pg = sbomComponentService.listReleaseSbomComponentPage(orgUuid,
					releaseUuid, SupportAttestationFilter.UNATTESTED, null, 3, cursor);
			if (pg.componentUuids().isEmpty()) break;
			visited.addAll(pg.componentUuids());
			sbomComponentService.bulkSetSbomComponentSupport(orgUuid, pg.componentUuids(),
					req, UUID.randomUUID(), UUID.randomUUID());
			if (!pg.hasMore()) break;
			cursor = pg.endCursor();
		}

		assertEquals(9, visited.size(), "every component visited, and none twice");
		assertEquals(9, new HashSet<>(visited).size());
		assertEquals(deps.stream().map(SbomComponent::getUuid).toList(), visited,
				"and in cursor order");
		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid, releaseUuid);
		assertEquals(cov.total(), cov.attested(), "the gauge closes with no leftover gap");
	}

	/**
	 * A withdrawn component inside a paged sweep: refused, and the walk still TERMINATES.
	 *
	 * <p>The two halves of this change interact and the interaction is the interesting part.
	 * UNATTESTED includes withdrawn rows, and un-retracting now needs a reason -- so a
	 * reason-less "select all unattested" sweep leaves that component FAILED and the gauge
	 * permanently short. That is the correct outcome; the operator has to decide, per
	 * component, whether reversing someone's withdrawal is justified.
	 *
	 * <p>What matters here is that it terminates. Under the deleted offset design the
	 * documented protocol was "re-request offset 0 until totalCount is 0", and a component
	 * that can never be attested would have made that an INFINITE LOOP -- the same page
	 * returned and refused forever. The cursor walks past it instead. That is a second,
	 * independent reason keyset was the right call, and it is asserted rather than argued.
	 */
	@Test
	void aWithdrawnRowIsRefusedInASweepAndTheWalkStillTerminates() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		UUID attester = UUID.randomUUID();
		List<SbomComponent> deps = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			deps.add(newComponent(orgUuid, String.format("pkg:maven/org.example/wd%02d@1.0.0", i)));
		}
		seedWithdrawn(deps.get(2), LocalDate.now().plusYears(1), attester);
		UUID releaseUuid = newReleaseWith(orgUuid, deps);

		SupportAttestationRequest reasonless = new SupportAttestationRequest(null, null, null,
				"swept", null, null, null, null, null, Set.of(), null);
		List<SupportBulkResult> outcomes = new ArrayList<>();
		UUID cursor = null;
		int guard = 0;
		SbomComponentPage pg;
		do {
			pg = sbomComponentService.listReleaseSbomComponentPage(orgUuid, releaseUuid,
					SupportAttestationFilter.UNATTESTED, null, 2, cursor);
			if (pg.componentUuids().isEmpty()) break;
			outcomes.addAll(sbomComponentService.bulkSetSbomComponentSupport(orgUuid,
					pg.componentUuids(), reasonless, attester, UUID.randomUUID()));
			cursor = pg.endCursor();
		} while (pg.hasMore() && guard++ < 10);

		assertTrue(guard < 10, "the walk terminated; under offset this would have looped forever");
		assertEquals(5, outcomes.size(), "every component was visited exactly once");
		assertEquals(1, outcomes.stream()
				.filter(r -> SupportBulkOutcome.FAILED == r.outcome()).count(),
				"only the withdrawn one is refused");
		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid, releaseUuid);
		assertEquals(cov.total() - 1, cov.attested(),
				"and the gauge is honestly short by exactly that component");
	}

	/**
	 * The cursor survives its own anchor leaving the filtered set.
	 *
	 * <p>This is the case offset could not express and the reason keyset is correct here: by
	 * the time the caller asks for the page AFTER a component, that component has usually
	 * just been attested and no longer matches UNATTESTED. The cursor resolves against the
	 * component's existence and sort position, not against the filter.
	 */
	@Test
	void aCursorStillWorksAfterItsAnchorLeavesTheFilteredSet() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		List<SbomComponent> deps = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			deps.add(newComponent(orgUuid, String.format("pkg:maven/org.example/cur%02d@1.0.0", i)));
		}
		UUID releaseUuid = newReleaseWith(orgUuid, deps);

		SbomComponentPage first = sbomComponentService.listReleaseSbomComponentPage(orgUuid,
				releaseUuid, SupportAttestationFilter.UNATTESTED, null, 2, null);
		assertEquals(2, first.componentUuids().size());
		assertTrue(first.hasMore());
		UUID cursor = first.endCursor();

		sbomComponentService.bulkSetSbomComponentSupport(orgUuid, first.componentUuids(),
				new SupportAttestationRequest(null, null, null, "swept", null, null, null, null,
						null, Set.of(), null),
				UUID.randomUUID(), UUID.randomUUID());

		SbomComponentPage second = sbomComponentService.listReleaseSbomComponentPage(orgUuid,
				releaseUuid, SupportAttestationFilter.UNATTESTED, null, 2, cursor);
		assertEquals(List.of(deps.get(2).getUuid(), deps.get(3).getUuid()),
				second.componentUuids(),
				"the anchor no longer matches UNATTESTED, but the cursor still knows where it sat");
		assertFalse(second.hasMore());
		assertEquals(2, second.totalCount(),
				"totalCount tracks the REMAINING work under a mutating filter -- by design");
	}

	/** An unrecognised cursor is an error, never a silent restart. */
	@Test
	void anUnknownCursorIsRejectedRatherThanRestartingTheWalk() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent dep = newComponent(orgUuid, "pkg:maven/org.example/badcursor@1.0.0");
		UUID releaseUuid = newReleaseWith(orgUuid, List.of(dep));

		// RelizaException, not IllegalArgumentException: the latter has no GraphQL handler
		// and would reach the client as "Internal server error", which a caller cannot act
		// on. A stale cursor is routine, not an outage.
		assertThrows(RelizaException.class,
				() -> sbomComponentService.listReleaseSbomComponentPage(orgUuid, releaseUuid,
						SupportAttestationFilter.ALL, null, 50, UUID.randomUUID()),
				"silently restarting would turn a select-all sweep into an infinite loop");

		// A component belonging to ANOTHER org is equally not a cursor into this walk: the
		// lookup is org-filtered, so a caller cannot probe foreign component ids by watching
		// which cursors are accepted.
		SbomComponent foreign = newComponent(UUID.randomUUID(),
				"pkg:maven/org.example/foreigncursor@1.0.0");
		// Without this the arm below cannot tell "the org filter rejected it" from "the row
		// was never written", and would go vacuous the day sbom_components.org gained a
		// constraint that stopped the save.
		assertTrue(sbomComponentRepository.findById(foreign.getUuid()).isPresent(),
				"the foreign component really exists -- so the rejection is the ORG filter");
		assertThrows(RelizaException.class,
				() -> sbomComponentService.listReleaseSbomComponentPage(orgUuid, releaseUuid,
						SupportAttestationFilter.ALL, null, 50, foreign.getUuid()),
				"an existing component in a different org must not resolve as a cursor");

		// And on a release with NO components: the cursor is validated before the
		// empty-release short-circuit, so "a bad cursor always errors" is total rather than
		// true only of non-empty releases.
		UUID emptyRelease = newReleaseWith(orgUuid, List.of());
		assertThrows(RelizaException.class,
				() -> sbomComponentService.listReleaseSbomComponentPage(orgUuid, emptyRelease,
						SupportAttestationFilter.ALL, null, 50, UUID.randomUUID()));
	}

	/**
	 * Paging must not drop or repeat a component.
	 *
	 * <p>The merged rows come back through a HashMap whose iteration order is not stable
	 * across requests, so the order has to come from the SQL. It is also why the ORDER BY
	 * carries a uuid tiebreak: canonical_purl is not unique per org, and without it a cursor
	 * sitting on a tied purl could not say which of the tied rows it had already returned.
	 */
	@Test
	void pagesPartitionTheReleaseWithoutGapsOrRepeats() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		List<SbomComponent> deps = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			deps.add(newComponent(orgUuid, String.format("pkg:maven/org.example/pg%02d@1.0.0", i)));
		}
		UUID releaseUuid = newReleaseWith(orgUuid, deps);

		List<UUID> walked = new ArrayList<>();
		UUID cursor = null;
		SbomComponentPage pg;
		do {
			pg = sbomComponentService.listReleaseSbomComponentPage(orgUuid, releaseUuid,
					SupportAttestationFilter.ALL, null, 3, cursor);
			// The cursor clause is deliberately kept OUT of the count query, so totalCount
			// describes the whole filtered population on EVERY page rather than the tail
			// after the cursor. Asserted here because it is the one place the two readings
			// differ visibly: 7/7/7 if the count ignores the cursor, 7/4/1 if it leaks in.
			assertEquals(7, pg.totalCount(),
					"totalCount is the filtered population, not the remaining tail");
			walked.addAll(pg.componentUuids());
			cursor = pg.endCursor();
		} while (pg.hasMore());
		assertEquals(7, walked.size(), "no row lost or duplicated across page boundaries");
		assertEquals(7, new HashSet<>(walked).size());
		assertEquals(deps.stream().map(SbomComponent::getUuid).toList(), walked,
				"and in canonical-purl order, which is what makes the boundaries stable");
	}

	/** A search is a literal substring: LIKE metacharacters in it must not widen the match. */
	@Test
	void searchTreatsWildcardsInTheQueryAsLiterals() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent plain = newComponent(orgUuid, "pkg:maven/org.example/needle@1.0.0");
		SbomComponent other = newComponent(orgUuid, "pkg:maven/org.example/haystack@1.0.0");
		UUID releaseUuid = newReleaseWith(orgUuid, List.of(plain, other));

		assertEquals(1, page(orgUuid, releaseUuid, SupportAttestationFilter.ALL, "needle").totalCount());
		assertEquals(1, page(orgUuid, releaseUuid, SupportAttestationFilter.ALL, "NEEDLE").totalCount(),
				"case-insensitive");
		assertEquals(0, page(orgUuid, releaseUuid, SupportAttestationFilter.ALL, "n%e").totalCount(),
				"a % typed by the user is a percent sign, not 'match anything'");
		assertEquals(0, page(orgUuid, releaseUuid, SupportAttestationFilter.ALL, "needl_").totalCount(),
				"and _ is an underscore, not 'any character'");
		assertEquals(2, page(orgUuid, releaseUuid, SupportAttestationFilter.ALL, "  ").totalCount(),
				"a blank search is no search, not a search for spaces");
	}

	/**
	 * An unknown or foreign release yields an empty page, never the org-wide set.
	 * Same reasoning as the coverage guard: answering with a different scope's rows would be
	 * confidently wrong, and here it would also leak another org's component ids.
	 */
	@Test
	void unknownReleaseYieldsAnEmptyPageRatherThanTheOrgWideSet() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent dep = newComponent(orgUuid, "pkg:maven/org.example/scoped@1.0.0");
		UUID releaseUuid = newReleaseWith(orgUuid, List.of(dep));
		assertEquals(1, page(orgUuid, releaseUuid, SupportAttestationFilter.ALL, null).totalCount());

		SbomComponentPage unknown = page(orgUuid, UUID.randomUUID(), SupportAttestationFilter.ALL, null);
		assertEquals(0, unknown.totalCount());
		assertTrue(unknown.componentUuids().isEmpty());

		SbomComponentPage foreignOrg = page(UUID.randomUUID(), releaseUuid,
				SupportAttestationFilter.ALL, null);
		assertEquals(0, foreignOrg.totalCount(),
				"the release resolves, but its components belong to another org");
	}

	/** limit is clamped, not trusted: the paged endpoint must not become the unpaged one. */
	@Test
	void limitIsClamped() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent dep = newComponent(orgUuid, "pkg:maven/org.example/clamp@1.0.0");
		UUID releaseUuid = newReleaseWith(orgUuid, List.of(dep));

		assertEquals(SbomComponentService.RELEASE_PAGE_MAX_LIMIT,
				sbomComponentService.listReleaseSbomComponentPage(orgUuid, releaseUuid,
						SupportAttestationFilter.ALL, null, 100_000, null).limit());
		assertEquals(1, sbomComponentService.listReleaseSbomComponentPage(orgUuid, releaseUuid,
				SupportAttestationFilter.ALL, null, 0, null).limit(),
				"limit 0 is clamped UP to 1 -- a zero-size page would page forever");
		// Pins the enum-to-SQL coupling. The page SQL branches on these three literals with
		// no ELSE, so a renamed constant would make every branch false and return an empty
		// page with no error -- indistinguishable from "nothing matched".
		assertEquals(SupportAttestationFilter.ALL.name(), SupportAttestationFilter.CODE_ALL);
		assertEquals(SupportAttestationFilter.ATTESTED.name(), SupportAttestationFilter.CODE_ATTESTED);
		assertEquals(SupportAttestationFilter.UNATTESTED.name(), SupportAttestationFilter.CODE_UNATTESTED);
		assertEquals(3, SupportAttestationFilter.values().length,
				"a new filter value needs a SQL branch and a constant above; the exhaustive"
						+ " switch in sqlCode makes that a compile error, this makes it visible");
		assertEquals(1, page(orgUuid, releaseUuid, null, null).totalCount(),
				"a null filter is ALL, not an empty page");
	}

	private SbomComponentPage page(UUID orgUuid, UUID releaseUuid,
			SupportAttestationFilter f, String search) throws RelizaException {
		return sbomComponentService.listReleaseSbomComponentPage(orgUuid, releaseUuid, f, search, 50, null);
	}

	/**
	 * The service treats an UNKNOWN release as empty, which is what makes the resolver's
	 * cross-org guard the only thing standing between a caller and a confusing answer.
	 *
	 * <p>Both of the guard's branches matter and only one is obvious. A FOREIGN release
	 * resolves to an org that differs; a NONEXISTENT one resolves to null through
	 * {@code orElse(null)}, and null never equals the caller's org, so both are refused with
	 * the same message. That sameness is deliberate -- distinguishing them would tell an
	 * outsider which release ids exist.
	 */
	@Test
	void anUnknownReleaseResolvesToNoComponentsAtTheServiceLayer() {
		assertTrue(sbomComponentService.findReleaseComponentUuids(UUID.randomUUID()).isEmpty(),
				"an unknown release resolves to nothing rather than throwing");
	}

	/**
	 * An empty release reports 0/0, never the org's number.
	 *
	 * <p>HONEST LIMIT: this pins the CONTRACT, not the code path. The service short-circuits
	 * before the query when the component set is empty, but if that short-circuit were
	 * removed the empty id string would reach SQL and
	 * {@code CAST(string_to_array('', ',') AS uuid[])} yields a zero-length array, so the
	 * count would still be 0 -- verified against PG 16.14. Both routes give 0/0 and no
	 * assertion here can tell them apart.
	 *
	 * <p>The short-circuit is kept anyway, because relying on that coincidence is how a
	 * behaviour survives until the coincidence stops holding. What this test does guarantee
	 * is the part that matters to a caller: an empty release must never answer with the
	 * org-wide number.
	 */
	@Test
	void anEmptyReleaseReportsNoCoverageRatherThanFallingBackToOrgScope() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent orphan = newComponent(orgUuid, "pkg:maven/org.example/elsewhere@1.0.0");
		sbomComponentService.setSbomComponentSupport(orphan.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), UUID.randomUUID());

		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid, UUID.randomUUID());
		assertEquals(0, cov.total(), "a release with no components has no coverage to report");
		assertEquals(0, cov.attested());
		assertEquals(1, sbomComponentService.getSupportCoverage(orgUuid).total(),
				"while the org-wide number is unaffected");
	}

	/**
	 * The guard is scoped to FRESH rows. A partial write against an existing attestation is
	 * the PATCH contract and must keep working with any subset of fields -- including none,
	 * which is how a caller touches nothing but bumps the record.
	 */
	@Test
	void anEmptyWriteAgainstAnExistingAttestationIsStillAllowed() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/emptypatch@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), attester);

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, null, null, null, null,
						null, null, null, null, Set.of(), null),
				attester);

		assertEquals(LocalDate.now().plusYears(1),
				supportOf(sc.getUuid()).milestoneDate(SupportMilestoneType.END_OF_SUPPORT),
				"the existing facts survive an empty patch");
	}

	/**
	 * Bulk attestation: the three rules, and idempotence, in one pass.
	 *
	 * <p>ROOT skipped (the app is not a third-party dependency), already-attested SKIPPED
	 * rather than overwritten (a sweep must not flatten an earlier per-component judgement),
	 * and therefore a re-run applies nothing.
	 */
	@Test
	void bulkAttestationSkipsRootAndAlreadyAttestedAndIsIdempotent() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		UUID attester = UUID.randomUUID();

		SbomComponent root = newComponent(orgUuid, "pkg:maven/org.example/bulkroot@1.0.0");
		Map<String, Object> rd = new HashMap<>();
		rd.put("name", "bulkroot");
		rd.put("isRoot", Boolean.TRUE);
		root.setRecordData(rd);
		sbomComponentRepository.save(root);

		SbomComponent already = newComponent(orgUuid, "pkg:maven/org.example/bulkdone@1.0.0");
		SbomComponent fresh = newComponent(orgUuid, "pkg:maven/org.example/bulkfresh@1.0.0");
		sbomComponentService.setSbomComponentSupport(already.getUuid(),
				dates(null, LocalDate.now().plusYears(5), null, "attested by hand earlier"),
				attester);

		SupportAttestationRequest req = new SupportAttestationRequest(null, null, null,
				"bulk sweep: no upstream EOS published", null, null, null, null, null,
				Set.of(), null);
		List<SupportBulkResult> first = sbomComponentService.bulkSetSbomComponentSupport(
				orgUuid, List.of(root.getUuid(), already.getUuid(), fresh.getUuid()), req, attester, UUID.randomUUID());

		Map<UUID, SupportBulkOutcome> byId = new HashMap<>();
		first.forEach(r -> byId.put(r.sbomComponentUuid(), r.outcome()));
		assertEquals(SupportBulkOutcome.SKIPPED_ROOT, byId.get(root.getUuid()));
		assertEquals(SupportBulkOutcome.SKIPPED_ATTESTED, byId.get(already.getUuid()));
		assertEquals(SupportBulkOutcome.APPLIED, byId.get(fresh.getUuid()));

		assertEquals(LocalDate.now().plusYears(5),
				supportOf(already.getUuid()).milestoneDate(SupportMilestoneType.END_OF_SUPPORT),
				"the earlier judgement is untouched, not overwritten by the sweep");

		List<SupportBulkResult> second = sbomComponentService.bulkSetSbomComponentSupport(
				orgUuid, List.of(root.getUuid(), already.getUuid(), fresh.getUuid()), req, attester, UUID.randomUUID());
		assertTrue(second.stream().noneMatch(r -> r.outcome() == SupportBulkOutcome.APPLIED),
				"a re-run applies nothing -- idempotent");
	}

	/**
	 * A payload this build cannot parse must FAIL its component, never be overwritten.
	 *
	 * <p>The read-path rule: unreadable is not the same as not-assessed. Treating it as
	 * not-assessed would let a bulk sweep silently destroy a recorded regulatory claim that a
	 * newer build wrote and this one merely does not understand. It fails loudly instead, and
	 * the rest of the batch is unaffected.
	 *
	 * <p>The message that reaches the caller deliberately names no field or value -- the
	 * cause goes to the log. Asserted here so a future "helpful" change that echoes the
	 * Jackson message, and with it a fragment of the stored payload, fails.
	 */
	@Test
	void bulkFailsAnUnreadablePayloadInsteadOfOverwritingIt() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent fine = newComponent(orgUuid, "pkg:maven/org.example/bulkfine@1.0.0");
		SbomComponent poison = newComponent(orgUuid, "pkg:maven/org.example/bulkpoison@1.0.0");
		sbomComponentService.setSbomComponentSupport(poison.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), UUID.randomUUID());
		sbomComponentService.setSbomComponentSupport(poison.getUuid(),
				new SupportAttestationRequest(null, SupportState.WITHDRAWN, null, "wrong row",
						null, null, null, null, null, Set.of(), null),
				UUID.randomUUID());
		plantUnknownMilestoneType(poison.getUuid());

		List<SupportBulkResult> res = sbomComponentService.bulkSetSbomComponentSupport(orgUuid,
				List.of(fine.getUuid(), poison.getUuid()),
				new SupportAttestationRequest(null, null, null, "sweep", null, null, null, null,
						null, Set.of(), null),
				UUID.randomUUID(), UUID.randomUUID());

		assertEquals(SupportBulkOutcome.APPLIED, res.get(0).outcome(),
				"the unreadable row must not take the batch with it");
		assertEquals(SupportBulkOutcome.FAILED, res.get(1).outcome(),
				"withdrawn would normally mean 're-attest me' -- unreadable outranks it");
		assertTrue(res.get(1).message().contains("cannot be read by this build"));
		assertFalse(res.get(1).message().contains("END_OF_UPDATES"),
				"the cause names the offending field and belongs in the log, not on the wire");
	}

	/** A batch id from another org is reported, not written. */
	@Test
	void bulkReportsAForeignComponentRatherThanWritingIt() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent mine = newComponent(orgUuid, "pkg:maven/org.example/bulkmine@1.0.0");
		UUID foreign = UUID.randomUUID();

		List<SupportBulkResult> res = sbomComponentService.bulkSetSbomComponentSupport(orgUuid,
				List.of(mine.getUuid(), foreign),
				new SupportAttestationRequest(null, null, null, "sweep", null, null, null, null,
						null, Set.of(), null),
				UUID.randomUUID(), UUID.randomUUID());
		assertEquals(SupportBulkOutcome.APPLIED, res.get(0).outcome());
		assertEquals(SupportBulkOutcome.FAILED, res.get(1).outcome());
		assertTrue(res.get(1).message().contains("not found in this organization"));
	}

	/** Plants a milestone key no Java enum can produce, committed and visible to the service. */
	private void plantUnknownMilestoneType(UUID componentUuid) {
		try (Connection c = dataSource.getConnection();
				PreparedStatement ps = c.prepareStatement(
						"UPDATE rearm.sbom_component_support SET support_data ="
						+ " jsonb_set(support_data, '{milestones,END_OF_UPDATES}',"
						+ " '{\"date\":\"2030-01-01\",\"source\":\"MANUAL\"}')"
						+ " WHERE sbom_component_uuid = ?")) {
			ps.setObject(1, componentUuid);
			assertEquals(1, ps.executeUpdate(), "the poison row was planted");
		} catch (SQLException e) {
			throw new IllegalStateException("could not plant the poison row", e);
		}
	}

	/**
	 * BOTH DIRECTIONS on the definition of "already attested": it must mean exactly what the
	 * coverage gauge means, or a bulk sweep cannot close the gap the gauge reports.
	 *
	 * <p>A WITHDRAWN attestation reads as UNATTESTED on the gauge. If bulk skipped it merely
	 * because a row exists, the operator would see a component counted as missing and have no
	 * way to fill it in -- the sweep would report SKIPPED_ATTESTED forever while the number
	 * stayed stubbornly short.
	 */
	@Test
	void bulkReAttestsAWithdrawnRowBecauseTheGaugeCountsItAsMissing() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		UUID attester = UUID.randomUUID();
		SbomComponent sc = newComponent(orgUuid, "pkg:maven/org.example/bulkwithdrawn@1.0.0");

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, "initial"), attester);
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, SupportState.WITHDRAWN, null,
						"asserted against the wrong component", null, null, null, null, null,
						Set.of(), null),
				attester);

		assertEquals(0, sbomComponentService.getSupportCoverage(orgUuid).attested(),
				"precondition: the gauge counts a withdrawn attestation as missing");

		// A reason is required to un-retract -- the audit row is the only trace the
		// withdrawal was reversed. Covered on its own in
		// bulkCarriesTheBatchReasonOntoTheAuditRowAndRefusesToUnRetractWithout.
		List<SupportBulkResult> res = sbomComponentService.bulkSetSbomComponentSupport(orgUuid,
				List.of(sc.getUuid()),
				new SupportAttestationRequest(null, null, null, "re-assessed in a sweep", null,
						null, null, null, null, Set.of(), "re-checked against the supplier page"),
				attester, UUID.randomUUID());

		assertEquals(SupportBulkOutcome.APPLIED, res.get(0).outcome(),
				"so bulk must be able to re-attest it -- row-existence is the wrong test");
		assertEquals(1, sbomComponentService.getSupportCoverage(orgUuid).attested(),
				"and the sweep closes exactly the gap the gauge showed");
	}

	/**
	 * Bulk forces state=ATTESTED, and state is deliberately NOT one of the fields the
	 * fresh-row guard counts as content. If it ever were, this call would create an empty
	 * row per component -- a batch of attestations that assert nothing.
	 */
	@Test
	void bulkStillCannotCreateAnEmptyFreshRow() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(), "pkg:maven/org.example/bulkempty@1.0.0");
		List<SupportBulkResult> res = sbomComponentService.bulkSetSbomComponentSupport(
				org.getUuid(), List.of(sc.getUuid()),
				new SupportAttestationRequest(null, null, null, null, null, null, null, null,
						null, Set.of(), null),
				UUID.randomUUID(), UUID.randomUUID());
		assertEquals(SupportBulkOutcome.FAILED, res.get(0).outcome(),
				"forcing ATTESTED must not make an otherwise-empty request look like content");
		assertEquals(0, sbomComponentService.getSupportCoverage(org.getUuid()).attested());
	}

	/**
	 * A bulk un-retraction must leave a WHY on the audit row.
	 *
	 * <p>Bulk forces state=ATTESTED, so a withdrawn row silently becomes a live regulatory
	 * claim again. The audit row is the only trace that the withdrawal was reversed, and a
	 * row recording a state change with no reason is the half that does not survive scrutiny.
	 * Same argument that already makes clearing a milestone require one.
	 */
	@Test
	void bulkCarriesTheBatchReasonOntoTheAuditRowAndRefusesToUnRetractWithout() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		UUID attester = UUID.randomUUID();
		SbomComponent sc = newComponent(orgUuid, "pkg:maven/org.example/unretract@1.0.0");
		seedWithdrawn(sc, LocalDate.now().plusYears(1), attester);

		// No reason -> refused, and nothing written.
		List<SupportBulkResult> refused = sbomComponentService.bulkSetSbomComponentSupport(
				orgUuid, List.of(sc.getUuid()),
				new SupportAttestationRequest(null, null, null, "re-assessed", null, null, null,
						null, null, Set.of(), null),
				attester, UUID.randomUUID());
		assertEquals(SupportBulkOutcome.FAILED, refused.get(0).outcome());
		assertTrue(refused.get(0).message().contains("supply a reason"));
		assertEquals(SupportState.WITHDRAWN, supportOf(sc.getUuid()).state(),
				"still withdrawn -- the refusal wrote nothing");

		// With one -> applied, and the reason lands on the audit row for THIS revision.
		List<SupportBulkResult> applied = sbomComponentService.bulkSetSbomComponentSupport(
				orgUuid, List.of(sc.getUuid()),
				new SupportAttestationRequest(null, null, null, "re-assessed", null, null, null,
						null, null, Set.of(), "supplier confirmed the original claim in writing"),
				attester, UUID.randomUUID());
		assertEquals(SupportBulkOutcome.APPLIED, applied.get(0).outcome());
		assertEquals(SupportState.ATTESTED, supportOf(sc.getUuid()).state());

		String latest = auditRepository
				.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid()).get(0).getReason();
		assertEquals("supplier confirmed the original claim in writing", latest,
				"the batch reason is what explains the un-retraction; a null here would leave"
						+ " the reversal recorded with no why");
	}

	/** A uuid repeated in the input is written once, not re-asserted and re-dated. */
	@Test
	void aDuplicatedUuidInTheBatchIsAppliedOnlyOnce() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent sc = newComponent(orgUuid, "pkg:maven/org.example/bulkdup@1.0.0");
		List<SupportBulkResult> res = sbomComponentService.bulkSetSbomComponentSupport(orgUuid,
				List.of(sc.getUuid(), sc.getUuid(), sc.getUuid()),
				new SupportAttestationRequest(null, null, null, "sweep", null, null, null, null,
						null, Set.of(), null),
				UUID.randomUUID(), UUID.randomUUID());
		assertEquals(1, res.size(), "deduplicated before the write, not applied three times");
		assertEquals(SupportBulkOutcome.APPLIED, res.get(0).outcome());
	}

	/**
	 * One rejected component must not take the batch with it -- in ONE batch.
	 *
	 * <p>This is why each write runs in its own transaction. Under a single shared
	 * transaction the rejection marks it rollback-only, and catching the exception does not
	 * undo that -- the components that had already succeeded would silently vanish at commit,
	 * which is the exact opposite of skip-and-report.
	 *
	 * <p>The previous version of this test could not see any of that. It issued two separate
	 * SINGLE-item batches with two DIFFERENT requests, so nothing ever failed and succeeded
	 * in the same call; it would have passed with REQUIRES_NEW deleted outright.
	 *
	 * <p>Getting a genuine mixed batch takes care, because one request is applied to every
	 * component. The lever is that the ordering guard validates the MERGED stored-plus-staged
	 * dates: with a uniform staged endOfGuaranteedSupport of +5y, a component already holding
	 * endOfSupport +10y passes and one holding +1y is rejected. The rejection therefore
	 * happens INSIDE applySupport, after the earlier component's write has already committed
	 * in its own transaction -- which is the only arrangement that actually exercises the
	 * isolation. The pre-existing rows are withdrawn first so bulk re-attests rather than
	 * skipping them.
	 */
	@Test
	void oneRejectedComponentDoesNotRollBackTheOnesThatSucceeded() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		UUID attester = UUID.randomUUID();
		SbomComponent good1 = newComponent(orgUuid, "pkg:maven/org.example/bulkok1@1.0.0");
		SbomComponent bad = newComponent(orgUuid, "pkg:maven/org.example/bulkbad@1.0.0");
		SbomComponent good2 = newComponent(orgUuid, "pkg:maven/org.example/bulkok2@1.0.0");

		seedWithdrawn(good1, LocalDate.now().plusYears(10), attester);
		seedWithdrawn(bad, LocalDate.now().plusYears(1), attester);
		seedWithdrawn(good2, LocalDate.now().plusYears(10), attester);

		// Carries a reason because the seeded rows are withdrawn and un-retracting needs one;
		// without it all three would fail on that guard and the isolation would go untested.
		SupportAttestationRequest req = new SupportAttestationRequest(null, null, null,
				"sweep", null, LocalDate.now().plusYears(5), null, null, null, Set.of(),
				"batch re-assessment");
		List<SupportBulkResult> res = sbomComponentService.bulkSetSbomComponentSupport(
				orgUuid, List.of(good1.getUuid(), bad.getUuid(), good2.getUuid()), req, attester, UUID.randomUUID());

		assertEquals(3, res.size());
		assertEquals(SupportBulkOutcome.APPLIED, res.get(0).outcome());
		assertEquals(SupportBulkOutcome.FAILED, res.get(1).outcome(),
				"+5y guaranteed-support against a stored +1y end-of-support is out of order");
		assertNotNull(res.get(1).message(), "the reason travels with the failure");
		assertEquals(SupportBulkOutcome.APPLIED, res.get(2).outcome(),
				"a failure mid-batch must not stop the components after it");

		// The point of the test: BOTH surviving writes are still there after the method
		// returned. A shared transaction would have discarded them at commit.
		assertEquals(LocalDate.now().plusYears(5),
				supportOf(good1.getUuid()).milestoneDate(SupportMilestoneType.END_OF_GUARANTEED_SUPPORT),
				"the write that committed BEFORE the failure survived it");
		assertEquals(LocalDate.now().plusYears(5),
				supportOf(good2.getUuid()).milestoneDate(SupportMilestoneType.END_OF_GUARANTEED_SUPPORT),
				"and the one after the failure was still attempted");
		assertNull(supportOf(bad.getUuid())
				.milestoneDate(SupportMilestoneType.END_OF_GUARANTEED_SUPPORT),
				"while the rejected component wrote nothing");
	}

	/** An attested-then-withdrawn row: keeps its milestones, but reads as undisclosed. */
	private void seedWithdrawn(SbomComponent sc, LocalDate endOfSupport, UUID attester) {
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, endOfSupport, null, "seed"), attester);
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, SupportState.WITHDRAWN, null,
						"seed withdrawn", null, null, null, null, null, Set.of(), null),
				attester);
	}

	/**
	 * REGRESSION: clearing must never CREATE an attestation.
	 *
	 * <p>A clear against a component with no attestation, or against a milestone that is not
	 * set, previously took the fresh-row branch and wrote assessmentSource=MANUAL with
	 * assessedAt=now -- an assessment conjured out of a deletion nobody could have performed,
	 * which then counted toward coverage and exported as "a human looked".
	 */
	@Test
	void clearingCannotCreateAnAttestation() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent never = newComponent(orgUuid,
				"pkg:maven/org.example/neverassessed@1.0.0?t=" + UUID.randomUUID());

		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(never.getUuid(),
						new SupportAttestationRequest(null, null, null, null, null,
								null, null, null, null, Set.of(SupportMilestoneType.END_OF_LIFE),
								"removing a stale EOL"),
						UUID.randomUUID()));

		assertTrue(supportRepository.findBySbomComponentUuid(never.getUuid()).isEmpty(),
				"no attestation row was conjured into existence");
		assertEquals(0, sbomComponentService.getSupportCoverage(orgUuid).attested());

		// Same rule on a component that IS attested, for a milestone that is not set.
		SbomComponent attested = newComponent(orgUuid,
				"pkg:maven/org.example/partial@1.0.0?t=" + UUID.randomUUID());
		sbomComponentService.setSbomComponentSupport(attested.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), UUID.randomUUID());
		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(attested.getUuid(),
						new SupportAttestationRequest(null, null, null, null, null,
								null, null, null, null, Set.of(SupportMilestoneType.END_OF_LIFE),
								"no EOL was ever set"),
						UUID.randomUUID()));
	}

	/**
	 * REGRESSION (#3): a mistaken milestone date must be removable on its own.
	 *
	 * <p>Null means "leave alone", so without an explicit clear a typo is re-merged by every
	 * later write and permanently blocks any date after it, and WITHDRAWN suppresses the whole
	 * component rather than the one wrong fact. Clearing supersedes rather than erases: the
	 * removal is itself audited.
	 */
	@Test
	void aMistakenMilestoneCanBeClearedAndTheRemovalIsAudited() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/typo@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, LocalDate.now().plusYears(1), LocalDate.now().plusYears(5), null), attester);
		assertNotNull(supportOf(sc.getUuid()).milestoneDate(SupportMilestoneType.END_OF_LIFE));

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, null, null, "EOL was asserted in error", null,
						null, null, null, null, Set.of(SupportMilestoneType.END_OF_LIFE),
						"EOL was asserted in error"),
				attester);
		// A removal without a stated reason is refused: the audit keeps after-images only, so
		// nothing else on the record would say why the fact disappeared.
		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(sc.getUuid(),
						new SupportAttestationRequest(null, null, null, null, null,
								null, null, null, null, Set.of(SupportMilestoneType.END_OF_SUPPORT), null),
						attester));

		SupportData after = supportOf(sc.getUuid());
		assertNull(after.milestoneDate(SupportMilestoneType.END_OF_LIFE), "the bad date is gone");
		assertNotNull(after.milestoneDate(SupportMilestoneType.END_OF_SUPPORT),
				"the milestone nobody mentioned is untouched");
		List<SbomComponentSupportAudit> hist =
				auditRepository.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid());
		assertEquals(2, hist.size(), "one row per call: the assertion, then its correction");
		assertNull(hist.get(0).getSupportData().milestoneDate(SupportMilestoneType.END_OF_LIFE),
				"the newest audit row shows the milestone gone -- the removal is on the record");
		assertNotNull(hist.get(1).getSupportData().milestoneDate(SupportMilestoneType.END_OF_LIFE),
				"and the earlier row still shows what was claimed before it");

		// And the cleared slot no longer blocks a later date that would have sat after it.
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				dates(null, null, LocalDate.now().plusYears(2), null), attester);
		assertEquals(LocalDate.now().plusYears(2),
				supportOf(sc.getUuid()).milestoneDate(SupportMilestoneType.END_OF_LIFE));
	}

	/** Setting and clearing the same milestone in one call is rejected, not silently resolved. */
	@Test
	void settingAndClearingTheSameMilestoneIsRejected() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/conflict@1.0.0?t=" + UUID.randomUUID());
		assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.setSbomComponentSupport(sc.getUuid(),
						new SupportAttestationRequest(null, null, null, null, null,
								null, LocalDate.now().plusYears(1), null, null,
								Set.of(SupportMilestoneType.END_OF_SUPPORT), null),
						UUID.randomUUID()));
	}

	/**
	 * REGRESSION (#2): a payload this build cannot read is EXCLUDED, not silently downgraded,
	 * and it does not take the rest of the batch with it.
	 *
	 * <p>An earlier revision degraded unknown enum values to null on read; the write path then
	 * re-serialized the null and audited it, permanently destroying an attestation. Failing
	 * loudly is correct, but a throw during bulk materialization would fail a whole release's
	 * component list -- so the bulk read parses per row.
	 */
	@Test
	void anUnreadablePayloadIsExcludedWithoutFailingTheBatch() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent good = newComponent(orgUuid,
				"pkg:maven/org.example/good@1.0.0?t=" + UUID.randomUUID());
		SbomComponent poison = newComponent(orgUuid,
				"pkg:maven/org.example/poison@1.0.0?t=" + UUID.randomUUID());
		sbomComponentService.setSbomComponentSupport(good.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), UUID.randomUUID());
		sbomComponentService.setSbomComponentSupport(poison.getUuid(),
				dates(null, LocalDate.now().plusYears(1), null, null), UUID.randomUUID());

		// Simulate a row written by a build that knows a MILESTONE TYPE this one does not.
		// Deliberately a map key rather than a scalar enum value: an unknown key is the case
		// SupportData's javadoc calls forward-only, it is the most likely poison in practice
		// (adding a milestone type is on the roadmap), and an earlier revision of the coverage
		// query validated only levelOfSupport -- so a level-poisoned row passed this test
		// while leaving the actual gap open.
		// Plain JDBC on its own connection: this has to be committed and visible to the
		// service's own read, and it writes a shape no Java enum can produce.
		try (Connection c = dataSource.getConnection();
				PreparedStatement ps = c.prepareStatement(
						"UPDATE rearm.sbom_component_support SET support_data ="
						+ " jsonb_set(support_data, '{milestones,END_OF_UPDATES}',"
						+ " '{\"date\":\"2030-01-01\",\"source\":\"MANUAL\"}')"
						+ " WHERE sbom_component_uuid = ?")) {
			ps.setObject(1, poison.getUuid());
			assertEquals(1, ps.executeUpdate(), "the poison row was planted");
		} catch (SQLException e) {
			throw new IllegalStateException("could not plant the poison row", e);
		}

		Map<UUID, SupportData> loaded = sbomComponentService.findSupportByComponentIds(
				orgUuid, Set.of(good.getUuid(), poison.getUuid()));

		assertNotNull(loaded.get(good.getUuid()), "the readable row survives the poison one");
		assertNull(loaded.get(poison.getUuid()), "the unreadable row is excluded, not downgraded");

		// And it is excluded from coverage too, so the gauge cannot disagree with the export.
		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid);
		assertEquals(1, cov.attested(), "coverage counts only the row the export can serve");
	}

	/**
	 * A withdrawn attestation is retracted without being erased: the row survives, the
	 * history keeps BOTH entries, and the retraction is itself on the record. Deleting
	 * instead would destroy the evidence that makes a correction credible.
	 */
	@Test
	void withdrawalSupersedesWithoutDestroyingHistory() {
		Organization org = testInitializer.obtainOrganization();
		SbomComponent sc = newComponent(org.getUuid(),
				"pkg:maven/org.example/withdrawn@1.0.0?t=" + UUID.randomUUID());
		UUID attester = UUID.randomUUID();
		LocalDate eos = LocalDate.now().plusYears(1);

		sbomComponentService.setSbomComponentSupport(sc.getUuid(), dates(null, eos, null, null), attester);
		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(null, SupportState.WITHDRAWN, null,
						"asserted against the wrong component", null, null, null, null, null, Set.of(), null),
				attester);

		SupportData data = supportOf(sc.getUuid());
		assertEquals(SupportState.WITHDRAWN, data.state());
		assertEquals(eos, data.milestoneDate(SupportMilestoneType.END_OF_SUPPORT),
				"the withdrawn claim keeps its facts -- it is evidence of what was believed");

		List<SbomComponentSupportAudit> hist = auditRepository
				.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid());
		assertEquals(2, hist.size(), "both the assertion and its retraction are on the record");
		assertEquals(SupportState.WITHDRAWN, hist.get(0).getSupportData().state());
		assertEquals(SupportState.ATTESTED, hist.get(1).getSupportData().state());
	}

	/**
	 * The batch id is what makes an 800-component sweep legible as ONE act. Without it the
	 * only evidence is identical justification text plus one user plus clustered timestamps,
	 * and that inference fails on the case that matters: two sweeps minutes apart with the
	 * same justification are indistinguishable from one.
	 */
	@Test
	void everyAuditRowOfOneSweepCarriesTheSameBatchId() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		List<SbomComponent> deps = new ArrayList<>();
		for (int i = 0; i < 4; i++) deps.add(newComponent(orgUuid, "pkg:maven/org.example/batch" + i + "@1.0.0"));
		UUID batchId = UUID.randomUUID();

		sbomComponentService.bulkSetSbomComponentSupport(orgUuid,
				deps.stream().map(SbomComponent::getUuid).toList(),
				new SupportAttestationRequest(null, null, null, "swept", null, null, null, null,
						null, Set.of(), "closing the backlog"),
				UUID.randomUUID(), batchId);

		List<SbomComponentSupportAudit> rows =
				auditRepository.findByBatchIdAndOrgOrderByAssertedDateAsc(batchId, orgUuid);
		assertEquals(4, rows.size(), "every component written by the sweep must be reachable"
				+ " from its batch id");
		assertEquals(Set.copyOf(deps.stream().map(SbomComponent::getUuid).toList()),
				Set.copyOf(rows.stream().map(SbomComponentSupportAudit::getSbomComponentUuid).toList()));
	}

	/**
	 * Batches 2..n of a paged sweep pass back the first batch's id, so one sweep is one id
	 * however many round trips it took. This is the whole reason the argument exists.
	 */
	@Test
	void asuppliedBatchIdIsReusedAcrossCallsSoAPagedSweepIsOneAction() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent a = newComponent(orgUuid, "pkg:maven/org.example/paged0@1.0.0");
		SbomComponent b = newComponent(orgUuid, "pkg:maven/org.example/paged1@1.0.0");
		UUID batchId = UUID.randomUUID();
		SupportAttestationRequest req = new SupportAttestationRequest(null, null, null, "swept",
				null, null, null, null, null, Set.of(), "closing the backlog");

		sbomComponentService.bulkSetSbomComponentSupport(orgUuid, List.of(a.getUuid()), req,
				UUID.randomUUID(), batchId);
		sbomComponentService.bulkSetSbomComponentSupport(orgUuid, List.of(b.getUuid()), req,
				UUID.randomUUID(), batchId);

		assertEquals(2, auditRepository.findByBatchIdAndOrgOrderByAssertedDateAsc(batchId, orgUuid).size(),
				"two calls sharing a batch id are one sweep in the record, not two");
	}

	/**
	 * A single-component attestation is not a batch, and NULL says so. Recording a synthetic
	 * batch id here would make every attestation look like a sweep of one, which destroys the
	 * distinction the column was added to draw.
	 */
	@Test
	void aSingleComponentAttestationHasNoBatchId() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent sc = newComponent(orgUuid, "pkg:maven/org.example/solo@1.0.0");

		sbomComponentService.setSbomComponentSupport(sc.getUuid(),
				new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null, null,
						"upstream is active", null, null, null, null, null, Set.of(), null),
				UUID.randomUUID());

		List<SbomComponentSupportAudit> hist =
				auditRepository.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid());
		assertEquals(1, hist.size());
		assertNull(hist.get(0).getBatchId(),
				"a one-component attestation is not a sweep and must not be recorded as one");
	}

	/**
	 * The id lookup behind every bulk write must not read rows belonging to another org.
	 *
	 * <p>It used to: findAllById loaded every requested id and the caller discarded the
	 * foreign ones in Java afterwards -- the right answer, obtained by reading rows it had no
	 * business reading. The filter is in the query now.
	 */
	@Test
	void theIdLookupNeverReadsAnotherOrgsComponents() throws Exception {
		Organization orgA = testInitializer.obtainOrganization();
		// obtainOrganization mints a fresh org per call (testOrg_<random uuid>), so this is a
		// genuinely separate tenant, not a second handle on the same one.
		Organization orgB = testInitializer.obtainOrganization();
		SbomComponent mine = newComponent(orgA.getUuid(), "pkg:maven/org.example/mine@1.0.0");
		SbomComponent theirs = newComponent(orgB.getUuid(), "pkg:maven/org.example/theirs@1.0.0");

		Map<UUID, SbomComponent> found = sbomComponentService.findSbomComponentsByIds(
				List.of(mine.getUuid(), theirs.getUuid()), orgA.getUuid());

		assertEquals(Set.of(mine.getUuid()), found.keySet(),
				"a foreign component id must resolve to nothing, not be loaded and then dropped");
	}

	/**
	 * The org scoping on the batch read. This is the property that BOUNDS the client-echoable
	 * batch id: a caller can put their own rows under any uuid they like, but the read can
	 * never hand them somebody else's. Untested, the argument for accepting an unvalidated
	 * argument has nothing holding it up.
	 */
	@Test
	void theBatchReadNeverCrossesOrgs() throws Exception {
		Organization orgA = testInitializer.obtainOrganization();
		Organization orgB = testInitializer.obtainOrganization();
		SbomComponent a = newComponent(orgA.getUuid(), "pkg:maven/org.example/xa@1.0.0");
		SbomComponent b = newComponent(orgB.getUuid(), "pkg:maven/org.example/xb@1.0.0");
		// The SAME id, deliberately -- the case an echoing client can create.
		UUID shared = UUID.randomUUID();
		SupportAttestationRequest req = new SupportAttestationRequest(null, null, null, "swept",
				null, null, null, null, null, Set.of(), "closing the backlog");

		sbomComponentService.bulkSetSbomComponentSupport(orgA.getUuid(), List.of(a.getUuid()),
				req, UUID.randomUUID(), shared);
		sbomComponentService.bulkSetSbomComponentSupport(orgB.getUuid(), List.of(b.getUuid()),
				req, UUID.randomUUID(), shared);

		List<SbomComponentSupportAudit> seenByA =
				auditRepository.findByBatchIdAndOrgOrderByAssertedDateAsc(shared, orgA.getUuid());
		assertEquals(1, seenByA.size(), "a shared batch id must not widen into another org's rows");
		assertEquals(a.getUuid(), seenByA.get(0).getSbomComponentUuid());
	}

	/**
	 * A batch row exists for what was WRITTEN, not for what was asked about. A skipped
	 * component has no new audit row at all, so the batch must not appear to cover it --
	 * otherwise the correlation id claims authorship of a write that never happened.
	 */
	@Test
	void skippedComponentsAreNotPartOfTheBatch() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent root = newRootComponent(orgUuid, "pkg:maven/org.example/skiproot@1.0.0");
		SbomComponent dep = newComponent(orgUuid, "pkg:maven/org.example/skipdep@1.0.0");
		UUID batchId = UUID.randomUUID();

		List<SupportBulkResult> res = sbomComponentService.bulkSetSbomComponentSupport(orgUuid,
				List.of(root.getUuid(), dep.getUuid()),
				new SupportAttestationRequest(null, null, null, "swept", null, null, null, null,
						null, Set.of(), "closing the backlog"),
				UUID.randomUUID(), batchId);

		long applied = res.stream().filter(r -> SupportBulkOutcome.APPLIED == r.outcome()).count();
		assertEquals(1, applied, "the root is skipped, the dependency is written");
		assertEquals(applied,
				auditRepository.findByBatchIdAndOrgOrderByAssertedDateAsc(batchId, orgUuid).size(),
				"the batch must name exactly the rows it wrote, not the ids it was handed");
	}

	/**
	 * The id lookup at a size that would break a derived IN list.
	 *
	 * <p>The whole reason change 3 exists is the Postgres 65,535 bound-parameter cap, and the
	 * two-id org test does not exercise it at all -- a revert to findAllById would pass it.
	 * 3,000 ids is comfortably past the point where the old shape's parameter count is the
	 * thing under test, while staying quick.
	 */
	@Test
	void theIdLookupHandlesAnIdSetFarPastAnInListsComfortZone() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		SbomComponent real = newComponent(orgUuid, "pkg:maven/org.example/needle@1.0.0");
		List<UUID> haystack = new ArrayList<>();
		haystack.add(real.getUuid());
		// Ids that resolve to nothing: the query must be asked about all of them, which is
		// exactly the parameter-count pressure a derived IN list could not take.
		for (int i = 0; i < 3000; i++) haystack.add(UUID.randomUUID());

		Map<UUID, SbomComponent> found =
				sbomComponentService.findSbomComponentsByIds(haystack, orgUuid);

		assertEquals(Set.of(real.getUuid()), found.keySet(),
				"a 3001-id lookup must return the one real row, not fail on parameter count");
	}
}
