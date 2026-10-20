/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Audit;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.Link;
import io.reliza.repositories.VersionAssignmentRepository;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuditService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.ReleaseService;
import io.reliza.service.SbomWriteAuthority;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-36: a release artifact append keeps what other requests committed to the release while the
 * upload ran. addArtifactManual reads the release before the upload and, in the same persistence
 * context (open-in-view in production, the fetcher's transaction here), ReleaseService.addArtifact
 * and replaceArtifact used to save that pre-upload copy whole. They now take the release row lock
 * and refresh the entity right before the append; the upload itself stays outside the lock.
 *
 * <p>Cases 1 to 3 are deterministic: the second writer runs inside the stubbed upload step, on its
 * own transaction and connection, and commits before the append. Its 5 s lock timeout turns a
 * release row lock held across the upload into a lock-timeout failure instead of a hang. Case 4 is
 * the concurrent shape of ApprovalVoteConcurrencyTest.everyConcurrentVoteIsPersisted.
 */
@SpringBootTest(classes = {App.class})
public class ReleaseArtifactAppendConcurrencyTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final int CONCURRENT_APPENDS = 8;

	@MockitoSpyBean private UserService userService;
	@MockitoSpyBean private ArtifactService artifactService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private ReleaseService releaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private AuditService auditService;
	@Autowired private VersionAssignmentRepository versionAssignmentRepository;
	@Autowired private GetComponentService getComponentService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the resolver's authorization refuses both first. */
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

	/** A component with one DRAFT release on its base branch holding one externally stored artifact. */
	private record Owned(UUID org, UUID component, UUID release, UUID artifact) {}

	private Owned owned(UUID org) throws Exception {
		Component component = componentService.createComponent("tpf36_" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch branch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component.getUuid()).org(org)
				.branch(branch.getUuid()).version("1.0.0").status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
		UUID artifact = artifact(org, ArtifactType.OTHER);
		releaseService.addArtifact(artifact, release, WU);
		return new Owned(org, component.getUuid(), release, artifact);
	}

	private UUID artifact(UUID org, ArtifactType type) throws RelizaException {
		Link link = new Link();
		link.setUri("https://example.com/tpf36/" + UUID.randomUUID());
		ArtifactDto dto = ArtifactDto.builder()
				.org(org)
				.type(type)
				.displayIdentifier("original-" + UUID.randomUUID())
				.storedIn(StoredIn.EXTERNALLY)
				.downloadLinks(List.of(link))
				.build();
		return artifactService.createArtifact(dto, WU).getUuid();
	}

	/** A COMPONENT READ_WRITE RESOURCE user on {@code component}: writer A of the TPF-1 class. */
	private UserData writerOf(UUID org, UUID component) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("TPF-36 " + tag, "tpf36-" + tag + "@append.io", true, List.of(org),
				"tpf36-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org, PermissionScope.COMPONENT, component,
				PermissionType.READ_WRITE, List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/**
	 * Calls addArtifactManual through the bean as {@code user}, so the fetcher's transaction gives
	 * the one persistence context production gets from open-in-view.
	 */
	private ReleaseData addAs(UserData user, UUID release, UUID artifactUuid, Map<String, Object> artifact)
			throws RelizaException {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf36-append-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);

		Map<String, Object> input = new LinkedHashMap<>();
		input.put("release", release.toString());
		input.put("artifact", artifact);
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getArgument("artifactInput")).thenReturn(input);
		return releaseDatafetcher.addArtifactManual(dfe, artifactUuid);
	}

	/**
	 * The other request: DRAFT to ASSEMBLED on its own transaction and connection, committed before
	 * this returns. Returns the revision that write committed (R1).
	 */
	private int secondWriter(UUID release) {
		TransactionTemplate other = new TransactionTemplate(transactionManager);
		other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return other.execute(s -> {
			versionAssignmentRepository.setLocalLockTimeout("5s");
			return ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.ASSEMBLED, WU).getRevision();
		});
	}

	private ReleaseData readBack(UUID release) {
		return sharedReleaseService.getReleaseData(release).orElseThrow();
	}

	private int revisionOf(UUID release) {
		return sharedReleaseService.getRelease(release).orElseThrow().getRevision();
	}

	private static List<ReleaseUpdateEvent> events(ReleaseData rd, ReleaseUpdateScope scope) {
		return rd.getUpdateEvents().stream().filter(e -> e.rus() == scope).toList();
	}

	private static List<UUID> artifactAddedIds(ReleaseData rd) {
		return events(rd, ReleaseUpdateScope.ARTIFACT).stream()
				.filter(e -> e.rua() == ReleaseUpdateAction.ADDED).map(ReleaseUpdateEvent::objectId).toList();
	}

	/** The lifecycle the other request set, and its LIFECYCLE CHANGED DRAFT to ASSEMBLED event, survived. */
	private static void assertLifecycleSurvived(ReleaseData rd) {
		assertEquals(ReleaseLifecycle.ASSEMBLED, rd.getLifecycle(), "the concurrent lifecycle change was erased");
		List<ReleaseUpdateEvent> lifecycle = events(rd, ReleaseUpdateScope.LIFECYCLE);
		assertTrue(lifecycle.stream().anyMatch(e -> e.rua() == ReleaseUpdateAction.CHANGED
				&& ReleaseLifecycle.DRAFT.name().equals(e.oldValue())
				&& ReleaseLifecycle.ASSEMBLED.name().equals(e.newValue())),
				"the concurrent LIFECYCLE event was erased: " + lifecycle);
	}

	/** The audit row of {@code revision} holds the record the other request committed (ASSEMBLED). */
	private void assertAuditRecordsAssembled(UUID release, int revision) {
		Optional<Audit> audit = auditService.getAuditRevision(release, TableName.RELEASES, revision);
		assertTrue(audit.isPresent(), "no audit row for revision " + revision);
		assertEquals(ReleaseLifecycle.ASSEMBLED.name(), audit.get().getRevisionRecordData().get("lifecycle"));
	}

	@Test
	public void aNewArtifactAppendKeepsALifecycleChangeCommittedDuringTheUpload() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Owned o = owned(org);
		UserData writer = writerOf(org, o.component());
		AtomicInteger r1 = new AtomicInteger(-1);
		doAnswer(inv -> {
			r1.set(secondWriter(o.release()));
			return inv.callRealMethod();
		}).when(artifactService).createArtifact(any(), any());

		Map<String, Object> link = new LinkedHashMap<>();
		link.put("uri", "https://example.com/tpf36/new-" + UUID.randomUUID());
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("type", ArtifactType.OTHER.name());
		artifact.put("displayIdentifier", "new-" + UUID.randomUUID());
		artifact.put("storedIn", StoredIn.EXTERNALLY.name());
		artifact.put("downloadLinks", List.of(link));
		ReleaseData returned = addAs(writer, o.release(), null, artifact);

		ReleaseData rd = readBack(o.release());
		assertEquals(2, rd.getArtifacts().size(), rd.getArtifacts().toString());
		assertEquals(o.artifact(), rd.getArtifacts().get(0));
		UUID added = rd.getArtifacts().get(1);
		assertLifecycleSurvived(rd);
		assertTrue(artifactAddedIds(rd).contains(added), "no ARTIFACT ADDED event for " + added);
		assertEquals(r1.get() + 1, revisionOf(o.release()));
		assertAuditRecordsAssembled(o.release(), r1.get() + 1);
		assertEquals(ReleaseLifecycle.ASSEMBLED, returned.getLifecycle());
		assertEquals(List.of(o.artifact(), added), returned.getArtifacts());
	}

	@Test
	public void aReplacementWithANewUuidKeepsALifecycleChangeCommittedDuringTheUpload() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Owned o = owned(org);
		UserData writer = writerOf(org, o.component());
		UUID b = artifact(org, ArtifactType.OTHER);
		AtomicInteger r1 = new AtomicInteger(-1);
		doAnswer(inv -> {
			r1.set(secondWriter(o.release()));
			return b;
		}).when(artifactService).uploadArtifact(any(), any(), any(), any(), any());

		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("type", ArtifactType.OTHER.name());
		artifact.put("displayIdentifier", "replacement-" + UUID.randomUUID());
		artifact.put("file", new MockMultipartFile("file", "replacement.txt", "text/plain",
				"tpf-36 replacement".getBytes(StandardCharsets.UTF_8)));
		addAs(writer, o.release(), o.artifact(), artifact);

		verify(artifactService, times(1)).uploadArtifact(any(), any(), any(), any(), any());
		ReleaseData rd = readBack(o.release());
		assertLifecycleSurvived(rd);
		assertTrue(artifactAddedIds(rd).contains(b), "no ARTIFACT ADDED event for " + b);
		assertEquals(List.of(b), rd.getArtifacts());
		assertEquals(r1.get() + 1, revisionOf(o.release()));
		assertAuditRecordsAssembled(o.release(), r1.get() + 1);
	}

	@Test
	public void theProgrammaticReleaseAppendKeepsALifecycleChangeAndItsOwnEarlierAppend() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Owned o = owned(org);
		UUID b = artifact(org, ArtifactType.OTHER);
		UUID c = artifact(org, ArtifactType.OTHER);
		AtomicInteger r1 = new AtomicInteger(-1);

		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.execute(s -> {
			// The managed read ProgrammaticReleaseTargetService does under open-in-view.
			ReleaseData rd = sharedReleaseService.getReleaseData(o.release()).orElseThrow();
			try {
				doAnswer(inv -> {
					r1.set(secondWriter(o.release()));
					return List.of(b, c);
				}).when(artifactService).uploadListOfArtifacts(any(), any(), any(), any(), any());
				ComponentData cd = getComponentService.getComponentData(rd.getComponent()).orElseThrow();
				OrganizationData od = getOrganizationService.getOrganizationData(rd.getOrg()).orElseThrow();
				releaseService.processReleaseArtifacts(List.of(Map.of("type", "OTHER"), Map.of("type", "OTHER")),
						rd, cd, od, rd.getVersion(), SbomWriteAuthority.NO_PRINCIPAL, WU);
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
			return null;
		});

		ReleaseData rd = readBack(o.release());
		assertLifecycleSurvived(rd);
		assertEquals(List.of(o.artifact(), b, c), rd.getArtifacts());
		assertTrue(artifactAddedIds(rd).containsAll(List.of(b, c)), artifactAddedIds(rd).toString());
		assertEquals(r1.get() + 2, revisionOf(o.release()));
	}

	@Test
	public void everyConcurrentAppendIsPersisted() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Owned o = owned(org);
		List<UUID> arts = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_APPENDS; i++) arts.add(artifact(org, ArtifactType.OTHER));
		int startRevision = revisionOf(o.release());

		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_APPENDS);
		CountDownLatch startTogether = new CountDownLatch(1);
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		boolean allFinished;
		try {
			for (UUID art : arts) {
				pool.submit(() -> {
					try {
						startTogether.await();
						tx.execute(s -> {
							// The fetcher's unlocked pre-read, sharing this persistence context.
							sharedReleaseService.getReleaseData(o.release());
							return releaseService.addArtifact(art, o.release(), WU);
						});
					} catch (RuntimeException | InterruptedException e) {
						// Kept, not counted: a swallowed cause would read as a lost append.
						errors.add(e);
					}
					return null;
				});
			}
			startTogether.countDown();
			pool.shutdown();
			allFinished = pool.awaitTermination(120, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		assertTrue(allFinished, "appenders did not finish within the timeout");
		assertEquals(List.of(), errors, "no appender should have errored");
		ReleaseData rd = readBack(o.release());
		List<UUID> expected = new ArrayList<>(arts);
		expected.add(o.artifact());
		assertEquals(CONCURRENT_APPENDS + 1, rd.getArtifacts().size(), "an append was lost: " + rd.getArtifacts());
		assertEquals(new HashSet<>(expected), new HashSet<>(rd.getArtifacts()));
		List<UUID> addedEvents = artifactAddedIds(rd).stream().filter(arts::contains).toList();
		assertEquals(CONCURRENT_APPENDS, addedEvents.size(), addedEvents.toString());
		assertEquals(new HashSet<>(arts), new HashSet<>(addedEvents));
		assertEquals(startRevision + CONCURRENT_APPENDS, revisionOf(o.release()));
	}
}
