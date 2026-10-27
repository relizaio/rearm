/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.StatusEnum;
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
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * archiveBranches (TPF-77, mutation round): what {@link BranchBulkArchiveServiceTest} leaves open. The bound
 * admits exactly 200 ids; a refusal written for a person (a RelizaException) reaches the outcome as written;
 * each write commits on its own even when the caller's transaction is rolled back; an empty call is not
 * refused by a sealed system, since it writes nothing.
 */
@SpringBootTest(classes = {App.class})
public class BranchBulkArchiveGapsTest {

	@MockitoSpyBean private BranchService branchService;
	@Autowired private BranchBulkArchiveService branchBulkArchiveService;
	@Autowired private ComponentService componentService;
	@Autowired private UserService userService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private PlatformTransactionManager transactionManager;

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

	/** A component with two REGULAR branches, a member who may write its branches and one who may only read them. */
	private record World(Component a, UUID b1, UUID b2, UserData writer, UserData reader) {}

	private World world() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = componentService.createComponent("tpf77-gap-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		UUID b1 = branchService.createBranch("b1", a.getUuid(), BranchType.REGULAR, WU).getUuid();
		UUID b2 = branchService.createBranch("b2", a.getUuid(), BranchType.REGULAR, WU).getUuid();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("TPF77 gap " + tag, "tpf77-gap-" + tag + "@bulk-archive.io", true,
				List.of(org.getUuid()), "tpf77-gap-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.COMPONENT, a.getUuid(),
				PermissionType.READ_WRITE, List.of(PermissionFunction.RESOURCE), null, WU);
		String rtag = UUID.randomUUID().toString().substring(0, 8);
		User r = userService.createUser("TPF77 reader " + rtag, "tpf77-reader-" + rtag + "@bulk-archive.io", true,
				List.of(org.getUuid()), "tpf77-reader-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(r.getUuid(), org.getUuid(), PermissionScope.COMPONENT, a.getUuid(),
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null, WU);
		return new World(a, b1, b2, userService.getUserData(u.getUuid()).orElseThrow(),
				userService.getUserData(r.getUuid()).orElseThrow());
	}

	private StatusEnum status(UUID branch) {
		return branchService.getBranchData(branch).orElseThrow().getStatus();
	}

	@Test
	void exactlyTheBoundIsAdmitted() throws Exception {
		World w = world();
		List<UUID> ids = new ArrayList<>();
		ids.add(w.b1());
		while (ids.size() < BranchBulkArchiveService.BULK_ARCHIVE_MAX_IDS) ids.add(UUID.randomUUID());

		List<BranchArchiveResult> results = branchBulkArchiveService.archiveBranches(ids, w.writer(), WU);
		assertEquals(BranchBulkArchiveService.BULK_ARCHIVE_MAX_IDS, results.size());
		assertEquals(BranchArchiveOutcome.ARCHIVED, results.get(0).outcome());
		assertEquals(StatusEnum.ARCHIVED, status(w.b1()));
	}

	@Test
	void aRefusalWrittenForAPersonReachesTheOutcomeAsWritten() throws Exception {
		World w = world();
		doThrow(new RelizaException("branch name b2 is not allowed")).when(branchService)
				.archiveBranchIsolated(eq(w.b2()), any());

		List<BranchArchiveResult> results = branchBulkArchiveService.archiveBranches(List.of(w.b1(), w.b2()),
				w.writer(), WU);
		assertEquals(BranchArchiveOutcome.ARCHIVED, results.get(0).outcome());
		assertEquals(BranchArchiveOutcome.FAILED, results.get(1).outcome());
		assertEquals("branch name b2 is not allowed", results.get(1).message());
		assertEquals(StatusEnum.ACTIVE, status(w.b2()));
	}

	@Test
	void eachWriteCommitsOnItsOwnWhenTheCallersTransactionRollsBack() throws Exception {
		World w = world();
		new TransactionTemplate(transactionManager).execute(tx -> {
			try {
				branchBulkArchiveService.archiveBranches(List.of(w.b1()), w.writer(), WU);
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
			tx.setRollbackOnly();
			return null;
		});
		assertEquals(StatusEnum.ARCHIVED, status(w.b1()), "the archive is its own transaction, not the caller's");
	}

	@Test
	void anEmptyCallWritesNothingSoASealedSystemDoesNotRefuseIt() throws Exception {
		World w = world();
		licenseStatus.setSystemSealed(true);
		assertTrue(branchBulkArchiveService.archiveBranches(List.of(), w.writer(), WU).isEmpty());
	}

	@Test
	void aMemberWhoMayOnlyReadAnArchiveIsForbidden() throws Exception {
		World w = world();
		List<BranchArchiveResult> results = branchBulkArchiveService.archiveBranches(List.of(w.b1()), w.reader(), WU);
		assertEquals(List.of(BranchArchiveOutcome.FORBIDDEN), results.stream().map(BranchArchiveResult::outcome).toList());
		assertEquals(StatusEnum.ACTIVE, status(w.b1()));
	}

	@Test
	void anotherOrganizationsBaseAndArchivedBranchesAnswerWhatAnUnknownUuidAnswers() throws Exception {
		World w = world();
		Organization other = testInitializer.obtainOrganization();
		Component c = componentService.createComponent("tpf77-gap-x-" + UUID.randomUUID(), other.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		UUID foreignBase = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow().getUuid();
		UUID foreignArchived = branchService.createBranch("fx", c.getUuid(), BranchType.REGULAR, WU).getUuid();
		branchService.archiveBranch(foreignArchived, WU);

		List<BranchArchiveResult> results = branchBulkArchiveService.archiveBranches(
				List.of(foreignBase, foreignArchived, UUID.randomUUID()), w.writer(), WU);
		assertEquals(List.of(BranchArchiveOutcome.NOT_FOUND, BranchArchiveOutcome.NOT_FOUND, BranchArchiveOutcome.NOT_FOUND),
				results.stream().map(BranchArchiveResult::outcome).toList(),
				"another organization's base and archived branches read exactly what an unknown uuid reads");
		assertEquals(StatusEnum.ARCHIVED, status(foreignArchived));
	}

	@Test
	void aMemberWhoMayNotWriteReadsForbiddenForBaseAndArchivedBranchesToo() throws Exception {
		World w = world();
		UUID base = branchService.getBaseBranchOfComponent(w.a().getUuid()).orElseThrow().getUuid();
		assertEquals(BranchArchiveOutcome.ARCHIVED,
				branchBulkArchiveService.archiveBranches(List.of(w.b2()), w.writer(), WU).get(0).outcome());

		List<BranchArchiveOutcome> reader = branchBulkArchiveService.archiveBranches(List.of(base, w.b2()), w.reader(), WU)
				.stream().map(BranchArchiveResult::outcome).toList();
		assertEquals(List.of(BranchArchiveOutcome.FORBIDDEN, BranchArchiveOutcome.FORBIDDEN), reader,
				"a caller who may not write learns neither the type nor the status of the branch");
		List<BranchArchiveOutcome> writer = branchBulkArchiveService.archiveBranches(List.of(base, w.b2()), w.writer(), WU)
				.stream().map(BranchArchiveResult::outcome).toList();
		assertEquals(List.of(BranchArchiveOutcome.SKIPPED_BASE, BranchArchiveOutcome.SKIPPED_ARCHIVED), writer);
	}
}
