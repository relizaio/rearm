/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchArchiveOutcome;
import io.reliza.model.BranchArchiveResult;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AuditRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * archiveBranches (TPF-77, TPF-73 architecture 4.1 to 4.3): one outcome per distinct branch in
 * input order, authorized per branch with archiveBranch's own question by the real
 * AuthorizationService, each archive in its own transaction with its own audit row, the bound
 * before any read, and the sealed-system and licence gate once for the whole batch.
 */
@SpringBootTest(classes = {App.class})
public class BranchBulkArchiveServiceTest {

	@MockitoSpyBean private BranchService branchService;
	@Autowired private BranchBulkArchiveService branchBulkArchiveService;
	@Autowired private ComponentService componentService;
	@Autowired private UserService userService;
	@Autowired private AuditRepository auditRepository;
	@Autowired private TestInitializer testInitializer;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	/** Component A (base, b1, b2, b3 REGULAR, t1 TAG), B in the same org (bB), bX in a second org. */
	private record World(Organization org, Component a, UUID baseA, UUID b1, UUID b2, UUID b3, UUID t1,
			UUID bB, UUID bX, UserData writerA, UserData writerB1) {}

	private World world() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org);
		UUID baseA = branchService.getBaseBranchOfComponent(a.getUuid()).orElseThrow().getUuid();
		UUID b1 = branch("b1", a);
		UUID b2 = branch("b2", a);
		UUID b3 = branch("b3", a);
		UUID t1 = branchService.createBranch("t1", a.getUuid(), BranchType.TAG, WU).getUuid();
		UUID bB = branch("bB", component(org));
		Organization other = testInitializer.obtainOrganization();
		UUID bX = branch("bX", component(other));
		return new World(org, a, baseA, b1, b2, b3, t1, bB, bX,
				writer(org, PermissionScope.COMPONENT, a.getUuid()),
				writer(org, PermissionScope.BRANCH, b1));
	}

	private Component component(Organization org) throws RelizaException {
		return componentService.createComponent("tpf77-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	private UUID branch(String name, Component c) throws RelizaException {
		return branchService.createBranch(name, c.getUuid(), BranchType.REGULAR, WU).getUuid();
	}

	/** A member of {@code org} with READ_WRITE RESOURCE on one object and nothing else. */
	private UserData writer(Organization org, PermissionScope scope, UUID object) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("TPF77 " + tag, "tpf77-" + tag + "@bulk-archive.io", true,
				List.of(org.getUuid()), "tpf77-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, PermissionType.READ_WRITE,
				List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private int audits(UUID branch) {
		return auditRepository.retrieveAuditRecordsForEntity(TableName.BRANCHES.toString(), branch.toString(),
				"1000", "0").size();
	}

	private StatusEnum status(UUID branch) {
		return branchService.getBranchData(branch).orElseThrow().getStatus();
	}

	private static List<BranchArchiveOutcome> outcomes(List<BranchArchiveResult> results) {
		return results.stream().map(BranchArchiveResult::outcome).toList();
	}

	private static List<UUID> uuids(List<BranchArchiveResult> results) {
		return results.stream().map(BranchArchiveResult::branchUuid).toList();
	}

	@Test
	void aMixedBatchAnswersEveryBranchInInputOrder() throws Exception {
		World w = world();
		UUID random = UUID.randomUUID();
		List<UUID> ids = List.of(w.b1(), w.b2(), w.baseA(), random, w.bX(), w.bB(), w.t1());
		List<UUID> unchanged = List.of(w.baseA(), w.bB(), w.bX());
		List<UUID> archived = List.of(w.b1(), w.b2(), w.t1());
		List<Integer> auditsBefore = ids.stream().filter(u -> !random.equals(u)).map(this::audits).toList();

		List<BranchArchiveResult> results = branchBulkArchiveService.archiveBranches(ids, w.writerA(), WU);

		assertEquals(ids, uuids(results));
		assertEquals(List.of(BranchArchiveOutcome.ARCHIVED, BranchArchiveOutcome.ARCHIVED,
				BranchArchiveOutcome.SKIPPED_BASE, BranchArchiveOutcome.NOT_FOUND,
				BranchArchiveOutcome.NOT_FOUND, BranchArchiveOutcome.FORBIDDEN,
				BranchArchiveOutcome.ARCHIVED), outcomes(results),
				"another organization's branch must answer the random uuid's word, not FORBIDDEN");
		results.forEach(r -> assertNull(r.message(), "no message outside FAILED: " + r));
		List<UUID> known = ids.stream().filter(u -> !random.equals(u)).toList();
		for (int i = 0; i < known.size(); i++) {
			UUID b = known.get(i);
			if (archived.contains(b)) {
				assertEquals(StatusEnum.ARCHIVED, status(b));
				assertEquals(auditsBefore.get(i) + 1, audits(b), "exactly one audit row per archived branch");
			} else {
				assertTrue(unchanged.contains(b));
				assertTrue(StatusEnum.ARCHIVED != status(b), "a refused or skipped branch keeps its status");
				assertEquals(auditsBefore.get(i), audits(b), "a refused or skipped branch gets no audit row");
			}
		}
	}

	@Test
	void aRerunSkipsWhatIsAlreadyArchivedAndWritesNothing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = component(org);
		UUID b1 = branch("b1", c);
		UUID b2 = branch("b2", c);
		UserData writerB1 = writer(org, PermissionScope.BRANCH, b1);

		assertEquals(List.of(BranchArchiveOutcome.ARCHIVED, BranchArchiveOutcome.FORBIDDEN),
				outcomes(branchBulkArchiveService.archiveBranches(List.of(b1, b2), writerB1, WU)));
		int b1Audits = audits(b1);
		int b2Audits = audits(b2);

		assertEquals(List.of(BranchArchiveOutcome.SKIPPED_ARCHIVED, BranchArchiveOutcome.FORBIDDEN),
				outcomes(branchBulkArchiveService.archiveBranches(List.of(b1, b2), writerB1, WU)));
		assertEquals(b1Audits, audits(b1), "a re-run must not pad the history");
		assertEquals(StatusEnum.ARCHIVED, status(b1));
		assertEquals(StatusEnum.ACTIVE, status(b2));
		assertEquals(b2Audits, audits(b2));
	}

	@Test
	void duplicatesAnswerOnceAndTheBoundRefusesBeforeAnyRead() throws Exception {
		World w = world();
		List<BranchArchiveResult> once = branchBulkArchiveService.archiveBranches(List.of(w.b3(), w.b3()),
				w.writerA(), WU);
		assertEquals(1, once.size());
		assertEquals(BranchArchiveOutcome.ARCHIVED, once.get(0).outcome());

		int b2Audits = audits(w.b2());
		List<UUID> tooMany = new ArrayList<>();
		tooMany.add(w.b2());
		while (tooMany.size() < BranchBulkArchiveService.BULK_ARCHIVE_MAX_IDS + 1) tooMany.add(UUID.randomUUID());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> branchBulkArchiveService.archiveBranches(tooMany, w.writerA(), WU));
		assertTrue(e.getMessage().contains("200") && e.getMessage().contains("201"), e.getMessage());
		assertEquals(StatusEnum.ACTIVE, status(w.b2()));
		assertEquals(b2Audits, audits(w.b2()), "nothing is written past the bound");
	}

	@Test
	void oneFailedWriteUndoesNothingElse() throws Exception {
		World w = world();
		doThrow(new RuntimeException("boom")).when(branchService).archiveBranchIsolated(eq(w.b2()), any());
		int b1Audits = audits(w.b1());
		int b2Audits = audits(w.b2());
		int b3Audits = audits(w.b3());

		List<BranchArchiveResult> results = branchBulkArchiveService.archiveBranches(
				List.of(w.b1(), w.b2(), w.b3()), w.writerA(), WU);

		assertEquals(List.of(BranchArchiveOutcome.ARCHIVED, BranchArchiveOutcome.FAILED,
				BranchArchiveOutcome.ARCHIVED), outcomes(results));
		assertEquals(BranchBulkArchiveService.FAILED_GENERIC_MESSAGE, results.get(1).message(),
				"the generic text, never the exception's own message");
		assertEquals(StatusEnum.ARCHIVED, status(w.b1()));
		assertEquals(b1Audits + 1, audits(w.b1()));
		assertEquals(StatusEnum.ARCHIVED, status(w.b3()));
		assertEquals(b3Audits + 1, audits(w.b3()));
		assertEquals(StatusEnum.ACTIVE, status(w.b2()));
		assertEquals(b2Audits, audits(w.b2()));
	}

	@Test
	void aGlobalAdminArchivesAnotherOrganizationsBranch() throws Exception {
		World w = world();
		UserData admin = writer(w.org(), PermissionScope.COMPONENT, w.a().getUuid());
		ReflectionTestUtils.setField(admin, "globalAdmin", true);

		assertEquals(List.of(BranchArchiveOutcome.ARCHIVED),
				outcomes(branchBulkArchiveService.archiveBranches(List.of(w.bX()), admin, WU)));
		assertEquals(StatusEnum.ARCHIVED, status(w.bX()));
	}

	@Test
	void aSealedSystemRefusesTheWholeBatch() throws Exception {
		World w = world();
		UUID b6 = branch("b6", w.a());
		int b6Audits = audits(b6);
		licenseStatus.setSystemSealed(true);

		RelizaException e = assertThrows(RelizaException.class,
				() -> branchBulkArchiveService.archiveBranches(List.of(b6), w.writerA(), WU));
		assertEquals("System is sealed. Please unseal the system first.", e.getMessage());
		licenseStatus.setSystemSealed(false);
		assertEquals(StatusEnum.ACTIVE, status(b6));
		assertEquals(b6Audits, audits(b6));
	}
}
