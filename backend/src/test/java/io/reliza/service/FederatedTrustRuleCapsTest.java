/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.FederatedTrustRuleRepository;

/**
 * The matcher caps apply to what an edit adds, not to what a rule already holds: a rule saved
 * before the caps stays editable for an unrelated change.
 */
@ExtendWith(MockitoExtension.class)
class FederatedTrustRuleCapsTest {

	@Mock private FederatedTrustRuleRepository repository;
	@InjectMocks private FederatedTrustRuleService service;

	private final UUID ruleUuid = UUID.randomUUID();
	private final String overCap = "repo:relizaio/" + "*".repeat(FederatedMatching.MAX_GLOB_LENGTH);

	private FederatedMatcher matcher(String... subjects) {
		FederatedMatcher m = new FederatedMatcher();
		m.setOwner("relizaio");
		m.setSubjects(new ArrayList<>(List.of(subjects)));
		return m;
	}

	/** A rule whose stored matcher predates the caps, written straight to the entity. */
	private void storedRule() {
		FederatedTrustRule r = new FederatedTrustRule();
		r.setOrg(UUID.randomUUID());
		r.setName("legacy");
		r.setMatcherOf(matcher(overCap));
		when(repository.findById(ruleUuid)).thenReturn(Optional.of(r));
		lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
	}

	@Test
	void renamingARuleWithALegacyOverCapGlobSaves() throws RelizaException {
		storedRule();
		FederatedTrustRule saved = service.update(ruleUuid, "renamed", matcher(overCap), null, null,
				WhoUpdated.getTestWhoUpdated());
		assertEquals("renamed", saved.getName());
		assertEquals(List.of(overCap), saved.matcherOf().getSubjects());
	}

	@Test
	void addingANewOverCapGlobIsStillRefused() {
		storedRule();
		RelizaException e = assertThrows(RelizaException.class, () -> service.update(ruleUuid, "legacy",
				matcher(overCap, overCap + "x"), null, null, WhoUpdated.getTestWhoUpdated()));
		assertEquals("Matcher subjects entry exceeds " + FederatedMatching.MAX_GLOB_LENGTH + " characters",
				e.getMessage());
	}
	@Test
	void aLegacyOverCapGlobCannotBeMultipliedByAnEdit() {
		// Kept means kept as many times as the rule held it: a second copy is a new entry.
		storedRule();
		RelizaException e = assertThrows(RelizaException.class, () -> service.update(ruleUuid, "legacy",
				matcher(overCap, overCap), null, null, WhoUpdated.getTestWhoUpdated()));
		assertEquals("Matcher subjects entry exceeds " + FederatedMatching.MAX_GLOB_LENGTH + " characters",
				e.getMessage());
	}
}
