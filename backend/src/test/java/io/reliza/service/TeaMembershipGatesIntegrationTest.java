/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.common.CommonVariables.OauthType;

/**
 * Task TEA-9 round 2, design 4.4 cases 43 to 45, 48 and 49: through the user GraphQL API against the
 * test database, a change of a component's perspectives and a perspective-row removal over its
 * members need an organization admin when they make a component resolve PUBLIC; the membership
 * dry-run answers them; an archived component takes no membership that widens it, for anyone; a
 * followed perspective of another organization is named by uuid only. Perspectives are ReARM Pro;
 * the class skips in CE.
 */
public class TeaMembershipGatesIntegrationTest extends TeaProfileGraphqlIntegrationTestBase {

	private static final String SET = "mutation($c: ID!, $p: [ID!]!) { setPerspectivesOnComponent(componentUuid: $c,"
			+ " perspectiveUuids: $p) { uuid } }";
	private static final String ADD = "mutation($c: ID!, $p: ID!) { addPerspectiveToComponent(componentUuid: $c,"
			+ " perspectiveUuid: $p) { uuid } }";
	private static final String MEMBERSHIP = "query($c: ID!, $p: [ID!]!) { teaMembershipExposureChange(componentUuid: $c,"
			+ " perspectiveUuids: $p) { before after path afterSource afterSourceObject publishedReleases"
			+ " widened { component name afterSource afterSourceObject } } }";
	private static final String EXPOSURE = "query($o: ID!, $s: TeaProfileScope!, $ob: ID, $p: TeaProfileInput) {"
			+ " teaProfileExposureChange(org: $o, scope: $s, object: $ob, profile: $p) { path"
			+ " widened { component name afterSource afterSourceObject } } }";

	@Autowired private GetComponentService getComponentService;

	private Organization org;
	private UserData admin;

	@BeforeEach
	void proOnly() throws RelizaException {
		assumeFalse(LicensingConstants.isOssEdition(), "TEA-9 membership gates need perspectives (ReARM Pro)");
		org = testInitializer.obtainOrganization();
		admin = admin(org);
	}

	private static List<String> ids(UUID... uuids) {
		return Arrays.stream(uuids).map(UUID::toString).toList();
	}

	private ExecutionResult set(UserData u, Component c, UUID... perspectives) {
		return exec(u, SET, "c", c.getUuid().toString(), "p", ids(perspectives));
	}

	private ExecutionResult add(UserData u, Component c, UUID perspective) {
		return exec(u, ADD, "c", c.getUuid().toString(), "p", perspective.toString());
	}

	private ExecutionResult membershipOf(UserData u, Component c, UUID... perspectives) {
		return exec(u, MEMBERSHIP, "c", c.getUuid().toString(), "p", ids(perspectives));
	}

	private Map<String, Object> membership(UserData u, Component c, UUID... perspectives) {
		return ok(membershipOf(u, c, perspectives), "teaMembershipExposureChange");
	}

	private Set<UUID> perspectivesOf(Component c) {
		Set<UUID> ps = getComponentService.getComponentData(c.getUuid()).orElseThrow().getPerspectives();
		return null == ps ? Set.of() : ps;
	}

	private Object effective(Component c) {
		return at(view(admin, org, "COMPONENT", c.getUuid()), "effective.profile.visibility");
	}

	private String nameOf(UUID perspective) {
		List<Map<String, Object>> ps = ok(exec(admin, "query($o: ID!) { perspectives(org: $o) { uuid name } }", "o",
				org.getUuid().toString()), "perspectives");
		return (String) ps.stream().filter(p -> perspective.toString().equals(p.get("uuid"))).findFirst().orElseThrow()
				.get("name");
	}

	private static Map<String, Object> follow(UUID perspective) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("mode", "FOLLOW_PERSPECTIVE");
		m.put("followedPerspective", perspective.toString());
		return m;
	}

	private UserData reader(Component c) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Tea " + tag, "tea9-" + tag + "@tea.io", true, List.of(org.getUuid()),
				"tea9-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.COMPONENT, c.getUuid(),
				PermissionType.READ_ONLY, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	@Test
	public void membershipNeedsAnOrganizationAdminWhenItResolvesPublic() throws RelizaException {
		// Case 43: P PUBLIC, Q and Q2 PRIVATE, no organization row; U is ADMIN on C only.
		UUID p = perspective(admin, org, "tea9-mem-pub");
		UUID q = perspective(admin, org, "tea9-mem-priv");
		UUID q2 = perspective(admin, org, "tea9-mem-priv2");
		ok(save(admin, org, "PERSPECTIVE", p, profile("visibility", "PUBLIC")), "saveTeaProfile");
		ok(save(admin, org, "PERSPECTIVE", q, profile()), "saveTeaProfile");
		ok(save(admin, org, "PERSPECTIVE", q2, profile()), "saveTeaProfile");
		Component c = component(org);
		UserData u = user(org, PermissionScope.COMPONENT, c.getUuid());

		ExecutionResult joined = set(u, c, p);
		failsWith(joined, "organization admin");
		failsWith(joined, nameOf(p));
		// TEA-14 row 51: the gated writer refuses under the lock, and the owner of the transaction rolls back for
		// the RelizaException, so the caller reads TEA-9's text and not a silent rollback
		assertFalse(joined.getErrors().toString().contains("rolled back"), joined.getErrors().toString());
		assertTrue(perspectivesOf(c).isEmpty(), "nothing written");
		ok(set(admin, c, p), "setPerspectivesOnComponent");
		assertEquals("PUBLIC", effective(c));

		ok(set(u, c, p, q), "setPerspectivesOnComponent");
		failsWith(set(u, c, p), "organization admin");
		assertEquals(Set.of(p, q), perspectivesOf(c));
		ok(set(admin, c, p), "setPerspectivesOnComponent");
		assertEquals("PUBLIC", effective(c));

		ok(set(admin, c, p, q), "setPerspectivesOnComponent");
		ok(save(admin, org, "COMPONENT", c.getUuid(), follow(q)), "saveTeaProfile");
		failsWith(set(u, c, p), "organization admin");
		assertEquals(Set.of(p, q), perspectivesOf(c), "the org admin's pinning holds");
		ok(set(admin, c, p), "setPerspectivesOnComponent");
		assertEquals("PUBLIC", effective(c), "the FOLLOW falls through to P");
		assertEquals(Boolean.TRUE, ok(delete(admin, org, "COMPONENT", c.getUuid()), "deleteTeaProfile"));

		ok(set(u, c), "setPerspectivesOnComponent");
		assertEquals("PRIVATE", effective(c), "narrowing needs no organization admin");

		ok(save(admin, org, "ORGANIZATION", null, profile("visibility", "PUBLIC")), "saveTeaProfile");
		ok(set(u, c, q2), "setPerspectivesOnComponent");
		failsWith(set(u, c), "the organization");
		assertEquals(Set.of(q2), perspectivesOf(c));
		ok(set(admin, c), "setPerspectivesOnComponent");
		assertEquals("PUBLIC", effective(c));
	}

	@Test
	public void theMembershipExposureQueryAnswers() throws RelizaException {
		// Case 44: READ on the component suffices.
		UUID p = perspective(admin, org, "tea9-mem-pub");
		ok(save(admin, org, "PERSPECTIVE", p, profile("visibility", "PUBLIC")), "saveTeaProfile");
		Component c = component(org);
		UserData r = reader(c);

		Map<String, Object> x = membership(r, c, p);
		assertEquals("PRIVATE", x.get("before"));
		assertEquals("PUBLIC", x.get("after"));
		assertEquals("MEMBERSHIP_RESOLVES_PUBLIC", x.get("path"));
		assertEquals("PERSPECTIVE", x.get("afterSource"));
		assertEquals(p.toString(), x.get("afterSourceObject"));
		assertEquals(0, x.get("publishedReleases"));
		assertEquals(List.of(), x.get("widened"));
		assertEquals("NONE", membership(r, c).get("path"), "the stored set");

		UUID unknown = UUID.randomUUID();
		failsWith(membershipOf(r, c, unknown), "Perspective not found: " + unknown);
		Component foreign = component(testInitializer.obtainOrganization());
		failsWith(membershipOf(admin, foreign, p), "not found");
		assertFalse(set(r, c, p).getErrors().isEmpty(), "READ does not set perspectives");
		assertTrue(perspectivesOf(c).isEmpty());
	}

	@Test
	public void aPerspectiveRowRemovalOverMembersNeedsAnOrganizationAdmin() throws RelizaException {
		// Case 45: org PRIVATE, P PUBLIC, Q PRIVATE; C16 in {P, Q} without a row, C17 FOLLOW Q.
		UUID p = perspective(admin, org, "tea9-mem-pub");
		UUID q = perspective(admin, org, "tea9-mem-priv");
		ok(save(admin, org, "PERSPECTIVE", p, profile("visibility", "PUBLIC")), "saveTeaProfile");
		ok(save(admin, org, "PERSPECTIVE", q, profile()), "saveTeaProfile");
		Component c16 = component(org);
		Component c17 = component(org);
		ok(set(admin, c16, p, q), "setPerspectivesOnComponent");
		ok(set(admin, c17, p, q), "setPerspectivesOnComponent");
		ok(save(admin, org, "COMPONENT", c17.getUuid(), follow(q)), "saveTeaProfile");
		UserData v = user(org, PermissionScope.PERSPECTIVE, q);

		ExecutionResult refused = delete(v, org, "PERSPECTIVE", q);
		failsWith(refused, "2 component(s)");
		failsWith(refused, "organization admin");
		String n16 = getComponentService.getComponentData(c16.getUuid()).orElseThrow().getName();
		String n17 = getComponentService.getComponentData(c17.getUuid()).orElseThrow().getName();
		assertTrue(refused.getErrors().toString().contains(n16) || refused.getErrors().toString().contains(n17),
				refused.getErrors().toString());
		assertTrue(teaProfileRepository.findByScopeObject(org.getUuid(), "PERSPECTIVE", q).isPresent(), "the row is kept");

		Map<String, Object> x = ok(exec(v, EXPOSURE, "o", org.getUuid().toString(), "s", "PERSPECTIVE", "ob",
				q.toString(), "p", null), "teaProfileExposureChange");
		assertEquals("REMOVAL_RESOLVES_PUBLIC", x.get("path"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> widened = (List<Map<String, Object>>) x.get("widened");
		assertEquals(Set.of(c16.getUuid().toString(), c17.getUuid().toString()),
				Set.copyOf(widened.stream().map(w -> (String) w.get("component")).toList()));
		assertTrue(widened.stream().allMatch(w -> "PERSPECTIVE".equals(w.get("afterSource"))
				&& p.toString().equals(w.get("afterSourceObject"))), widened.toString());

		assertEquals(Boolean.TRUE, ok(delete(admin, org, "PERSPECTIVE", q), "deleteTeaProfile"));
		assertEquals("PUBLIC", effective(c16));
		assertEquals("PUBLIC", effective(c17));

		ok(save(admin, org, "PERSPECTIVE", q, profile()), "saveTeaProfile");
		ok(save(admin, org, "COMPONENT", c16.getUuid(), profile("mode", "OVERRIDE")), "saveTeaProfile");
		ok(save(admin, org, "COMPONENT", c17.getUuid(), profile("mode", "OVERRIDE")), "saveTeaProfile");
		assertEquals(Boolean.TRUE, ok(delete(v, org, "PERSPECTIVE", q), "deleteTeaProfile"), "no member widens");
	}

	@Test
	public void archivedComponentsTakeNoWideningMembership() throws RelizaException {
		// Case 48, for a component and a product, as the org admin.
		UUID p = perspective(admin, org, "tea9-mem-pub");
		UUID q = perspective(admin, org, "tea9-mem-priv");
		ok(save(admin, org, "PERSPECTIVE", p, profile("visibility", "PUBLIC")), "saveTeaProfile");
		ok(save(admin, org, "PERSPECTIVE", q, profile()), "saveTeaProfile");
		for (ComponentType type : List.of(ComponentType.COMPONENT, ComponentType.PRODUCT)) {
			Component m = componentService.createComponent("tea9-arch-" + UUID.randomUUID(), org.getUuid(), type,
					"semver", "Branch.Micro", null, WU);
			componentService.archiveComponent(m.getUuid(), WU);
			failsWith(set(admin, m, p), "archived");
			failsWith(membershipOf(admin, m, p), "archived");
			assertTrue(perspectivesOf(m).isEmpty());
			ok(set(admin, m, q), "setPerspectivesOnComponent");
			assertEquals(Set.of(q), perspectivesOf(m));
		}
		assertNull(orgTeaUuid(admin, org), "no TEA id minted");
	}

	@Test
	public void aForeignFollowedPerspectiveIsNamedByUuidOnly() throws RelizaException {
		// Case 49 (design 3.13).
		Organization other = testInitializer.obtainOrganization();
		UserData otherAdmin = admin(other);
		String secret = "tea9-secret-" + UUID.randomUUID();
		UUID theirs = UUID.fromString((String) ((Map<?, ?>) ok(exec(otherAdmin,
				"mutation($o: ID!, $n: String!) { createPerspective(org: $o, name: $n) { uuid } }", "o",
				other.getUuid().toString(), "n", secret), "createPerspective")).get("uuid"));
		Component c = component(org);
		ExecutionResult r = save(admin, org, "COMPONENT", c.getUuid(), follow(theirs));
		failsWith(r, theirs.toString());
		assertFalse(r.getErrors().toString().contains(secret), r.getErrors().toString());
	}

	// ---- TEA-14 decision 3: addPerspectiveToComponent carries TEA-9's gate (architecture rows 19 to 21) ----

	@Test
	public void addPerspectiveToComponentThatResolvesPublicNeedsOrgAdmin() throws RelizaException {
		UUID p = perspective(admin, org, "tea14-add-pub");
		ok(save(admin, org, "PERSPECTIVE", p, profile("visibility", "PUBLIC")), "saveTeaProfile");
		Component c = component(org);
		UserData u = user(org, PermissionScope.COMPONENT, c.getUuid());

		ExecutionResult refused = add(u, c, p);
		failsWith(refused, "Only an organization admin can change the perspectives of component");
		failsWith(refused, nameOf(p));
		assertFalse(refused.getErrors().toString().contains("rolled back"), refused.getErrors().toString());
		assertTrue(perspectivesOf(c).isEmpty(), "nothing written");

		ok(add(admin, c, p), "addPerspectiveToComponent");
		assertEquals(Set.of(p), perspectivesOf(c));
		assertEquals("PUBLIC", effective(c));
	}

	@Test
	public void addPerspectiveToComponentOfAnArchivedComponentThatWouldResolveEnabledIsRefusedForEveryone()
			throws RelizaException {
		UUID p = perspective(admin, org, "tea14-add-enabled");
		ok(save(admin, org, "PERSPECTIVE", p, profile("publishing", "ENABLED")), "saveTeaProfile");
		Component m = componentService.createComponent("tea14-arch-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		componentService.archiveComponent(m.getUuid(), WU);

		failsWith(add(admin, m, p), "archived");
		assertTrue(perspectivesOf(m).isEmpty());
	}

	@Test
	public void addingAPerspectiveTheComponentAlreadyHasWritesNothing() throws RelizaException {
		UUID p = perspective(admin, org, "tea14-add-held");
		Component c = component(org);
		ok(set(admin, c, p), "setPerspectivesOnComponent");
		ok(save(admin, org, "PERSPECTIVE", p, profile("visibility", "PUBLIC")), "saveTeaProfile");
		UserData u = user(org, PermissionScope.COMPONENT, c.getUuid());
		int revision = getComponentService.getComponent(c.getUuid()).orElseThrow().getRevision();

		ok(add(u, c, p), "addPerspectiveToComponent");

		assertEquals(Set.of(p), perspectivesOf(c));
		assertEquals(revision, getComponentService.getComponent(c.getUuid()).orElseThrow().getRevision(),
				"the delta is empty: no save, and the gate is not consulted");
	}
}
