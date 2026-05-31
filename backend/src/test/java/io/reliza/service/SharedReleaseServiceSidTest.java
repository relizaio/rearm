/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.common.CommonVariables.SidPurlMode;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.OrganizationData;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.repositories.ReleaseLiteRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.oss.OssPerspectiveService;

/**
 * Orchestrator + carryover behavior on {@link SharedReleaseService}. Wires a real
 * {@link SidPurlResolver} with a mock {@link OssPerspectiveService} so the production
 * algorithm runs without a Spring context.
 */
@ExtendWith(MockitoExtension.class)
public class SharedReleaseServiceSidTest {

	@Mock private ReleaseRepository releaseRepository;
	@Mock private ReleaseLiteRepository releaseLiteRepository;
	@Mock private MetricsAuditRepository metricsAuditRepository;
	@Mock private GetComponentService getComponentService;
	@Mock private OssPerspectiveService ossPerspectiveService;

	// Real resolver wired with the mock perspective service so we exercise the actual
	// resolution algorithm (not a stubbed out behavior).
	private SidPurlResolver resolver;

	private SharedReleaseService service;

	@org.junit.jupiter.api.BeforeEach
	void setUp() throws Exception {
		resolver = new SidPurlResolver();
		// Reflectively wire the @Autowired field on the resolver — no Spring context.
		var ospField = SidPurlResolver.class.getDeclaredField("ossPerspectiveService");
		ospField.setAccessible(true);
		ospField.set(resolver, ossPerspectiveService);

		service = new SharedReleaseService(releaseRepository, releaseLiteRepository, metricsAuditRepository);
		var resolverField = SharedReleaseService.class.getDeclaredField("sidPurlResolver");
		resolverField.setAccessible(true);
		resolverField.set(service, resolver);
		var gcsField = SharedReleaseService.class.getDeclaredField("getComponentService");
		gcsField.setAccessible(true);
		gcsField.set(service, getComponentService);
	}

	// ---- Orchestrator: caller-supplied + sid enabled (gate-removal regression) ----

	@Test
	void buildReleaseIdentifiers_callerProvidedNonPurl_sidStillAppended_noCarryover() throws RelizaException {
		// Caller supplies a CPE (non-PURL). Orchestrator must:
		//   1. Append the platform sid PURL (gate at OssReleaseService.java:1995 was dropped).
		//   2. NOT run carryover — caller-supplied identifiers opt out of carryover.
		ComponentData cd = component(BelongsToOrganization.INTERNAL, "ReARM Backend");
		// Component has a maven PURL on it that would normally carry over — confirm it doesn't.
		cd.setIdentifiers(new LinkedList<>(List.of(
				teaPurl("pkg:maven/io.reliza/rearm-backend"))));
		OrganizationData org = orgEnabledStrict("reliza.io");

		List<TeaIdentifier> caller = List.of(teaCpe("cpe:2.3:a:reliza:rearm:25.04.5"));
		var result = service.buildReleaseIdentifiers(cd, org, "25.04.5", null, caller);

		List<TeaIdentifier> ids = result.identifiers();
		assertEquals(2, ids.size(), "expected caller CPE + platform sid PURL only — no carryover maven PURL");
		assertTrue(ids.stream().anyMatch(t -> t.getIdType() == TeaIdentifierType.CPE),
				"caller CPE preserved");
		assertTrue(ids.stream().anyMatch(t -> "pkg:sid/reliza.io/ReARM%20Backend@25.04.5".equals(t.getIdValue())),
				"platform sid PURL appended");
		assertTrue(ids.stream().noneMatch(t -> t.getIdValue() != null && t.getIdValue().startsWith("pkg:maven/")),
				"carryover suppressed when callerProvided is non-empty");
		assertEquals("ReARM Backend", result.sidComponentNameSnapshot(),
				"first sid emission captures cd.getName() as the snapshot");
	}

	// ---- Disabled mode pass-through ----

	@Test
	void buildReleaseIdentifiers_disabledMode_doesNotStripCallerSidPurl() throws RelizaException {
		ComponentData cd = component(BelongsToOrganization.INTERNAL, "X");
		OrganizationData org = orgWith(SidPurlMode.DISABLED, null);

		// Tenant supplied a sid PURL directly — must pass through unchanged.
		List<TeaIdentifier> caller = List.of(teaPurl("pkg:sid/foo.example.com/X@1.0"));
		var result = service.buildReleaseIdentifiers(cd, org, "1.0", null, caller);

		List<TeaIdentifier> ids = result.identifiers();
		assertEquals(1, ids.size());
		assertEquals("pkg:sid/foo.example.com/X@1.0", ids.get(0).getIdValue());
		assertNull(result.sidComponentNameSnapshot(), "snapshot stays null when sid not platform-emitted");
	}

	// ---- EXTERNAL component vendor-sid round-trip ----

	@Test
	void buildReleaseIdentifiers_externalComponent_vendorSidRoundTripsViaCarryover() throws RelizaException {
		// EXTERNAL component with vendor sid PURL on cd.identifiers (no version).
		// Resolver returns enabled=false → strip rule no-op → carryover runs with skipSid=false
		// → vendor sid is version-stamped onto the release.
		ComponentData cd = component(BelongsToOrganization.EXTERNAL, "Flyway");
		cd.setIdentifiers(new LinkedList<>(List.of(
				teaPurl("pkg:sid/flywaydb.org/flyway"))));
		OrganizationData org = orgEnabledStrict("reliza.io");

		var result = service.buildReleaseIdentifiers(cd, org, "9.22.0", null, null);

		List<TeaIdentifier> ids = result.identifiers();
		assertEquals(1, ids.size());
		assertEquals("pkg:sid/flywaydb.org/flyway@9.22.0", ids.get(0).getIdValue(),
				"vendor sid version-stamped via carryover (skipSid=false because resolver disabled this component)");
		assertNull(result.sidComponentNameSnapshot(),
				"snapshot stays null — platform did not author this sid");
	}

	@Test
	void buildReleaseIdentifiers_externalComponent_noPlatformSidEvenUnderStrict() throws RelizaException {
		// EXTERNAL hard ceiling under STRICT — no platform sid appended at all.
		ComponentData cd = component(BelongsToOrganization.EXTERNAL, "Flyway");
		OrganizationData org = orgEnabledStrict("reliza.io");

		var result = service.buildReleaseIdentifiers(cd, org, "9.22.0", null, null);

		assertTrue(result.identifiers().stream()
				.noneMatch(t -> t.getIdValue() != null && t.getIdValue().startsWith("pkg:sid/reliza.io/")),
				"platform must not stamp its own authority on EXTERNAL components");
	}

	// ---- Snapshot semantics ----

	@Test
	void buildReleaseIdentifiers_existingSnapshot_honored() throws RelizaException {
		// Component renamed after release creation; orchestrator must use the existing
		// snapshot, not the current cd.getName().
		ComponentData cd = component(BelongsToOrganization.INTERNAL, "RenamedNow");
		OrganizationData org = orgEnabledStrict("reliza.io");

		var result = service.buildReleaseIdentifiers(cd, org, "1.0", "OriginalName", null);

		assertEquals("OriginalName", result.sidComponentNameSnapshot());
		assertTrue(result.identifiers().stream()
				.anyMatch(t -> "pkg:sid/reliza.io/OriginalName@1.0".equals(t.getIdValue())),
				"sid PURL must be built from the snapshot, not the current name");
	}

	@Test
	void buildReleaseIdentifiers_freshSnapshot_capturedFromComponentName() throws RelizaException {
		ComponentData cd = component(BelongsToOrganization.INTERNAL, "ReARM Backend");
		OrganizationData org = orgEnabledStrict("reliza.io");

		var result = service.buildReleaseIdentifiers(cd, org, "25.04.5", null, null);

		assertEquals("ReARM Backend", result.sidComponentNameSnapshot());
	}

	@Test
	void buildReleaseIdentifiers_resolverDisabled_snapshotPreserved() throws RelizaException {
		// Existing snapshot must be carried through unchanged when the resolver currently
		// returns disabled (e.g. component flipped to EXTERNAL after a previous emission).
		ComponentData cd = component(BelongsToOrganization.EXTERNAL, "Flipped");
		OrganizationData org = orgEnabledStrict("reliza.io");

		var result = service.buildReleaseIdentifiers(cd, org, "1.0", "OldSnapshot", null);

		assertEquals("OldSnapshot", result.sidComponentNameSnapshot(),
				"existing snapshot must not be cleared just because emission is now disabled");
	}

	// ---- Fail-loud on invalid resolved segments ----

	@Test
	void buildReleaseIdentifiers_strictWithoutSegments_throws() {
		ComponentData cd = component(BelongsToOrganization.INTERNAL, "X");
		// STRICT but org segments are null → resolver returns enabled=true with null segments
		// → orchestrator's validateAuthoritySegments check throws.
		OrganizationData org = orgWith(SidPurlMode.ENABLED_STRICT, null);

		assertThrows(RelizaException.class,
				() -> service.buildReleaseIdentifiers(cd, org, "1.0", null, null));
	}

	// ---- deriveCarryoverPurl skipSid branches ----

	@Test
	void deriveCarryoverPurl_skipSidTrue_skipsSidPurlOnComponent() {
		ComponentData cd = component(BelongsToOrganization.INTERNAL, "X");
		cd.setIdentifiers(new LinkedList<>(List.of(
				teaPurl("pkg:sid/vendor.example/X@—"),
				teaPurl("pkg:maven/io.example/x"))));

		var carry = service.deriveCarryoverPurl(cd, "1.0", true);

		assertTrue(carry.isPresent());
		assertEquals("pkg:maven/io.example/x@1.0", carry.get().getIdValue(),
				"skipSid=true must skip pkg:sid/... and version-stamp the next eligible PURL");
	}

	@Test
	void deriveCarryoverPurl_skipSidFalse_preservesSidPurlOnComponent() {
		ComponentData cd = component(BelongsToOrganization.INTERNAL, "X");
		cd.setIdentifiers(new LinkedList<>(List.of(
				teaPurl("pkg:sid/vendor.example/X"))));

		var carry = service.deriveCarryoverPurl(cd, "1.0", false);

		assertTrue(carry.isPresent());
		assertEquals("pkg:sid/vendor.example/X@1.0", carry.get().getIdValue(),
				"skipSid=false must preserve sid PURL and version-stamp it (vendor identity round-trip)");
	}

	@Test
	void deriveCarryoverPurl_emptyIdentifiers_returnsEmpty() {
		ComponentData cd = component(BelongsToOrganization.INTERNAL, "X");
		// cd.getIdentifiers() returns an empty new LinkedList by default.

		assertTrue(service.deriveCarryoverPurl(cd, "1.0", true).isEmpty());
	}

	// ---- Suppress unused-warning: keep API surface used in helpers below ----

	@SuppressWarnings("unused")
	private static void unused() {
		any();
		anySet();
	}

	// ---- Helpers ----

	private static ComponentData component(BelongsToOrganization isInternal, String name) {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setName(name);
		cd.setIsInternal(isInternal);
		return cd;
	}

	private static OrganizationData orgEnabledStrict(String authority) {
		return orgWith(SidPurlMode.ENABLED_STRICT, List.of(authority));
	}

	private static OrganizationData orgWith(SidPurlMode mode, List<String> segments) {
		OrganizationData od = new OrganizationData();
		od.setUuid(UUID.randomUUID());
		OrganizationData.Settings s = new OrganizationData.Settings();
		s.setSidPurlMode(mode);
		s.setSidAuthoritySegments(segments);
		od.setSettings(s);
		return od;
	}

	private static TeaIdentifier teaPurl(String value) {
		TeaIdentifier ti = new TeaIdentifier();
		ti.setIdType(TeaIdentifierType.PURL);
		ti.setIdValue(value);
		return ti;
	}

	private static TeaIdentifier teaCpe(String value) {
		TeaIdentifier ti = new TeaIdentifier();
		ti.setIdType(TeaIdentifierType.CPE);
		ti.setIdValue(value);
		return ti;
	}
}
