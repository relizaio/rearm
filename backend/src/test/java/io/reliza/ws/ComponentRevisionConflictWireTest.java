/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.GetComponentService;

/**
 * TPF-61 (architecture-2 4.2): the stale-save refusal over the real {@code updateComponent} mutation, with
 * a signed-in person, real permissions and the real {@code GraphQLExceptionHandlers}: the message, the
 * BAD_REQUEST classification and the extensions a page switches on; the resend; no revision, no check;
 * {@code Component.revision} on the read; and authorization before the check (threat model, row 19).
 */
class ComponentRevisionConflictWireTest extends AgentBoardEnforcementTestBase {

	static final String READ = "query($c: ID!) { component(componentUuid: $c) { uuid revision featureBranchVersioning } }";
	static final String SAVE = "mutation($i: UpdateComponentInput!) { updateComponent(component: $i) { uuid revision featureBranchVersioning } }";

	@Autowired GetComponentService getComponentService;

	World w;
	UserData writer;
	UserData other;
	UserData reader;
	Component comp;
	String name;

	@BeforeEach
	void fixtures() throws RelizaException {
		w = world();
		writer = member(w, PermissionType.READ_WRITE);
		other = member(w, PermissionType.READ_WRITE);
		reader = member(w, PermissionType.READ_ONLY);
		name = "tpf61-" + UUID.randomUUID().toString().substring(0, 8);
		comp = componentService.createComponent(name, w.org().getUuid(), ComponentType.COMPONENT, "semver",
				"Branch.Micro", null, WU);
	}

	// ---------- helpers ----------

	private int read(UserData as) {
		Map<String, Object> c = data(asPerson(as, READ, vars("c", comp.getUuid().toString())), "component");
		return ((Number) c.get("revision")).intValue();
	}

	/** SAVE as {@code as}: the stored uuid and name, the given feature-branch schema and, when not null, the expected revision. */
	private ExecutionResult save(UserData as, String featureBranchVersioning, Integer expectedRevision) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("uuid", comp.getUuid().toString());
		input.put("name", name);
		input.put("featureBranchVersioning", featureBranchVersioning);
		if (null != expectedRevision) input.put("expectedRevision", expectedRevision);
		return asPerson(as, SAVE, vars("i", input));
	}

	private Component storedRow() {
		return getComponentService.getComponent(comp.getUuid()).orElseThrow();
	}

	private ComponentData storedData() {
		return getComponentService.getComponentData(comp.getUuid()).orElseThrow();
	}

	// ---------- rows ----------

	/** Rows 15 and 16: a stale save is refused with the code and who changed it when; the resend with the reported revision saves. */
	@Test
	@Timeout(60)
	void aStaleSaveIsRefusedWithTheConflictExtensionsAndTheResendSaves() {
		int r0 = read(writer);
		assertTrue(ok(save(other, "YYYY.0M.Branch.Micro", null)));
		ExecutionResult refused = save(writer, "semver", r0);

		// Row 15
		assertEquals(1, refused.getErrors().size(), refused.getErrors().toString());
		GraphQLError err = refused.getErrors().get(0);
		assertEquals("Component was changed since it was read: expected revision " + r0 + ", current revision " + (r0 + 1),
				err.getMessage());
		assertEquals("BAD_REQUEST", String.valueOf(err.getErrorType()));
		Map<String, Object> ext = err.getExtensions();
		assertEquals(Set.of("code", "revision", "lastUpdatedBy", "lastUpdatedType", "lastUpdatedDate"), ext.keySet());
		assertEquals("COMPONENT_REVISION_CONFLICT", ext.get("code"));
		assertEquals(r0 + 1, ((Number) ext.get("revision")).intValue());
		assertEquals(other.getUuid().toString(), ext.get("lastUpdatedBy"));
		assertEquals("MANUAL", ext.get("lastUpdatedType"));
		assertFalse(ext.containsKey("lastUpdatedActor"));
		Component row = storedRow();
		assertEquals(row.getLastUpdatedDate().toInstant(), OffsetDateTime.parse((String) ext.get("lastUpdatedDate")).toInstant());
		Map<?, ?> d = refused.getData();
		assertTrue(null == d || null == d.get("updateComponent"), "no component on a refusal: " + d);
		assertEquals("YYYY.0M.Branch.Micro", storedData().getFeatureBranchVersioning());
		assertEquals(r0 + 1, row.getRevision());

		// Row 16
		ExecutionResult resend = save(writer, "semver", r0 + 1);
		Map<String, Object> saved = data(resend, "updateComponent");
		assertEquals(r0 + 2, ((Number) saved.get("revision")).intValue());
		assertEquals("semver", saved.get("featureBranchVersioning"));
	}

	/** Row 17: a save without expectedRevision is not checked. */
	@Test
	@Timeout(60)
	void aSaveWithoutARevisionIsNotChecked() {
		assertTrue(ok(save(other, "Branch.Micro", null)));
		ExecutionResult r = save(writer, "semver", null);
		assertTrue(ok(r), r.getErrors().toString());
		assertEquals("semver", storedData().getFeatureBranchVersioning());
	}

	/** Row 18: the read serves the stored row's revision. */
	@Test
	@Timeout(60)
	void theReadServesTheStoredRevision() {
		assertTrue(ok(save(other, "Branch.Micro", null)));
		assertEquals(storedRow().getRevision(), read(writer));
	}

	/** Row 19 (threat model): a caller without write is refused before the check, with no conflict extensions. */
	@Test
	@Timeout(60)
	void aCallerWithoutWriteIsRefusedBeforeTheCheck() {
		int stale = read(writer);
		assertTrue(ok(save(other, "YYYY.0M.Branch.Micro", null)));
		ComponentData before = storedData();
		int revisionBefore = storedRow().getRevision();
		ExecutionResult r = save(reader, "semver", stale);
		assertEquals(1, r.getErrors().size(), r.getErrors().toString());
		GraphQLError err = r.getErrors().get(0);
		assertEquals("Not authorized", err.getMessage());
		assertTrue(null == err.getExtensions() || !err.getExtensions().containsKey("code"), String.valueOf(err.getExtensions()));
		assertEquals(before, storedData());
		assertEquals(revisionBefore, storedRow().getRevision());
	}
}
