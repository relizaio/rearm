/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ExportMetadataChoice;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.OrganizationData;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import tools.jackson.databind.JsonNode;

/**
 * The two per-export metadata flags, on every egress that can honour them.
 *
 * <p>These flags exist because an FDA premarket submission and an ordinary customer-facing SBOM
 * are two different documents assembled from the same release. One wants the support
 * attestations and does not mind the tooling provenance; the other is meant to read as the
 * manufacturer's own content, with nothing of ReARM's in it. Before this, both were the same
 * bytes and the only lever was an organization-wide setting.
 *
 * <p>THE CONTRACT THESE TESTS EXIST TO PIN, in order of how expensive it would be to break:
 * <ol>
 *   <li><b>Silence changes nothing.</b> Every caller that predates the arguments -- the CLI,
 *       the TEA endpoints, an API script -- sends neither flag and must receive exactly the
 *       bytes it received before. Tested per path, not once, because the paths do not share
 *       a serialization step.</li>
 *   <li><b>Asking for support metadata an org has disabled is REFUSED, never quietly
 *       stripped.</b> A document that silently came back without the disclosure is
 *       indistinguishable from one where nothing was attested, and on a submission that
 *       difference is the submission.</li>
 *   <li><b>The internal strip does not touch the disclosure.</b> Including
 *       {@code reliza:device:*}, which is not spelled {@code reliza:support:} and is the one
 *       classification mistake available here -- it carries the device's own 524B support
 *       window.</li>
 *   <li><b>RAW ignores both flags.</b> Raw means as-uploaded; a flag that edited those bytes
 *       would break the checksum the TEA endpoint advertises over them.</li>
 * </ol>
 *
 * <p>Hand-built services, as the sibling strip tests are: the merge and the rebom fetch need a
 * database and a live rebom, so those are substituted and everything downstream of them --
 * the gate, the strips, the serialization -- is production code.
 */
class ExportMetadataFlagsTest {

	private static final UUID BOM_ID = UUID.randomUUID();
	private static final String LIB_PURL = "pkg:maven/ex/libattested@1.0.0";

	/** One genuine attested fact, so the support flag has something observable to gate. */
	private static final SupportData GENUINE_SUPPORT = new SupportData(
			LevelOfSupport.NO_LONGER_MAINTAINED, SupportState.ATTESTED, SupportParty.THIRD_PARTY,
			SupportSource.MANUAL, "2026-09-08T00:00:00Z", UUID.randomUUID(),
			"upstream announced end of maintenance", Map.of());

	/**
	 * A served document carrying one of everything the flags have an opinion about.
	 *
	 * <p>The three ReARM-internal markers are the three that exist in the codebase today
	 * ({@code reliza:containerSafeVersion} from the OBOM assembler,
	 * {@code reliza:devops:integrationType} from CommonVariables, {@code reliza:rearmImport:*}
	 * from CdxImportService). {@code trivy} under {@code metadata.tools} is the control: it is
	 * the manufacturer's own toolchain and must survive a strip that removes ours.
	 *
	 * <p>THE TOOL ENTRY IS SPELLED THE WAY REBOM SPELLS IT -- {@code "rearm"}, lowercase --
	 * because rebom wrote every document that reaches this seam. An earlier version of this
	 * fixture used {@code "ReARM"}, taken from {@code Utils.setRearmBomMetadata}, which writes
	 * only onto the OBOM/VDR/VEX documents that never pass through here. The test passed
	 * against a matcher that removed nothing in production. A fixture copied from the writer
	 * you are not exercising proves the thing you did not build.
	 */
	private static final String BOM_WITH_INTERNAL_MARKERS = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "metadata": {
			    "tools": {
			      "components": [
			        {"group": "io.reliza", "name": "rearm", "type": "application"},
			        {"group": "aquasecurity", "name": "trivy", "version": "0.50.0"}
			      ]
			    },
			    "component": {
			      "name": "device-root", "purl": "pkg:generic/device-root@1.0.0",
			      "properties": [
			        {"name": "reliza:devops:integrationType", "value": "GITHUB"}
			      ]
			    }
			  },
			  "components": [
			    {
			      "name": "libattested", "purl": "pkg:maven/ex/libattested@1.0.0",
			      "properties": [
			        {"name": "reliza:containerSafeVersion", "value": "1.0.0"},
			        {"name": "reliza:rearmImport:source", "value": "cdx"},
			        {"name": "some:other:property", "value": "kept"}
			      ]
			    }
			  ]
			}""";

	/**
	 * The legacy CycloneDX 1.4 {@code metadata.tools} ARRAY shape, as rebom actually writes it.
	 *
	 * <p>Rebom pushes the SAME object into the 1.4 array that it puts in
	 * {@code tools.components} on 1.5+, so the namespace is under {@code group} here too -- not
	 * {@code vendor}. The first version of the remover looked only at {@code vendor} on this
	 * branch, matched by a fixture that had been written to agree with it, and would have found
	 * nothing in a real 1.4 document.
	 */
	private static final String BOM_WITH_LEGACY_TOOLS_ARRAY = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.4",
			  "metadata": {
			    "tools": [
			      {"group": "io.reliza", "name": "rearm"},
			      {"vendor": "aquasecurity", "name": "trivy", "version": "0.50.0"}
			    ]
			  },
			  "components": [
			    {"name": "libattested", "purl": "pkg:maven/ex/libattested@1.0.0"}
			  ]
			}""";

	/**
	 * The OTHER 1.4 spelling: {@code vendor} as the CycloneDX 1.4 tool schema defines it, and
	 * the historic {@code "rebom"} name that documents stored before rebom's rename still carry
	 * and that this server still serves.
	 */
	private static final String BOM_WITH_LEGACY_VENDOR_AND_OLD_NAME = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.4",
			  "metadata": {
			    "tools": [
			      {"vendor": "io.reliza", "name": "rebom", "version": "0.20.0"},
			      {"vendor": "aquasecurity", "name": "trivy", "version": "0.50.0"}
			    ]
			  },
			  "components": [
			    {"name": "libattested", "purl": "pkg:maven/ex/libattested@1.0.0"}
			  ]
			}""";

	/**
	 * A third party whose tool happens to sit in a namespace we do not own, and one that shares
	 * a NAME with ours under a different group. Neither is ours to remove: taking someone
	 * else's tool entry out of their BOM is a worse error than leaving ours in.
	 */
	private static final String BOM_WITH_LOOKALIKE_TOOLS = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "metadata": {
			    "tools": {
			      "components": [
			        {"group": "com.example", "name": "rearm", "version": "9.9.9"},
			        {"group": "io.reliza", "name": "some-other-tool", "version": "1.0.0"}
			      ]
			    }
			  },
			  "components": [
			    {"name": "libattested", "purl": "pkg:maven/ex/libattested@1.0.0"}
			  ]
			}""";

	/**
	 * The injected level of support as it appears IN THE DOCUMENT -- the wire value, not the
	 * enum's Java name. Asserting on the name passes nothing and fails everything, which is
	 * exactly what it did on the first run of this class.
	 */
	private static final String ATTESTED_LEVEL_IN_DOCUMENT =
			LevelOfSupport.NO_LONGER_MAINTAINED.getWireValue();

	/**
	 * Whether a served document still carries a ReARM tool entry, under ANY of the names this
	 * organisation has shipped.
	 *
	 * <p>LITERALS, NOT {@code Utils.REARM_TOOL_NAMES}. Reading the production constant would
	 * make this helper blind to exactly the defect it exists to catch: drop a name from the
	 * constant and both the matcher and the assertion stop looking for it, and the suite goes
	 * green on a document that still carries the entry. That is the same mistake in a second
	 * costume -- the first was a fixture copied from the writer we were not exercising.
	 */
	private static final List<String> REARM_TOOL_NAMES_IN_CIRCULATION =
			List.of("ReARM", "rearm", "rebom");

	private static boolean hasRearmTool(String served) {
		return REARM_TOOL_NAMES_IN_CIRCULATION.stream()
				.anyMatch(n -> served.contains("\"name\":\"" + n + "\""));
	}

	/**
	 * The production constant must cover every name this test knows to be in circulation.
	 *
	 * <p>The pair above is deliberately independent, so this is the one place they are made to
	 * meet -- and it fails on the REMOVAL of a name rather than only on its presence.
	 */
	@Test
	void everyToolNameInCirculationIsOneTheMatcherKnows() {
		for (String name : REARM_TOOL_NAMES_IN_CIRCULATION) {
			assertTrue(Utils.REARM_TOOL_NAMES.contains(name),
					"Utils.REARM_TOOL_NAMES does not cover " + name
							+ ", so an export cannot remove a tool entry carrying it");
		}
	}

	private static ExportMetadataOptions silent() {
		return ExportMetadataOptions.callerSilent();
	}

	private static ExportMetadataOptions options(ExportMetadataChoice support, ExportMetadataChoice internal) {
		return new ExportMetadataOptions(support, internal);
	}

	// ------------------------------------------------------------------ merged release export

	private static ReleaseService releaseServiceServing(String bomJson) {
		ReleaseService svc = new ReleaseService(null) {
			@Override
			UUID getReleaseBomId(UUID releaseUuid, Boolean tldOnly, Boolean ignoreDev,
					ArtifactBelongsTo belongsTo, BomStructureType structure, WhoUpdated wu,
					List<CommonVariables.ArtifactCoverageType> excludeCoverageTypes) {
				return BOM_ID;
			}
		};
		RebomService rebom = new RebomService("http://rebom.invalid") {
			@Override
			public JsonNode findBomByIdJson(UUID bomSerialNumber, UUID org) {
				return Utils.OM.readTree(bomJson);
			}
		};
		ReflectionTestUtils.setField(svc, "rebomService", rebom);
		return svc;
	}

	/**
	 * An injector wired to an org with the given setting and one genuine attested fact.
	 *
	 * <p>Shared by the release and the artifact harnesses so the two egresses are exercised
	 * against the SAME injector configuration -- the whole point of the seam is that they
	 * cannot disagree, and a test that built two would not be able to see it if they did.
	 */
	private static SupportInjectionService injectorFor(SupportInjectionSetting setting) {
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setSupportInjection(setting);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getSettingsWithDefaults()).thenReturn(settings);
		GetOrganizationService gos = mock(GetOrganizationService.class);
		when(gos.getOrganizationData(any())).thenReturn(Optional.of(od));

		SbomComponentSupportRepository supportRepo = mock(SbomComponentSupportRepository.class);
		when(supportRepo.existsSupportByOrg(any())).thenReturn(true);
		UserService userService = mock(UserService.class);
		when(userService.getUserData(any())).thenReturn(Optional.empty());

		return new SupportInjectionService(mock(SbomComponentRepository.class), supportRepo, gos, userService) {
			@Override
			public Map<String, ComponentSupportFacts> resolveSupportFactsForBom(JsonNode bom, UUID orgUuid) {
				SbomComponent sc = new SbomComponent();
				sc.setCanonicalPurl(LIB_PURL);
				return Map.of(LIB_PURL, new ComponentSupportFacts(sc, GENUINE_SUPPORT));
			}
		};
	}

	private static String exportMerged(String bomJson, SupportInjectionSetting setting,
			ExportMetadataOptions options) throws Exception {
		ReleaseService svc = releaseServiceServing(bomJson);
		ReflectionTestUtils.setField(svc, "supportInjectionService", injectorFor(setting));
		return svc.exportReleaseSbom(UUID.randomUUID(), false, false, null, BomStructureType.FLAT,
				BomMediaType.JSON, UUID.randomUUID(), WhoUpdated.getAutoWhoUpdated(), null, options);
	}

	/**
	 * THE BASELINE, and the assertion the other merged-export tests are only meaningful
	 * against: a caller who says nothing gets what it always got.
	 */
	@Test
	void mergedExportIsUnchangedWhenTheCallerSaysNothing() throws Exception {
		String served = exportMerged(BOM_WITH_INTERNAL_MARKERS, SupportInjectionSetting.ENABLED, silent());

		assertTrue(served.contains("reliza:containerSafeVersion"),
				"an omitted includeInternalMetadata must not strip anything: " + served);
		assertTrue(hasRearmTool(served),
				"an omitted includeInternalMetadata must leave the ReARM tool entry: " + served);
		assertTrue(served.contains(ATTESTED_LEVEL_IN_DOCUMENT),
				"an omitted includeSupportMetadata must still honour the org setting (ENABLED): " + served);
	}

	@Test
	void mergedExportDropsInternalMarkersWhenInternalMetadataIsExcluded() throws Exception {
		String served = exportMerged(BOM_WITH_INTERNAL_MARKERS, SupportInjectionSetting.ENABLED,
				options(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.EXCLUDE));

		assertFalse(served.contains("reliza:containerSafeVersion"),
				"reliza:containerSafeVersion is ReARM's own marker and must go: " + served);
		assertFalse(served.contains("reliza:rearmImport"),
				"reliza:rearmImport:* is ReARM's own marker and must go: " + served);
		assertFalse(served.contains("reliza:devops:integrationType"),
				"reliza:devops:integrationType is ReARM's own marker and must go: " + served);
		assertFalse(hasRearmTool(served),
				"the io.reliza ReARM entry must leave metadata.tools: " + served);
		assertTrue(served.contains("trivy"),
				"another producer's tool entry is the manufacturer's own content and must stay: " + served);
		assertTrue(served.contains("some:other:property"),
				"a non-reliza property must survive the internal strip: " + served);
	}

	/**
	 * THE CLASSIFICATION MISTAKE THIS FLAG COULD MOST EASILY MAKE.
	 *
	 * <p>{@code reliza:device:endOfSupport} / {@code endOfLife} are reliza-namespaced and are
	 * NOT spelled {@code reliza:support:}, so a strip written as "every reliza:* property that
	 * is not reliza:support:*" removes them. They carry the DEVICE's own support window -- the
	 * section 524B commitment -- and an export that asked to keep the support disclosure and
	 * silently lost the device window is the one combination a premarket submission cannot use.
	 */
	@Test
	void theInternalStripLeavesTheSupportDisclosureIntact() throws Exception {
		String served = exportMerged(BOM_WITH_INTERNAL_MARKERS, SupportInjectionSetting.ENABLED,
				options(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.EXCLUDE));

		assertTrue(served.contains("reliza:support:"),
				"the internal strip must not touch the support disclosure: " + served);
		assertTrue(served.contains(ATTESTED_LEVEL_IN_DOCUMENT),
				"the attested level of support must survive an internal strip: " + served);
		assertTrue(served.contains("derived-non-attested-current-state"),
				"the disclosure marker must survive an internal strip: " + served);
	}

	@Test
	void mergedExportStripsTheDisclosureWhenSupportMetadataIsExcluded() throws Exception {
		String served = exportMerged(BOM_WITH_INTERNAL_MARKERS, SupportInjectionSetting.ENABLED,
				options(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.DEFAULT));

		assertFalse(served.contains(ATTESTED_LEVEL_IN_DOCUMENT),
				"includeSupportMetadata=false must strip the support facts, exactly as the"
						+ " org-DISABLED branch does -- only the marker differs: " + served);
		assertFalse(served.contains("provenance-stripped-no-disclosure"),
				"an explicit decline is NOT marked -- there is no support statement for the"
						+ " marker to qualify: " + served);
		assertTrue(served.contains("reliza:containerSafeVersion"),
				"declining the support disclosure must not remove ReARM's internal markers --"
						+ " that is the other flag: " + served);
	}

	/**
	 * Both flags off leaves NOTHING of ours in the document -- not one {@code reliza:} property.
	 *
	 * <p>Including the disclosure marker, which used to survive this combination. Operator
	 * decision 2026-09-22: the marker exists to disambiguate an ABSENT support property --
	 * "we hold no attestation for this component" versus "this document asserts nothing about
	 * support" -- and that ambiguity is only real in a document that is making a support
	 * statement. A caller who sent {@code includeSupportMetadata=false} is not making one, so
	 * the marker has nothing to qualify and is just our name on a file they asked to be free
	 * of it.
	 *
	 * <p>THIS IS THE ASSERTION THAT MAKES THE UI COPY TRUE. "Off produces a BOM with only the
	 * manufacturer's own content" was ALMOST true before -- one property short -- and a promise
	 * that is almost true is the kind a reviewer finds rather than the kind they trust.
	 *
	 * <p>The STRIP is untouched: the uploader's forged level is gone from this document as
	 * from every other. Only the marker is withheld.
	 */
	@Test
	void bothFlagsOffLeavesTheManufacturersOwnContentOnly() throws Exception {
		String served = exportMerged(BOM_WITH_INTERNAL_MARKERS, SupportInjectionSetting.ENABLED,
				options(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.EXCLUDE));

		assertFalse(served.contains("reliza:"),
				"both flags off must leave NO reliza-namespaced content at all: " + served);
		assertFalse(served.contains(ATTESTED_LEVEL_IN_DOCUMENT), served);
		assertFalse(hasRearmTool(served), served);
		assertFalse(served.contains("\"declarations\""), served);
		// The uploader's own content, untouched.
		assertTrue(served.contains("some:other:property"), served);
		assertTrue(served.contains("trivy"), served);
	}

	/**
	 * DECLINED and DISABLED strip the same and say different things, and the difference is who
	 * decided.
	 *
	 * <p>An organization-wide DISABLED produces a document its reader had no part in choosing,
	 * and that is exactly where "why is there no support data here?" needs an answer -- so the
	 * marker stays. A caller who declined the disclosure outright already knows why.
	 */
	@Test
	void theOrgDisabledPathStillMarksButAnExplicitDeclineDoesNot() throws Exception {
		String orgDisabled = exportMerged(BOM_WITH_INTERNAL_MARKERS,
				SupportInjectionSetting.DISABLED, silent());
		assertTrue(orgDisabled.contains("provenance-stripped-no-disclosure"),
				"an org-wide DISABLED must still say so: " + orgDisabled);

		String declined = exportMerged(BOM_WITH_INTERNAL_MARKERS,
				SupportInjectionSetting.DISABLED,
				options(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.DEFAULT));
		assertFalse(declined.contains("provenance-stripped-no-disclosure"),
				"an explicit decline must not be marked: " + declined);
		// And the strip still ran on both -- this is a content choice, never a security one.
		for (String doc : List.of(orgDisabled, declined)) {
			assertFalse(doc.contains("reliza:support:levelOfSupport"),
					"the forged-provenance strip is unconditional: " + doc);
		}
	}

	/**
	 * The legacy 1.4 {@code metadata.tools} array shape. Documents in that shape are the ones
	 * least likely to be re-examined, so a strip that only understood the modern shape would
	 * leave our entry exactly where nobody would look for it.
	 */
	@Test
	void theRearmToolEntryIsRemovedFromTheLegacyToolsArrayToo() throws Exception {
		String served = exportMerged(BOM_WITH_LEGACY_TOOLS_ARRAY, SupportInjectionSetting.DISABLED,
				options(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.EXCLUDE));

		assertFalse(hasRearmTool(served),
				"the legacy tool entry must be removed too: " + served);
		assertTrue(served.contains("trivy"), served);
	}

	/**
	 * EVERY SPELLING IN CIRCULATION, because there are two writers and three names and the
	 * matcher originally knew one of each.
	 *
	 * <p>{@code vendor} rather than {@code group}, and {@code "rebom"} rather than
	 * {@code "rearm"} -- the name that writer used before its rename, still carried by every
	 * document stored before then and still served today. A flag that promises "only the
	 * manufacturer's own content" cannot answer differently depending on when a BOM was
	 * uploaded.
	 */
	@Test
	void theHistoricNameAndTheVendorFieldAreRemovedToo() throws Exception {
		String served = exportMerged(BOM_WITH_LEGACY_VENDOR_AND_OLD_NAME,
				SupportInjectionSetting.DISABLED,
				options(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.EXCLUDE));

		assertFalse(hasRearmTool(served),
				"a vendor-keyed entry under the historic name must go too: " + served);
		assertTrue(served.contains("trivy"),
				"the other producer's entry must survive: " + served);
	}

	/**
	 * THE OVER-REMOVAL SIDE, which matters more than the under-removal side: taking a
	 * customer's own tool entry out of their BOM is a worse error than leaving one of ours in,
	 * and it is the failure a name-only or group-only match would produce.
	 */
	@Test
	void aLookalikeToolBelongingToSomeoneElseIsLeftAlone() throws Exception {
		String served = exportMerged(BOM_WITH_LOOKALIKE_TOOLS, SupportInjectionSetting.DISABLED,
				options(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.EXCLUDE));

		assertTrue(served.contains("com.example"),
				"a tool sharing OUR NAME under someone else's group is not ours: " + served);
		assertTrue(served.contains("some-other-tool"),
				"a tool in our group under a name we never shipped is not ours: " + served);
	}

	/**
	 * THE FOUR-WAY DELTA, asserted cell by cell and printed as the table an operator reads.
	 *
	 * <p>Printed as well as asserted because "what is actually different in the file" is the
	 * question this feature exists to answer, and a boolean pass/fail cannot answer it. The
	 * live probe emits the same table from a served document; this one emits it from the same
	 * production code path with the rebom fetch substituted, so the two are comparable and this
	 * one is available even when the sandbox's OCI registry is down -- which is how it was
	 * first needed.
	 *
	 * <p>The assertions are the contract; the print is the evidence.
	 */
	@Test
	void theFourWayDeltaIsExactlyWhatTheDocsDescribe() throws Exception {
		Map<String, String> docs = new LinkedHashMap<>();
		docs.put("support=on  internal=on ", exportMerged(BOM_WITH_INTERNAL_MARKERS,
				SupportInjectionSetting.ENABLED, options(ExportMetadataChoice.INCLUDE, ExportMetadataChoice.INCLUDE)));
		docs.put("support=on  internal=off", exportMerged(BOM_WITH_INTERNAL_MARKERS,
				SupportInjectionSetting.ENABLED, options(ExportMetadataChoice.INCLUDE, ExportMetadataChoice.EXCLUDE)));
		docs.put("support=off internal=on ", exportMerged(BOM_WITH_INTERNAL_MARKERS,
				SupportInjectionSetting.ENABLED, options(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.INCLUDE)));
		docs.put("support=off internal=off", exportMerged(BOM_WITH_INTERNAL_MARKERS,
				SupportInjectionSetting.ENABLED, options(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.EXCLUDE)));
		docs.put("(both omitted)         ", exportMerged(BOM_WITH_INTERNAL_MARKERS,
				SupportInjectionSetting.ENABLED, silent()));

		Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
		Set<String> allNames = new TreeSet<>();
		for (Map.Entry<String, String> e : docs.entrySet()) {
			Map<String, Integer> c = namespacedPropertyCounts(e.getValue());
			counts.put(e.getKey(), c);
			allNames.addAll(c.keySet());
		}

		StringBuilder table = new StringBuilder("\n| property | s1i1 | s1i0 | s0i1 | s0i0 | omitted |\n");
		table.append("|---|---|---|---|---|---|\n");
		for (String name : allNames) {
			table.append("| `").append(name).append("` |");
			for (Map<String, Integer> c : counts.values()) {
				table.append(' ').append(c.getOrDefault(name, 0)).append(" |");
			}
			table.append('\n');
		}
		table.append("| ReARM tool entry |");
		for (String doc : docs.values()) {
			table.append(hasRearmTool(doc) ? " present |" : " removed |");
		}
		table.append('\n');
		System.out.println(table);

		// NULLS CHANGE NOTHING. On an ENABLED org, omitting both arguments must be
		// byte-identical to asking for both -- the guarantee every pre-existing caller relies
		// on, and the one a regression here would break silently.
		assertEquals(docs.get("support=on  internal=on "), docs.get("(both omitted)         "),
				"omitting both arguments must equal asking for both on an ENABLED org");

		// The support namespace tracks the SUPPORT flag and nothing else.
		for (String on : List.of("support=on  internal=on ", "support=on  internal=off")) {
			assertTrue(counts.get(on).keySet().stream().anyMatch(n -> n.startsWith("reliza:support:levelOf")),
					"support on must carry the attested level: " + on);
		}
		for (String off : List.of("support=off internal=on ", "support=off internal=off")) {
			assertFalse(counts.get(off).keySet().stream().anyMatch(n -> n.startsWith("reliza:support:levelOf")),
					"support off must carry no attested level: " + off);
		}

		// The internal markers track the INTERNAL flag and nothing else.
		for (String on : List.of("support=on  internal=on ", "support=off internal=on ")) {
			assertTrue(counts.get(on).containsKey("reliza:containerSafeVersion"),
					"internal on must keep the tooling marker: " + on);
			assertTrue(hasRearmTool(docs.get(on)), "internal on must keep the tool entry: " + on);
		}
		for (String off : List.of("support=on  internal=off", "support=off internal=off")) {
			assertFalse(counts.get(off).containsKey("reliza:containerSafeVersion"),
					"internal off must drop the tooling marker: " + off);
			assertFalse(hasRearmTool(docs.get(off)), "internal off must drop the tool entry: " + off);
		}

		// The marker now tracks the SUPPORT column like everything else in it -- present where
		// a support statement is being made, absent where the caller declined to make one.
		for (String on : List.of("support=on  internal=on ", "support=on  internal=off",
				"(both omitted)         ")) {
			assertTrue(counts.get(on).containsKey("reliza:support:disclosure"),
					"a support-carrying export must be marked: " + on);
		}
		for (String off : List.of("support=off internal=on ", "support=off internal=off")) {
			assertFalse(counts.get(off).containsKey("reliza:support:disclosure"),
					"an explicitly declined export must not be marked: " + off);
		}
	}

	/** Every reliza: / cdx:lifecycle: property in a served document, counted by name. */
	private static Map<String, Integer> namespacedPropertyCounts(String served) {
		Map<String, Integer> counts = new TreeMap<>();
		Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"((?:reliza|cdx:lifecycle):[^\"]+)\"").matcher(served);
		while (m.find()) {
			counts.merge(m.group(1), 1, Integer::sum);
		}
		return counts;
	}

	// ----------------------------------------------------------------- artifact download paths

	private static ArtifactData artifactData(BomFormat format) {
		Map<String, Object> rd = new HashMap<>();
		rd.put("uuid", UUID.randomUUID().toString());
		rd.put("org", UUID.randomUUID().toString());
		rd.put("bomFormat", format.name());
		rd.put("internalBom", Map.of("id", UUID.randomUUID().toString(), "belongsTo", "DELIVERABLE"));
		return Utils.OM.convertValue(rd, ArtifactData.class);
	}

	private static SharedArtifactService artifactServiceServing(String bomJson,
			SupportInjectionSetting setting) {
		SharedArtifactService svc = new SharedArtifactService(null, "", "");
		RebomService rebom = new RebomService("http://rebom.invalid") {
			@Override
			public JsonNode findRawBomById(UUID bomSerialNumber, UUID org, BomFormat format) {
				return Utils.OM.readTree(bomJson);
			}

			@Override
			public JsonNode findRawBomById(UUID bomSerialNumber, UUID org) {
				return Utils.OM.readTree(bomJson);
			}

			@Override
			public JsonNode findBomByIdJson(UUID bomSerialNumber, UUID org) {
				return Utils.OM.readTree(bomJson);
			}
		};
		ReflectionTestUtils.setField(svc, "rebomService", rebom);
		ReflectionTestUtils.setField(svc, "supportInjectionService", injectorFor(setting));
		return svc;
	}

	private static String downloaded(BomFormat format, SupportInjectionSetting setting,
			ExportMetadataOptions options) throws Exception {
		ResponseEntity<byte[]> resp = artifactServiceServing(BOM_WITH_INTERNAL_MARKERS, setting)
				.downloadArtifact(artifactData(format), null, options).block();
		assertNotNull(resp, "no response from the artifact download");
		assertNotNull(resp.getBody(), "empty body from the artifact download");
		return new String(resp.getBody(), StandardCharsets.UTF_8);
	}

	@Test
	void cyclonedxDownloadIsUnchangedWhenTheCallerSaysNothing() throws Exception {
		String served = downloaded(BomFormat.CYCLONEDX, SupportInjectionSetting.ENABLED, silent());

		assertTrue(served.contains("reliza:containerSafeVersion"), served);
		assertTrue(hasRearmTool(served), served);
		assertTrue(served.contains(ATTESTED_LEVEL_IN_DOCUMENT), served);
	}

	@Test
	void cyclonedxDownloadHonoursBothFlags() throws Exception {
		String internalOff = downloaded(BomFormat.CYCLONEDX, SupportInjectionSetting.ENABLED,
				options(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.EXCLUDE));
		assertFalse(internalOff.contains("reliza:containerSafeVersion"), internalOff);
		assertFalse(hasRearmTool(internalOff), internalOff);
		assertTrue(internalOff.contains(ATTESTED_LEVEL_IN_DOCUMENT), internalOff);

		String supportOff = downloaded(BomFormat.CYCLONEDX, SupportInjectionSetting.ENABLED,
				options(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.DEFAULT));
		assertFalse(supportOff.contains(ATTESTED_LEVEL_IN_DOCUMENT), supportOff);
		assertFalse(supportOff.contains("provenance-stripped-no-disclosure"),
				"an explicit decline is not marked, on this path as on the merged export: "
						+ supportOff);
		// The OTHER flag was left at DEFAULT, so our internal markers stay -- declining the
		// disclosure must not quietly take them too.
		assertTrue(supportOff.contains("reliza:containerSafeVersion"), supportOff);
	}

	@Test
	void spdxAugmentedDownloadIsUnchangedWhenTheCallerSaysNothing() throws Exception {
		String served = downloaded(BomFormat.SPDX, SupportInjectionSetting.ENABLED, silent());

		assertTrue(served.contains("reliza:containerSafeVersion"), served);
		assertTrue(hasRearmTool(served), served);
		assertTrue(served.contains(ATTESTED_LEVEL_IN_DOCUMENT), served);
	}

	/**
	 * The SPDX-augmented download serves the CONVERTED CycloneDX form, so it carries exactly
	 * the same markers as the native path and must answer the flags the same way. The two
	 * drifted apart once before -- the native path injected while this one stripped -- which is
	 * why they are pinned separately rather than assumed to share a code path.
	 */
	@Test
	void spdxAugmentedDownloadHonoursBothFlags() throws Exception {
		String internalOff = downloaded(BomFormat.SPDX, SupportInjectionSetting.ENABLED,
				options(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.EXCLUDE));
		assertFalse(internalOff.contains("reliza:containerSafeVersion"), internalOff);
		assertFalse(hasRearmTool(internalOff), internalOff);
		assertTrue(internalOff.contains(ATTESTED_LEVEL_IN_DOCUMENT), internalOff);

		String supportOff = downloaded(BomFormat.SPDX, SupportInjectionSetting.ENABLED,
				options(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.DEFAULT));
		assertFalse(supportOff.contains(ATTESTED_LEVEL_IN_DOCUMENT), supportOff);
		assertFalse(supportOff.contains("provenance-stripped-no-disclosure"),
				"an explicit decline is not marked, on this path as on the merged export: "
						+ supportOff);
		// The OTHER flag was left at DEFAULT, so our internal markers stay -- declining the
		// disclosure must not quietly take them too.
		assertTrue(supportOff.contains("reliza:containerSafeVersion"), supportOff);
	}

	/**
	 * THE FAILURE FALLBACK STILL HONOURS THE CALLER.
	 *
	 * <p>When support resolution throws, the download path serves the document anyway -- never
	 * failing a BOM download over an add-on is deliberate. That fallback still SERVES a
	 * document, so it still owes the caller the internal-metadata choice they made: a caller
	 * who asked for a BOM without our markers must not receive one with them because something
	 * unrelated broke. The fallback made that decision at the call site in an earlier revision,
	 * which is the second decision point the whole seam exists to prevent.
	 */
	@Test
	void theResolutionFailureFallbackStillStripsInternalMarkers() throws Exception {
		SharedArtifactService svc = new SharedArtifactService(null, "", "");
		RebomService rebom = new RebomService("http://rebom.invalid") {
			@Override
			public JsonNode findBomByIdJson(UUID bomSerialNumber, UUID org) {
				return Utils.OM.readTree(BOM_WITH_INTERNAL_MARKERS);
			}
		};
		ReflectionTestUtils.setField(svc, "rebomService", rebom);

		// ENABLED, so the injecting branch is taken -- and the very first DB touch inside it
		// throws, which is the outage this fallback is for.
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setSupportInjection(SupportInjectionSetting.ENABLED);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getSettingsWithDefaults()).thenReturn(settings);
		GetOrganizationService gos = mock(GetOrganizationService.class);
		when(gos.getOrganizationData(any())).thenReturn(Optional.of(od));
		SbomComponentSupportRepository supportRepo = mock(SbomComponentSupportRepository.class);
		when(supportRepo.existsSupportByOrg(any()))
				.thenThrow(new IllegalStateException("transient db failure"));
		ReflectionTestUtils.setField(svc, "supportInjectionService",
				new SupportInjectionService(mock(SbomComponentRepository.class), supportRepo, gos, null));

		ResponseEntity<byte[]> resp = svc.downloadArtifact(artifactData(BomFormat.CYCLONEDX), null,
				options(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.EXCLUDE)).block();
		assertNotNull(resp, "no response from the artifact download");
		String served = new String(resp.getBody(), StandardCharsets.UTF_8);

		assertFalse(served.contains("reliza:containerSafeVersion"),
				"a resolution failure must not resurrect ReARM's internal markers in a document"
						+ " the caller asked to have them removed from: " + served);
		assertFalse(hasRearmTool(served), served);
		assertTrue(served.contains("provenance-stripped-no-disclosure"),
				"the fallback must still MARK the document: " + served);
		assertTrue(served.contains("some:other:property"),
				"the manufacturer's own content must survive the fallback: " + served);
	}

	/**
	 * THE FAILURE FALLBACK DOES NOT MARK A DOCUMENT WHOSE CALLER DECLINED THE DISCLOSURE.
	 *
	 * <p>Called DIRECTLY, deliberately. On the live paths a decline short-circuits in
	 * {@code supportDecision} before any DB touch, so the outage this fallback exists for cannot
	 * co-occur with it -- the branch is defence against a programming error in the tree walk,
	 * not against an outage, and there is no end-to-end route that reaches it. That makes it
	 * exactly the kind of code a review found asserted only by a commit message: reverting the
	 * branch left the whole suite green. It is pinned here so the rule holds at the one place
	 * that implements it, whatever reaches it later.
	 */
	@Test
	void theFailureFallbackDoesNotMarkADeclinedDocument() throws Exception {
		SupportInjectionService svc = new SupportInjectionService(
				mock(SbomComponentRepository.class), mock(SbomComponentSupportRepository.class),
				mock(GetOrganizationService.class), null);

		JsonNode declined = svc.stripForgedProvenanceAndMark(
				Utils.OM.readTree(BOM_WITH_INTERNAL_MARKERS),
				options(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.DEFAULT));
		assertFalse(declined.toString().contains("provenance-stripped-no-disclosure"),
				"the fallback must not stamp a marker on a document whose caller declined the"
						+ " disclosure -- an outage is not a reason to put our name on it: " + declined);

		// The org's OWN silence still is marked, on this path as on every other: that reader had
		// no part in the decision and is owed the answer.
		JsonNode silentCaller = svc.stripForgedProvenanceAndMark(
				Utils.OM.readTree(BOM_WITH_INTERNAL_MARKERS), silent());
		assertTrue(silentCaller.toString().contains("provenance-stripped-no-disclosure"),
				"a caller who said nothing must still get the marker: " + silentCaller);
	}

	/**
	 * A FORGED DISCLOSURE MARKER IS SWEPT ON THE SILENT PATH TOO.
	 *
	 * <p>The declined path is the only served path where a surviving forged marker would stand
	 * ALONE: {@code stripOnly} always appends our own value afterwards, so a reader of any other
	 * stripped document sees at least one authentic marker and can tell the two apart. Here an
	 * uploader's forged value would be the document's only disclosure, reading as this server's
	 * claim. The tree-wide prefix sweep already removes it; this is the regression pin that was
	 * missing, since every forged-marker fixture elsewhere runs only with silent options.
	 */
	@Test
	void theSilentStripRemovesAForgedDisclosureMarkerToo() throws Exception {
		String forged = BOM_WITH_INTERNAL_MARKERS.replace(
				"\"tools\": {",
				"\"properties\": [{\"name\": \"reliza:support:disclosure\","
						+ " \"value\": \"derived-non-attested-current-state\"}],\n    \"tools\": {");
		assertTrue(forged.contains("reliza:support:disclosure"), "fixture did not take the forgery");

		String swept = SupportBomInjector.stripSilently(Utils.OM.readTree(forged)).toString();
		assertFalse(swept.contains("reliza:support:disclosure"),
				"an uploader's forged disclosure must not survive the silent strip, where it"
						+ " would be the document's ONLY marker and read as ours: " + swept);
	}

	// ------------------------------------------------------------------------------- raw pin

	/**
	 * RAW IGNORES BOTH FLAGS, and the strongest way to say so is that there is no way to tell
	 * it about them: {@code downloadRawArtifact} takes no options and the flags never reach it.
	 *
	 * <p>The pin is that the served raw document is the ingested one, byte for byte, with every
	 * marker both flags would otherwise remove still present -- taken from a service whose
	 * injector is ENABLED, which is the configuration under which the augmented paths above do
	 * the most editing. Raw means as-uploaded; TEA advertises a checksum and a detached
	 * signature over these bytes, so a flag that edited them would break a verifying consumer.
	 */
	@Test
	void rawDownloadIgnoresBothFlags() throws Exception {
		SharedArtifactService svc = artifactServiceServing(BOM_WITH_INTERNAL_MARKERS,
				SupportInjectionSetting.ENABLED);
		ResponseEntity<byte[]> resp = svc.downloadRawArtifact(artifactData(BomFormat.CYCLONEDX)).block();
		assertNotNull(resp, "no response from the raw download");
		assertNotNull(resp.getBody(), "empty body from the raw download");
		String served = new String(resp.getBody(), StandardCharsets.UTF_8);

		assertEquals(Utils.OM.readTree(BOM_WITH_INTERNAL_MARKERS), Utils.OM.readTree(served),
				"the raw download must serve the ingested document unedited: " + served);
		assertTrue(served.contains("reliza:containerSafeVersion"), served);
		assertTrue(hasRearmTool(served), served);
		assertFalse(served.contains("provenance-stripped-no-disclosure"),
				"raw carries no disclosure marker -- it makes no claim about the document: " + served);
		assertFalse(served.contains(ATTESTED_LEVEL_IN_DOCUMENT),
				"raw must not gain injected facts even on an ENABLED org: " + served);
	}

	// ---------------------------------------------------------------------------- the refusal

	/**
	 * Asking for support metadata an org has DISABLED is refused, and the message says which
	 * setting and where.
	 *
	 * <p>Stripping silently was the alternative and it is strictly worse: the caller named the
	 * disclosure, and a document that came back without it reads exactly like a document where
	 * nothing happened to be attested.
	 */
	@Test
	void requestingSupportMetadataOnADisabledOrgIsRefused() {
		SupportInjectionService injector = injectorFor(SupportInjectionSetting.DISABLED);
		RelizaException e = assertThrows(RelizaException.class, () ->
				injector.assertExportMetadataRequestable(UUID.randomUUID(),
						options(ExportMetadataChoice.INCLUDE, ExportMetadataChoice.DEFAULT)));
		assertTrue(e.getMessage().contains("DISABLED"),
				"the refusal must name the state it found: " + e.getMessage());
		assertTrue(e.getMessage().contains("Organization Settings"),
				"the refusal must say where to change it: " + e.getMessage());
	}

	/** An unreadable org is UNKNOWN, which is also not ENABLED, and is refused the same way. */
	@Test
	void requestingSupportMetadataOnAnUnreadableOrgIsRefused() {
		SupportInjectionService injector = new SupportInjectionService(null, null, null, null);
		RelizaException e = assertThrows(RelizaException.class, () ->
				injector.assertExportMetadataRequestable(UUID.randomUUID(),
						options(ExportMetadataChoice.INCLUDE, ExportMetadataChoice.DEFAULT)));
		assertTrue(e.getMessage().contains("UNKNOWN"), e.getMessage());
	}

	@Test
	void requestingSupportMetadataOnAnEnabledOrgIsAllowed() {
		SupportInjectionService injector = injectorFor(SupportInjectionSetting.ENABLED);
		assertDoesNotThrow(() -> injector.assertExportMetadataRequestable(UUID.randomUUID(),
				options(ExportMetadataChoice.INCLUDE, ExportMetadataChoice.EXCLUDE)));
	}

	/**
	 * Only INCLUDE can be refused. DEFAULT defers to the setting, which it always may, and
	 * EXCLUDE asks for less than the setting allows -- so neither can fail on any org, and a
	 * refusal that fired for them would break every existing caller at once.
	 */
	@Test
	void silenceAndDeclineAreNeverRefused() {
		SupportInjectionService disabled = injectorFor(SupportInjectionSetting.DISABLED);
		UUID org = UUID.randomUUID();
		assertDoesNotThrow(() -> disabled.assertExportMetadataRequestable(org, silent()));
		assertDoesNotThrow(() -> disabled.assertExportMetadataRequestable(org,
				options(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.EXCLUDE)));
		assertDoesNotThrow(() -> disabled.assertExportMetadataRequestable(org, null));
	}

	/** The boundary parser: three wire states, three choices, and null is not false. */
	@Test
	void nullIsNotFalseAtTheBoundary() {
		assertEquals(ExportMetadataChoice.DEFAULT, ExportMetadataChoice.fromCallerInput(null));
		assertEquals(ExportMetadataChoice.INCLUDE, ExportMetadataChoice.fromCallerInput(Boolean.TRUE));
		assertEquals(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.fromCallerInput(Boolean.FALSE));

		ExportMetadataOptions silent = ExportMetadataOptions.fromCallerInput(null, null);
		assertFalse(silent.supportMetadataRequested());
		assertFalse(silent.supportMetadataDeclined());
		assertFalse(silent.internalMetadataDeclined());
		assertEquals(ExportMetadataOptions.callerSilent(), silent);
	}
}
