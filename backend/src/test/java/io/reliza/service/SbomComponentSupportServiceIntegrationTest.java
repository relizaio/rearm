/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Organization;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentSupportAudit;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportStatus;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.service.SbomComponentService.SupportCoverage;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Behavioral validation for FDA-Readiness-1 PR1 (per-component support-status +
 * EOS/EOL). Boots the app against the shared rearm-test-pg so the V82 migration
 * actually applies, then exercises the attestation write end-to-end: columns
 * persist, the derived status is computed from the stored dates, the append-only
 * attestation history captures the after-image + attester (and a second
 * attestation APPENDS rather than overwrites), and the coverage counts move.
 * Uses a fresh per-test org so the shared DB cannot contaminate the assertions.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SbomComponentSupportServiceIntegrationTest {

	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private SbomComponentSupportAuditRepository auditRepository;
	@Autowired private TestInitializer testInitializer;

	private SbomComponent newComponent(UUID org, String canonicalPurl) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonicalPurl);
		return sbomComponentRepository.save(sc);
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
				sc.getUuid(), pastEos, null, "vendor announced EOS", attester);

		// Persisted facts.
		SbomComponent reloaded = sbomComponentRepository.findById(sc.getUuid()).orElseThrow();
		assertEquals(pastEos, reloaded.getEndOfSupportDate());
		assertNull(reloaded.getEndOfLifeDate());
		assertEquals(SupportSource.MANUAL, reloaded.getSupportSource());
		assertEquals(attester, reloaded.getSupportAssertedBy());
		assertNotNull(reloaded.getSupportLastAssessed());
		assertEquals("vendor announced EOS", reloaded.getSupportNotes());

		// Derived status: past EOS, no EOL -> END_OF_SUPPORT.
		assertEquals(SupportStatus.END_OF_SUPPORT, SupportStatus.derive(
				reloaded.getEndOfSupportDate(), reloaded.getEndOfLifeDate(),
				reloaded.getSupportSource(), LocalDate.now()));

		// Append-only history: one after-image row with the attester.
		List<SbomComponentSupportAudit> hist = auditRepository
				.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid());
		assertEquals(1, hist.size());
		assertEquals(pastEos, hist.get(0).getEndOfSupportDate());
		assertEquals(SupportSource.MANUAL, hist.get(0).getSupportSource());
		assertEquals(attester, hist.get(0).getSupportAssertedBy());
		assertEquals(reloaded.getRevision(), hist.get(0).getSupportRevision(),
				"audit revision should match the committed component revision");

		// A second attestation APPENDS (never overwrites) and moves the derived status.
		LocalDate futureEos = LocalDate.now().plusYears(1);
		sbomComponentService.setSbomComponentSupport(sc.getUuid(), futureEos, null, null, attester);
		List<SbomComponentSupportAudit> hist2 = auditRepository
				.findBySbomComponentUuidOrderByAssertedDateDesc(sc.getUuid());
		assertEquals(2, hist2.size());

		SbomComponent reloaded2 = sbomComponentRepository.findById(sc.getUuid()).orElseThrow();
		assertEquals(SupportStatus.ACTIVELY_SUPPORTED, SupportStatus.derive(
				reloaded2.getEndOfSupportDate(), reloaded2.getEndOfLifeDate(),
				reloaded2.getSupportSource(), LocalDate.now()));

		// Coverage counts the (only, non-root) MANUAL-attested component in this fresh org.
		SupportCoverage cov = sbomComponentService.getSupportCoverage(orgUuid);
		assertEquals(1, cov.total(), "the one non-root component is counted in total");
		assertEquals(1, cov.attested(), "the MANUAL attestation is counted");

		// Discriminating: a SUPPLIER-sourced row is NOT a manufacturer attestation, so it
		// counts toward total but MUST NOT count as attested (pins the MANUAL-only numerator).
		SbomComponent supplierComp = newComponent(orgUuid,
				"pkg:maven/org.example/other@2.0.0?t=" + UUID.randomUUID());
		supplierComp.setSupportSource(SupportSource.SUPPLIER);
		sbomComponentRepository.save(supplierComp);
		SupportCoverage cov2 = sbomComponentService.getSupportCoverage(orgUuid);
		assertEquals(2, cov2.total(), "both non-root components counted in total");
		assertEquals(1, cov2.attested(), "SUPPLIER provenance excluded; only MANUAL attested");
	}
}
