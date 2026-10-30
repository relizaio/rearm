/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;

/**
 * Task TEA-9, design 4.3: through the user GraphQL API against the test database, every path to
 * PUBLIC needs an organization admin (a PUBLIC row, a FOLLOW or a removal that makes the object
 * resolve PUBLIC), the exposure dry-run answers each path, and an archived component or product
 * takes only the row that hides it. Perspectives are ReARM Pro; the class skips in CE.
 */
public class TeaProfileGatesIntegrationTest extends TeaProfileGraphqlIntegrationTestBase {

	private static final String STORED = "query($o: ID!, $s: TeaProfileScope!, $ob: ID) {"
			+ " teaProfileEditorView(org: $o, scope: $s, object: $ob) { stored { uuid mode followedPerspective visibility }"
			+ " effective { status source sourceObject profile { visibility } } } }";
	private static final String EXPOSURE = "query($o: ID!, $s: TeaProfileScope!, $ob: ID, $p: TeaProfileInput) {"
			+ " teaProfileExposureChange(org: $o, scope: $s, object: $ob, profile: $p) { before after path afterSource"
			+ " afterSourceObject publishedReleases } }";

	private Organization org;
	private UserData admin;

	@BeforeEach
	void proOnly() throws RelizaException {
		assumeFalse(LicensingConstants.isOssEdition(), "TEA-9 gates need perspectives (ReARM Pro)");
		org = testInitializer.obtainOrganization();
		admin = admin(org);
	}

	private static Map<String, Object> follow(UUID perspective) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("mode", "FOLLOW_PERSPECTIVE");
		m.put("followedPerspective", perspective.toString());
		return m;
	}

	private static Map<String, Object> overridePrivate() {
		return profile("mode", "OVERRIDE");
	}

	private Map<String, Object> stored(UserData u, String scope, UUID object) {
		return ok(exec(u, STORED, "o", org.getUuid().toString(), "s", scope, "ob",
				null == object ? null : object.toString()), "teaProfileEditorView");
	}

	private ExecutionResult exposureOf(UserData u, String scope, UUID object, Map<String, Object> p) {
		return exec(u, EXPOSURE, "o", org.getUuid().toString(), "s", scope, "ob", null == object ? null : object.toString(),
				"p", p);
	}

	private Map<String, Object> exposure(UserData u, String scope, UUID object, Map<String, Object> p) {
		return ok(exposureOf(u, scope, object, p), "teaProfileExposureChange");
	}

	private Component create(ComponentType type) throws RelizaException {
		return componentService.createComponent("tea9-" + UUID.randomUUID(), org.getUuid(), type, "semver",
				"Branch.Micro", null, WU);
	}

	private String nameOf(UUID perspective) {
		List<Map<String, Object>> ps = ok(exec(admin, "query($o: ID!) { perspectives(org: $o) { uuid name } }", "o",
				org.getUuid().toString()), "perspectives");
		return (String) ps.stream().filter(p -> perspective.toString().equals(p.get("uuid"))).findFirst().orElseThrow()
				.get("name");
	}

	@Test
	public void followAndRemovalNeedAnOrganizationAdminWhenTheyResolvePublic() throws RelizaException {
		UUID p = perspective(admin, org, "tea9-pub");
		UUID q = perspective(admin, org, "tea9-priv");
		ok(save(admin, org, "PERSPECTIVE", p, profile("visibility", "PUBLIC")), "saveTeaProfile");
		ok(save(admin, org, "PERSPECTIVE", q, profile()), "saveTeaProfile");
		Component c = component(org);
		perspectivesOf(admin, c, p, q);
		UserData u = user(org, PermissionScope.COMPONENT, c.getUuid());

		Map<String, Object> override = ok(save(u, org, "COMPONENT", c.getUuid(), overridePrivate()), "saveTeaProfile");
		failsWith(save(u, org, "COMPONENT", c.getUuid(), follow(p)), "organization admin");
		failsWith(save(u, org, "COMPONENT", c.getUuid(), follow(p)), nameOf(p));
		assertEquals("OVERRIDE", at(stored(u, "COMPONENT", c.getUuid()), "stored.mode"));
		assertEquals(override.get("uuid"), at(stored(u, "COMPONENT", c.getUuid()), "stored.uuid"));

		ok(save(u, org, "COMPONENT", c.getUuid(), follow(q)), "saveTeaProfile");
		failsWith(save(u, org, "COMPONENT", c.getUuid(), follow(p)), "organization admin");
		assertEquals(q.toString(), at(stored(u, "COMPONENT", c.getUuid()), "stored.followedPerspective"));

		ok(save(admin, org, "COMPONENT", c.getUuid(), follow(p)), "saveTeaProfile");
		Map<String, Object> followed = stored(admin, "COMPONENT", c.getUuid());
		assertEquals("FOLLOWED_PERSPECTIVE", at(followed, "effective.source"));
		assertEquals("PUBLIC", at(followed, "effective.profile.visibility"));

		ok(save(u, org, "COMPONENT", c.getUuid(), overridePrivate()), "saveTeaProfile");
		// With both perspective rows the parent is a CONFLICT (concealed, never PUBLIC): the removal widens
		// nothing. Without q's row the parent is p's PUBLIC profile, and the removal is gated.
		ok(delete(admin, org, "PERSPECTIVE", q), "deleteTeaProfile");
		failsWith(delete(u, org, "COMPONENT", c.getUuid()), "remove");
		assertEquals("OVERRIDE", at(stored(u, "COMPONENT", c.getUuid()), "stored.mode"));
		assertEquals(Boolean.TRUE, ok(delete(admin, org, "COMPONENT", c.getUuid()), "deleteTeaProfile"));

		Component d = component(org);
		UserData dAdmin = user(org, PermissionScope.COMPONENT, d.getUuid());
		ok(save(dAdmin, org, "COMPONENT", d.getUuid(), overridePrivate()), "saveTeaProfile");
		ok(save(admin, org, "ORGANIZATION", null, profile("visibility", "PUBLIC")), "saveTeaProfile");
		failsWith(delete(dAdmin, org, "COMPONENT", d.getUuid()), "the organization");
		assertEquals(Boolean.TRUE, ok(delete(admin, org, "COMPONENT", d.getUuid()), "deleteTeaProfile"));
	}

	@Test
	public void aFollowOnAnAlreadyPublicComponentIsNotWidening() throws RelizaException {
		UUID p = perspective(admin, org, "tea9-pub");
		ok(save(admin, org, "PERSPECTIVE", p, profile("visibility", "PUBLIC")), "saveTeaProfile");
		Component c = component(org);
		perspectivesOf(admin, c, p);
		UserData u = user(org, PermissionScope.COMPONENT, c.getUuid());
		ok(save(u, org, "COMPONENT", c.getUuid(), follow(p)), "saveTeaProfile");
		assertEquals("FOLLOWED_PERSPECTIVE", at(stored(u, "COMPONENT", c.getUuid()), "effective.source"));
	}

	@Test
	public void theExposureQueryAnswersEveryPath() throws RelizaException {
		UUID p = perspective(admin, org, "tea9-pub");
		ok(save(admin, org, "PERSPECTIVE", p, profile("visibility", "PUBLIC")), "saveTeaProfile");
		Component c = component(org);
		perspectivesOf(admin, c, p);
		UserData u = user(org, PermissionScope.COMPONENT, c.getUuid());
		ok(save(u, org, "COMPONENT", c.getUuid(), overridePrivate()), "saveTeaProfile");

		Map<String, Object> f = exposure(u, "COMPONENT", c.getUuid(), follow(p));
		assertEquals("PRIVATE", f.get("before"));
		assertEquals("PUBLIC", f.get("after"));
		assertEquals("FOLLOW_RESOLVES_PUBLIC", f.get("path"));
		assertEquals("FOLLOWED_PERSPECTIVE", f.get("afterSource"));
		assertEquals(p.toString(), f.get("afterSourceObject"));
		assertEquals(0, f.get("publishedReleases"));

		Map<String, Object> r = exposure(u, "COMPONENT", c.getUuid(), null);
		assertEquals("REMOVAL_RESOLVES_PUBLIC", r.get("path"));
		assertEquals("PERSPECTIVE", r.get("afterSource"));
		assertEquals("ROW_PUBLIC", exposure(u, "COMPONENT", c.getUuid(),
				profile("mode", "OVERRIDE", "visibility", "PUBLIC")).get("path"));
		assertEquals("NONE", exposure(u, "COMPONENT", c.getUuid(), overridePrivate()).get("path"));

		ok(save(admin, org, "COMPONENT", c.getUuid(), follow(p)), "saveTeaProfile");
		Map<String, Object> again = exposure(u, "COMPONENT", c.getUuid(), follow(p));
		assertEquals("PUBLIC", again.get("before"));
		assertEquals("NONE", again.get("path"));

		failsWith(exposureOf(u, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE", "minimumLifecycle", "DRAFT")),
				"too low");
		Organization other = testInitializer.obtainOrganization();
		Component foreign = component(other);
		failsWith(exposureOf(admin, "COMPONENT", foreign.getUuid(), overridePrivate()), "not found");
		failsWith(exposureOf(admin, "ORGANIZATION", null, null), "cannot be deleted");
	}

	@Test
	public void aPerspectiveRowRemovalUnderAPublicOrganizationNeedsAnOrganizationAdmin() throws RelizaException {
		UUID p = perspective(admin, org, "tea9-priv");
		ok(save(admin, org, "ORGANIZATION", null, profile("visibility", "PUBLIC")), "saveTeaProfile");
		ok(save(admin, org, "PERSPECTIVE", p, profile()), "saveTeaProfile");
		UserData perspectiveAdmin = user(org, PermissionScope.PERSPECTIVE, p);
		assertEquals("REMOVAL_RESOLVES_PUBLIC", exposure(perspectiveAdmin, "PERSPECTIVE", p, null).get("path"));
		failsWith(delete(perspectiveAdmin, org, "PERSPECTIVE", p), "organization admin");
		assertEquals(Boolean.TRUE, ok(delete(admin, org, "PERSPECTIVE", p), "deleteTeaProfile"));
	}

	@Test
	public void archivedComponentsOnlyHide() throws RelizaException {
		UUID p = perspective(admin, org, "tea9-arch");
		Component a = create(ComponentType.COMPONENT);
		perspectivesOf(admin, a, p);
		Component b = create(ComponentType.PRODUCT);
		componentService.archiveComponent(a.getUuid(), WU);
		componentService.archiveComponent(b.getUuid(), WU);

		failsWith(save(admin, org, "COMPONENT", a.getUuid(), profile("mode", "OVERRIDE", "publishing", "ENABLED")),
				"archived");
		assertNull(orgTeaUuid(admin, org), "an archived component's save mints nothing");
		failsWith(save(admin, org, "COMPONENT", a.getUuid(), profile("mode", "OVERRIDE", "visibility", "PUBLIC")),
				"archived");
		failsWith(save(admin, org, "COMPONENT", a.getUuid(), follow(p)), "archived");
		failsWith(exposureOf(admin, "COMPONENT", a.getUuid(), profile("mode", "OVERRIDE", "visibility", "PUBLIC")),
				"archived");
		ok(save(admin, org, "COMPONENT", a.getUuid(), overridePrivate()), "saveTeaProfile");

		failsWith(save(admin, org, "COMPONENT", b.getUuid(), profile("mode", "OVERRIDE", "publishing", "ENABLED")),
				"archived");
		ok(save(admin, org, "COMPONENT", b.getUuid(), overridePrivate()), "saveTeaProfile");
		assertNull(orgTeaUuid(admin, org));

		ok(save(admin, org, "ORGANIZATION", null, profile("publishing", "ENABLED", "visibility", "PUBLIC")),
				"saveTeaProfile");
		assertTrue(null != orgTeaUuid(admin, org), "the org row mints");
		failsWith(delete(admin, org, "COMPONENT", a.getUuid()), "archived");
		ok(save(admin, org, "ORGANIZATION", null, profile()), "saveTeaProfile");
		assertEquals(Boolean.TRUE, ok(delete(admin, org, "COMPONENT", a.getUuid()), "deleteTeaProfile"));

		List<Map<String, Object>> rows = ok(exec(admin, LIST, "o", org.getUuid().toString()), "teaProfilesOfOrg");
		assertTrue(rows.stream().anyMatch(r -> b.getUuid().toString().equals(r.get("object"))), rows.toString());
	}
}
