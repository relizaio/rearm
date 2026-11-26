/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.VcsRepositoryRepository;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * PFA-4 mutation round: what the batch's and the provisioning's transaction attributes promise, checked
 * in the database. The class is not transactional (each test owns its organization, key, URI and commits)
 * and every check reads through {@link JdbcTemplate}, a connection of its own, so only committed rows count.
 * A failure after the last release is injected through the datafetcher's {@code OssReleaseService}, the
 * last collaborator the batch calls, so every exception kind meets a batch that has written everything.
 */
@SpringBootTest(classes = {App.class})
class AddReleasesBatchTransactionEdgesTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String DISPLAY_NAME = "PFA-4 display";
	private static final String IP = "198.51.100.62";

	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private ComponentService componentService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private PlatformTransactionManager transactionManager;

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

	private record Caller(UUID org, ApiKey key) {}

	private Caller caller() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.ORGANIZATION_RW, org, null,
				"pfa-4 batch edges key", WU);
		return new Caller(org, apiKeyService.getApiKey(key.getUuid()).orElseThrow());
	}

	// ---------- the batch rolls back for every kind of exception ----------

	/**
	 * The batch is all-or-nothing for a checked exception that is not a RelizaException as well: the
	 * failure comes after the last release of the batch, so the release, its component and its branch
	 * were all written. What was committed on its own (the repository row, the commit's entry) stays.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"IOException", "Exception", "RelizaException", "IllegalStateException", "Error"})
	void anExceptionAfterTheLastReleaseRollsTheWholeBatchBack(String kind) throws Exception {
		Throwable failure = switch (kind) {
			case "IOException" -> new IOException("pfa-4 injected");
			case "Exception" -> new Exception("pfa-4 injected");
			case "RelizaException" -> new RelizaException("pfa-4 injected");
			case "Error" -> new AssertionError("pfa-4 injected");
			default -> new IllegalStateException("pfa-4 injected");
		};
		Caller c = caller();
		String uri = uri();
		String version = version();
		String commit = commit();
		Map<String, Object> input = newRepositoryInput(uri, "a", version, commit);

		Throwable thrown = withReleaseServiceStub(
				ws -> Mockito.doAnswer(i -> { throw failure; }).when(ws).autoIntegrateProductsForBatch(Mockito.any()),
				() -> assertThrows(failure.getClass(), () -> batch(c, input)));

		assertSame(failure, thrown, "the batch rethrows what failed, as it is");
		assertEquals(List.of(DISPLAY_NAME), repositoryNames(c.org(), uri), "the provisioned repository is committed on its own");
		UUID vcs = repository(c.org(), uri);
		assertEquals(0, releasesOf(c.org(), version), "the release rolled back");
		assertEquals(0, componentsOn(vcs), "the component rolled back");
		assertEquals(1, entriesOn(vcs, commit), "the commit's entry is committed on its own");
	}

	/** The same batch without the failure commits all it wrote: the rollback rule is not a blanket one. */
	@Test
	void aBatchThatDoesNotFailCommitsWhatItWrote() {
		Caller c = caller();
		String uri = uri();
		String version = version();
		String commit = commit();

		List<ReleaseData> created = assertDoesNotThrow(() -> batch(c, newRepositoryInput(uri, "a", version, commit)));

		assertEquals(1, created.size());
		assertEquals(List.of(DISPLAY_NAME), repositoryNames(c.org(), uri));
		UUID vcs = repository(c.org(), uri);
		assertEquals(1, releasesOf(c.org(), version));
		assertEquals(1, componentsOn(vcs));
		assertEquals(1, entriesOn(vcs, commit));
	}

	// ---------- the repository is provisioned in a transaction of its own ----------

	/**
	 * Both overloads commit the row although the caller's transaction rolls back, and both write it as the
	 * caller: the actor, the kind and the address of an API caller reach the row of either overload.
	 */
	@Test
	void provisioningCommitsEvenWhenTheCallersTransactionRollsBack() {
		Caller c = caller();
		UUID org = c.org();
		WhoUpdated actor = WhoUpdated.getApiWhoUpdated(c.key().getUuid(), IP);
		String fiveArgUri = uri();
		String fourArgUri = uri();
		UUID[] provisioned = new UUID[2];

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			provisioned[0] = assertDoesNotThrow(() ->
					vcsRepositoryService.provisionVcsRepository(org, fiveArgUri, DISPLAY_NAME, VcsType.GIT, actor));
			provisioned[1] = assertDoesNotThrow(() ->
					vcsRepositoryService.provisionVcsRepository(org, fourArgUri, VcsType.GIT, actor));
			status.setRollbackOnly();
		});

		assertEquals(List.of(DISPLAY_NAME), repositoryNames(org, fiveArgUri), "five-argument overload");
		assertEquals(List.of(Utils.deriveVcsNameFromUri(Utils.canonicalVcsUri(fourArgUri))),
				repositoryNames(org, fourArgUri), "four-argument overload");
		assertEquals(provisioned[0], repository(org, fiveArgUri));
		assertEquals(provisioned[1], repository(org, fourArgUri));
		Map<String, String> asTheCaller = Map.of("by", c.key().getUuid().toString(), "type", "API", "ip", IP);
		assertEquals(asTheCaller, actorOf(provisioned[0]), "five-argument overload: the row is written as the caller");
		assertEquals(asTheCaller, actorOf(provisioned[1]), "four-argument overload: the row is written as the caller");
	}

	/** The display name names a new row only; a blank one derives the name; the type is the caller's. */
	@Test
	void theDisplayNameAndTheTypeNameANewRowOnlyAndAnExistingRowIsReused() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String uri = uri();
		String derived = Utils.deriveVcsNameFromUri(Utils.canonicalVcsUri(uri));

		UUID first = assertDoesNotThrow(() ->
				vcsRepositoryService.provisionVcsRepository(org, uri, DISPLAY_NAME, VcsType.SUBVERSION, WU));
		UUID again = assertDoesNotThrow(() ->
				vcsRepositoryService.provisionVcsRepository(org, uri, "another name", VcsType.GIT, WU));

		assertEquals(first, again, "the existing row is reused");
		assertEquals(List.of(DISPLAY_NAME), repositoryNames(org, uri), "the existing row keeps its name");
		assertEquals(List.of("Subversion"), repositoryTypes(org, uri), "and its type");

		for (String blank : new String[] {null, ""}) {
			String blankUri = uri();
			assertDoesNotThrow(() -> vcsRepositoryService.provisionVcsRepository(org, blankUri, blank, VcsType.MERCURIAL, WU));
			assertEquals(List.of(Utils.deriveVcsNameFromUri(Utils.canonicalVcsUri(blankUri))), repositoryNames(org, blankUri),
					"a blank display name derives the name from the URI: " + blank);
			assertEquals(List.of("Mercurial"), repositoryTypes(org, blankUri));
		}

		String fourArgUri = uri();
		assertDoesNotThrow(() -> vcsRepositoryService.provisionVcsRepository(org, fourArgUri, VcsType.SUBVERSION, WU));
		assertEquals(List.of(Utils.deriveVcsNameFromUri(Utils.canonicalVcsUri(fourArgUri))), repositoryNames(org, fourArgUri),
				"the four-argument overload derives the name");
		assertEquals(List.of("Subversion"), repositoryTypes(org, fourArgUri));
		assertNotNull(derived);
	}

	/**
	 * createComponentFromVcsUri provisions through the other bean's proxy, so the row outlives a rollback of
	 * the caller's transaction that discards the component; the row carries the caller's name, type and actor.
	 */
	@Test
	void createComponentFromVcsUriCommitsTheRepositoryBeforeTheComponentAndRecordsTheCaller() {
		Caller c = caller();
		String uri = uri();
		WhoUpdated actor = WhoUpdated.getApiWhoUpdated(c.key().getUuid(), IP);

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertDoesNotThrow(() -> componentService.createComponentFromVcsUri(c.org(), uri, "a", DISPLAY_NAME,
					VcsType.SUBVERSION, "semver", "Branch.Micro", null, actor));
			status.setRollbackOnly();
		});

		assertEquals(List.of(DISPLAY_NAME), repositoryNames(c.org(), uri), "the repository outlives the rollback");
		UUID vcs = repository(c.org(), uri);
		assertEquals(0, componentsOn(vcs), "the component does not");
		assertEquals(List.of("Subversion"), repositoryTypes(c.org(), uri));
		assertEquals(Map.of("by", c.key().getUuid().toString(), "type", "API", "ip", IP), actorOf(vcs),
				"the row is written as the caller");
	}

	/** Without a type the repository is a Git one; the component points at the provisioned row. */
	@Test
	void createComponentFromVcsUriDefaultsToGitAndLinksTheComponentToTheRepository() {
		Caller c = caller();
		String uri = uri();
		WhoUpdated actor = WhoUpdated.getApiWhoUpdated(c.key().getUuid(), IP);

		ComponentData created = assertDoesNotThrow(() -> componentService.createComponentFromVcsUri(c.org(), uri, null,
				DISPLAY_NAME, null, "semver", "Branch.Micro", null, actor));

		assertEquals(List.of("Git"), repositoryTypes(c.org(), uri));
		assertEquals(repository(c.org(), uri), created.getVcs());
		assertEquals(1, componentsOn(created.getVcs()));
	}

	/** The repository is written by one transaction: a failure right after the insert leaves no row. */
	@Test
	void aFailureRightAfterTheInsertLeavesNoRepositoryRow() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String uri = uri();

		RuntimeException thrown = withRepositoryFailingAfterSave(
				() -> assertThrows(IllegalStateException.class,
						() -> vcsRepositoryService.provisionVcsRepository(org, uri, DISPLAY_NAME, VcsType.GIT, WU)));

		assertEquals("pfa-4 after the insert", thrown.getMessage());
		assertEquals(List.of(), repositoryNames(org, uri), "the insert rolled back with the provisioning transaction");
	}

	/** The batch joins a transaction its caller already has: rolling that one back discards the releases. */
	@Test
	void aBatchRunsInTheTransactionOfItsCallerWhenThereIsOne() {
		Caller c = caller();
		String uri = uri();
		String version = version();
		Map<String, Object> input = newRepositoryInput(uri, "a", version, commit());

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertDoesNotThrow(() -> batch(c, input));
			status.setRollbackOnly();
		});

		assertEquals(0, releasesOf(c.org(), version), "the release rolled back with the caller's transaction");
		assertEquals(List.of(DISPLAY_NAME), repositoryNames(c.org(), uri), "the repository was committed on its own");
	}

	/**
	 * The rollback rule is the batch's alone (PFA-1 design 3.2): the single-release resolver keeps the
	 * default rule, under which a checked RelizaException does not undo the component the call created.
	 */
	@Test
	void theSingleReleaseResolverKeepsTheDefaultRollbackRule() {
		Caller c = caller();
		String uri = uri();
		String version = version();
		RelizaException failure = new RelizaException("pfa-4 injected");
		Map<String, Object> input = newRepositoryInput(uri, "a", version, commit());

		RelizaException thrown = withReleaseServiceStub(
				ws -> assertDoesNotThrow(() -> Mockito.doAnswer(i -> { throw failure; }).when(ws)
						.createRelease(Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.anyBoolean())),
				() -> assertThrows(RelizaException.class, () -> releaseDatafetcher
						.addReleaseProgrammatic(env(c.key(), "release", input))));

		assertSame(failure, thrown);
		UUID vcs = repository(c.org(), uri);
		assertEquals(1, componentsOn(vcs), "the component the call created is committed: no rollback for a checked exception");
		assertEquals(0, releasesOf(c.org(), version));
	}

	// ---------- failure injection ----------

	/**
	 * Runs {@code body} with the datafetcher's OssReleaseService replaced by a wrapper that delegates to the
	 * real bean except for what {@code stub} stubs.
	 */
	private <T> T withReleaseServiceStub(Consumer<OssReleaseService> stub, Supplier<T> body) {
		Object target = unwrapped(releaseDatafetcher);
		OssReleaseService real = (OssReleaseService) ReflectionTestUtils.getField(target, "ossReleaseService");
		OssReleaseService wrapper = Mockito.mock(OssReleaseService.class,
				Mockito.withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(real)));
		stub.accept(wrapper);
		ReflectionTestUtils.setField(target, "ossReleaseService", wrapper);
		try {
			return body.get();
		} finally {
			ReflectionTestUtils.setField(target, "ossReleaseService", real);
		}
	}

	/** Runs {@code body} with the repository store of VcsRepositoryService throwing right after each save. */
	private <T> T withRepositoryFailingAfterSave(Supplier<T> body) {
		Object target = unwrapped(vcsRepositoryService);
		VcsRepositoryRepository real = (VcsRepositoryRepository) ReflectionTestUtils.getField(target, "repository");
		VcsRepositoryRepository wrapper = Mockito.mock(VcsRepositoryRepository.class,
				Mockito.withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(real)));
		Mockito.doAnswer(i -> {
			real.save(i.getArgument(0));
			throw new IllegalStateException("pfa-4 after the insert");
		}).when(wrapper).save(Mockito.any());
		ReflectionTestUtils.setField(target, "repository", wrapper);
		try {
			return body.get();
		} finally {
			ReflectionTestUtils.setField(target, "repository", real);
		}
	}

	private static Object unwrapped(Object bean) {
		Object target = AopProxyUtils.getSingletonTarget(bean);
		return target == null ? bean : target;
	}

	// ---------- calls and inputs ----------

	@SafeVarargs
	private List<ReleaseData> batch(Caller c, Map<String, Object>... inputs) throws Exception {
		return releaseDatafetcher.addReleasesProgrammatic(env(c.key(), "releases", new ArrayList<>(List.of(inputs))));
	}

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

	private static Map<String, Object> newRepositoryInput(String uri, String repoPath, String version, String commit) {
		return map("vcsUri", uri, "repoPath", repoPath, CommonVariables.BRANCH_FIELD, "main",
				CommonVariables.VERSION_FIELD, version, CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name(),
				"createComponentIfMissing", Boolean.TRUE, "createComponentVersionSchema", "semver",
				"createComponentFeatureBranchVersionSchema", "Branch.Micro", "vcsDisplayName", DISPLAY_NAME,
				CommonVariables.SOURCE_CODE_ENTRY_FIELD, map("uri", uri, "type", "git", "commit", commit));
	}

	private static String uri() {
		return "github.com/example/pfa4-" + UUID.randomUUID();
	}

	private static String commit() {
		return UUID.randomUUID().toString().replace("-", "") + "abcdef01";
	}

	private static String version() {
		return "42." + ThreadLocalRandom.current().nextInt(1, 1_000_000) + ".0";
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	// ---------- reads ----------

	private List<String> repositoryNames(UUID org, String uri) {
		return jdbcTemplate.queryForList("SELECT record_data->>'name' FROM rearm.vcs_repositories"
				+ " WHERE record_data->>'org' = ? AND record_data->>'uri' = ?", String.class, org.toString(),
				Utils.canonicalVcsUri(uri));
	}

	private List<String> repositoryTypes(UUID org, String uri) {
		return jdbcTemplate.queryForList("SELECT record_data->>'type' FROM rearm.vcs_repositories"
				+ " WHERE record_data->>'org' = ? AND record_data->>'uri' = ?", String.class, org.toString(),
				Utils.canonicalVcsUri(uri));
	}

	/** The actor, the kind of actor and the address a repository row was last written with. */
	private Map<String, String> actorOf(UUID vcs) {
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT record_data->>'lastUpdatedBy' AS by,"
				+ " record_data->>'createdType' AS type, record_data->>'lastUpdatedIp' AS ip FROM rearm.vcs_repositories"
				+ " WHERE uuid = ?", vcs);
		Map<String, String> actor = new HashMap<>();
		row.forEach((k, v) -> actor.put(k, String.valueOf(v)));
		return actor;
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

	private long releasesOf(UUID org, String version) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.releases WHERE record_data->>'org' = ?"
				+ " AND record_data->>'version' = ?", Long.class, org.toString(), version);
	}

	private long entriesOn(UUID vcs, String commit) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.source_code_entries WHERE record_data->>'vcs' = ?"
				+ " AND record_data->>'commit' = ?", Long.class, vcs.toString(), commit);
	}
}
