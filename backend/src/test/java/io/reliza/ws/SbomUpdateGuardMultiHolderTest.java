/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ApiKey;
import io.reliza.model.Component;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;

/**
 * SCORE-52 T-12 (ADR-3 step 4): S held by A and B. A key with write on A only is refused, one with
 * write on A and B passes, an organization-wide key passes without reading anything for the rule;
 * an organization admin passes through {@code addArtifactManual} and a user with write on A only is
 * refused there. A listing of S by a release of another organization, or of the external
 * organization, is a holder no principal of this organization writes: a key with write on A and B
 * is refused, an organization-wide key passes.
 */
class SbomUpdateGuardMultiHolderTest extends SbomUpdateGuardProgrammaticTestBase {

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	/** The world, with B's release also listing an authorizing row of S. */
	private World heldByAAndB() throws Exception {
		World w = world();
		listDirectly(w.releaseB(), bomArtifact(w.org().getUuid(), w.serial(), "1"));
		return w;
	}

	@Test
	void aKeyNeedsWriteOnEveryHolder() throws Exception {
		World w = heldByAAndB();
		ApiKey onA = freeformKey(w, null, w.a().getUuid());
		assertRefusedUpload(() -> progUpload(onA, w.releaseA(), cdx(urn(w.serial()), 2)));
		assertRefusedUpload(() -> progUpload(componentKey(w, w.a()), w.releaseA(), cdx(urn(w.serial()), 2)));

		progUpload(freeformKey(w, null, w.a().getUuid(), w.b().getUuid()), w.releaseA(), cdx(urn(w.serial()), 2));
		assertEquals(1, rebom.versionsStored(w.serial()));
	}

	@Test
	void anOrganizationWideKeyPassesWithoutReadingForTheRule() throws Exception {
		World w = heldByAAndB();
		clearInvocations(guard, rebomService);
		progUpload(orgRwKey(w), w.releaseB(), cdx(urn(w.serial()), 2));
		verify(guard, never()).holdersOfArtifact(any(), any());
		verify(rebomService, never()).latestStoredBomVersion(any(), any());
		assertEquals(1, rebom.versionsStored(w.serial()));
	}

	@Test
	void anOrganizationAdminPassesAddArtifactManualAndAWriterOnAOnlyIsRefused() throws Exception {
		World w = heldByAAndB();
		ExecutionResult refusedResult = manualUpload(writer(w.org(), PermissionScope.COMPONENT, w.a().getUuid()),
				w.releaseA(), cdx(urn(w.serial()), 2));
		assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, onlyError(refusedResult).getExtensions().get("code"));

		ExecutionResult r = manualUpload(member(w.org(), PermissionScope.ORGANIZATION, w.org().getUuid(),
				PermissionType.ADMIN), w.releaseA(), cdx(urn(w.serial()), 2));
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		assertEquals(1, rebom.versionsStored(w.serial()));
	}

	// (f)
	@Test
	void aListingOfAnotherOrganizationOrOfTheExternalOrganizationNeedsOrganizationWideWrite() throws Exception {
		for (boolean external : new boolean[] { false, true }) {
			World w = heldByAAndB();
			Organization other = testInitializer.obtainOrganization();
			Component foreign = component(other);
			UUID foreignRelease = release(other, foreign, List.of());
			listDirectly(foreignRelease, w.x());
			if (external) {
				jdbcTemplate.update("UPDATE rearm.releases SET record_data = jsonb_set(record_data, '{org}',"
						+ " to_jsonb(?::text)) WHERE uuid = ?", CommonVariables.EXTERNAL_PROJ_ORG_UUID.toString(),
						foreignRelease);
			}
			ApiKey onAAndB = freeformKey(w, null, w.a().getUuid(), w.b().getUuid());
			assertRefusedUpload(() -> progUpload(onAAndB, w.releaseA(), cdx(urn(w.serial()), 2)));

			progUpload(orgRwKey(w), w.releaseA(), cdx(urn(w.serial()), 2));
			assertEquals(1, rebom.versionsStored(w.serial()), external ? "external" : "another organization");
		}
	}
}
