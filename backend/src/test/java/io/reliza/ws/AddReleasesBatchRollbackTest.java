/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Link.ContentEnum;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ApiKeyService;
import io.reliza.service.BranchService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.LicenseStatus;
import io.reliza.ws.oss.TestInitializer;

/**
 * PFA-1 T-1 to T-3: {@code addReleasesProgrammatic} provisions the VCS repository of a component it
 * creates in its own transaction, before the source code entry's own transaction reads it, and rolls
 * the whole batch back on a checked {@link RelizaException}. Called through the Spring bean, so the
 * transaction rule under test applies; the class is not transactional, and each test owns its
 * organization, ORGANIZATION_RW key, repository URI and commits.
 *
 * <p>What survives a rolled-back batch (the repository row and the commit's entry, without the
 * request's artifacts and with a branch reference that names no row) and how a repeated batch
 * reuses it are the PFA-1 design, section 3.3.
 */
@SpringBootTest(classes = {App.class})
class AddReleasesBatchRollbackTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String DISPLAY_NAME = "PFA-1 display";

	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private BranchService branchService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;
	@Autowired private JdbcTemplate jdbcTemplate;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; a programmatic write needs it operational. */
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

	/** An organization of the test's own and an ORGANIZATION_RW key on it. */
	private record Caller(UUID org, ApiKey key) {}

	/** What T-2's refused batch leaves behind, for T-3's retry. */
	private record Survivors(Caller caller, Map<String, Object> firstInput, String uri, String commit, String version,
			UUID vcs, UUID entry, UUID danglingBranch) {}

	private Caller caller() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.ORGANIZATION_RW, org, null,
				"pfa-1 batch rollback key", WU);
		return new Caller(org, apiKeyService.getApiKey(key.getUuid()).orElseThrow());
	}

	// ---------- T-1 ----------

	@Test
	void aBatchOnANewRepositoryProvisionsItOnceWithTheCallersDisplayName() throws Exception {
		Caller c = caller();
		String uri = uri();
		String version = version();
		Map<String, Object> first = newRepositoryInput(uri, "a", version, commit());
		Map<String, Object> second = newRepositoryInput(uri, "b", version, commit());

		List<ReleaseData> created = batch(c, first, second);

		assertEquals(2, created.size());
		assertEquals(List.of(DISPLAY_NAME), repositoryNames(c.org(), uri), "one repository row, named by the caller");
		UUID vcs = repository(c.org(), uri);
		for (ReleaseData rd : created) {
			assertNotNull(rd.getSourceCodeEntry(), rd.getVersion());
			SourceCodeEntryData entry = getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry())
					.orElseThrow();
			assertEquals(vcs, entry.getVcs());
		}
		assertEquals(2, componentsOn(vcs), "a component for each repoPath on the one repository");
	}

	// ---------- T-2 ----------

	@Test
	void aRelizaExceptionAtALaterReleaseRollsBackTheEarlierReleaseItsComponentAndItsBranch() throws Exception {
		refusedBatch();
	}

	/**
	 * T-2's batch and assertions: release[0] creates a component on a new repository and records a commit
	 * carrying an artifact; release[1] names an unregistered repository without createComponentIfMissing
	 * and is refused with a RelizaException.
	 */
	private Survivors refusedBatch() throws Exception {
		Caller c = caller();
		String uri = uri();
		String commit = commit();
		String version = version();
		String artifactId = "pfa-1-report-" + UUID.randomUUID();
		Map<String, Object> first = newRepositoryInput(uri, "a", version, commit);
		@SuppressWarnings("unchecked")
		Map<String, Object> firstSce = (Map<String, Object>) first.get(CommonVariables.SOURCE_CODE_ENTRY_FIELD);
		firstSce.put("artifacts", new ArrayList<>(List.of(externalReport(artifactId))));
		Map<String, Object> unresolvable = map("vcsUri", uri(), "repoPath", "a", CommonVariables.BRANCH_FIELD, "main",
				CommonVariables.VERSION_FIELD, version, CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name());

		RelizaException refused = assertThrows(RelizaException.class, () -> batch(c, first, unresolvable));
		assertTrue(refused.getMessage().startsWith("Component cannot be resolved"), refused.getMessage());

		// rolled back with the batch
		UUID vcs = repository(c.org(), uri);
		assertEquals(0, componentsOn(vcs), "the component release[0] created rolled back");
		assertEquals(0, releasesOf(c.org(), version), "release[0] rolled back");
		assertEquals(0, artifactsOf(c.org(), artifactId), "the artifact row release[0] uploaded rolled back");
		// committed on their own, so they survive
		assertEquals(1, repositoryNames(c.org(), uri).size(), "the provisioned repository survives, once");
		List<UUID> entries = entries(vcs, commit);
		assertEquals(1, entries.size(), "the commit's entry survives");
		SourceCodeEntryData entry = getSourceCodeEntryService.getSourceCodeEntryData(entries.get(0)).orElseThrow();
		assertTrue(null == entry.getArtifacts() || entry.getArtifacts().isEmpty(),
				"the artifact attached to the entry rolled back: " + entry.getArtifacts());
		assertNotNull(entry.getBranch());
		assertTrue(branchService.getBranchData(entry.getBranch()).isEmpty(), "the base branch rolled back");

		Map<String, Object> retryInput = newRepositoryInput(uri, "a", version, commit);
		return new Survivors(c, retryInput, uri, commit, version, vcs, entry.getUuid(), entry.getBranch());
	}

	// ---------- T-3 ----------

	@Test
	void aRepeatedBatchReusesTheRepositoryAndTheEntryAndRepointsItsBranch() throws Exception {
		Survivors s = refusedBatch();

		List<ReleaseData> created = batch(s.caller(), s.firstInput());

		assertEquals(1, created.size());
		ReleaseData rd = created.get(0);
		assertEquals(1, repositoryNames(s.caller().org(), s.uri()).size(), "no second repository row");
		assertEquals(s.vcs(), repository(s.caller().org(), s.uri()));
		assertEquals(1, componentsOn(s.vcs(), "a"), "one component on the repository for the repoPath");
		assertEquals(1, releasesOf(s.caller().org(), s.version()));
		assertEquals(List.of(s.entry()), entries(s.vcs(), s.commit()), "the surviving entry is reused");
		assertEquals(s.entry(), rd.getSourceCodeEntry());
		SourceCodeEntryData entry = getSourceCodeEntryService.getSourceCodeEntryData(s.entry()).orElseThrow();
		assertEquals(rd.getBranch(), entry.getBranch(), "the entry now names the retry's branch");
		assertNotEquals(s.danglingBranch(), entry.getBranch());
		assertTrue(branchService.getBranchData(entry.getBranch()).isPresent());
	}

	// ---------- calls ----------

	@SafeVarargs
	private List<ReleaseData> batch(Caller c, Map<String, Object>... inputs) throws Exception {
		return releaseDatafetcher.addReleasesProgrammatic(env(c.key(), "releases", new ArrayList<>(List.of(inputs))));
	}

	/** A resolver environment carrying the principal the programmatic filter leaves for {@code key}. */
	private DgsDataFetchingEnvironment env(ApiKey key, String argument, Object input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.61"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(argument, input);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	// ---------- inputs ----------

	/** The design's new-repository input: creates its component when missing, with a commit on {@code uri}. */
	private static Map<String, Object> newRepositoryInput(String uri, String repoPath, String version, String commit) {
		return map("vcsUri", uri, "repoPath", repoPath, CommonVariables.BRANCH_FIELD, "main",
				CommonVariables.VERSION_FIELD, version, CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name(),
				"createComponentIfMissing", Boolean.TRUE, "createComponentVersionSchema", "semver",
				"createComponentFeatureBranchVersionSchema", "Branch.Micro", "vcsDisplayName", DISPLAY_NAME,
				CommonVariables.SOURCE_CODE_ENTRY_FIELD, map("uri", uri, "type", "git", "commit", commit));
	}

	/** A test report stored externally: an artifact row with no upload to storage. */
	private static Map<String, Object> externalReport(String displayIdentifier) {
		return map("type", ArtifactType.TEST_REPORT.name(), "storedIn", StoredIn.EXTERNALLY.name(),
				"displayIdentifier", displayIdentifier,
				"downloadLinks", new ArrayList<>(List.of(map("uri", "https://example.com/" + displayIdentifier,
						"content", ContentEnum.OCTET_STREAM.name()))),
				"file", new MockMultipartFile("file", "report.txt", "text/plain",
						"pfa-1".getBytes(StandardCharsets.UTF_8)));
	}

	private static String uri() {
		return "github.com/example/pfa1-" + UUID.randomUUID();
	}

	private static String commit() {
		return UUID.randomUUID().toString().replace("-", "") + "abcdef01";
	}

	private static String version() {
		return "41." + ThreadLocalRandom.current().nextInt(1, 1_000_000) + ".0";
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	// ---------- reads ----------

	/** The names of the organization's repository rows for {@code uri}, one per row. */
	private List<String> repositoryNames(UUID org, String uri) {
		return jdbcTemplate.queryForList("SELECT record_data->>'name' FROM rearm.vcs_repositories"
				+ " WHERE record_data->>'org' = ? AND record_data->>'uri' = ?", String.class, org.toString(),
				Utils.canonicalVcsUri(uri));
	}

	private UUID repository(UUID org, String uri) {
		return jdbcTemplate.queryForObject("SELECT uuid FROM rearm.vcs_repositories"
				+ " WHERE record_data->>'org' = ? AND record_data->>'uri' = ?", UUID.class, org.toString(),
				Utils.canonicalVcsUri(uri));
	}

	private long componentsOn(UUID vcs) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.components WHERE record_data->>'vcs' = ?",
				Long.class, vcs.toString());
	}

	private long componentsOn(UUID vcs, String repoPath) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.components WHERE record_data->>'vcs' = ?"
				+ " AND record_data->>'repoPath' = ?", Long.class, vcs.toString(), repoPath);
	}

	private long releasesOf(UUID org, String version) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.releases WHERE record_data->>'org' = ?"
				+ " AND record_data->>'version' = ?", Long.class, org.toString(), version);
	}

	private long artifactsOf(UUID org, String displayIdentifier) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.artifacts WHERE record_data->>'org' = ?"
				+ " AND record_data->>'displayIdentifier' = ?", Long.class, org.toString(), displayIdentifier);
	}

	private List<UUID> entries(UUID vcs, String commit) {
		return jdbcTemplate.queryForList("SELECT uuid FROM rearm.source_code_entries WHERE record_data->>'vcs' = ?"
				+ " AND record_data->>'commit' = ?", UUID.class, vcs.toString(), commit);
	}
}
