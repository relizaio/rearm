/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.model.SubmissionData;
import io.reliza.model.SubmissionVersionData;
import io.reliza.model.UserData;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SubmissionHook;
import io.reliza.service.UserService;

/**
 * Task FDAW-4, design T10 (B14, F-1 of the security review): the four actor-name child resolvers of
 * {@code Submission} and {@code SubmissionVersion}. Each resolves through the membership-checked
 * {@code getUserDataWithOrg} with the parent's organization: a current member answers their name, else
 * their email there; anyone else answers their stored name or null, and their primary email appears
 * nowhere. No authorization call is made.
 */
class SubmissionDataFetcherActorNameTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final String PRIMARY_EMAIL = "former@elsewhere.example";

	private SubmissionDataFetcher fetcher;
	private UserService userService;
	private AuthorizationService auth;
	private SubmissionHook hook;
	private UUID actor;

	/** One field under test: its name, and how to fill the actor on a parent and resolve it. */
	private record Field(String name, Function<UUID, Object> parentWithActor,
			BiFunction<SubmissionDataFetcher, Object, String> resolve) {
	}

	private List<Field> fields;

	@BeforeEach
	void rig() {
		fetcher = new SubmissionDataFetcher();
		userService = mock(UserService.class);
		auth = mock(AuthorizationService.class);
		hook = mock(SubmissionHook.class);
		ReflectionTestUtils.setField(fetcher, "userService", userService);
		ReflectionTestUtils.setField(fetcher, "authorizationService", auth);
		ReflectionTestUtils.setField(fetcher, "sharedReleaseService", mock(SharedReleaseService.class));
		ReflectionTestUtils.setField(fetcher, "getComponentService", mock(GetComponentService.class));
		ReflectionTestUtils.setField(fetcher, "hook", hook);
		actor = UUID.randomUUID();
		fields = new ArrayList<>();
		fields.add(new Field("Submission.createdByName", a -> head(h -> h.setCreatedBy(a)),
				(f, p) -> f.createdByName(env(p))));
		fields.add(new Field("Submission.lastActByName", a -> head(h -> h.setLastActBy(a)),
				(f, p) -> f.lastActByName(env(p))));
		fields.add(new Field("Submission.withdrawnByName", a -> head(h -> h.setWithdrawnBy(a)),
				(f, p) -> f.withdrawnByName(env(p))));
		fields.add(new Field("SubmissionVersion.createdByName", a -> {
			SubmissionVersionData v = new SubmissionVersionData();
			v.setOrg(ORG);
			v.setCreatedBy(a);
			return v;
		}, (f, p) -> f.versionCreatedByName(env(p))));
	}

	private static SubmissionData head(Consumer<SubmissionData> actorSetter) {
		SubmissionData h = new SubmissionData();
		h.setUuid(UUID.randomUUID());
		h.setOrg(ORG);
		actorSetter.accept(h);
		return h;
	}

	private static DgsDataFetchingEnvironment env(Object source) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.<Object>getSource()).thenReturn(source);
		return dfe;
	}

	private static UserData user(String name, String emailInOrg) {
		UserData u = mock(UserData.class);
		when(u.getName()).thenReturn(name);
		when(u.getEmail(ORG)).thenReturn(emailInOrg);
		when(u.getEmail()).thenReturn(emailInOrg);
		return u;
	}

	@Test
	void aCurrentMemberWithANameAnswersTheName() {
		UserData stored = user("Ada Lovelace", "ada@org.example");
		when(userService.getUserDataWithOrg(actor, ORG)).thenReturn(Optional.of(stored));
		for (Field f : fields) {
			assertEquals("Ada Lovelace", f.resolve().apply(fetcher, f.parentWithActor().apply(actor)), f.name());
		}
		verify(userService, times(fields.size())).getUserDataWithOrg(actor, ORG);
		verifyNoInteractions(auth);
	}

	@Test
	void aCurrentMemberWithNoNameAnswersTheirEmailInTheOrganizationAndNeverReadsTheUnscopedRecord() {
		UserData stored = user("", "ada@org.example");
		when(userService.getUserDataWithOrg(actor, ORG)).thenReturn(Optional.of(stored));
		for (Field f : fields) {
			assertEquals("ada@org.example", f.resolve().apply(fetcher, f.parentWithActor().apply(actor)), f.name());
		}
		verify(userService, times(fields.size())).getUserDataWithOrg(actor, ORG);
		verify(userService, never()).getUserData(any());
		verifyNoInteractions(auth);
	}

	@Test
	void aNonMemberWithNoNameAnswersNullAndTheirPrimaryEmailAppearsNowhere() {
		when(userService.getUserDataWithOrg(actor, ORG)).thenReturn(Optional.empty());
		UserData stored = user(null, PRIMARY_EMAIL);
		when(userService.getUserData(actor)).thenReturn(Optional.of(stored));
		for (Field f : fields) {
			String answer = f.resolve().apply(fetcher, f.parentWithActor().apply(actor));
			assertNull(answer, f.name());
			assertFalse(String.valueOf(answer).contains(PRIMARY_EMAIL), f.name());
		}
		verify(userService, times(fields.size())).getUserDataWithOrg(actor, ORG);
		verifyNoInteractions(auth);
	}

	@Test
	void aFormerMemberWithAStoredNameAnswersTheNameAndNoEmail() {
		when(userService.getUserDataWithOrg(actor, ORG)).thenReturn(Optional.empty());
		UserData stored = user("Grace Hopper", PRIMARY_EMAIL);
		when(userService.getUserData(actor)).thenReturn(Optional.of(stored));
		for (Field f : fields) {
			assertEquals("Grace Hopper", f.resolve().apply(fetcher, f.parentWithActor().apply(actor)), f.name());
		}
		verifyNoInteractions(auth);
	}

	@Test
	void anUnknownUuidAnswersNull() {
		when(userService.getUserDataWithOrg(actor, ORG)).thenReturn(Optional.empty());
		when(userService.getUserData(actor)).thenReturn(Optional.empty());
		for (Field f : fields) {
			assertNull(f.resolve().apply(fetcher, f.parentWithActor().apply(actor)), f.name());
		}
		verifyNoInteractions(auth);
	}

	@Test
	void aNullUuidAnswersNullWithNoUserLookup() {
		for (Field f : fields) {
			assertNull(f.resolve().apply(fetcher, f.parentWithActor().apply(null)), f.name());
		}
		verifyNoInteractions(userService, auth);
	}

	@Test
	void aUserServiceFailureAnswersNull() {
		when(userService.getUserDataWithOrg(actor, ORG)).thenThrow(new IllegalStateException("database down"));
		for (Field f : fields) {
			assertNull(f.resolve().apply(fetcher, f.parentWithActor().apply(actor)), f.name());
		}
		verifyNoInteractions(auth);
	}

	@Test
	void theNamesResolveTheSameWithoutAHook() {
		ReflectionTestUtils.setField(fetcher, "hook", null);
		UserData stored = user("Ada Lovelace", "ada@org.example");
		when(userService.getUserDataWithOrg(actor, ORG)).thenReturn(Optional.of(stored));
		for (Field f : fields) {
			assertEquals("Ada Lovelace", f.resolve().apply(fetcher, f.parentWithActor().apply(actor)), f.name());
		}
		verifyNoInteractions(auth, hook);
	}
}
