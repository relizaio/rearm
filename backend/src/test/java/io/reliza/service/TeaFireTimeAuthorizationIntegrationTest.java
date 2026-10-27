/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.GlobalInputEventRef;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.ComponentData.TeaGrantBasis;
import io.reliza.model.ComponentData.TeaWiringStamp;
import io.reliza.model.FlowControl.TeaActKind;
import io.reliza.model.OrganizationData.GlobalApprovalPolicyRule;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.dto.saas.ApprovalPolicyDto;
import io.reliza.model.saas.ApprovalPolicyData;
import io.reliza.model.saas.ApprovalPolicyData.RetiredGlobal;
import io.reliza.model.saas.ApprovalPolicyData.RetiredKind;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.saas.ApprovalPolicyService;
import io.reliza.service.saas.TeaWiringAuthorization;
import io.reliza.testsupport.StampAssertions;
import io.reliza.testsupport.StampAssertions.RowSnapshot;

/**
 * TEA-7, round 5, case 55 (T-12, the run 4 replays Y3 to Y6): a TEA_PUBLISH fires only from
 * wiring a holder of PUBLISH_EXTERNALLY or an admin wrote. A removed global leaves a tombstone and
 * its uuid is refused when a stale editor page or a create sends it again; and should anything
 * else give such a uuid a meaning again (a hand edit of the row, played here by SQL), the writer's
 * rule or ref that names it carries no stamp, so the publish is withheld and the ref ignored.
 * Round 6 adds step (f2) to case 55 (T-15: the hide on the false branch only) and case 61 (T-14:
 * an override is authorized by the ref and the action; the overridden rule gives its condition
 * only).
 */
public class TeaFireTimeAuthorizationIntegrationTest extends TeaWiringIntegrationTestBase {

	private static final String PUBLISH_BY_HAND = "mutation($r: ID!) { publishReleaseOnTea(release: $r) { outcome } }";
	private static final String ASSEMBLED = "release.lifecycle == \"ASSEMBLED\"";

	private List<ReleaseUpdateEvent> guard(UUID release, String newValue) {
		return events(release, ReleaseUpdateScope.GUARD).stream().filter(e -> newValue.equals(e.newValue())).toList();
	}

	private List<ReleaseUpdateEvent> notAuthorized(UUID release) {
		return guard(release, "TEA_PUBLISH withheld: NOT_AUTHORIZED");
	}

	/** The hand edit of a policy row: its record replaced by SQL, as an operator's restore would. */
	private void writeRow(ApprovalPolicyData d) {
		int n = jdbcTemplate.update("UPDATE rearm.approval_policies SET record_data = CAST(? AS jsonb) WHERE uuid = ?",
				Utils.OM.writeValueAsString(Utils.dataToRecord(d)), d.getUuid());
		assertEquals(1, n);
	}

	private static void assertRefusedWith(ExecutionResult r, String what, String message) {
		assertFalse(r.getErrors().isEmpty(), what + " was saved");
		assertTrue(r.getErrors().get(0).getMessage().contains(message), what + ": " + r.getErrors().get(0).getMessage());
	}

	/** The hand edit of a component row's refs, as an operator's restore or a planted row would write them. */
	private void plantRefs(UUID component, GlobalInputEventRef... refs) {
		int n = jdbcTemplate.update("UPDATE rearm.components SET record_data = jsonb_set(record_data, "
				+ "'{globalInputEventRefs}', CAST(? AS jsonb)) WHERE uuid = ?", Utils.OM.writeValueAsString(List.of(refs)),
				component);
		assertEquals(1, n);
	}

	/** An unstamped ref, as a writer's planted row carries it. */
	private static GlobalInputEventRef unstampedRef(UUID rule, boolean disabled, UUID... override) {
		GlobalInputEventRef ref = new GlobalInputEventRef();
		ref.setUuid(rule);
		ref.setDisabled(disabled);
		ref.setOverrideOutputEventsLocally(override.length > 0);
		ref.setOutputEventsOverride(override.length > 0 ? Set.of(override) : null);
		return ref;
	}

	/** The policy editor's actions save, with each mail action's (empty) recipient list, so the mail fires. */
	private ExecutionResult setOutputsWithRecipients(UserData admin, UUID policy, ReleaseOutputEvent... events) {
		return asUser(admin, SET_OUTPUTS, vars("p", policy.toString(), "e", List.of(events).stream().map(oe -> {
			Map<String, Object> m = action(oe);
			if (null != oe.getUsers()) m.put("users", oe.getUsers().stream().map(UUID::toString).toList());
			return m;
		}).toList()));
	}

	private static String version() {
		return "1.9." + Math.abs(UUID.randomUUID().hashCode() % 100000);
	}

	private static String retired(ApprovalPolicyData d, UUID uuid) {
		return d.getRetiredGlobals().stream().filter(t -> uuid.equals(t.uuid())).findFirst().orElseThrow().retiredAt();
	}

	@Test
	void aPublishFiresOnlyFromWiringAHolderWrote() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID e = approvalEntry();
		UserData admin = admin();

		ReleaseOutputEvent gpD = teaAction(EventType.TEA_PUBLISH, "gpD");
		ReleaseInputEvent rtD = rule("rtD", approved(e), gpD);
		UUID tpd = policyWith("tea-7-55-tpd", List.of(gpD), List.of(rtD), e);
		ReleaseOutputEvent mE = mail("mE");
		ReleaseInputEvent rmE = rule("rmE", approved(e), mE);
		UUID tpe = policyWith("tea-7-55-tpe", List.of(mE), List.of(rmE), e);
		ReleaseOutputEvent ghF = teaAction(EventType.TEA_HIDE, "ghF");
		ReleaseInputEvent rhF = rule("rhF", "release.lifecycle == \"READY_TO_SHIP\"", ghF);
		UUID hpf = policyWith("tea-7-55-hpf", List.of(ghF), List.of(rhF), e);
		ReleaseOutputEvent ghF2 = teaAction(EventType.TEA_HIDE, "ghF2");
		ReleaseInputEvent rfalseF = rule("rfalseF", ASSEMBLED);
		rfalseF.setOutputEventsOnFalse(Set.of(ghF2.getUuid()));
		UUID hpf2 = policyWith("tea-7-55-hpf2", List.of(ghF2), List.of(rfalseF), e);

		World w = componentRelease("pkg:generic/c55w@1.0.0");
		World w2 = componentRelease("pkg:generic/c55w2@1.0.0");
		World w3 = componentRelease("pkg:generic/c55w3@1.0.0");
		World w4 = componentRelease("pkg:generic/c55w4@1.0.0");
		UserData writer = writer(w.component());
		UserData writer2 = writer(w2.component());
		UserData writer3 = writer(w3.component());

		List<GlobalApprovalPolicyRule> previous = orgRules();
		try {
			setOrgRules(previous, orgRule("tea-7-55-w", "^" + nameOf(w.component()) + "$", tpd),
					orgRule("tea-7-55-w2", "^" + nameOf(w2.component()) + "$", tpe),
					orgRule("tea-7-55-w3", "^" + nameOf(w3.component()) + "$", hpf),
					orgRule("tea-7-55-w4", "^" + nameOf(w4.component()) + "$", hpf2));

			// (a) a writer's rule naming the live gpD is refused (rule D); the admin removes rtD, then gpD
			ReleaseInputEvent planted = rule("w-planted", ASSEMBLED, gpD);
			assertRefused(update(writer, input(w.component(), "releaseInputTriggers", List.of(rule(planted)))),
					"(a) a rule naming the live gpD");
			ApprovalPolicyData tpdLive = policyData(tpd);
			assertTrue(TeaWiringAuthorization.valid(tpdLive.getGlobalOutputEvents().get(0), tpd), "the policy stamps gpD");
			assertSaved(setInputs(admin, tpd), "(a) rtD removed");
			assertSaved(setOutputs(admin, tpd), "(a) gpD removed");
			ApprovalPolicyData tpdEmpty = policyData(tpd);
			assertEquals(List.of(rtD.getUuid(), gpD.getUuid()),
					tpdEmpty.getRetiredGlobals().stream().map(RetiredGlobal::uuid).toList(), "(a) two tombstones");
			assertEquals(List.of(RetiredKind.RULE, RetiredKind.ACTION),
					tpdEmpty.getRetiredGlobals().stream().map(RetiredGlobal::kind).toList());
			assertTrue(tpdEmpty.getRetiredGlobals().stream().allMatch(RetiredGlobal::tea));
			assertTrue(tpdEmpty.getRetiredGlobals().stream().allMatch(t -> admin.getUuid().equals(t.retiredBy())));
			assertSaved(update(writer, input(w.component(), "releaseInputTriggers", List.of(rule(planted)))),
					"(a) the same rule once gpD is dead");
			assertNull(storedComponent(w.component()).getReleaseInputTriggers().get(0).getTeaAuth(),
					"(a) the writer's rule carries no stamp");

			// (b) the admin's stale editor page re-sends gpD and rtD under their uuids
			assertRefusedWith(setOutputs(admin, tpd, gpD), "(b) gpD re-sent", String.format(ApprovalPolicyService.UUID_RETIRED,
					"Global output event", "gpD", gpD.getUuid(), retired(tpdEmpty, gpD.getUuid()), "action"));
			assertRefusedWith(setInputs(admin, tpd, rtD), "(b) rtD re-sent", String.format(ApprovalPolicyService.UUID_RETIRED,
					"Global rule", "rtD", rtD.getUuid(), retired(tpdEmpty, rtD.getUuid()), "rule"));
			assertTrue(policyData(tpd).getGlobalOutputEvents().isEmpty() && policyData(tpd).getGlobalInputEvents().isEmpty(),
					"(b) the stored policy is unchanged");

			// (c) a hand edit writes gpD back, stamp and all: the writer's rule is what is missing
			ApprovalPolicyData handEdit = policyData(tpd);
			handEdit.setGlobalOutputEvents(new ArrayList<>(tpdLive.getGlobalOutputEvents()));
			writeRow(handEdit);
			UUID r1 = draftRelease(w, "1.9." + Math.abs(UUID.randomUUID().hashCode() % 100000));
			RowSnapshot beforeC = StampAssertions.snapshot(jdbcTemplate, TableName.RELEASES, r1);
			List<Map<String, Object>> eventsBeforeC = StampAssertions.updateEvents(jdbcTemplate, r1);
			ossReleaseService.updateReleaseLifecycle(r1, ReleaseLifecycle.ASSEMBLED, WU);
			// TPF-106 row 10: the promotion is the pass's one content save and the withheld publish is noted in
			// place after it: one revision and one audit row, not two, and the note is the last event
			RowSnapshot afterC = StampAssertions.snapshot(jdbcTemplate, TableName.RELEASES, r1);
			List<Map<String, Object>> eventsAfterC = StampAssertions.updateEvents(jdbcTemplate, r1);
			StampAssertions.assertContentSave(beforeC, afterC);
			StampAssertions.assertPrefixKept(eventsBeforeC, eventsAfterC);
			Map<String, Object> lastC = eventsAfterC.get(eventsAfterC.size() - 1);
			assertEquals("GUARD", lastC.get("rus"));
			assertEquals("TEA_PUBLISH withheld: NOT_AUTHORIZED", lastC.get("newValue"));
			assertEquals(gpD.getUuid().toString(), lastC.get("objectId"));
			sbomPass(r1);
			assertFalse(queued(r1), "(c) no request");
			assertTrue(triggerRows(r1, gpD.getUuid()).isEmpty(), "(c) no TRIGGER row for gpD");
			assertEquals(1, notAuthorized(r1).size());
			assertEquals(gpD.getUuid(), notAuthorized(r1).get(0).objectId());
			assertEquals("Action 'gpD': withheld because rule 'w-planted' of this component carries no valid TEA "
					+ "authorization; a holder of the Publish Externally (TEA) permission or an organization admin must "
					+ "edit and save it on this component", notAuthorized(r1).get(0).message());
			sbomPass(r1);
			assertEquals(1, notAuthorized(r1).size(), "(c) a second pass adds no line");

			// (d) the admin edits the rule and saves: stamped, and the next pass publishes
			ReleaseInputEvent edited = copyOf(planted);
			edited.setName("w-planted-reviewed");
			assertSaved(update(admin, input(w.component(), "releaseInputTriggers", List.of(rule(edited)))), "(d) admin");
			TeaWiringStamp stamp = storedComponent(w.component()).getReleaseInputTriggers().get(0).getTeaAuth();
			assertEquals(TeaGrantBasis.ORG_ADMIN, stamp.basis());
			assertEquals(admin.getUuid(), stamp.by());
			sbomPass(r1);
			assertEquals(TeaActKind.PUBLISH, teaAct(r1).teaActKind(), "(d) the reviewed rule requests the publish");
			assertEquals("Action 'gpD' fired by rule 'w-planted-reviewed'", teaAct(r1).teaActComment());
			ReleaseInputEvent writersEdit = copyOf(edited);
			writersEdit.setCelExpression("true");
			assertRefused(update(writer, input(w.component(), "releaseInputTriggers", List.of(rule(writersEdit)))),
					"(d) the writer's edit of a rule naming a live TEA global");

			// (e) Y4: a writer's mail rule whose action comes back as a TEA_PUBLISH under its uuid
			ReleaseInputEvent wMail = rule("w-mail", ASSEMBLED, mE);
			assertSaved(update(writer2, input(w2.component(), "releaseInputTriggers", List.of(rule(wMail)))),
					"(e) a rule naming a mail action");
			assertSaved(setInputs(admin, tpe), "(e) rmE removed");
			assertSaved(setOutputs(admin, tpe), "(e) mE removed");
			ApprovalPolicyData retyped = policyData(tpe);
			ReleaseOutputEvent mEasTea = ReleaseOutputEvent.builder().uuid(mE.getUuid()).name("mE")
					.type(EventType.TEA_PUBLISH).build();
			// the hand that edits the row can compute the action's stamp (round 5, section 5, residual 2)
			mEasTea.setTeaAuth(new TeaWiringStamp(TeaWiringAuthorization.hashOf(mEasTea, tpe), null,
					TeaGrantBasis.ORG_ADMIN, TeaWiringAuthorization.now()));
			retyped.setGlobalOutputEvents(new ArrayList<>(List.of(mEasTea)));
			writeRow(retyped);
			UUID r2 = anotherRelease(w2, "1.9." + Math.abs(UUID.randomUUID().hashCode() % 100000));
			sbomPass(r2);
			assertFalse(queued(r2), "(e) no request");
			assertEquals(1, notAuthorized(r2).size());
			assertTrue(notAuthorized(r2).get(0).message().contains("rule 'w-mail' of this component"),
					notAuthorized(r2).get(0).message());

			// (f) Y5: a writer's ref disabling a hide rule that is dead when it is saved
			assertRefused(update(writer3, input(w3.component(), "globalInputEventRefs", List.of(ref(rhF.getUuid(), true)))),
					"(f) a ref on the live rhF");
			ApprovalPolicyData hpfLive = policyData(hpf);
			assertSaved(setInputs(admin, hpf), "(f) rhF removed");
			assertSaved(update(writer3, input(w3.component(), "globalInputEventRefs", List.of(ref(rhF.getUuid(), true)))),
					"(f) the same ref once rhF is dead");
			assertNull(storedComponent(w3.component()).getGlobalInputEventRefs().get(0).getTeaAuth());
			data(asUser(admin, PUBLISH_BY_HAND, vars("r", w3.release().toString())), "publishReleaseOnTea");
			assertEquals(TeaPublicationState.PUBLISHED, head(w3.release()).orElseThrow().getState());
			ApprovalPolicyData hpfBack = policyData(hpf);
			hpfBack.setGlobalInputEvents(new ArrayList<>(hpfLive.getGlobalInputEvents()));
			writeRow(hpfBack);
			RowSnapshot beforeF = StampAssertions.snapshot(jdbcTemplate, TableName.RELEASES, w3.release());
			List<Map<String, Object>> eventsBeforeF = StampAssertions.updateEvents(jdbcTemplate, w3.release());
			ossReleaseService.updateReleaseLifecycle(w3.release(), ReleaseLifecycle.READY_TO_SHIP, WU);
			// TPF-106 row 10: the lifecycle change is the pass's one content save; the hide's TRIGGER record and
			// the ignored-ref note are appended in place after it, the note last (recordTeaGuardNotes runs after the loop)
			RowSnapshot afterF = StampAssertions.snapshot(jdbcTemplate, TableName.RELEASES, w3.release());
			List<Map<String, Object>> eventsAfterF = StampAssertions.updateEvents(jdbcTemplate, w3.release());
			StampAssertions.assertContentSave(beforeF, afterF);
			StampAssertions.assertPrefixKept(eventsBeforeF, eventsAfterF);
			Map<String, Object> lastF = eventsAfterF.get(eventsAfterF.size() - 1);
			assertEquals("GUARD", lastF.get("rus"));
			assertEquals("TEA_REF ignored: NOT_AUTHORIZED", lastF.get("newValue"));
			assertEquals(rhF.getUuid().toString(), lastF.get("objectId"));
			assertEquals(TeaActKind.HIDE, teaAct(w3.release()).teaActKind(), "(f) the hide is requested");
			assertEquals(1, triggerRows(w3.release(), ghF.getUuid()).size());
			List<ReleaseUpdateEvent> ignored = guard(w3.release(), "TEA_REF ignored: NOT_AUTHORIZED");
			assertEquals(1, ignored.size());
			assertEquals(rhF.getUuid(), ignored.get(0).objectId());
			assertTrue(ignored.get(0).message().startsWith("Ref on global rule 'rhF' of approval policy '"
					+ hpfLive.getPolicyName() + "': ignored"), ignored.get(0).message());
			teaOutputEventService.runRequested(w3.release());
			assertEquals(TeaPublicationState.HIDDEN, head(w3.release()).orElseThrow().getState(), "(f) HIDDEN");

			// (f2) round 6, T-15, the run 5 V6g replay: the hide on the false branch only. Its own
			// policy, so that HIDDEN is the hide of rfalseF alone; the writer's unstamped disabling
			// ref is planted by SQL.
			assertTrue(TeaWiringAuthorization.valid(policyData(hpf2).getGlobalInputEvents().get(0), hpf2),
					"(f2) the policy stamps a rule whose TEA_HIDE is on the false branch");
			plantRefs(w4.component(), unstampedRef(rfalseF.getUuid(), true));
			data(asUser(admin, PUBLISH_BY_HAND, vars("r", w4.release().toString())), "publishReleaseOnTea");
			assertEquals(TeaPublicationState.PUBLISHED, head(w4.release()).orElseThrow().getState());
			ossReleaseService.updateReleaseLifecycle(w4.release(), ReleaseLifecycle.READY_TO_SHIP, WU);
			assertEquals(TeaActKind.HIDE, teaAct(w4.release()).teaActKind(), "(f2) the false-branch hide is requested");
			assertEquals(1, triggerRows(w4.release(), ghF2.getUuid()).size());
			List<ReleaseUpdateEvent> ignoredF2 = guard(w4.release(), "TEA_REF ignored: NOT_AUTHORIZED");
			assertEquals(1, ignoredF2.size());
			assertEquals(rfalseF.getUuid(), ignoredF2.get(0).objectId());
			assertEquals("rfalseF", ignoredF2.get(0).oldValue());
			teaOutputEventService.runRequested(w4.release());
			assertEquals(TeaPublicationState.HIDDEN, head(w4.release()).orElseThrow().getState(), "(f2) HIDDEN");

			// (g) Y6: a create carrying an archived policy's global uuid
			ReleaseOutputEvent gpG = teaAction(EventType.TEA_PUBLISH, "gpG");
			UUID tpg = policyWith("tea-7-55-tpg", List.of(gpG), List.of(), e);
			approvalPolicyService.archiveApprovalPolicy(tpg, WU);
			ReleaseOutputEvent again = ReleaseOutputEvent.builder().uuid(gpG.getUuid()).name("gpG")
					.type(EventType.TEA_PUBLISH).build();
			RelizaException create = assertThrows(RelizaException.class, () -> approvalPolicyService.createApprovalPolicy(
					ApprovalPolicyDto.builder().org(org.getUuid()).policyName("tea-7-55-again-" + UUID.randomUUID())
							.approvalEntries(List.of(e)).globalOutputEvents(new ArrayList<>(List.of(again))).build(), WU));
			assertEquals(String.format(ApprovalPolicyService.UUID_IN_USE, "Global output event", "gpG", gpG.getUuid(),
					"action", "gpG", policyData(tpg).getPolicyName()), create.getMessage());
		} finally {
			orgApprovalPolicyService.setRules(org.getUuid(), previous, WU);
		}
	}

	/**
	 * Case 61 (round 6, T-14, the run 5 V7 replay): a ref overriding a global rule's outputs to a
	 * TEA_PUBLISH is authorized by the ref and the action; the overridden rule gives its condition
	 * only and is never asked for a stamp on the branch its override replaced. A global rule that
	 * names the component's publish by its own outputs and fires no TEA action of its policy is
	 * withheld with the cure that exists (path 3), until a ref of the component names it.
	 */
	@Test
	void anOverrideIsAuthorizedByTheRefAndTheAction() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID e = approvalEntry();
		UserData admin = admin();

		ReleaseOutputEvent gpubA = teaAction(EventType.TEA_PUBLISH, "gpubA");
		ReleaseOutputEvent mA = mail("mA");
		ReleaseInputEvent rmailA = rule("rmailA", ASSEMBLED, mA);
		UUID prA = policyWith("tea-7-61-prA", List.of(), List.of(), e);
		assertSaved(setOutputsWithRecipients(admin, prA, gpubA, mA), "the policy's actions");
		assertSaved(setInputs(admin, prA, rmailA), "the policy's rules");
		ApprovalPolicyData saved = policyData(prA);
		String policyName = saved.getPolicyName();
		assertTrue(TeaWiringAuthorization.valid(saved.getGlobalOutputEvents().get(0), prA), "gpubA carries a stamp");
		assertNull(saved.getGlobalInputEvents().get(0).getTeaAuth(), "rmailA carries none");

		World w = componentRelease("pkg:generic/c61@1.0.0");
		UUID c = w.component();
		ReleaseOutputEvent lpA = teaAction(EventType.TEA_PUBLISH, "lpA");
		List<GlobalApprovalPolicyRule> previous = orgRules();
		try {
			setOrgRules(previous, orgRule("tea-7-61", "^" + nameOf(c) + "$", prA));
			assertSaved(update(admin, input(c, "outputTriggers", List.of(action(lpA)))), "lpA");
			assertTrue(TeaWiringAuthorization.valid(storedComponent(c).getOutputTriggers().get(0), c), "lpA stamped");

			// (a) the admin's ref overriding the mail rule to the global publish (V7a)
			assertSaved(update(admin, input(c, "globalInputEventRefs", List.of(ref(rmailA.getUuid(), false,
					gpubA.getUuid())))), "(a) the admin's override");
			assertEquals(TeaGrantBasis.ORG_ADMIN, storedComponent(c).getGlobalInputEventRefs().get(0).getTeaAuth().basis());
			UUID ra = anotherRelease(w, version());
			sbomPass(ra);
			assertEquals(TeaActKind.PUBLISH, teaAct(ra).teaActKind(), "(a) the publish is requested");
			assertEquals("Action 'gpubA' fired by rule 'rmailA'", teaAct(ra).teaActComment());
			assertFalse(triggerRows(ra, gpubA.getUuid()).isEmpty(), "(a) a TRIGGER row for gpubA");
			assertTrue(notAuthorized(ra).isEmpty(), "(a) no GUARD line");

			// (b) the same ref saved by a holder of PUBLISH_EXTERNALLY at WRITE on the component (V7b)
			assertSaved(update(admin, input(c, "globalInputEventRefs", List.of())), "(b) cleared");
			UserData holder = writer(c, PermissionFunction.PUBLISH_EXTERNALLY);
			assertSaved(update(holder, input(c, "globalInputEventRefs", List.of(ref(rmailA.getUuid(), false,
					gpubA.getUuid())))), "(b) the holder's override");
			assertEquals(TeaGrantBasis.PUBLISH_EXTERNALLY,
					storedComponent(c).getGlobalInputEventRefs().get(0).getTeaAuth().basis());
			UUID rb = anotherRelease(w, version());
			assertEquals(TeaActKind.PUBLISH, teaAct(rb).teaActKind(), "(b) the publish is requested");
			assertEquals("Action 'gpubA' fired by rule 'rmailA'", teaAct(rb).teaActComment());
			assertTrue(notAuthorized(rb).isEmpty());

			// (c) a writer with COMPONENT READ_WRITE only is refused at the save (run 4 Y1e)
			assertSaved(update(admin, input(c, "globalInputEventRefs", List.of())), "(c) cleared");
			assertRefused(update(writer(c), input(c, "globalInputEventRefs", List.of(ref(rmailA.getUuid(), false,
					gpubA.getUuid())))), "(c) the writer's override");
			// and the same ref planted by SQL is ignored: the mail fires, nothing publishes
			plantRefs(c, unstampedRef(rmailA.getUuid(), false, gpubA.getUuid()));
			UUID rc = anotherRelease(w, version());
			assertFalse(queued(rc), "(c) no request");
			assertFalse(triggerRows(rc, mA.getUuid()).isEmpty(), "(c) the rule runs as the policy says");
			assertEquals(1, guard(rc, "TEA_REF ignored: NOT_AUTHORIZED").size());
			assertEquals(rmailA.getUuid(), guard(rc, "TEA_REF ignored: NOT_AUTHORIZED").get(0).objectId());
			assertTrue(notAuthorized(rc).isEmpty());

			// (d) the admin's ref overriding the mail rule to the component's own publish (V7c)
			assertSaved(update(admin, input(c, "globalInputEventRefs", List.of(ref(rmailA.getUuid(), false,
					lpA.getUuid())))), "(d) the admin's override to lpA");
			UUID rd = anotherRelease(w, version());
			assertEquals(TeaActKind.PUBLISH, teaAct(rd).teaActKind(), "(d) the publish is requested");
			assertEquals("Action 'lpA' fired by rule 'rmailA'", teaAct(rd).teaActComment());
			assertTrue(notAuthorized(rd).isEmpty());

			// (e) the admin's ref overriding a TEA-firing rule to another publish (V7d)
			ReleaseOutputEvent gpubB = teaAction(EventType.TEA_PUBLISH, "gpubB");
			ReleaseInputEvent rB = rule("rB", ASSEMBLED, gpubB);
			assertSaved(setOutputsWithRecipients(admin, prA, gpubA, mA, gpubB), "(e) gpubB added");
			assertSaved(setInputs(admin, prA, rmailA, rB), "(e) rB added");
			assertSaved(update(admin, input(c, "globalInputEventRefs", List.of(ref(rB.getUuid(), false,
					gpubA.getUuid())))), "(e) the admin's override of rB");
			UUID re = anotherRelease(w, version());
			assertEquals(TeaActKind.PUBLISH, teaAct(re).teaActKind(), "(e) the publish is requested");
			assertEquals("Action 'gpubA' fired by rule 'rB'", teaAct(re).teaActComment());
			assertTrue(triggerRows(re, gpubB.getUuid()).isEmpty(), "(e) the override replaced rB's own outputs");
			assertTrue(notAuthorized(re).isEmpty());

			// (f) path 3: a global rule naming lpA by its own outputs, no TEA action of its policy
			ReleaseInputEvent rlocal = rule("rlocal", ASSEMBLED, lpA);
			assertSaved(setInputs(admin, prA, rmailA, rlocal), "(f) rlocal added, rB removed");
			ReleaseInputEvent storedRlocal = policyData(prA).getGlobalInputEvents().stream()
					.filter(r -> rlocal.getUuid().equals(r.getUuid())).findFirst().orElseThrow();
			assertNull(storedRlocal.getTeaAuth(), "(f) the stored rule carries no stamp");
			assertSaved(update(admin, input(c, "globalInputEventRefs", List.of())), "(f) refs cleared");
			UUID rf = anotherRelease(w, version());
			assertFalse(queued(rf), "(f) no request");
			assertTrue(triggerRows(rf, lpA.getUuid()).isEmpty(), "(f) no TRIGGER row for lpA");
			assertEquals(1, notAuthorized(rf).size());
			assertEquals(lpA.getUuid(), notAuthorized(rf).get(0).objectId());
			assertEquals("Action 'lpA': withheld because global rule 'rlocal' of approval policy '" + policyName
					+ "' names it by the rule's own outputs and fires no TEA action of that policy, which no save can "
					+ "authorize; a holder of the Publish Externally (TEA) permission or an organization admin must fire "
					+ "it from a rule of this component or from a ref of this component overriding that rule's outputs",
					notAuthorized(rf).get(0).message());
			sbomPass(rf);
			assertEquals(1, notAuthorized(rf).size(), "(f) a second pass adds no line");
			assertSaved(update(admin, input(c, "globalInputEventRefs", List.of(ref(rlocal.getUuid(), false,
					lpA.getUuid())))), "(f) the named cure: a ref of this component");
			sbomPass(rf);
			assertEquals(TeaActKind.PUBLISH, teaAct(rf).teaActKind(), "(f) the publish is requested");
			assertEquals("Action 'lpA' fired by rule 'rlocal'", teaAct(rf).teaActComment());
		} finally {
			orgApprovalPolicyService.setRules(org.getUuid(), previous, WU);
		}
	}
}
