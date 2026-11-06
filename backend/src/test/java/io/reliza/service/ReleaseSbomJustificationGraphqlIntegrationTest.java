/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import graphql.ExecutionResult;
import io.reliza.common.Utils;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.model.JustifiedUnknownReason;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.SupportBulkSelector;
import io.reliza.model.SupportBulkSelectorForm;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.ReleaseSbomJustificationRepository;
import io.reliza.repositories.ReleaseSbomRepository;
import io.reliza.service.ReleaseSbomJustificationService.Overwrite;
import tools.jackson.databind.JsonNode;

/**
 * FDAW-14 rows 4, 5, 6 and 10: hash justifications per release SBOM head through the user schema --
 * the COMPONENT WRITE gate and its one not-found text, the document rules, the row lock, the selector
 * sweep with its receipt, and the publication seam writing the head's codes.
 */
class ReleaseSbomJustificationGraphqlIntegrationTest extends ReleaseSbomJustificationTestBase {

	@MockitoSpyBean ReleaseSbomJustificationRepository justificationRepository;
	@MockitoSpyBean ReleaseSbomRepository headRepository;
	@Autowired SupportInjectionService injection;

	static final String SET = "mutation($h: ID!, $k: [String!]!, $c: JustifiedUnknownReason!, $t: String, $r: String) {"
			+ " setReleaseSbomHashJustification(releaseSbom: $h, keys: $k, code: $c, text: $t, reason: $r) {"
			+ " releaseSbom revision skippedKeys hashes { key code text bulkAct assertedBy assertedByName assertedAt } } }";
	static final String CLEAR = "mutation($h: ID!, $k: [String!]!, $r: String!) {"
			+ " clearReleaseSbomHashJustification(releaseSbom: $h, keys: $k, reason: $r) { revision hashes { key } } }";
	static final String SWEEP = "mutation($h: ID!, $s: SupportBulkSelectorInput!, $c: JustifiedUnknownReason!,"
			+ " $t: String, $r: String) { bulkSetReleaseSbomHashJustificationBySelector(releaseSbom: $h, selector: $s,"
			+ " code: $c, text: $t, reason: $r) { revision skippedKeys hashes { key code text bulkAct } } }";
	static final String HEADS = "query($r: ID!) { releaseSboms(releaseUuid: $r) { uuid profileName latestVersion"
			+ " hashlessCount } }";
	static final String HASHLESS = "query($h: ID!) { releaseSbomHashlessComponents(releaseSbom: $h) { key name"
			+ " version justification { field code text } } }";
	static final String CURRENT = "query($h: ID!) { releaseSbomJustifications(releaseSbom: $h) { revision"
			+ " hashes { key code } } }";
	static final String COUNT = "query($h: ID!, $s: SupportBulkSelectorInput!) {"
			+ " releaseSbomHashlessSelectorCount(releaseSbom: $h, selector: $s) }";

	static final String DEB_A = "pkg:deb/debian/a@1";
	static final String DEB_B = "pkg:deb/debian/b@1";
	static final String DEB_C = "pkg:deb/debian/c@1";
	static final String DEB_HASHED = "pkg:deb/debian/hashed@1";
	static final String NPM_ACME = "pkg:npm/%40acme/x@1";
	static final String NPM_PLAIN = "pkg:npm/left-pad@1";

	private record Fix(World world, UUID head, UserData writer, UserData reader) {}

	private Fix fixture() throws Exception {
		World w = componentRelease(null);
		UUID head = headOf(w.release(), PROFILE, List.of(hashless(DEB_A), hashless(DEB_B), hashless(DEB_C),
				hashed(DEB_HASHED), hashed("pkg:deb/debian/hashed2@1"), hashless(NPM_ACME), hashless(NPM_PLAIN)));
		UserData writer = userOf(org, PermissionScope.COMPONENT, w.component(), PermissionType.READ_WRITE);
		UserData reader = userOf(org, PermissionScope.COMPONENT, w.component(), PermissionType.READ_ONLY);
		return new Fix(w, head, writer, reader);
	}

	private static Map<String, Object> setVars(UUID head, List<String> keys, String code, String text, String reason) {
		Map<String, Object> v = vars("h", head.toString(), "k", keys, "c", code);
		if (null != text) v.put("t", text);
		if (null != reason) v.put("r", reason);
		return v;
	}

	private static Map<String, Object> selector(String form, String type, String namespace) {
		Map<String, Object> s = vars("form", form, "type", type);
		if (null != namespace) s.put("namespace", namespace);
		return s;
	}

	/** The refusal; a non-null mutation result adds the null-bubbling error after it. */
	private static String error(ExecutionResult r) {
		assertFalse(r.getErrors().isEmpty(), "expected a refusal");
		return r.getErrors().get(0).getMessage();
	}

	// ------------------------------------------------------------------ row 5: the gate

	@Test
	void theWriteNeedsComponentWriteAndEveryOtherCallerGetsOneText() throws Exception {
		Fix f = fixture();
		Organization other = testInitializer.obtainOrganization();
		World foreign = componentReleaseIn(other, null);
		UUID foreignHead = headOf(foreign.release(), PROFILE, List.of(hashless(DEB_A)));
		UserData otherWriter = userOf(other, PermissionScope.ORGANIZATION, other.getUuid(), PermissionType.READ_WRITE);
		String key = keyOf(DEB_A);

		for (var attempt : List.of(
				new Object[] {f.writer(), foreignHead}, new Object[] {f.writer(), UUID.randomUUID()},
				new Object[] {f.reader(), f.head()}, new Object[] {otherWriter, f.head()})) {
			ExecutionResult r = asUser((UserData) attempt[0], SET, setVars((UUID) attempt[1], List.of(key),
					"DISTRIBUTION_PUBLISHES_NO_HASH", null, null));
			assertEquals("release SBOM not found", error(r));
		}
		assertEquals(0, count("release_sbom_justification_audit", "release_sbom = ?", f.head()));

		Map<String, Object> ok = data(asUser(f.writer(), SET, setVars(f.head(), List.of(key),
				"DISTRIBUTION_PUBLISHES_NO_HASH", null, "distro policy")), "setReleaseSbomHashJustification");
		assertEquals(1, ok.get("revision"));
		@SuppressWarnings("unchecked")
		Map<String, Object> h = ((List<Map<String, Object>>) ok.get("hashes")).get(0);
		assertEquals(key, h.get("key"));
		assertEquals(f.writer().getUuid().toString(), h.get("assertedBy"));
		assertEquals(f.writer().getName(), h.get("assertedByName"));

		// reads: the component READ member answers, the other organisation's caller does not
		Map<String, Object> current = data(asUser(f.reader(), CURRENT, vars("h", f.head().toString())),
				"releaseSbomJustifications");
		assertEquals(1, current.get("revision"));
		List<Map<String, Object>> hashless = data(asUser(f.reader(), HASHLESS, vars("h", f.head().toString())),
				"releaseSbomHashlessComponents");
		assertEquals(5, hashless.size());
		assertEquals("HASH", hashless.stream().filter(n -> key.equals(n.get("key"))).findFirst().orElseThrow()
				.get("justification") instanceof Map<?, ?> j ? j.get("field") : null);
		assertEquals("release SBOM not found", error(asUser(otherWriter, CURRENT, vars("h", f.head().toString()))));
		assertEquals("release SBOM not found", error(asUser(otherWriter, HASHLESS, vars("h", f.head().toString()))));

		// heads: only this release's, and a release the caller cannot read is refused
		headOf(componentRelease(null).release(), PROFILE, List.of(hashless(DEB_A)));
		List<Map<String, Object>> heads = data(asUser(f.reader(), HEADS, vars("r", f.world().release().toString())),
				"releaseSboms");
		assertEquals(1, heads.size());
		assertEquals(f.head().toString(), heads.get(0).get("uuid"));
		assertEquals(5, heads.get(0).get("hashlessCount"));
		assertEquals(1, heads.get(0).get("latestVersion"));
		assertFalse(asUser(otherWriter, HEADS, vars("r", f.world().release().toString())).getErrors().isEmpty());
	}

	// ------------------------------------------------------------------ row 6: the document rules and the lock

	@Test
	void theDocumentDecidesWhichKeysCanBeJustifiedAndEveryWriteIsAudited() throws Exception {
		Fix f = fixture();
		String hashedKey = keyOf(DEB_HASHED);
		assertEquals("component " + hashedKey + " has a hash in release SBOM version 1 and cannot be justified",
				error(asUser(f.writer(), SET, setVars(f.head(), List.of(hashedKey), "OTHER", "x", null))));
		String absent = keyOf("pkg:deb/debian/absent@1");
		assertEquals("component " + absent + " is not in release SBOM version 1",
				error(asUser(f.writer(), SET, setVars(f.head(), List.of(absent), "OTHER", "x", null))));
		String cpe = "cpe:2.3:a:vendor:product:1.0:*:*:*:*:*:*:*";
		assertEquals("component " + cpe + " is not in release SBOM version 1",
				error(asUser(f.writer(), SET, setVars(f.head(), List.of(cpe), "OTHER", "x", null))));
		assertEquals("justified unknown: the code OTHER needs a text",
				error(asUser(f.writer(), SET, setVars(f.head(), List.of(keyOf(DEB_A)), "OTHER", null, null))));

		reset(justificationRepository, headRepository);
		for (Map<String, Object> v : List.of(
				setVars(f.head(), List.of(), "OTHER", "x", null),
				setVars(f.head(), List.of("k".repeat(2049)), "OTHER", "x", null),
				setVars(f.head(), List.of("pkg:deb/a\u0001@1"), "OTHER", "x", null),
				setVars(f.head(), List.of(keyOf(DEB_A)), "OTHER", "x", "r".repeat(2049)),
				setVars(f.head(), List.of(keyOf(DEB_A)), "OTHER", "t".repeat(513), null))) {
			assertFalse(asUser(f.writer(), SET, v).getErrors().isEmpty(), v.toString());
		}
		verifyNoInteractions(justificationRepository, headRepository);
		// a 2048-character key passes the bound and is refused by the document, after the head
		String longKey = "pkg:deb/debian/" + "l".repeat(2048 - "pkg:deb/debian/".length());
		assertEquals("component " + longKey + " is not in release SBOM version 1",
				error(asUser(f.writer(), SET, setVars(f.head(), List.of(longKey), "OTHER", "x", null))));
		assertEquals(0, count("release_sbom_justification_audit", "release_sbom = ?", f.head()));

		data(asUser(f.writer(), SET, setVars(f.head(), List.of(keyOf(DEB_A)), "DISTRIBUTION_PUBLISHES_NO_HASH", null,
				"policy")), "setReleaseSbomHashJustification");
		Map<String, Object> audit = jdbc.queryForMap("SELECT asserted_by, reason, bulk_act, revision FROM "
				+ "rearm.release_sbom_justification_audit WHERE release_sbom = ?", f.head());
		assertEquals(f.writer().getUuid(), audit.get("asserted_by"));
		assertEquals("policy", audit.get("reason"));
		assertNull(audit.get("bulk_act"));
		Map<String, Object> second = data(asUser(f.writer(), SET, setVars(f.head(), List.of(keyOf(DEB_B)),
				"VENDORED_SOURCE_NO_HASH", null, null)), "setReleaseSbomHashJustification");
		assertEquals(2, second.get("revision"));
		assertEquals(2, ((List<?>) second.get("hashes")).size(), "a second set keeps the first");

		assertFalse(asUser(f.writer(), CLEAR.replace("$r: String!", "$r: String"), vars("h", f.head().toString(),
				"k", List.of(keyOf(DEB_A)))).getErrors().isEmpty(), "clear without a reason");
		Map<String, Object> cleared = data(asUser(f.writer(), CLEAR, vars("h", f.head().toString(), "k",
				List.of(keyOf(DEB_A)), "r", "upstream added hashes")), "clearReleaseSbomHashJustification");
		assertEquals(3, cleared.get("revision"));
		assertEquals(1, ((List<?>) cleared.get("hashes")).size());
		assertEquals(3, count("release_sbom_justification_audit", "release_sbom = ?", f.head()));
	}

	@Test
	void twoConcurrentSetsOnOneHeadBothLand() throws Exception {
		Fix f = fixture();
		UUID caller = admin().getUuid();
		CountDownLatch go = new CountDownLatch(1);
		List<CompletableFuture<Void>> writes = new ArrayList<>();
		for (String purl : List.of(DEB_A, DEB_B)) {
			writes.add(CompletableFuture.runAsync(() -> {
				try {
					go.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				justificationService.set(head(f.head()), List.of(keyOf(purl)), JustifiedUnknownReason.OTHER, "x", null,
						caller, null, Overwrite.REPLACE);
			}));
		}
		go.countDown();
		CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
		assertEquals(2, justificationService.current(head(f.head())).getRevision());
		assertEquals(2, justificationService.current(head(f.head())).getRecordData().hashes().size());
		assertEquals(2, count("release_sbom_justification_audit", "release_sbom = ?", f.head()));
	}

	// ------------------------------------------------------------------ row 10: the selector sweep

	@Test
	void aSelectorSweepJustifiesTheMatchingHashlessKeysSkipsJustifiedOnesAndRecordsOnce() throws Exception {
		Fix f = fixture();
		assertEquals(3, (Integer) data(asUser(f.reader(), COUNT, vars("h", f.head().toString(), "s",
				selector("TYPE", "deb", null))), "releaseSbomHashlessSelectorCount"));
		assertEquals(2, (Integer) data(asUser(f.reader(), COUNT, vars("h", f.head().toString(), "s",
				selector("TYPE", "npm", null))), "releaseSbomHashlessSelectorCount"));
		assertEquals(0, count("support_bulk_acts", "release_sbom = ?", f.head()), "a count writes nothing");

		data(asUser(f.writer(), SET, setVars(f.head(), List.of(keyOf(DEB_B)), "VENDORED_SOURCE_NO_HASH", "x", null)),
				"setReleaseSbomHashJustification");
		Map<String, Object> swept = data(asUser(f.writer(), SWEEP, vars("h", f.head().toString(), "s",
				selector("TYPE", "deb", null), "c", "DISTRIBUTION_PUBLISHES_NO_HASH", "r", "debian policy")),
				"bulkSetReleaseSbomHashJustificationBySelector");
		assertEquals(List.of(keyOf(DEB_B)), swept.get("skippedKeys"));
		Map<String, Object> act = jdbc.queryForMap("SELECT uuid, kind, release, release_sbom, matched_count, "
				+ "applied_count, list_hash, selector::text AS sel FROM rearm.support_bulk_acts WHERE release_sbom = ?",
				f.head());
		assertEquals("HASH_JUSTIFICATION", act.get("kind"));
		assertEquals(f.world().release(), act.get("release"));
		assertEquals(3, act.get("matched_count"));
		assertEquals(2, act.get("applied_count"));
		assertEquals(SupportBulkActService.listHash(List.of(keyOf(DEB_A), keyOf(DEB_B), keyOf(DEB_C))),
				act.get("list_hash"));
		assertTrue(((String) act.get("sel")).contains("\"type\": \"deb\""), (String) act.get("sel"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> hashes = (List<Map<String, Object>>) swept.get("hashes");
		for (Map<String, Object> h : hashes) {
			if (keyOf(DEB_B).equals(h.get("key"))) {
				assertEquals("VENDORED_SOURCE_NO_HASH", h.get("code"), "byte-equal");
				assertEquals("x", h.get("text"));
				assertNull(h.get("bulkAct"));
			} else {
				assertEquals(act.get("uuid").toString(), h.get("bulkAct"));
			}
		}
		assertEquals(1, count("release_sbom_justification_audit", "release_sbom = ? AND bulk_act = ?", f.head(),
				act.get("uuid")));

		// the explicit set replaces the skipped key's code and text and audits once
		long before = count("release_sbom_justification_audit", "release_sbom = ?", f.head());
		data(asUser(f.writer(), SET, setVars(f.head(), List.of(keyOf(DEB_B)), "DISTRIBUTION_PUBLISHES_NO_HASH", null,
				null)), "setReleaseSbomHashJustification");
		assertEquals(before + 1, count("release_sbom_justification_audit", "release_sbom = ?", f.head()));

		// by decoded namespace
		Map<String, Object> acme = data(asUser(f.writer(), SWEEP, vars("h", f.head().toString(), "s",
				selector("NAMESPACE", "npm", "@acme"), "c", "OTHER", "t", "scoped")),
				"bulkSetReleaseSbomHashJustificationBySelector");
		assertTrue(((List<?>) acme.get("hashes")).stream().anyMatch(h -> keyOf(NPM_ACME).equals(((Map<?, ?>) h)
				.get("key"))));
		assertFalse(((List<?>) acme.get("hashes")).stream().anyMatch(h -> keyOf(NPM_PLAIN).equals(((Map<?, ?>) h)
				.get("key"))));
	}

	@Test
	void aSelectorOverTheCapIsRefusedWithNoRowWritten() throws Exception {
		World w = componentRelease(null);
		List<Node> many = new ArrayList<>();
		for (int i = 0; i <= SbomComponentService.BULK_SUPPORT_MAX_IDS; i++) many.add(hashless("pkg:deb/debian/p" + i + "@1"));
		UUID head = headOf(w.release(), PROFILE, many);
		UserData writer = userOf(org, PermissionScope.COMPONENT, w.component(), PermissionType.READ_WRITE);
		assertEquals(SbomComponentService.selectorOverCap(SupportBulkSelector.validated(SupportBulkSelectorForm.TYPE,
				"deb", null), true), error(asUser(writer, SWEEP, vars("h", head.toString(), "s",
						selector("TYPE", "deb", null), "c", "DISTRIBUTION_PUBLISHES_NO_HASH"))));
		assertEquals(0, count("release_sbom_justification_audit", "release_sbom = ?", head));
		assertEquals(0, count("support_bulk_acts", "release_sbom = ?", head));
	}

	// ------------------------------------------------------------------ row 4: the publication seam

	@Test
	void thePublicationOfTheHeadsProfileWritesItsCodesAndAReadFailureStripsAndMarks() throws Exception {
		OrganizationData.Settings enable = new OrganizationData.Settings();
		enable.setSupportInjection(SupportInjectionSetting.ENABLED);
		organizationService.updateSettings(org.getUuid(), enable, WU);
		World w = componentRelease(null);
		UUID head = headOf(w.release(), "fda", List.of(hashless(DEB_A), hashed(DEB_HASHED)));
		justificationService.set(head(head), List.of(keyOf(DEB_A)), JustifiedUnknownReason.DISTRIBUTION_PUBLISHES_NO_HASH,
				null, null, admin().getUuid(), null, Overwrite.REPLACE);
		String prop = "reliza:support:justifiedUnknown:hash";

		JsonNode fda = injection.injectIfEnabledElseStrip(docOf(List.of(hashless(DEB_A))), org.getUuid(),
				null, ExportMetadataOptions.callerSilent(), w.release(), "fda");
		assertTrue(fda.toString().contains(prop + "\",\"value\":\"DISTRIBUTION_PUBLISHES_NO_HASH"), fda.toString());
		JsonNode other = injection.injectIfEnabledElseStrip(docOf(List.of(hashless(DEB_A))),
				org.getUuid(), null, ExportMetadataOptions.callerSilent(), w.release(), "default");
		assertFalse(other.toString().contains(prop), "no head for that profile");
		JsonNode none = injection.injectIfEnabledElseStrip(docOf(List.of(hashless(DEB_A))),
				org.getUuid(), null, ExportMetadataOptions.callerSilent(), w.release(), null);
		assertFalse(none.toString().contains(prop), "a null profile writes none");
		assertNotEquals(Utils.bytesToHexSha256(Utils.OM.writeValueAsBytes(fda)),
				Utils.bytesToHexSha256(Utils.OM.writeValueAsBytes(none)), "the justification moves the content digest");

		doThrow(new IllegalStateException("justification read failed")).when(justificationRepository).findById(any());
		JsonNode failed = injection.injectIfEnabledElseStrip(docOf(List.of(hashless(DEB_A))),
				org.getUuid(), null, ExportMetadataOptions.callerSilent(), w.release(), "fda");
		assertFalse(failed.toString().contains(prop), failed.toString());
		assertTrue(failed.toString().contains("provenance-stripped-no-disclosure"), failed.toString());
		reset(justificationRepository);
	}

}
