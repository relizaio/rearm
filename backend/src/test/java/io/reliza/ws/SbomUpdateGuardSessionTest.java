/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.GraphQLError;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;

/**
 * SCORE-52 T-14 (ADR-5): an agent session's upload is checked with the calling key's own rights.
 * An agent-only key uploads new serialNumbers and is refused a higher version of A's S; a key that
 * also writes A passes it, and the session row it leaves blocks nobody. A serialNumber only sessions
 * hold, also nested in a session artifact, needs organization-wide write. A verbatim copy of a
 * session's document by B is a copy, and B's next version of it is refused, also in place.
 */
class SbomUpdateGuardSessionTest extends SbomUpdateGuardProgrammaticTestBase {

	@Autowired private AgentDataFetcher agentDataFetcher;
	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private record Agent(ApiKey key, UUID session) {}

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	/** A Free Form agent key with AGENT at {@code orgType} on the organization, write on {@code components}, and a session of it. */
	private Agent agent(World w, PermissionType orgType, UUID... components) throws RelizaException {
		UUID org = w.org().getUuid();
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org, UUID.randomUUID().toString(),
				"score52 agent", WU);
		List<PermissionDto> perms = new ArrayList<>();
		perms.add(new PermissionDto(org, PermissionScope.ORGANIZATION, org, orgType, Set.of(PermissionFunction.AGENT),
				null));
		for (UUID c : components) {
			perms.add(new PermissionDto(org, PermissionScope.COMPONENT, c, PermissionType.READ_WRITE,
					Set.of(PermissionFunction.RESOURCE), null));
		}
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, perms, WU);
		key = apiKeyService.getApiKey(key.getUuid()).orElseThrow();
		UUID identity = agentIdentityService.findOrRegisterByCredential(org, IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agentData = agentService.findOrRegisterRootAgent(org, identity, "score52-" + UUID.randomUUID(), null,
				null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org, agentData.getUuid(), key.getUuid(),
				"score52-" + UUID.randomUUID(), "score52 session", null, null, WU);
		return new Agent(key, session.getUuid());
	}

	private AgentSessionData sessionUpload(Agent a, List<Map<String, Object>> artifacts) throws Exception {
		return agentDataFetcher.sessionAddArtifactProgrammatic(env(a.key(), "addArtifact",
				map("sessionUuid", a.session().toString(), "artifacts", artifacts)));
	}

	private List<UUID> sessionArtifacts(Agent a) {
		return agentSessionService.getSessionData(a.session()).orElseThrow().getArtifacts();
	}

	@Test
	void anAgentOnlyKeyUploadsNewSerialNumbersAndIsRefusedAsSerialNumber() throws Exception {
		World w = world();
		Agent agentOnly = agent(w, PermissionType.ESSENTIAL_READ);
		UUID fresh = UUID.randomUUID();
		sessionUpload(agentOnly, entries(cdx(urn(fresh), 1)));
		assertEquals(1, rebom.versionsStored(fresh));

		assertRefusedUpload(() -> sessionUpload(agentOnly, entries(cdx(urn(w.serial()), 2))));
	}

	@Test
	void aKeyThatWritesAPassesAndItsSessionRowBlocksNobody() throws Exception {
		World w = world();
		Agent writesA = agent(w, PermissionType.ESSENTIAL_READ, w.a().getUuid());
		sessionUpload(writesA, entries(cdx(urn(w.serial()), 2)));
		assertEquals(1, sessionArtifacts(writesA).size());
		assertFalse(isCopy(sessionArtifacts(writesA).get(0)));

		progUpload(componentKey(w, w.a()), w.releaseA(), cdx(urn(w.serial()), 3));
		assertEquals(2, rebom.versionsStored(w.serial()));
	}

	@Test
	void aSerialNumberOnlySessionsHoldNeedsOrganizationWideWrite() throws Exception {
		World w = world();
		Agent agentOnly = agent(w, PermissionType.ESSENTIAL_READ);
		UUID s = UUID.randomUUID();
		sessionUpload(agentOnly, entries(cdx(urn(s), 1)));
		sessionUpload(agentOnly, new ArrayList<>(List.of(container(fresh(), entry(cdx(urn(s), 1))))));
		assertEquals(2, rowsOf(w.org().getUuid(), s).size());

		assertRefusedUpload(() -> sessionUpload(agentOnly, entries(cdx(urn(s), 2))));
		sessionUpload(agent(w, PermissionType.READ_WRITE), entries(cdx(urn(s), 2)));
		assertEquals(2, rebom.versionsStored(s));
	}

	@Test
	void bsVerbatimCopyOfASessionDocumentIsACopyAndItsNextVersionIsRefused() throws Exception {
		World w = world();
		Agent agentOnly = agent(w, PermissionType.ESSENTIAL_READ);
		UUID s = UUID.randomUUID();
		sessionUpload(agentOnly, entries(cdx(urn(s), 1)));
		ApiKey keyB = componentKey(w, w.b());

		progUpload(keyB, w.releaseB(), cdx(urn(s), 1));
		UUID copy = addedRow(w.releaseB(), List.of(w.y()));
		assertTrue(isCopy(copy));
		assertRefusedUpload(() -> progUpload(keyB, w.releaseB(), cdx(urn(s), 2)));

		GraphQLError error = onlyError(manualUpdate(writerOn(w.org(), w.b().getUuid()), w.releaseB(), copy,
				BomFormat.CYCLONEDX, cdx(urn(s), 2)));
		assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, error.getExtensions().get("code"), error.toString());
	}
}
