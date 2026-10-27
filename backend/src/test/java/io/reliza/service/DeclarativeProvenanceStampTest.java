/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.testsupport.StampAssertions.assertContentSave;
import static io.reliza.testsupport.StampAssertions.assertStampedOnly;
import static io.reliza.testsupport.StampAssertions.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.ComponentRevisionConflictException;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeclarativeProvenance;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ComponentData;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ComponentDto;
import io.reliza.service.DeclarativeConfigService.Action;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.service.DeclarativeConfigService.BranchSpecDto;
import io.reliza.service.DeclarativeConfigService.BranchesSpecDto;
import io.reliza.service.DeclarativeConfigService.CatalogComponentDto;
import io.reliza.service.DeclarativeConfigService.CatalogSpecDto;
import io.reliza.service.DeclarativeConfigService.SourceDto;
import io.reliza.testsupport.StampAssertions.RowSnapshot;

/**
 * The declarative provenance stamps of components and branches are in place (TPF-71 / TPF-108,
 * architecture round 2, 4.3-4.7, 5.2): a stamp adds no revision and no audit row and touches only
 * {@code declarative}; a field-changing apply is one revision, not two, whose audit copy carries the
 * previous fields and provenance; a created row ends its apply at revision 0; the TPF-61 revision check
 * is refused by a field-changing apply only.
 */
public class DeclarativeProvenanceStampTest extends ComponentConcurrentWriteTestBase {

	@Autowired private BranchService branchService;
	@Autowired private io.reliza.repositories.ComponentRepository componentRepository;
	@Autowired private io.reliza.repositories.BranchRepository branchRepository;
	@Autowired private DeclarativeConfigService declarativeConfigService;

	private static final String IP = "203.0.113.9";

	// ---------- fixtures ----------

	private RowSnapshot comp(UUID component) {
		return snapshot(jdbcTemplate, TableName.COMPONENTS, component);
	}

	private RowSnapshot branch(UUID branchUuid) {
		return snapshot(jdbcTemplate, TableName.BRANCHES, branchUuid);
	}

	private static SourceDto source(String repo, String path, String commit) {
		SourceDto s = new SourceDto();
		s.setRepo(repo);
		s.setPath(path);
		s.setCommit(commit);
		return s;
	}

	/** One component, as a CI spec names it; versionSchema and featureBranchVersioning let a create succeed. */
	private static CatalogSpecDto spec(String name, String repoPath) {
		CatalogSpecDto spec = new CatalogSpecDto();
		CatalogComponentDto c = new CatalogComponentDto();
		c.setName(name);
		c.setType(ComponentType.COMPONENT);
		c.setVersionSchema("semver");
		c.setFeatureBranchVersioning("Branch.Micro");
		c.setRepoPath(repoPath);
		spec.getComponents().add(c);
		return spec;
	}

	private static CatalogSpecDto spec(String name) {
		return spec(name, null);
	}

	/** One branch of one component, not authoritative (no pruning of the component's other branches). */
	private static BranchesSpecDto branches(String component, BranchSpecDto bs) {
		BranchesSpecDto spec = new BranchesSpecDto();
		spec.setComponent(component);
		spec.setAuthoritative(false);
		spec.getBranches().add(bs);
		return spec;
	}

	private static BranchSpecDto branchSpec(String name, String vcsBranch) {
		BranchSpecDto bs = new BranchSpecDto();
		bs.setName(name);
		bs.setVcsBranch(vcsBranch);
		return bs;
	}

	private ApplyResult apply(CatalogSpecDto spec, String commit) throws Exception {
		return apply(spec, commit, WU);
	}

	private ApplyResult apply(CatalogSpecDto spec, String commit, WhoUpdated wu) throws Exception {
		ApplyResult r = declarativeConfigService.applyCatalog(org, spec, false, source("r", "p", commit), wu);
		assertEquals(0, r.getErrors(), String.valueOf(r.getChanges()));
		return r;
	}

	private ApplyResult apply(BranchesSpecDto spec, String commit) throws Exception {
		ApplyResult r = declarativeConfigService.applyBranches(org, spec, false, source("r", "p", commit), WU);
		assertEquals(0, r.getErrors(), String.valueOf(r.getChanges()));
		return r;
	}

	private static Action action(ApplyResult r) {
		return r.getChanges().get(0).getAction();
	}

	private String name(UUID component) {
		return stored(component).getName();
	}

	private UUID componentNamed(String name) {
		return jdbcTemplate.queryForObject("select uuid from rearm.components where record_data->>'name' = ? "
				+ "and record_data->>'org' = ?", UUID.class, name, org.toString());
	}

	private UUID baseBranch(UUID component) {
		return branchService.getBaseBranchOfComponent(component).orElseThrow().getUuid();
	}

	private UUID branchNamed(UUID component, String name) throws Exception {
		return branchService.findBranchByName(component, name).orElseThrow().getUuid();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> latestAuditCopy(TableName tn, UUID uuid) {
		String rd = jdbcTemplate.queryForObject("select cast(revision_record_data as text) from rearm.audit "
				+ "where entity_name = ? and entity_uuid = ? order by revision desc limit 1", String.class, tn.toString(), uuid);
		return Utils.OM.readValue(rd, Map.class);
	}

	private String appliedAtType(TableName tn, UUID uuid) {
		return jdbcTemplate.queryForObject("select jsonb_typeof(record_data->'declarative'->'appliedAt') from rearm."
				+ tn.toString() + " where uuid = ?", String.class, uuid);
	}

	// ---------- 5.2, components ----------

	/** Row 1. */
	@Test
	public void theComponentStampTouchesOnlyDeclarative() throws Exception {
		int r = revision(c);
		int a = audits(c);
		RowSnapshot before = comp(c);
		DeclarativeProvenance prov = provenance();
		componentService.stampDeclarativeProvenance(c, prov, WU);
		assertStampedOnly(before, comp(c), Set.of("declarative"));
		assertEquals(prov.getSpecHash(), stored().getDeclarative().getSpecHash());
		assertEquals("number", appliedAtType(TableName.COMPONENTS, c));
		assertEquals(r, revision(c));
		assertEquals(a, audits(c));
	}

	/** The two stamp statements change the one row they name; an unknown uuid changes nothing. */
	@Test
	public void theComponentAndBranchStatementsChangeTheirOwnRowOnly() throws Exception {
		UUID other = newComponent();
		UUID otherBranch = baseBranch(other);
		RowSnapshot otherComponentBefore = comp(other);
		RowSnapshot otherBranchBefore = branch(otherBranch);
		String json = Utils.OM.writeValueAsString(provenance());

		assertEquals(1, componentRepository.stampDeclarativeProvenance(c, json));
		assertEquals(otherComponentBefore, comp(other), "another component changed");
		assertEquals(1, branchRepository.stampDeclarativeProvenance(baseBranch(c), json));
		assertEquals(otherBranchBefore, branch(otherBranch), "another branch changed");

		UUID unknown = UUID.randomUUID();
		assertEquals(0, componentRepository.stampDeclarativeProvenance(unknown, json));
		assertEquals(0, branchRepository.stampDeclarativeProvenance(unknown, json));
		assertEquals(otherComponentBefore, comp(other));
		assertEquals(otherBranchBefore, branch(otherBranch));
	}

	/** A missing provenance is refused before anything is written: it must not be stored as a JSON null. */
	@Test
	public void aNullProvenanceIsRefusedAndWritesNothing() throws Exception {
		componentService.stampDeclarativeProvenance(c, provenance(), WU);
		UUID branchUuid = baseBranch(c);
		branchService.stampDeclarativeProvenance(branchUuid, provenance(), WU);
		RowSnapshot componentBefore = comp(c);
		RowSnapshot branchBefore = branch(branchUuid);

		assertThrows(NullPointerException.class,
				() -> componentService.stampDeclarativeProvenance(c, null, WU));
		assertThrows(NullPointerException.class,
				() -> branchService.stampDeclarativeProvenance(branchUuid, null, WU));
		assertEquals(componentBefore, comp(c));
		assertEquals(branchBefore, branch(branchUuid));
	}

	/** The row a transaction read before the stamp is the stamped row afterwards: the stamp refreshes it. */
	@Test
	public void aRowReadBeforeTheStampInTheSameTransactionIsTheStampedRowAfterwards() throws Exception {
		UUID branchUuid = baseBranch(c);
		DeclarativeProvenance componentProv = provenance();
		DeclarativeProvenance branchProv = provenance();
		new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			try {
				componentRepository.findById(c).orElseThrow();       // the pre-stamp copies, now managed
				branchRepository.findById(branchUuid).orElseThrow();
				componentService.stampDeclarativeProvenance(c, componentProv, WU);
				branchService.stampDeclarativeProvenance(branchUuid, branchProv, WU);
				assertEquals(componentProv.getSpecHash(), ComponentData.dataFromRecord(componentRepository.findById(c).orElseThrow())
						.getDeclarative().getSpecHash(), "the component the transaction reads next carries the stamp");
				assertEquals(branchProv.getSpecHash(), io.reliza.model.BranchData.branchDataFromDbRecord(
						branchRepository.findById(branchUuid).orElseThrow()).getDeclarative().getSpecHash(),
						"and so does the branch");
			} catch (Exception e) {
				throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
			}
		});
	}

	/** Row 2: a whole save writes the stamped provenance back unchanged. */
	@Test
	public void aStampedProvenanceSurvivesAWholeSaveUnchanged() throws Exception {
		componentService.stampDeclarativeProvenance(c, provenance(), WU);
		RowSnapshot before = comp(c);
		componentService.setLocks(c, List.of(), WU);
		RowSnapshot after = comp(c);
		assertContentSave(before, after);
		assertEquals(before.recordData().get("declarative"), after.recordData().get("declarative"));
	}

	/** Row 3 (F-2): a spec-text-only apply stamps and does nothing else; an identical apply writes nothing. */
	@Test
	public void aSpecTextOnlyApplyIsAStampAndNothingElse() throws Exception {
		String n = "decl_" + UUID.randomUUID();
		apply(spec(n), "c1");
		UUID d = componentNamed(n);
		RowSnapshot before = comp(d);

		CatalogSpecDto v2 = spec(n);
		v2.setVersion(2);
		ApplyResult second = apply(v2, "c2");
		assertEquals(Action.UNCHANGED, action(second));
		RowSnapshot after = comp(d);
		assertStampedOnly(before, after, Set.of("declarative"));
		DeclarativeProvenance stamped = stored(d).getDeclarative();
		assertEquals(second.getSpecHash(), stamped.getSpecHash());
		assertNotEquals(((Map<?, ?>) before.recordData().get("declarative")).get("specHash"), stamped.getSpecHash());
		assertEquals("c2", stamped.getSource().getCommit());

		CatalogSpecDto v2again = spec(n);
		v2again.setVersion(2);
		assertEquals(Action.UNCHANGED, action(apply(v2again, "c3")));
		RowSnapshot third = comp(d);
		assertStampedOnly(after, third, Set.of());
		assertEquals(after.recordData().get("declarative"), third.recordData().get("declarative"));
	}

	/** Row 4: a field-changing apply is one revision; its audit copy holds the previous field and provenance. */
	@Test
	@SuppressWarnings("unchecked")
	public void aFieldChangingApplyIsOneRevisionNotTwo() throws Exception {
		String n = name(c);
		RowSnapshot before = comp(c);
		ApplyResult first = apply(spec(n, "one"), "c1");
		assertEquals(Action.UPDATE, action(first));
		RowSnapshot after = comp(c);
		assertContentSave(before, after);
		Map<String, Object> copy = latestAuditCopy(TableName.COMPONENTS, c);
		assertNull(copy.get("declarative"), "a first apply: the copy carries no provenance");
		assertEquals(before.recordData().get("repoPath"), copy.get("repoPath"));
		assertEquals("one", stored().getRepoPath());
		assertEquals(first.getSpecHash(), stored().getDeclarative().getSpecHash());

		ApplyResult second = apply(spec(n, "two"), "c2");
		assertEquals(Action.UPDATE, action(second));
		RowSnapshot after2 = comp(c);
		assertContentSave(after, after2);
		Map<String, Object> copy2 = latestAuditCopy(TableName.COMPONENTS, c);
		assertEquals(after.recordData().get("declarative"), copy2.get("declarative"), "the copy carries the previous provenance");
		assertEquals("one", copy2.get("repoPath"));
		assertEquals("two", stored().getRepoPath());
		assertEquals(second.getSpecHash(), ((Map<String, Object>) after2.recordData().get("declarative")).get("specHash"));
	}

	/** Row 5. */
	@Test
	public void anApplyThatCreatesEndsAtRevisionZero() throws Exception {
		String n = "decl_" + UUID.randomUUID();
		ApplyResult r = apply(spec(n), "c1");
		assertEquals(Action.CREATE, action(r));
		UUID d = componentNamed(n);
		assertEquals(r.getSpecHash(), stored(d).getDeclarative().getSpecHash());
		assertEquals(0, revision(d));
		assertEquals(0, audits(d));
	}

	/** Row 6 (TPF-61): an open settings page is refused by a field-changing apply only. */
	@Test
	public void theRevisionCheckOfAnOpenSettingsPageIsUnaffectedByAProvenanceStamp() throws Exception {
		String n = name(c);
		int r = revision(c);
		assertEquals(Action.UNCHANGED, action(apply(spec(n), "c1")));
		componentService.updateComponent(ComponentDto.builder().uuid(c).repoPath("mine").expectedRevision(r).build(), WU);
		assertEquals("mine", stored().getRepoPath());

		UUID m = newMember();
		WhoUpdated applier = WhoUpdated.getWhoUpdated(ProgrammaticType.MANUAL, m, IP);
		assertEquals(Action.UPDATE, action(apply(spec(n, "theirs"), "c2", applier)));
		ComponentRevisionConflictException e = assertThrows(ComponentRevisionConflictException.class,
				() -> componentService.updateComponent(ComponentDto.builder().uuid(c).repoPath("mine again")
						.expectedRevision(r + 1).build(), WU));
		assertEquals(r + 1, e.getExpectedRevision());
		assertEquals(r + 2, e.getCurrentRevision());
		assertEquals(m, e.getLastUpdatedBy());
		assertEquals(ProgrammaticType.MANUAL, e.getLastUpdatedType());
		assertEquals("theirs", stored().getRepoPath());
	}

	/** Row 7 (the counting form of ComponentConcurrentWriteIntegrationTest row 7). */
	@Test
	@Timeout(60)
	public void aHeldStampThenASettingsSaveIsOneRevision() throws Exception {
		int r = revision(c);
		int a = audits(c);
		DeclarativeProvenance prov = provenance();
		held(() -> componentService.stampDeclarativeProvenance(c, prov, WU), () -> x("race"));
		assertEquals(prov.getSpecHash(), stored().getDeclarative().getSpecHash());
		assertEquals("race", stored().getRepoPath());
		assertEquals(r + 1, revision(c), "the settings save's revision only");
		assertEquals(a + 1, audits(c), "the settings save's audit row only");
	}

	// ---------- 5.2, branches ----------

	/** Row 8. */
	@Test
	public void theBranchStampTouchesOnlyDeclarative() throws Exception {
		UUID b = baseBranch(c);
		RowSnapshot before = branch(b);
		DeclarativeProvenance prov = provenance();
		branchService.stampDeclarativeProvenance(b, prov, WU);
		RowSnapshot after = branch(b);
		assertStampedOnly(before, after, Set.of("declarative"));
		assertEquals(prov.getSpecHash(), branchService.getBranchData(b).orElseThrow().getDeclarative().getSpecHash());
		assertEquals("number", appliedAtType(TableName.BRANCHES, b));

		DeclarativeProvenance next = new DeclarativeProvenance("next-" + UUID.randomUUID(), prov.getAppliedAt(),
				new DeclarativeProvenance.Source("r", "p", "c9"));
		branchService.stampDeclarativeProvenance(b, next, WU);
		assertStampedOnly(after, branch(b), Set.of("declarative"));
		DeclarativeProvenance read = branchService.getBranchData(b).orElseThrow().getDeclarative();
		assertEquals(next.getSpecHash(), read.getSpecHash());
		assertEquals("c9", read.getSource().getCommit());
	}

	/** Row 9: the stamp lands on top of the pending branch save; one revision; the copy holds the previous values. */
	@Test
	public void aBranchApplyWithAFieldChangeIsOneRevisionAndTheStampLandsOnTopOfThePendingSave() throws Exception {
		UUID b = baseBranch(c);
		String bn = branchService.getBranchData(b).orElseThrow().getName();
		String n = name(c);
		ApplyResult first = apply(branches(n, branchSpec(bn, "vb-1-" + UUID.randomUUID())), "c1");
		assertEquals(Action.UPDATE, action(first));
		RowSnapshot before = branch(b);

		String v2 = "vb-2-" + UUID.randomUUID();
		ApplyResult second = apply(branches(n, branchSpec(bn, v2)), "c2");
		assertEquals(Action.UPDATE, action(second));
		RowSnapshot after = branch(b);
		assertContentSave(before, after);
		BranchData live = branchService.getBranchData(b).orElseThrow();
		assertEquals(v2, live.getVcsBranch());
		assertEquals(second.getSpecHash(), live.getDeclarative().getSpecHash());
		Map<String, Object> copy = latestAuditCopy(TableName.BRANCHES, b);
		assertEquals(before.recordData().get("vcsBranch"), copy.get("vcsBranch"));
		assertEquals(before.recordData().get("declarative"), copy.get("declarative"));

		assertEquals(Action.UNCHANGED, action(apply(branches(n, branchSpec(bn, v2)), "c3")));
		assertStampedOnly(after, branch(b), Set.of());
	}

	/** Row 10: a created branch ends its apply at revision 0, or 1 when the create needs an update. */
	@Test
	public void aBranchApplyThatCreatesEndsAtRevisionZero() throws Exception {
		String n = name(c);
		String plain = "feature/decl-" + UUID.randomUUID().toString().substring(0, 8);
		BranchSpecDto bs = branchSpec(plain, null);
		bs.setType(BranchType.FEATURE);
		ApplyResult r = apply(branches(n, bs), "c1");
		assertEquals(Action.CREATE, action(r));
		UUID b = branchNamed(c, plain);
		RowSnapshot s = branch(b);
		assertEquals(0, s.revision());
		assertEquals(0, s.auditRows());
		assertEquals(r.getSpecHash(), branchService.getBranchData(b).orElseThrow().getDeclarative().getSpecHash());

		String updated = "feature/decl-" + UUID.randomUUID().toString().substring(0, 8);
		BranchSpecDto bu = branchSpec(updated, null);
		bu.setType(BranchType.FEATURE);
		bu.setAutoIntegrate(AutoIntegrateState.DISABLED);
		ApplyResult ru = apply(branches(n, bu), "c2");
		assertEquals(Action.CREATE, action(ru));
		UUID bU = branchNamed(c, updated);
		RowSnapshot su = branch(bU);
		assertEquals(1, su.revision(), "the update the create needs for autoIntegrate");
		assertEquals(1, su.auditRows(), "the update's audit row only");
		assertEquals(ru.getSpecHash(), branchService.getBranchData(bU).orElseThrow().getDeclarative().getSpecHash());
	}

	/**
	 * Row 11, a fact, not a requirement (architecture 6.1, parent 5.2): branch writers do not lock, so a
	 * branch save from a copy read before a stamp writes the previous provenance back; the next apply restamps.
	 */
	@Test
	@Timeout(60)
	public void aStaleBranchSaveWritesThePreviousProvenanceBackAndTheNextApplyRestampsIt() throws Exception {
		UUID b = baseBranch(c);
		String bn = branchService.getBranchData(b).orElseThrow().getName();
		String n = name(c);
		ApplyResult first = apply(branches(n, branchSpec(bn, null)), "c1");
		BranchesSpecDto s2 = branches(n, branchSpec(bn, null));
		s2.setVersion(2);
		String[] secondHash = new String[1];
		TransactionTemplate inner = new TransactionTemplate(transactionManager);
		inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			try {
				branchService.getBranch(b).orElseThrow();
				inner.executeWithoutResult(s3 -> {
					try {
						secondHash[0] = apply(s2, "c2").getSpecHash();
					} catch (Exception e) {
						throw unchecked(e);
					}
				});
				branchService.updateBranch(BranchDto.builder().uuid(b).vcsBranch("stale-" + UUID.randomUUID()).build(), WU);
			} catch (Exception e) {
				throw unchecked(e);
			}
		});
		assertNotNull(secondHash[0]);
		assertEquals(first.getSpecHash(), branchService.getBranchData(b).orElseThrow().getDeclarative().getSpecHash(),
				"parent 5.2 / architecture 6.1: the stale branch save wrote the previous provenance back");

		BranchesSpecDto again = branches(n, branchSpec(bn, null));
		again.setVersion(2);
		assertEquals(Action.UNCHANGED, action(apply(again, "c3")));
		assertEquals(secondHash[0], branchService.getBranchData(b).orElseThrow().getDeclarative().getSpecHash(),
				"the next apply restamps a stale provenance");
	}
}
