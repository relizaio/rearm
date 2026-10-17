/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.service.LicenseStatus;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-24 (architecture-6 table C, 6.5 item 15): the legacy {@code /graphql} lane's residue, pinned where it lives. On
 * {@code /graphql} the chain's filter verifies Basic since TPF-29 ({@link LegacyGraphqlBasicFilter}); this class bypasses
 * the chain, so a field without its own verify line sees the unverified parse, and the org named in an
 * {@code __ORGANIZATION__} segment reaches it as {@code authCtx.orgUuid()}. One field per table C
 * group that a forged org-segment id reaches, called through the executor on a {@code /graphql} request without the
 * filter's principal, once with the segment naming a real organisation and once a random uuid; the first error message
 * of each answer is compared.
 *
 * <p>This is a characterisation, not a mitigation test: TPF-29 (verify or retire Basic on {@code /graphql}) stops the
 * unverified parse at the chain with a filter, which leaves these fetchers' own answers as they are (its proof is
 * chain-level), so TPF-29 keeps this class as the record of the lane behind its filter. Row 1 differs today; if a
 * per-field verify line is ever added to {@code authorizeProgrammaticOrg} it turns equal and table C is out of date.
 * Healthy instance only; the sealed variants are table C's prose.
 */
@SpringBootTest(classes = {App.class})
public class GraphqlLaneResidueTest {

	private static final String NOT_AUTHORIZED = "Not authorized";

	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	/** A forged org-segment Basic on /graphql: no principal attribute, so the resolver sees the unverified parse. */
	private String firstError(String query, UUID segmentOrg) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/graphql");
		request.setRemoteAddr("198.51.100.24");
		HttpHeaders headers = new HttpHeaders();
		String id = "FREEFORM__" + UUID.randomUUID() + "__ORGANIZATION__" + segmentOrg + "__ord__0";
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString((id + ":x").getBytes(StandardCharsets.UTF_8)));
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		ExecutionResult r = dgsQueryExecutor.execute(query, Map.of(), Map.of(), headers, null, new ServletWebRequest(request));
		assertFalse(r.getErrors().isEmpty(), query + ": expected a refusal, got " + String.valueOf((Object) r.getData()));
		return r.getErrors().get(0).getMessage();
	}

	private record Answers(String realOrg, String randomOrg, UUID random) {}

	private Answers answers(String query) {
		UUID real = testInitializer.obtainOrganization().getUuid();
		UUID random = UUID.randomUUID();
		return new Answers(firstError(query, real), firstError(query, random), random);
	}

	// row 1, agent surface (table A row 22): the org-existence oracle TPF-29 removes from the wire
	@Test
	public void agentSurfaceAnswersDifferForARealAndARandomOrg() {
		Answers a = answers("{ agentBoardsProgrammatic { uuid } }");
		assertEquals(NOT_AUTHORIZED, a.realOrg(), "a real org reaches the helper, which refuses the secret");
		assertEquals("Org not found: " + a.random(), a.randomOrg(), "a random org is looked up before the secret check");
		assertNotEquals(a.realOrg(), a.randomOrg(), "the residue: the two answers DIFFER on /graphql until TPF-29 lands");
	}

	// row 2, declarative (table A row 1): both Not authorized on a healthy instance
	@Test
	public void declarativeAnswersAreEqual() {
		// CatalogSpec is an object type, so the query selects a field of it (the design wrote the bare field)
		Answers a = answers("{ exportCatalogProgrammatic { kind } }");
		assertEquals(NOT_AUTHORIZED, a.realOrg());
		assertEquals(a.realOrg(), a.randomOrg(), "equal on a healthy instance");
	}

	// row 3, the release key lane (table A row 8): both Not authorized
	@Test
	public void releaseKeyLaneAnswersAreEqual() {
		Answers a = answers("query { release(releaseUuid: \"" + UUID.randomUUID() + "\") { uuid } }");
		assertEquals(NOT_AUTHORIZED, a.realOrg());
		assertEquals(a.realOrg(), a.randomOrg(), "equal");
	}

	// row 4, agenticReleaseProgrammatic (table A row 21): a random release, no session, so the agent refusal is what reads
	@Test
	public void agenticReleaseAnswersAreEqual() {
		Answers a = answers("query { agenticReleaseProgrammatic(releaseUuid: \"" + UUID.randomUUID() + "\") { uuid } }");
		assertEquals(NOT_AUTHORIZED, a.realOrg());
		assertEquals(a.realOrg(), a.randomOrg(), "equal");
	}
}
