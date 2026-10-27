/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import graphql.ExecutionResult;
import jakarta.persistence.EntityManagerFactory;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApprovalRole;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.Audit;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.FederatedGrant;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.FederatedTrustRule.Provider;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.ApiKeyRepository;
import io.reliza.repositories.AuditRepository;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.CliSessionService;
import io.reliza.service.EncryptionService;
import io.reliza.service.FederatedTrustRuleService;
import io.reliza.service.FederatedTrustRuleService.ExchangeException;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.saas.ApiKeyOrgService;
import io.reliza.service.saas.SaasOrganizationService;

/**
 * archiveOrganization (TPF-48): who may archive (organization-wide ADMIN, or a global admin on any
 * organization; one not-found answer for a missing, foreign or archived organization), what one
 * archive writes, that no credential of the organization works after it, that writers racing an
 * archive neither un-archive it nor lose their own change, and that the archive is all or nothing.
 *
 * <p>Shares the cached context of the board enforcement classes. The concurrency rig ({@link #inView},
 * {@link #held}, {@link #waitForWaiter}) is copied from {@link TaskPrefixClaimRaceIntegrationTest},
 * which stays as it is.
 */
public class OrganizationArchiveIntegrationTest extends AgentBoardEnforcementTestBase {

	private static final String ARCHIVE = "mutation($o: ID!) { archiveOrganization(orgUuid: $o) }";
	private static final String NOT_FOUND = "Organization not found";
	private static final String ADDRESS = "198.51.100.48";
	private static final String OWNER = "tpf48";

	@Autowired ApiKeyOrgService apiKeyOrgService;
	@Autowired SaasOrganizationService saasOrganizationService;
	@Autowired OrganizationService organizationService;
	@Autowired GetOrganizationService getOrganizationService;
	@Autowired OrganizationRepository organizationRepository;
	@Autowired AuditRepository auditRepository;
	@Autowired ApiKeyRepository apiKeyRepository;
	@Autowired ApiTokenService apiTokenService;
	@Autowired ApiKeyAccessService apiKeyAccessService;
	@Autowired CliSessionService cliSessionService;
	@Autowired FederatedTrustRuleService federatedTrustRuleService;
	@Autowired EncryptionService encryptionService;
	@Autowired EntityManagerFactory emf;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired JdbcTemplate jdbcTemplate;

	private static KeyPair throwaway;

	@BeforeAll
	static void keys() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		throwaway = g.generateKeyPair();
	}

	// ---------- the rig (copied from TaskPrefixClaimRaceIntegrationTest) ----------

	/** The call as one request runs it: one EntityManager bound to the thread for the whole call (open-in-view). */
	private <T> T inView(Callable<T> call) throws Exception {
		OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
		interceptor.setEntityManagerFactory(emf);
		ServletWebRequest web = new ServletWebRequest(new MockHttpServletRequest());
		interceptor.preHandle(web);
		try {
			return call.call();
		} finally {
			interceptor.afterCompletion(web, null);
		}
	}

	@FunctionalInterface
	private interface Write {
		void run() throws Exception;
	}

	/**
	 * Runs {@code firstWrite} in a background transaction, flushed, and keeps it open until another
	 * backend waits on one of its locks (at most 10 s); then runs {@code second} on this thread, which
	 * blocks until the holder commits, and asserts it did wait.
	 */
	private <T> T held(Write firstWrite, Callable<T> second) throws Exception {
		CountDownLatch ready = new CountDownLatch(1);
		AtomicBoolean seen = new AtomicBoolean(false);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread holder = new Thread(() -> {
			try {
				new TransactionTemplate(transactionManager).execute(status -> {
					try {
						firstWrite.run();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
					status.flush();
					int me = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
					ready.countDown();
					seen.set(waitForWaiter(me));
					return null;
				});
			} catch (Throwable t) {
				failure.set(t);
			} finally {
				ready.countDown();
			}
		}, "tpf48-holder");
		holder.start();
		assertTrue(ready.await(10, TimeUnit.SECONDS), "the holder never got ready");
		if (null != failure.get()) throw new IllegalStateException("the holder failed", failure.get());
		T result;
		try {
			result = second.call();
		} finally {
			holder.join(TimeUnit.SECONDS.toMillis(30));
		}
		assertFalse(holder.isAlive(), "the holder did not finish");
		if (null != failure.get()) throw new IllegalStateException("the holder failed", failure.get());
		assertTrue(seen.get(), "the second writer never waited on the holder: the row did not run the race");
		return result;
	}

	/** True once some backend waits on a lock this backend holds; polls the lock manager live, every 20 ms, 10 s at most. */
	private boolean waitForWaiter(int pid) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Integer waiting = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM pg_locks WHERE NOT granted AND CAST(? AS integer) = ANY(pg_blocking_pids(pid))",
					Integer.class, pid);
			if (null != waiting && waiting > 0) return true;
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	// ---------- fixtures ----------

	private static String tag() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private UUID org() {
		return testInitializer.obtainOrganization().getUuid();
	}

	private UserData member(UUID org, PermissionType level) throws Exception {
		String t = tag();
		User u = userService.createUser("Arc " + t, "arc-" + t + "@archive.io", true, List.of(org),
				"arc-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org, PermissionScope.ORGANIZATION, org, level, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** A user of no organization, by the given email. */
	private UserData outsider(String email) throws Exception {
		User u = userService.createUser("Arc " + tag(), email, true, List.of(), "arc-" + UUID.randomUUID(),
				OauthType.RELIZA_KEYCLOAK_OWN, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private UserData globalAdmin() throws Exception {
		UserData u = outsider("arc-ga-" + tag() + "@archive.io");
		jdbcTemplate.update("UPDATE rearm.users SET record_data = jsonb_set(record_data, '{" + CommonVariables.GLOBAL_ADMIN_FIELD
				+ "}', 'true'::jsonb) WHERE uuid = ?", u.getUuid());
		UserData g = userService.getUserData(u.getUuid()).orElseThrow();
		assertTrue(g.isGlobalAdmin());
		return g;
	}

	private record Key(ApiKey row, String secret) {
		UUID uuid() {
			return row.getUuid();
		}
	}

	private Key key(UUID object, ApiTypeEnum type, UUID org, String order) {
		ApiKey row = apiKeyService.createObjectApiKey(object, type, org, order, "tpf48", WU);
		String secret;
		try {
			secret = apiKeyService.setObjectApiKey(object, type, org, order, "tpf48", false, WU);
		} catch (io.reliza.exceptions.RelizaException e) {
			throw new IllegalStateException("fixture key", e);
		}
		return new Key(apiKeyService.getApiKey(row.getUuid()).orElseThrow(), secret);
	}

	private UUID matches(Key k) {
		HttpHeaders headers = new HttpHeaders();
		String basic = ApiKeyService.keyIdOf(k.row()) + ":" + k.secret();
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
		return apiKeyService.isMatchingApiKey(AuthHeaderParse.parseAuthHeader(headers, ADDRESS));
	}

	private ApiKeySecret firstSecret(UUID keyUuid) {
		ApiKey ak = apiKeyService.getApiKey(keyUuid).orElseThrow();
		return ApiKeyData.dataFromRecord(ak).effectiveSecrets(ak.getApiKey()).get(0);
	}

	private ApiKeyData keyData(UUID uuid) {
		return ApiKeyData.dataFromRecord(apiKeyService.getApiKey(uuid).orElseThrow());
	}

	/** The row itself, revoked or not (getApiKey hides revoked rows). */
	private boolean revoked(UUID uuid) {
		ApiKey ak = apiKeyRepository.findById(uuid).orElseThrow();
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		return ApiKeyStatus.REVOKED == akd.getStatus() && null == ak.getApiKey() && akd.getSecrets().isEmpty();
	}

	private record Filtered(MockHttpServletResponse response, MockFilterChain chain) {
		boolean admitted() {
			return null != chain.getRequest();
		}
	}

	private Filtered filter(String token) throws Exception {
		ProgrammaticAuthenticationFilter f = new ProgrammaticAuthenticationFilter(apiTokenService, apiKeyService, apiKeyAccessService,
				cliSessionService, federatedTrustRuleService);
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		req.setRequestURI(ProgrammaticGraphQlConfig.PATH);
		req.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		f.doFilter(req, resp, chain);
		return new Filtered(resp, chain);
	}

	private static void assertRefused(Filtered f) throws Exception {
		assertEquals(401, f.response().getStatus());
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, f.response().getContentAsString());
		assertNull(f.chain().getRequest(), "the request must not reach the resolvers");
	}

	private static String issuer() {
		return "https://issuer-" + tag() + ".tpf48.invalid";
	}

	private FederatedTrustRule rule(UUID org, String issuer) throws Exception {
		FederatedMatcher matcher = new FederatedMatcher();
		matcher.setOwner(OWNER);
		FederatedGrant grant = new FederatedGrant();
		grant.setOrgPermission(PermissionType.READ_ONLY);
		return federatedTrustRuleService.create(org, "tpf48-rule-" + UUID.randomUUID(), Provider.GITHUB_ACTIONS, issuer, matcher, grant, null, WU);
	}

	/** An RS256 assertion naming {@code iss}, signed by a throwaway key; only parsed, never verified, here. */
	private static String assertion(String iss) throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(iss).subject("repo:" + OWNER + "/app:ref:refs/heads/main")
				.audience("https://rearm.example.com").issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
				.jwtID(UUID.randomUUID().toString()).claim("repository", OWNER + "/app").claim("repository_owner", OWNER).build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("tpf48").build(), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) throwaway.getPrivate()));
		return jwt.serialize();
	}

	/** A real pending invitation to {@code org} for {@code email}, built as inviteUserToOrganization builds it, with no email sent. */
	private String invitation(UUID org, String email, UserData inviter) {
		String secret = encryptionService.encrypt(org.toString()) + CommonVariables.JOIN_SECRET_SEPARATOR
				+ KeyGenerators.string().generateKey() + KeyGenerators.string().generateKey();
		OrganizationData od = getOrganizationService.getOrganizationData(org).orElseThrow();
		od.addInvitee(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(secret), PermissionType.READ_ONLY, email,
				inviter.getUuid());
		Organization o = organizationRepository.findById(org).orElseThrow();
		o.setRecordData(Utils.dataToRecord(od));
		organizationService.saveOrg(o);
		return secret;
	}

	// ---------- reads (test thread, outside any transaction) ----------

	private StatusEnum status(UUID org) {
		return getOrganizationService.getOrganizationData(org).orElseThrow().getStatus();
	}

	private Set<UUID> members(UUID org) {
		return userService.listUserDataByOrg(org).stream().map(UserData::getUuid).collect(Collectors.toSet());
	}

	private List<ApiKey> liveKeys(UUID org) {
		return apiKeyService.listApiKeyByOrg(org);
	}

	private List<Audit> audits(UUID org) {
		return auditRepository.retrieveAuditRecordsForEntity(TableName.ORGANIZATIONS.toString(), org.toString(), "1000", "0");
	}

	private int revision(UUID org) {
		return organizationRepository.findById(org).orElseThrow().getRevision();
	}

	private Set<UUID> orgsOf(UserData u) {
		return userService.getUserData(u.getUuid()).orElseThrow().getOrganizations();
	}

	@SuppressWarnings("unchecked")
	private static Object answer(ExecutionResult r) {
		return null == r.getData() ? null : ((Map<String, Object>) r.getData()).get("archiveOrganization");
	}

	private static String message(ExecutionResult r) {
		assertFalse(r.getErrors().isEmpty(), "expected a refusal");
		return r.getErrors().get(0).getMessage();
	}

	private static String errorType(ExecutionResult r) {
		return String.valueOf(r.getErrors().get(0).getErrorType());
	}

	private ExecutionResult archive(UserData who, Object org) {
		return asPerson(who, ARCHIVE, vars("o", null == org ? null : org.toString()));
	}

	private void assertArchivedBy(UserData who, UUID org) {
		ExecutionResult r = archive(who, org);
		assertTrue(ok(r), r.getErrors().toString());
		assertEquals(Boolean.TRUE, answer(r));
	}

	/** Organization O of rows 1 to 6: admin A, member M, an ORGANIZATION key and a FREEFORM key. */
	private record Fx(UUID o, UserData a, UserData m, Key orgKey, Key freeform) {}

	private Fx fx() throws Exception {
		UUID o = org();
		return new Fx(o, member(o, PermissionType.ADMIN), member(o, PermissionType.READ_WRITE),
				key(o, ApiTypeEnum.ORGANIZATION, o, null), key(o, ApiTypeEnum.FREEFORM, o, UUID.randomUUID().toString()));
	}

	/** Rows 2 and 3: nothing about O changed. */
	private void assertUnchanged(Fx f, int r0, int n0) {
		assertNull(status(f.o()));
		assertEquals(ApiKeyStatus.ACTIVE, keyData(f.orgKey().uuid()).getStatus());
		assertEquals(ApiKeyStatus.ACTIVE, keyData(f.freeform().uuid()).getStatus());
		assertEquals(f.orgKey().uuid(), matches(f.orgKey()));
		assertEquals(f.freeform().uuid(), matches(f.freeform()));
		assertEquals(r0, revision(f.o()));
		assertEquals(n0, audits(f.o()).size());
	}

	// ---------- rows 1 to 9: who can archive, and what one archive writes ----------

	@Test
	@Timeout(60)
	public void row01AnOrganizationAdminArchivesIt() throws Exception {
		Fx f = fx();
		int r0 = revision(f.o());
		int n0 = audits(f.o()).size();

		assertArchivedBy(f.a(), f.o());

		assertEquals(StatusEnum.ARCHIVED, status(f.o()));
		assertEquals(Set.of(f.a().getUuid()), members(f.o()));
		assertFalse(orgsOf(f.m()).contains(f.o()));
		assertTrue(liveKeys(f.o()).isEmpty());
		assertTrue(revoked(f.orgKey().uuid()));
		assertTrue(revoked(f.freeform().uuid()));
		assertFalse(organizationService.listMyOrganizationData(userService.getUserData(f.a().getUuid()).orElseThrow()).stream()
				.anyMatch(od -> f.o().equals(od.getUuid())));
		assertEquals(r0 + 1, revision(f.o()));
		List<Audit> after = audits(f.o());
		assertEquals(n0 + 1, after.size());
		Audit before = after.stream().filter(a -> a.getRevision() == r0).findFirst().orElseThrow();
		assertNotEquals(StatusEnum.ARCHIVED.name(), before.getRevisionRecordData().get(CommonVariables.STATUS_FIELD),
				"the audit row is the record from before the archive");
		assertEquals(f.a().getUuid().toString(), String.valueOf(organizationRepository.findById(f.o()).orElseThrow()
				.getRecordData().get(CommonVariables.LAST_UPDATED_BY_FIELD)), "the organization row names the archiver");
	}

	@Test
	@Timeout(60)
	public void row02AMemberWithoutAdminIsRefused() throws Exception {
		Fx f = fx();
		int r0 = revision(f.o());
		int n0 = audits(f.o()).size();

		ExecutionResult r = archive(f.m(), f.o());

		assertEquals("Not authorized", message(r));
		assertNull(answer(r));
		assertEquals(Set.of(f.a().getUuid(), f.m().getUuid()), members(f.o()));
		assertUnchanged(f, r0, n0);
	}

	@Test
	@Timeout(60)
	public void row03AnAdminGrantOnAnObjectIsNotOrganizationAdmin() throws Exception {
		Fx f = fx();
		UUID c = componentService.createComponent("arc-" + tag(), f.o(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU)
				.getUuid();
		UserData k = member(f.o(), PermissionType.READ_ONLY);
		userService.setUserPermission(k.getUuid(), f.o(), PermissionScope.COMPONENT, c, PermissionType.ADMIN, List.of(), null, WU);
		k = userService.getUserData(k.getUuid()).orElseThrow();
		int r0 = revision(f.o());
		int n0 = audits(f.o()).size();

		ExecutionResult r = archive(k, f.o());

		assertEquals("Not authorized", message(r));
		assertNull(answer(r));
		assertEquals(Set.of(f.a().getUuid(), f.m().getUuid(), k.getUuid()), members(f.o()));
		assertUnchanged(f, r0, n0);
	}

	@Test
	@Timeout(60)
	public void row04AnotherOrganizationsUuidReadsNotFound() throws Exception {
		Fx f = fx();
		UUID p = org();
		UserData ap = member(p, PermissionType.ADMIN);

		ExecutionResult r = archive(f.a(), p);

		assertEquals(NOT_FOUND, message(r));
		assertEquals("BAD_REQUEST", errorType(r));
		assertNull(answer(r));
		assertNull(status(p));
		assertEquals(Set.of(ap.getUuid()), members(p));
	}

	@Test
	@Timeout(60)
	public void row05AnUnknownUuidReadsTheSame() throws Exception {
		Fx f = fx();
		UUID p = org();
		member(p, PermissionType.ADMIN);
		ExecutionResult foreign = archive(f.a(), p);

		ExecutionResult r = archive(f.a(), UUID.randomUUID());

		assertEquals(message(foreign), message(r));
		assertEquals(errorType(foreign), errorType(r));
		assertEquals(NOT_FOUND, message(r));
		assertNull(answer(r));
	}

	@Test
	@Timeout(60)
	public void row06AnArchivedOrganizationReadsTheSameAndASecondArchiveWritesNothing() throws Exception {
		Fx f = fx();
		UUID p = org();
		member(p, PermissionType.ADMIN);
		ExecutionResult foreign = archive(f.a(), p);
		assertArchivedBy(f.a(), f.o());
		int r1 = revision(f.o());
		int n1 = audits(f.o()).size();

		ExecutionResult r = archive(f.a(), f.o());

		assertEquals(message(foreign), message(r));
		assertEquals(errorType(foreign), errorType(r));
		assertNull(answer(r));
		assertEquals(r1, revision(f.o()));
		assertEquals(n1, audits(f.o()).size());
		assertEquals(Set.of(f.a().getUuid()), members(f.o()));
	}

	@Test
	@Timeout(60)
	public void row07AGlobalAdminArchivesAnyOrganization() throws Exception {
		UserData g = globalAdmin();
		UUID q = org();
		UserData aq = member(q, PermissionType.ADMIN);
		member(q, PermissionType.READ_WRITE);
		Key k = key(q, ApiTypeEnum.FREEFORM, q, UUID.randomUUID().toString());

		assertArchivedBy(g, q);

		assertEquals(StatusEnum.ARCHIVED, status(q));
		assertEquals(Set.of(aq.getUuid()), members(q));
		assertTrue(liveKeys(q).isEmpty());
		assertTrue(revoked(k.uuid()));
		assertTrue(orgsOf(g).isEmpty(), "the global admin joins nothing");
	}

	@Test
	@Timeout(60)
	public void row08AMalformedIdGetsTheUuidRefusal() throws Exception {
		Fx f = fx();

		ExecutionResult r = archive(f.a(), "not-a-uuid");

		assertEquals("orgUuid: not a uuid", message(r));
		assertEquals("BAD_REQUEST", errorType(r));
		assertNull(status(f.o()));
	}

	@Test
	@Timeout(60)
	public void row09AVerifiedKeyOnGraphqlCannotArchive() throws Exception {
		UUID o = org();
		member(o, PermissionType.ADMIN);
		Key k = key(o, ApiTypeEnum.FREEFORM, o, UUID.randomUUID().toString());
		apiKeyService.setPermissionsOnApiKey(k.uuid(), null, List.of(new PermissionDto(o, PermissionScope.ORGANIZATION, o,
				PermissionType.ADMIN, Set.of(PermissionFunction.RESOURCE), null)), WU);
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new AnonymousAuthenticationToken("tpf48", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
		SecurityContextHolder.setContext(ctx);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/graphql");
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(apiKeyService.getApiKey(k.uuid()).orElseThrow(), 1, ADDRESS));

		ExecutionResult r = dgsQueryExecutor.execute(ARCHIVE, vars("o", o.toString()), Map.of(), new HttpHeaders(), null,
				new ServletWebRequest(request));

		assertEquals("Not authorized", message(r));
		assertNull(answer(r));
		assertNull(status(o));
		assertEquals(ApiKeyStatus.ACTIVE, keyData(k.uuid()).getStatus());
	}

	// ---------- rows 10 to 14: no credential of the organization works after the archive ----------

	@Test
	@Timeout(60)
	public void row10EveryKeyTypeIsRevoked() throws Exception {
		UUID o = org();
		UserData a = member(o, PermissionType.ADMIN);
		UUID c = componentService.createComponent("arc-" + tag(), o, ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU)
				.getUuid();
		List<Key> keys = List.of(
				key(o, ApiTypeEnum.ORGANIZATION, o, null),
				key(o, ApiTypeEnum.ORGANIZATION_RW, o, null),
				key(o, ApiTypeEnum.FREEFORM, o, UUID.randomUUID().toString()),
				key(a.getUuid(), ApiTypeEnum.USER, o, UUID.randomUUID().toString()),
				key(c, ApiTypeEnum.COMPONENT, null, null),
				key(UUID.randomUUID(), ApiTypeEnum.INSTANCE, o, null),
				key(UUID.randomUUID(), ApiTypeEnum.CLUSTER, o, null));
		for (Key k : keys) assertEquals(k.uuid(), matches(k), k.row().getObjectType() + " matches before the archive");

		assertArchivedBy(a, o);

		for (Key k : keys) {
			assertNull(matches(k), k.row().getObjectType() + " must not match after the archive");
			assertTrue(revoked(k.uuid()), k.row().getObjectType() + " is revoked");
		}
		assertTrue(liveKeys(o).isEmpty());
	}

	@Test
	@Timeout(60)
	public void row11AnAccessTokenIssuedBeforeIsRefused() throws Exception {
		UUID o = org();
		UserData a = member(o, PermissionType.ADMIN);
		Key f = key(o, ApiTypeEnum.FREEFORM, o, UUID.randomUUID().toString());
		String token = apiTokenService.issue(apiKeyService.getApiKey(f.uuid()).orElseThrow(), firstSecret(f.uuid()),
				ApiTokenService.AUD_PROGRAMMATIC);
		assertTrue(filter(token).admitted(), "the token works before the archive");

		assertArchivedBy(a, o);

		assertRefused(filter(token));
	}

	@Test
	@Timeout(60)
	public void row12ACliSessionApprovedBeforeIsRefused() throws Exception {
		UUID o = org();
		UserData a = member(o, PermissionType.ADMIN);
		ApiKey u = apiKeyService.createObjectApiKey(a.getUuid(), ApiTypeEnum.USER, o, UUID.randomUUID().toString(), "tpf48 cli", WU);
		CliSessionService.DeviceAuthorization d = cliSessionService.start(new CliSessionService.DeviceInfo("tpf48-host", null, null, null, null));
		cliSessionService.approveWithKey(d.userCode(), a, u.getUuid());
		CliSessionService.PollResult p = cliSessionService.poll(d.deviceCode());
		assertEquals(CliSessionService.PollOutcome.DELIVERED, p.outcome());
		assertNotNull(p.delivery().refreshToken());
		String token = apiTokenService.issueForSession(apiKeyService.getApiKey(u.getUuid()).orElseThrow(), p.delivery().session(),
				ApiTokenService.AUD_PROGRAMMATIC);
		assertTrue(filter(token).admitted(), "the session token works before the archive");

		assertArchivedBy(a, o);

		assertTrue(cliSessionService.refresh(p.delivery().refreshToken(), ADDRESS).isEmpty(), "no refresh after the archive");
		assertRefused(filter(token));
	}

	@Test
	@Timeout(60)
	public void row13AFederatedTemplateRuleAndItsIdentityRowEnd() throws Exception {
		UUID o = org();
		UserData a = member(o, PermissionType.ADMIN);
		String iss = issuer();
		FederatedTrustRule r = rule(o, iss);
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(Provider.GITHUB_ACTIONS);
		fi.setIssuer(iss);
		fi.setOwner(OWNER);
		fi.setRepository(OWNER + "/app");
		ApiKey i = apiKeyService.ensureFederatedIdentity(o, fi, WU);
		String fp = ApiTokenService.fingerprint(List.of(r));
		assertTrue(federatedTrustRuleService.usable(List.of(r.getUuid()), fp).isPresent());
		assertEquals(ApiKeyStatus.ACTIVE, keyData(i.getUuid()).getStatus());

		assertArchivedBy(a, o);

		FederatedTrustRule after = federatedTrustRuleService.get(r.getUuid()).orElseThrow();
		assertEquals(FederatedTrustRule.Status.DISABLED, after.getStatus());
		assertEquals(r.getVersion() + 1, after.getVersion());
		assertTrue(federatedTrustRuleService.usable(List.of(r.getUuid()), fp).isEmpty());
		assertTrue(revoked(i.getUuid()));
		ExchangeException e = assertThrows(ExchangeException.class, () -> federatedTrustRuleService.exchange(assertion(iss), null));
		assertEquals("UNTRUSTED_ISSUER", e.reason().name());
		assertEquals("invalid_grant", e.code());
		assertEquals(FederatedTrustRuleService.NOT_ACCEPTED, e.getMessage());
		assertTrue(revoked(i.getUuid()), "the refused exchange revived nothing");
	}

	@Test
	@Timeout(60)
	public void row14APendingInvitationCannotJoinAnArchivedOrganization() throws Exception {
		UUID o = org();
		UserData a = member(o, PermissionType.ADMIN);
		UserData e1 = outsider("arc-e1-" + tag() + "@archive.io");
		UserData e2 = outsider("arc-e2-" + tag() + "@archive.io");
		String s1 = invitation(o, e1.getEmail(), a);
		String s2 = invitation(o, e2.getEmail(), a);
		assertTrue(organizationService.joinUserToOrganization(s1, e1).isPresent(), "the fixture builds a real invitation");
		assertTrue(orgsOf(e1).contains(o));

		assertArchivedBy(a, o);

		RelizaException re = assertThrows(RelizaException.class, () -> organizationService.joinUserToOrganization(s2, e2));
		assertEquals("Invitation expired", re.getMessage());
		assertFalse(orgsOf(e2).contains(o));
		assertFalse(orgsOf(e1).contains(o), "the archive removed the earlier joiner with the other non-admins");
		assertEquals(StatusEnum.ARCHIVED, status(o));
	}

	// ---------- rows 15 to 19: writers beside an archive ----------

	private static OrganizationData.Settings settings() {
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setJustificationMandatory(Boolean.TRUE);
		return settings;
	}

	private Boolean justificationMandatory(UUID org) {
		return getOrganizationService.getOrganizationData(org).orElseThrow().getSettings().getJustificationMandatory();
	}

	@Test
	@Timeout(60)
	public void row15TheArchiveKeepsAConcurrentSettingsSave() throws Exception {
		UUID o = org();
		member(o, PermissionType.ADMIN);

		Boolean archived = held(() -> organizationService.updateSettings(o, settings(), WU),
				() -> inView(() -> {
					getOrganizationService.getOrganizationData(o);
					return saasOrganizationService.archiveOrganization(o, WU);
				}));

		assertEquals(Boolean.TRUE, archived);
		assertEquals(StatusEnum.ARCHIVED, status(o));
		assertEquals(Boolean.TRUE, justificationMandatory(o), "the archive wrote the committed settings, not its stale copy");
	}

	@Test
	@Timeout(60)
	public void row16ASettingsSaveFromACopyReadBeforeTheArchiveKeepsArchived() throws Exception {
		UUID o = org();
		member(o, PermissionType.ADMIN);

		held(() -> saasOrganizationService.archiveOrganization(o, WU),
				() -> inView(() -> {
					getOrganizationService.getOrganizationData(o);
					return organizationService.updateSettings(o, settings(), WU);
				}));

		assertEquals(StatusEnum.ARCHIVED, status(o));
		assertEquals(Boolean.TRUE, justificationMandatory(o));
	}

	@Test
	@Timeout(60)
	public void row17TheSameThroughTheOtherFunnel() throws Exception {
		UUID o = org();
		member(o, PermissionType.ADMIN);
		String id = "tpf48-" + tag();

		held(() -> saasOrganizationService.archiveOrganization(o, WU),
				() -> inView(() -> {
					getOrganizationService.getOrganizationData(o);
					return saasOrganizationService.addApprovalRole(o, new ApprovalRole(id, "TPF-48 role"), WU);
				}));

		assertEquals(StatusEnum.ARCHIVED, status(o));
		assertTrue(getOrganizationService.getOrganizationData(o).orElseThrow().getApprovalRoles().stream()
				.anyMatch(ar -> id.equals(ar.id())));
	}

	@Test
	@Timeout(60)
	public void row18AJoinThatReadBeforeTheArchiveIsRefused() throws Exception {
		UUID o = org();
		UserData a = member(o, PermissionType.ADMIN);
		UserData e3 = outsider("arc-e3-" + tag() + "@archive.io");
		String s3 = invitation(o, e3.getEmail(), a);

		RelizaException re = held(() -> saasOrganizationService.archiveOrganization(o, WU),
				() -> assertThrows(RelizaException.class, () -> inView(() -> {
					getOrganizationService.getOrganizationData(o);
					return organizationService.joinUserToOrganization(s3, e3);
				})));

		assertEquals("Invitation expired", re.getMessage());
		assertFalse(orgsOf(e3).contains(o));
		assertEquals(StatusEnum.ARCHIVED, status(o));
	}

	@Test
	@Timeout(60)
	public void row19TheApprovalRoleMutationsOnALiveOrganization() throws Exception {
		UUID o = org();
		UserData a = member(o, PermissionType.ADMIN);
		String id = "tpf48-" + tag();

		ExecutionResult add = asPerson(a, "mutation($o: ID!, $r: ApprovalRoleInput!) { addApprovalRole(orgUuid: $o, approvalRole: $r) { uuid } }",
				vars("o", o.toString(), "r", Map.of("id", id, "displayView", "TPF-48 role")));
		assertTrue(ok(add), add.getErrors().toString());
		assertTrue(getOrganizationService.getOrganizationData(o).orElseThrow().getApprovalRoles().stream().anyMatch(ar -> id.equals(ar.id())));
		assertNull(status(o));

		ExecutionResult del = asPerson(a, "mutation($o: ID!, $i: String!) { deleteApprovalRole(orgUuid: $o, approvalRoleId: $i) { uuid } }",
				vars("o", o.toString(), "i", id));
		assertTrue(ok(del), del.getErrors().toString());
		assertFalse(getOrganizationService.getOrganizationData(o).orElseThrow().getApprovalRoles().stream().anyMatch(ar -> id.equals(ar.id())));
		assertNull(status(o));
	}

	// ---------- row 20: all or nothing ----------

	@Test
	@Timeout(60)
	public void row20AFailureInTheSweepRollsTheWholeArchiveBack() throws Exception {
		UUID o = org();
		UserData a = member(o, PermissionType.ADMIN);
		UserData m = member(o, PermissionType.READ_WRITE);
		FederatedTrustRule r = rule(o, issuer());
		Key f1 = key(o, ApiTypeEnum.FREEFORM, o, UUID.randomUUID().toString());
		Key f2 = key(o, ApiTypeEnum.FREEFORM, o, UUID.randomUUID().toString());
		int r0 = revision(o);
		int n0 = audits(o).size();
		jdbcTemplate.update("UPDATE rearm.api_keys SET schema_version = 1 WHERE uuid = ?", f2.uuid());
		try {
			ExecutionResult res = archive(a, o);

			assertTrue(message(res).startsWith("Internal server error (ref "), message(res));
			assertNull(answer(res));
			assertNull(status(o));
			assertEquals(Set.of(a.getUuid(), m.getUuid()), members(o));
			FederatedTrustRule after = federatedTrustRuleService.get(r.getUuid()).orElseThrow();
			assertEquals(FederatedTrustRule.Status.ACTIVE, after.getStatus());
			assertEquals(r.getVersion(), after.getVersion());
			assertEquals(ApiKeyStatus.ACTIVE, keyData(f1.uuid()).getStatus());
			assertEquals(f1.uuid(), matches(f1));
			assertEquals(r0, revision(o));
			assertEquals(n0, audits(o).size());
		} finally {
			jdbcTemplate.update("UPDATE rearm.api_keys SET schema_version = 0 WHERE uuid = ?", f2.uuid());
		}
	}

	// ---------- the service's own already-archived answer ----------

	@Test
	@Timeout(60)
	public void row21TheServiceAnswersFalseForAnArchivedOrganizationAndWritesNothing() throws Exception {
		UUID o = org();
		member(o, PermissionType.ADMIN);
		member(o, PermissionType.READ_WRITE);
		assertTrue(apiKeyOrgService.archiveOrganization(o, WU));
		int r1 = revision(o);
		int n1 = audits(o).size();
		Key late = key(o, ApiTypeEnum.FREEFORM, o, UUID.randomUUID().toString());

		assertFalse(apiKeyOrgService.archiveOrganization(o, WU));
		assertFalse(apiKeyOrgService.archiveOrganization(UUID.randomUUID(), WU), "a missing organization reads false");

		assertEquals(r1, revision(o));
		assertEquals(n1, audits(o).size());
		assertEquals(ApiKeyStatus.ACTIVE, keyData(late.uuid()).getStatus(), "a second call runs no sweep");
		assertEquals(Optional.of(StatusEnum.ARCHIVED.name()), organizationRepository.findById(o).map(x -> String.valueOf(x.getRecordData().get(CommonVariables.STATUS_FIELD))));
	}
}
