/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.UserData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * The executor rig of the client-input refusals (TPF-63, and TPF-47's NUL check): only who is asking is mocked (the
 * mock set of {@link AgentBoardGraphQlTest}, so the context is shared), a signed-in person per test, and the helpers
 * that read the state a refused request must leave unchanged.
 *
 * <p>One file per topic: add a test to the class for its topic ({@link InputConversionGraphQlTest} for the
 * conversion contract, {@link NulInputGraphQlTest} for U+0000), or to a new class extending this base.
 */
@SpringBootTest(classes = {App.class})
abstract class InputConversionGraphQlTestBase {

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	static final String ADD = "mutation add($in: CreateArtifactInput) { addArtifactManual(artifactInput: $in) { uuid } }";

	@MockitoBean UserService userService;
	@MockitoBean AuthorizationService authorizationService;
	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired TestInitializer testInitializer;
	@Autowired ComponentService componentService;
	@Autowired BranchService branchService;
	@Autowired OssReleaseService ossReleaseService;
	@Autowired SharedReleaseService sharedReleaseService;
	@Autowired VcsRepositoryService vcsRepositoryService;
	@Autowired ArtifactService artifactService;
	@Autowired ApiKeyService apiKeyService;

	@BeforeEach
	void signIn() {
		UserData user = mock(UserData.class);
		when(user.getUuid()).thenReturn(UUID.randomUUID());
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(user));
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf63-test").build();
		// A context of its own, not getContext(): several unit tests leave a mocked SecurityContext
		// in the holder, and setting an authentication on that mock does nothing.
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	/** A DRAFT release on a fresh component (side() of the TPF-44 rig without the VCS, entry and deliverable). */
	UUID release() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Component component = componentService.createComponent("tpf63_" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch branch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(component.getUuid()).org(org)
				.branch(branch.getUuid()).version("1.0.0").status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
	}

	List<UUID> artifactsOf(UUID release) {
		return List.copyOf(sharedReleaseService.getReleaseData(release).orElseThrow().getArtifacts());
	}

	static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	static Map<String, Object> artifact(String storedIn) {
		return map("type", "OTHER", "displayIdentifier", "tpf63-" + UUID.randomUUID(), "storedIn", storedIn,
				"downloadLinks", List.of(map("uri", "https://example.com/tpf63/" + UUID.randomUUID())));
	}

	static Map<String, Object> addInput(UUID release, Map<String, Object> artifact) {
		Map<String, Object> in = map("release", release.toString(), "belongsTo", "RELEASE");
		if (null != artifact) in.put("artifact", artifact);
		return in;
	}

	/** Exactly one error with that message and type, no extensions, and the state read before unchanged. */
	<S> void refused(ExecutionResult r, String message, String errorType, Supplier<S> state, S before) {
		assertEquals(1, r.getErrors().size(), "one refusal: " + r.getErrors());
		GraphQLError err = r.getErrors().get(0);
		assertEquals(message, err.getMessage());
		assertEquals(errorType, String.valueOf(err.getErrorType()));
		assertTrue(null == err.getExtensions() || err.getExtensions().isEmpty(), String.valueOf(err.getExtensions()));
		if (null != state) assertEquals(before, state.get());
	}
}
