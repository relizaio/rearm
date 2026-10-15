/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.model.AgentData;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.ApiKeyService;

/**
 * Agent.effectiveDisplayName is displayName if non-blank, else the
 * registration name; a bound key's notes never stand in for it, and the
 * resolver reads no credential or key (task t20261013-072815-28128).
 */
class AgentEffectiveDisplayNameTest {

	private AgentIdentityService agentIdentityService;
	private ApiKeyService apiKeyService;
	private AgentDataFetcher fetcher;

	@BeforeEach
	void wireMocks() throws Exception {
		agentIdentityService = mock(AgentIdentityService.class);
		apiKeyService = mock(ApiKeyService.class);
		fetcher = new AgentDataFetcher();
		inject("agentIdentityService", agentIdentityService);
		inject("apiKeyService", apiKeyService);
	}

	private void inject(String field, Object value) throws Exception {
		Field f = AgentDataFetcher.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(fetcher, value);
	}

	private String effectiveNameOf(AgentData ad) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getSource()).thenReturn(ad);
		return fetcher.agentEffectiveDisplayName(dfe);
	}

	/** An agent bound to a key identity, so the old notes fallback would have had a key to read. */
	private AgentData boundAgent(String name, String displayName) {
		AgentData ad = new AgentData();
		ad.setOrg(UUID.randomUUID());
		ad.setAgentIdentity(UUID.randomUUID());
		ad.setName(name);
		ad.setDisplayName(displayName);
		return ad;
	}

	@Test
	void displayNameWins() {
		assertEquals("Release bot", effectiveNameOf(boundAgent("claude-runner", "Release bot")));
		verifyNoInteractions(agentIdentityService, apiKeyService);
	}

	@Test
	void nullDisplayNameGivesName() {
		assertEquals("claude-runner", effectiveNameOf(boundAgent("claude-runner", null)));
		verifyNoInteractions(agentIdentityService, apiKeyService);
	}

	@Test
	void blankDisplayNameGivesName() {
		assertEquals("claude-runner", effectiveNameOf(boundAgent("claude-runner", "  ")));
		verifyNoInteractions(agentIdentityService, apiKeyService);
	}

	@Test
	void nullSourceGivesNull() {
		assertNull(effectiveNameOf(null));
		verifyNoInteractions(agentIdentityService, apiKeyService);
	}
}
