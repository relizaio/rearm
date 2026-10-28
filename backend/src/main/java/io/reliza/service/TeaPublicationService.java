/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.SidPurlUtils;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.MergedSbomUnavailableException;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ParentRelease;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublicationData.TeaAggregateArtifact;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.model.tea.publication.TeaArtifactDocument;
import io.reliza.model.tea.publication.TeaArtifactFormatDocument;
import io.reliza.model.tea.publication.TeaChecksumDocument;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.TeaPublicationRepository;
import io.reliza.repositories.TeaPublicationRepository.TeaPublicationHeadView;
import io.reliza.repositories.TeaPublishedCollectionRepository;
import io.reliza.service.ReleaseService.PublicationMergeRequest;
import io.reliza.service.SbomProfileResolver.SbomProfileResolution;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaProfileService.TeaOrgDiscovery;
import io.reliza.service.TeaPublishLock.Lease;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;
import io.reliza.service.tea.TeaExternalUrl;
import io.reliza.service.tea.TeaTransformerService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The TEA publication engine (task TEA-5): the explicit publish act per release. A publish
 * generates the aggregated SBOM at that moment with the effective profile's knobs, freezes it as a
 * generated artifact of the release, records the publication head and an immutable collection
 * version holding the canonical document TEA serves; a re-publish appends a version only when
 * something changed; a hide flips the state and touches no collection row; a product's publish
 * cascades to its component releases under the product's profile, bounded by each one's own.
 *
 * <p>Authorization is not this service's: the GraphQL fetchers check PUBLISH_EXTERNALLY before
 * calling it, and part 6 runs as the system. What parts 5 and 6 call is the public surface below.
 */
@Slf4j
@Service
public class TeaPublicationService {

	/** PUBLISH refuses a PUBLISHED head, REPUBLISH a missing one; PUBLISH_OR_REPUBLISH (part 6) takes both. */
	public enum TeaPublishMode { PUBLISH, REPUBLISH, PUBLISH_OR_REPUBLISH }

	public enum TeaPublicationState { PUBLISHED, HIDDEN }

	public enum TeaPublishOutcome { PUBLISHED, REPUBLISHED, UNCHANGED }

	public enum TeaChildOutcome {
		PUBLISHED, REPUBLISHED, UNCHANGED, SKIPPED_DIRECT, SKIPPED_HIDDEN, SKIPPED_OTHER_PRODUCT, REFUSED;

		/**
		 * Whether the cascade left the child alone on purpose (it is published directly, hidden, or held
		 * by another product). Every value is decided here, so a new one has to be classified.
		 */
		public boolean isSkipped() {
			return switch (this) {
				case SKIPPED_DIRECT, SKIPPED_HIDDEN, SKIPPED_OTHER_PRODUCT -> true;
				case PUBLISHED, REPUBLISHED, UNCHANGED, REFUSED -> false;
			};
		}
	}

	public enum TeaRideAlongStatus { PUBLISHED, SKIPPED_NO_RAW_BYTES, SKIPPED_NO_CHECKSUM,
			SKIPPED_VERSION_NOT_INTEGER, SKIPPED_SIGNATURE_TYPE, SKIPPED_NO_DOWNLOAD_LINK, SKIPPED_LINK_NOT_HTTP_URL }

	public enum TeaExposureState { PUBLIC, PRIVATE, CONCEALED }

	/** Why an exposure is CONCEALED (3.9): logged and shown to the organization, never served on TEA. */
	public enum TeaConcealReason { HIDDEN, RELEASE_MISSING, RELEASE_ARCHIVED, LIFECYCLE_BELOW_ASSEMBLED,
			COMPONENT_MISSING, PROFILE_CONFLICT, PUBLISHING_DISABLED, PRODUCT_MISSING, PRODUCT_CONCEALED }

	/**
	 * Who is waiting on an act (round 4, T-9): a person or a CLI behind a proxy, or part 6's
	 * background executor. The lane sets the act's one wait budget; it is a Java value only.
	 */
	public enum TeaActLane { INTERACTIVE, BACKGROUND }

	/**
	 * A publish request; the four-argument form is the interactive lane, which part 6 must not use.
	 * {@code gate} is null for every caller but part 6's automatic publish (task TEA-7, round 3,
	 * T-7): the five- and four-argument forms carry none.
	 */
	public record TeaPublishRequest(UUID release, TeaPublishMode mode, String comment, WhoUpdated wu,
			TeaActLane lane, TeaPublishGate gate) {
		public TeaPublishRequest(UUID release, TeaPublishMode mode, String comment, WhoUpdated wu, TeaActLane lane) {
			this(release, mode, comment, wu, lane, null);
		}

		public TeaPublishRequest(UUID release, TeaPublishMode mode, String comment, WhoUpdated wu) {
			this(release, mode, comment, wu, TeaActLane.INTERACTIVE, null);
		}
	}

	/**
	 * Asks whether a publish is still wanted (task TEA-7, round 3, T-7 and T-10). Called once,
	 * under the in-process lock and the lease, on the rows the act's fresh read refreshed, before
	 * the mode, profile and lifecycle gates, before the TEA ids are minted and before anything is
	 * generated or written. It may throw any RuntimeException to withhold the act; the service
	 * neither catches nor wraps it: the lease closes, the in-process lock is released, and nothing
	 * was read for the act or written. A null gate is no check.
	 */
	@FunctionalInterface
	public interface TeaPublishGate {
		void check(ReleaseData release, ComponentData component);
	}

	public record TeaRideAlongOutcome(UUID artifact, TeaRideAlongStatus status, String detail) {}

	public record TeaChildPublishResult(UUID release, String componentName, String version,
			TeaChildOutcome outcome, TeaPublishRefusal refusal, String detail, UUID publication) {}

	public record TeaPublishResult(TeaPublicationData publication, TeaPublishedCollectionData collection,
			TeaPublishOutcome outcome, List<TeaRideAlongOutcome> artifacts, List<TeaChildPublishResult> children) {}

	/**
	 * What a publication is right now. {@code concealedBecause} is null unless the state is
	 * CONCEALED. {@code governingComponent} is the publication's own component, a cascade child's
	 * too; {@code resolved} is that component's own resolution, except that a cascade child capped
	 * to PRIVATE by its owner carries a copy whose visibility is PRIVATE, so a reader of
	 * {@code resolved.profile().getVisibility()} sees what {@code state} says. {@code cappedBy} is
	 * the owner product's publication for a cascade child, null for a direct one.
	 */
	public record TeaPublicationExposure(TeaExposureState state, TeaConcealReason concealedBecause,
			ComponentData governingComponent, TeaResolvedProfile resolved, TeaPublicationData publication,
			TeaPublicationData cappedBy) {}

	/**
	 * What the release page reads. {@code cascades}, {@code cascadeChildren} and
	 * {@code profileRevision} are what the confirmation dialog states.
	 */
	public record TeaPublishPreview(UUID release, TeaCollectionBelongsToType kind, boolean canPublish,
			TeaPublishRefusal refusal, String refusalMessage, TeaVisibility effectiveVisibility,
			TeaProfileSource profileSource, UUID profileSourceObject, String apiBase, String publicUrl,
			boolean cascades, int cascadeChildren, Integer profileRevision,
			TeaPublicationData publication, List<TeaPublishedCollectionData> collections, List<String> profiles,
			TeaPublishing publishing) {}

	/** One SBOM profile name of a publish and the recipe it resolved to (task TEA-28). */
	record TeaNamedRecipe(String name, SbomProfileData recipe) {}

	/** The media type of the generated CycloneDX JSON aggregate. */
	static final String CYCLONEDX_JSON_MEDIA_TYPE = "application/vnd.cyclonedx+json";
	static final String OCTET_STREAM = "application/octet-stream";
	/** The product cascade recurses through nested products at most this deep. */
	static final int MAX_CASCADE_DEPTH = 16;

	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private TeaProfileResolver teaProfileResolver;
	@Autowired private SbomProfileResolver sbomProfileResolver;
	@Autowired private TeaProfileService teaProfileService;
	@Autowired private OrganizationService organizationService;
	@Autowired private ComponentService componentService;
	@Autowired private ReleaseService releaseService;
	@Autowired private SyntheticArtifactService syntheticArtifactService;
	@Autowired private ArtifactService artifactService;
	@Autowired private TeaPublicationRepository publicationRepository;
	@Autowired private TeaPublishedCollectionRepository collectionRepository;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private AuditService auditService;
	@Autowired private TeaPublishLock teaPublishLock;
	@Autowired private ComponentRepository componentRepository;

	/**
	 * The most an interactive act waits, in total, for other acts on its releases (round 4, T-9):
	 * inside the shortest shipped proxy limit (90 s on /api), with room left for the act's work.
	 */
	static final Duration INTERACTIVE_WAIT = Duration.ofSeconds(30);
	/** The most a background act (part 6) waits, in total, for other acts on its releases. */
	static final Duration BACKGROUND_WAIT = Duration.ofMinutes(5);

	/** {@link #INTERACTIVE_WAIT} and {@link #BACKGROUND_WAIT}, lowered by tests only. */
	private Duration interactiveWait = INTERACTIVE_WAIT;
	private Duration backgroundWait = BACKGROUND_WAIT;

	void setInteractiveWait(Duration wait) {
		this.interactiveWait = null == wait ? INTERACTIVE_WAIT : wait;
	}

	void setBackgroundWait(Duration wait) {
		this.backgroundWait = null == wait ? BACKGROUND_WAIT : wait;
	}

	Duration waitOf(TeaActLane lane) {
		return TeaActLane.BACKGROUND == lane ? backgroundWait : interactiveWait;
	}

	/**
	 * One act's wait budget (round 4, T-9 point 2): every wait the act makes, the in-process lock
	 * and the lease, the product's and each child's, draws on it, so the act never waits longer
	 * than its lane allows in total. It bounds waiting, never work.
	 */
	record WaitBudget(TeaActLane lane, Instant deadline) {
		/** What is left of the budget, never negative. */
		Duration remaining() {
			Duration d = Duration.between(Instant.now(), deadline);
			return d.isNegative() ? Duration.ZERO : d;
		}
	}

	WaitBudget budgetFor(TeaActLane lane) {
		TeaActLane l = null == lane ? TeaActLane.INTERACTIVE : lane;
		return new WaitBudget(l, Instant.now().plus(waitOf(l)));
	}

	/** Re-reads the head and the release rows inside a transaction (3.5 step 0b and step 10, 3.6). */
	@PersistenceContext
	private EntityManager entityManager;

	@Autowired(required = false)
	private ReleaseChangeHook releaseChangeHook;

	/** Proxy of this service, so the commit steps run in their own transaction. */
	@Autowired
	@Lazy
	private TeaPublicationService self;

	private TeaPublicationService proxy() {
		return null != self ? self : this;
	}

	// ------------------------------------------------------------------ reads

	public Optional<TeaPublicationData> getPublicationOfRelease(UUID release) {
		if (null == release) return Optional.empty();
		return publicationRepository.findByRelease(release).map(TeaPublicationData::dataFromRecord);
	}

	public Optional<TeaPublicationData> getPublication(UUID publicationUuid) {
		if (null == publicationUuid) return Optional.empty();
		return publicationRepository.findById(publicationUuid).map(TeaPublicationData::dataFromRecord);
	}

	public List<TeaPublicationData> listPublicationsOfComponent(UUID org, UUID component) {
		if (null == org || null == component) return List.of();
		return publicationRepository.listByComponent(org, component).stream().map(TeaPublicationData::dataFromRecord)
				.toList();
	}

	public List<TeaPublicationData> listPublicationsOfOrg(UUID org, TeaPublicationState state) {
		if (null == org || null == state) return List.of();
		return publicationRepository.listByOrgAndState(org, state).stream()
				.map(TeaPublicationData::dataFromRecord).toList();
	}

	/** Every collection version of a publication, oldest first. */
	public List<TeaPublishedCollectionData> listCollections(UUID publicationUuid) {
		if (null == publicationUuid) return List.of();
		return collectionRepository.listByPublication(publicationUuid).stream()
				.map(TeaPublishedCollectionData::dataFromRecord).toList();
	}

	/**
	 * Every collection version of the organization, of any publication in any state, whose frozen
	 * entries name the TEA artifact id (task TEA-10), oldest first (created date, publication, version).
	 */
	public List<TeaPublishedCollectionData> listCollectionsNamingArtifact(UUID org, UUID teaUuid) {
		if (null == org || null == teaUuid) return List.of();
		String needle = "[{\"teaUuid\":\"" + teaUuid + "\"}]";
		return collectionRepository.listNamingArtifact(org, needle).stream()
				.map(TeaPublishedCollectionData::dataFromRecord).toList();
	}

	public Optional<TeaPublishedCollectionData> getCollection(UUID publicationUuid, int version) {
		if (null == publicationUuid) return Optional.empty();
		return collectionRepository.findVersion(publicationUuid, version).map(TeaPublishedCollectionData::dataFromRecord);
	}

	public Optional<TeaPublishedCollectionData> getLatestCollection(UUID publicationUuid) {
		Optional<TeaPublicationData> head = getPublication(publicationUuid);
		if (head.isEmpty() || head.get().getLatestVersion() < 1) return Optional.empty();
		return getCollection(publicationUuid, head.get().getLatestVersion());
	}

	// ------------------------------------------------------------------ publish

	/**
	 * Publishes, re-publishes, or turns a hidden publication back to published, per the mode.
	 *
	 * @throws TeaPublishRefusedException with the reason; nothing is written for a refusal
	 */
	public TeaPublishResult publish(TeaPublishRequest request) throws TeaPublishRefusedException, RelizaException {
		WaitBudget b = budgetFor(request.lane());
		ReentrantLock lock = releaseLock(request.release());
		lockOrRefuse(lock, request.release(), b);
		try {
			return publishLocked(request, b);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * The in-process lock on the act's budget (3.5 step 0, 3.10): never unbounded; a budget that
	 * runs out, or an interrupted wait, is PUBLISH_IN_PROGRESS with nothing read or written.
	 */
	private void lockOrRefuse(ReentrantLock lock, UUID release, WaitBudget b) throws TeaPublishRefusedException {
		Duration remaining = b.remaining();
		boolean locked;
		try {
			locked = lock.tryLock(remaining.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("TEA publication of release {}: the wait for the in-process lock was interrupted", release, e);
			throw publishInProgress(release, b, remaining);
		}
		if (!locked) throw publishInProgress(release, b, remaining);
	}

	/**
	 * One in-process lock per release around a whole publish or hide: the cheap first gate, so two
	 * acts on one release in this process queue here rather than on the lease's polls. Across
	 * instances the per-release lease of {@link TeaPublishLock}, taken right after this lock and
	 * held to the act's end, is what serializes (3.10).
	 */
	private final ConcurrentHashMap<UUID, ReentrantLock> releaseLocks = new ConcurrentHashMap<>();

	private ReentrantLock releaseLock(UUID release) {
		return releaseLocks.computeIfAbsent(null == release ? new UUID(0, 0) : release, k -> new ReentrantLock());
	}

	/**
	 * The act under the in-process lock: the lease, then every read the act builds on, fresh,
	 * then the gates, the content, the commit, the cascade and the notification; the lease is
	 * closed on every exit. Package-private: the race tests call it from a second thread to stand
	 * for another instance, which skips the in-process lock and takes the lease.
	 */
	TeaPublishResult publishLocked(TeaPublishRequest request) throws TeaPublishRefusedException, RelizaException {
		return publishLocked(request, budgetFor(request.lane()));
	}

	TeaPublishResult publishLocked(TeaPublishRequest request, WaitBudget b)
			throws TeaPublishRefusedException, RelizaException {
		WhoUpdated wu = null == request.wu() ? WhoUpdated.getAutoWhoUpdated() : request.wu();
		TeaPublishMode mode = null == request.mode() ? TeaPublishMode.PUBLISH : request.mode();
		UUID releaseUuid = request.release();
		if (null == releaseUuid) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.RELEASE_NOT_FOUND, null, "Release null not found");
		}
		try (Lease lease = acquireOrRefuse(releaseUuid, b)) {
			HeadAndRelease fresh = proxy().readFresh(releaseUuid);
			ReleaseContext ctx = contextOf(releaseUuid, fresh);
			logWait(lease, ctx);
			if (null != request.gate()) request.gate().check(ctx.rd(), ctx.cd());
			Optional<TeaPublicationData> head = fresh.head().map(TeaPublicationData::dataFromRecord);
			checkMode(mode, head, ctx);
			TeaResolvedProfile resolved = teaProfileResolver.resolveForComponent(ctx.cd());
			checkProfile(resolved, ctx);
			checkLifecycle(ctx, resolved.profile());
			// every name's recipe, before the TEA ids are minted (tasks TEA-27, TEA-28)
			List<TeaNamedRecipe> recipes = recipesFor(ctx, resolved);
			mintIds(ctx, wu);

			Act act = publishCore(lease, ctx, head, resolved, recipes, null, request.comment(), wu);
			List<TeaChildPublishResult> children = new ArrayList<>();
			if (ComponentType.PRODUCT == ctx.cd().getType()) {
				cascade(ctx, act.publication(), resolved, recipes, request.comment(), wu, 1, children, b);
			}
			int childrenPublished = (int) children.stream().filter(c -> TeaChildOutcome.PUBLISHED == c.outcome()
					|| TeaChildOutcome.REPUBLISHED == c.outcome()).count();
			if (act.changed()) notifyPublished(ctx.rd().getUuid(), act, childrenPublished);
			logAct(ctx, act, children);
			return new TeaPublishResult(act.publication(), act.collection(), act.outcome(), act.rideAlongs(), children);
		}
	}

	/**
	 * Step 5 (T-10 point 3): the organization's and the component's TEA ids, each one conditional
	 * statement under the mint lock, before anything is generated, uploaded or written; a mint
	 * that fails is GENERATION_FAILED naming the cause, never a raw constraint error.
	 */
	private void mintIds(ReleaseContext ctx, WhoUpdated wu) throws TeaPublishRefusedException {
		try {
			organizationService.ensureTeaUuid(ctx.rd().getOrg(), wu);
		} catch (RelizaException e) {
			log.error("TEA publication of release {}: the TEA id of organization {} could not be assigned", ctx.label(),
					ctx.rd().getOrg(), e);
			throw new TeaPublishRefusedException(TeaPublishRefusal.GENERATION_FAILED, ctx.rd().getUuid(),
					"Release " + ctx.label() + ": the TEA id of organization " + ctx.rd().getOrg()
							+ " could not be assigned: " + e.getMessage());
		}
		mintComponentId(ctx.cd().getUuid(), ctx.rd().getUuid(), ctx.label(), wu);
	}

	/** The component's TEA id (3.5 step 5, 3.7 step c); a failure is GENERATION_FAILED with the detail of T-10 point 3. */
	private UUID mintComponentId(UUID component, UUID release, String label, WhoUpdated wu)
			throws TeaPublishRefusedException {
		try {
			return componentService.ensureTeaUuid(component, wu);
		} catch (RelizaException e) {
			log.error("TEA publication of release {}: the TEA id of component {} could not be assigned", label,
					component, e);
			throw new TeaPublishRefusedException(TeaPublishRefusal.GENERATION_FAILED, release, "Release " + label
					+ ": the TEA id of component " + component + " could not be assigned: " + e.getMessage());
		}
	}

	/**
	 * The release's lease (3.5 step 0a) on the act's budget: nothing is read, generated or
	 * written before it. A remaining budget of zero is one try. A budget that runs out, or an
	 * interrupted wait, is PUBLISH_IN_PROGRESS, retryable by definition and never GENERATION_FAILED.
	 */
	Lease acquireOrRefuse(UUID release, WaitBudget b) throws TeaPublishRefusedException {
		Duration remaining = b.remaining();
		Lease lease;
		try {
			lease = teaPublishLock.acquire(release, remaining);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("TEA publication of release {}: the wait for the publish lock was interrupted", release, e);
			throw publishInProgress(release, b, remaining);
		}
		if (null == lease) throw publishInProgress(release, b, remaining);
		return lease;
	}

	private TeaPublishRefusedException publishInProgress(UUID release, WaitBudget b, Duration remainingAtStart) {
		// read only to name the release in the message, after the refusal is decided
		String label = String.valueOf(release);
		try {
			Optional<ReleaseData> rd = null == release ? Optional.empty() : sharedReleaseService.getReleaseData(release);
			if (rd.isPresent()) {
				String name = getComponentService.getComponentData(rd.get().getComponent()).map(ComponentData::getName)
						.orElse(String.valueOf(rd.get().getComponent()));
				label = name + " " + rd.get().getVersion() + " (" + release + ")";
			}
		} catch (RuntimeException e) {
			log.error("TEA publication of release {}: reading the release to name it failed", release, e);
		}
		String waited = waitText(waitOf(b.lane()));
		log.warn("TEA publication of release {}: the {} act's wait budget of {} ran out ({} of it was left when this "
				+ "wait began)", label, b.lane(), waited, waitText(remainingAtStart));
		return new TeaPublishRefusedException(TeaPublishRefusal.PUBLISH_IN_PROGRESS, release, "Release " + label
				+ ": another publish or hide of this release is in progress on this or another ReARM instance; it did "
				+ "not finish within " + waited + "; repeat the act once it has finished");
	}

	static String waitText(Duration d) {
		long seconds = d.getSeconds();
		if (seconds > 0 && seconds % 60 == 0) {
			long m = seconds / 60;
			return m + (1 == m ? " minute" : " minutes");
		}
		if (seconds > 0 && d.getNano() == 0) return seconds + (1 == seconds ? " second" : " seconds");
		return d.toMillis() + " ms";
	}

	private static void logWait(Lease lease, ReleaseContext ctx) {
		if (lease.failedPolls() > 0) {
			log.info("TEA publish lock of release {}: waited {} ms", ctx.label(), lease.waitedMillis());
		}
	}

	/** The release and its component, loaded and checked (step 1). */
	record ReleaseContext(ReleaseData rd, ComponentData cd) {
		String label() {
			return cd.getName() + " " + rd.getVersion() + " (" + rd.getUuid() + ")";
		}
	}

	/** The preview's step 1, a read outside any act: the release and its component as the reads answer them. */
	ReleaseContext loadRelease(UUID release) throws TeaPublishRefusedException {
		ReleaseData rd = null == release ? null : sharedReleaseService.getReleaseData(release).orElse(null);
		ComponentData cd = null == rd ? null : getComponentService.getComponentData(rd.getComponent()).orElse(null);
		return contextOf(release, rd, cd);
	}

	/** Step 1 on the rows {@code readFresh} refreshed under the lease: the act judges these, never an earlier read. */
	ReleaseContext contextOf(UUID release, HeadAndRelease fresh) throws TeaPublishRefusedException {
		return contextOf(release, ReleaseData.dataFromRecord(fresh.release()),
				ComponentData.dataFromRecord(fresh.component()));
	}

	/** Step 1 on a release and its component already read: neither archived (T-10 point 6). */
	ReleaseContext contextOf(UUID release, ReleaseData rd, ComponentData cd) throws TeaPublishRefusedException {
		if (null == rd) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.RELEASE_NOT_FOUND, release,
					"Release " + release + " not found");
		}
		if (null == cd) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.RELEASE_NOT_FOUND, release,
					"Component " + rd.getComponent() + " of release " + rd.getVersion() + " (" + release + ") not found");
		}
		ReleaseContext ctx = new ReleaseContext(rd, cd);
		if (ReleaseStatus.ARCHIVED == rd.getStatus()) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.RELEASE_ARCHIVED, release,
					"Release " + ctx.label() + " is archived and cannot be published on TEA");
		}
		if (StatusEnum.ARCHIVED == cd.getStatus()) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.COMPONENT_ARCHIVED, release,
					"Component " + cd.getName() + " of release " + ctx.label()
							+ " is archived; its releases can only be hidden from TEA");
		}
		return ctx;
	}

	private static void checkMode(TeaPublishMode mode, Optional<TeaPublicationData> head, ReleaseContext ctx)
			throws TeaPublishRefusedException {
		boolean published = head.isPresent() && TeaPublicationState.PUBLISHED == head.get().getState();
		if (TeaPublishMode.PUBLISH == mode && published) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.ALREADY_PUBLISHED, ctx.rd().getUuid(),
					"Release " + ctx.label() + " is already published on TEA (collection version "
							+ head.get().getLatestVersion() + "); re-publish it instead");
		}
		if (TeaPublishMode.REPUBLISH == mode && head.isEmpty()) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.NOT_PUBLISHED, ctx.rd().getUuid(),
					"Release " + ctx.label() + " is not published on TEA; publish it first");
		}
	}

	/** Step 3: a CONFLICT at publish time is refused, never resolved through a recorded row. */
	private static void checkProfile(TeaResolvedProfile resolved, ReleaseContext ctx) throws TeaPublishRefusedException {
		if (TeaResolutionStatus.CONFLICT == resolved.status() || null == resolved.profile()) {
			String names = resolved.conflictingPerspectives().stream().map(TeaPerspectiveRef::name)
					.collect(Collectors.joining(", "));
			throw new TeaPublishRefusedException(TeaPublishRefusal.PROFILE_CONFLICT, ctx.rd().getUuid(),
					"Release " + ctx.label() + " cannot be published on TEA: its component resolves to the TEA "
							+ "profiles of several perspectives (" + names + "); give the component its own profile");
		}
		if (publishingDisabled(resolved)) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.PUBLISHING_DISABLED, ctx.rd().getUuid(),
					"Release " + ctx.label() + " cannot be published on TEA: publishing is DISABLED by the "
							+ sourceLabel(resolved) + " TEA profile");
		}
	}

	/**
	 * Whether a resolution says the component does not publish on TEA: RESOLVED with publishing
	 * DISABLED, which covers the built-in DEFAULT an organization has before it saves a profile.
	 * The one predicate the publish gate above and part 6's pre-filter
	 * (TeaOutputEventService.request, task TEA-7) apply, so the two never drift. A CONFLICT is
	 * not this answer: the gate refuses it as PROFILE_CONFLICT.
	 */
	public static boolean publishingDisabled(TeaResolvedProfile resolved) {
		return null != resolved && TeaResolutionStatus.RESOLVED == resolved.status() && null != resolved.profile()
				&& TeaPublishing.DISABLED == resolved.profile().getPublishing();
	}

	private static String sourceLabel(TeaResolvedProfile resolved) {
		String s = switch (resolved.source()) {
			case COMPONENT -> "component";
			case FOLLOWED_PERSPECTIVE -> "followed perspective";
			case PERSPECTIVE -> "perspective";
			case ORGANIZATION -> "organization";
			case DEFAULT -> "built-in default";
		};
		return null == resolved.sourceObject() ? s : s + " " + resolved.sourceObject();
	}

	/**
	 * The SBOM profile names a publish under {@code profile} consumes (task TEA-28): the row's list in
	 * order, duplicates dropped after the first, {@code ["default"]} for a row without names (the
	 * TEA-27 null guard); empty for no row.
	 */
	static List<String> profileNamesOf(TeaProfileData profile) {
		if (null == profile) return List.of();
		List<String> names = profile.getProfiles();
		if (null == names || names.isEmpty()) return List.of(SbomProfileData.DEFAULT_NAME);
		return List.copyOf(new LinkedHashSet<>(names.stream().filter(Objects::nonNull).toList()));
	}

	/**
	 * The recipes a publish of {@code ctx} is shaped by (task TEA-28): one per name of the resolved TEA
	 * settings, in list order, each resolved by {@link #resolveRecipe}. The first name that does not
	 * resolve refuses the whole act; nothing is minted, generated or uploaded for any name. A cascade
	 * child's own names go through the same call (3.4 step 1), so its refusal is its direct publish's.
	 */
	List<TeaNamedRecipe> recipesFor(ReleaseContext ctx, TeaResolvedProfile resolved) throws TeaPublishRefusedException {
		List<TeaNamedRecipe> out = new ArrayList<>();
		for (String name : profileNamesOf(resolved.profile())) {
			out.add(new TeaNamedRecipe(name, resolveRecipe(ctx, resolved, name)));
		}
		return out;
	}

	/**
	 * The recipe of one name (task TEA-27's per-name body): the SBOM profile {@code name}, resolved at
	 * the publishing component with the perspective the settings resolved from preferred among
	 * conflicting rows (F-1) and no recorded row. NOT_FOUND refuses PROFILE_NOT_FOUND and CONFLICT
	 * refuses PROFILE_CONFLICT, each naming the profile; nothing is written either way.
	 */
	SbomProfileData resolveRecipe(ReleaseContext ctx, TeaResolvedProfile resolved, String name)
			throws TeaPublishRefusedException {
		SbomProfileResolution r = sbomProfileResolver.resolveForComponent(ctx.cd(), name, null,
				settingsPerspectiveOf(resolved));
		if (ScopedResolutionStatus.CONFLICT == r.status()) {
			String perspectives = r.conflictingPerspectives().stream().map(TeaPerspectiveRef::name)
					.collect(Collectors.joining(", "));
			throw new TeaPublishRefusedException(TeaPublishRefusal.PROFILE_CONFLICT, ctx.rd().getUuid(),
					"Release " + ctx.label() + " cannot be published on TEA: SBOM profile " + name + " resolves to a "
							+ "conflict between perspectives " + perspectives + " for component " + ctx.cd().getName()
							+ "; define it on the component or follow one perspective for it");
		}
		if (ScopedResolutionStatus.RESOLVED != r.status() || null == r.profile()) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.PROFILE_NOT_FOUND, ctx.rd().getUuid(),
					"Release " + ctx.label() + " cannot be published on TEA: SBOM profile " + name + ", named by the "
							+ sourceLabel(resolved) + " TEA publishing settings, does not resolve for component "
							+ ctx.cd().getName());
		}
		return r.profile();
	}

	/**
	 * The perspective the TEA settings resolved from, the tie-break among conflicting SBOM profile rows
	 * (task TEA-27, D11): the followed or the one candidate perspective; null for COMPONENT,
	 * ORGANIZATION and DEFAULT.
	 */
	static UUID settingsPerspectiveOf(TeaResolvedProfile resolved) {
		if (null == resolved || null == resolved.source()) return null;
		return switch (resolved.source()) {
			case FOLLOWED_PERSPECTIVE, PERSPECTIVE -> resolved.sourceObject();
			case COMPONENT, ORGANIZATION, DEFAULT -> null;
		};
	}

	/** Step 4: DRAFT and below always refused, then the profile's minimum. */
	private static void checkLifecycle(ReleaseContext ctx, TeaProfileData profile) throws TeaPublishRefusedException {
		checkLifecycle(ctx, null == profile ? null : profile.getMinimumLifecycle());
	}

	/** Step 4 against an explicit minimum: the cascade passes the stricter of two profiles' (3.7 step g). */
	private static void checkLifecycle(ReleaseContext ctx, ReleaseLifecycle min) throws TeaPublishRefusedException {
		ReleaseLifecycle lc = ctx.rd().getLifecycle();
		if (ReleaseLifecycle.maturity(lc) < ReleaseLifecycle.maturity(ReleaseLifecycle.ASSEMBLED)) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.LIFECYCLE_TOO_LOW, ctx.rd().getUuid(),
					"Release " + ctx.label() + " is " + lc + "; a release is published on TEA from ASSEMBLED on"
							+ (null == min ? "" : " (the TEA profile's minimum lifecycle is " + min + ")"));
		}
		if (null != min && ReleaseLifecycle.maturity(lc) < ReleaseLifecycle.maturity(min)) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.LIFECYCLE_TOO_LOW, ctx.rd().getUuid(),
					"Release " + ctx.label() + " is " + lc + "; the TEA profile's minimum lifecycle is " + min);
		}
	}

	/** What one publish of one release did. {@code changed} is false for UNCHANGED with no state change. */
	record Act(TeaPublicationData publication, TeaPublishedCollectionData collection, TeaPublishOutcome outcome,
			List<TeaRideAlongOutcome> rideAlongs, boolean changed) {}

	/**
	 * The aggregates an act uploaded and has not bound yet, in upload order: the commit binds them, a
	 * failure archives them. A re-publish's in-place bump of a bound row is never one.
	 */
	private static final class Orphans {
		final List<UUID> artifacts = new ArrayList<>();
	}

	/** One name's generated document and its content digest (3.3 step 1). */
	private record NamedDocument(TeaNamedRecipe recipe, JsonNode document, String digest) {}

	/**
	 * Steps 5 (component id excepted) to 11 for one release under {@code resolved}, under the
	 * release's lease {@code held}, on the head and release {@code readFresh} returned. Each name of
	 * {@code recipes}, which the caller resolved (task TEA-28: the publishing component's, or the top
	 * product's capped per child for a cascade child), is one named aggregate; ride-alongs, TEI and the
	 * recorded row stay on {@code resolved}. Every document is generated before any upload (D11).
	 */
	private Act publishCore(Lease held, ReleaseContext ctx, Optional<TeaPublicationData> head,
			TeaResolvedProfile resolved, List<TeaNamedRecipe> recipes, UUID cascadeOf, String comment, WhoUpdated wu)
			throws TeaPublishRefusedException, RelizaException {
		TeaProfileData profile = resolved.profile();
		ReleaseData rd = ctx.rd();
		// 6. 1: every name's content, before any upload; a merge that fails refuses with nothing uploaded
		List<NamedDocument> documents = new ArrayList<>();
		for (TeaNamedRecipe nr : recipes) {
			JsonNode document = TeaCollectionAssembler.stripRebomProducerLink(generate(ctx, mergeRequestOf(nr.recipe()), wu));
			documents.add(new NamedDocument(nr, document, TeaCollectionAssembler.contentDigest(document)));
		}

		// 2. the publication uuid, chosen before any upload: every uploaded row and the TEI carry it
		UUID publicationUuid = head.map(TeaPublicationData::getUuid).orElseGet(() -> recoveredPublicationUuid(ctx));
		Orphans orphans = new Orphans();
		try {
			// 7. 3: per name, unchanged or a new revision of that name's aggregate
			List<TeaPublishedEntry> entries = new ArrayList<>();
			List<TeaAggregateArtifact> aggregates = new ArrayList<>();
			for (NamedDocument nd : documents) {
				ArtifactData existing = aggregateOf(head, rd, nd.recipe().name());
				TeaPublishedEntry aggregate = aggregateEntry(ctx, nd.recipe(), existing, publicationUuid, nd.document(),
						nd.digest(), orphans, wu);
				entries.add(aggregate);
				aggregates.add(new TeaAggregateArtifact(nd.recipe().name(), aggregate.artifact()));
			}

			// 8. 4: ride-alongs, after the aggregates
			List<TeaRideAlongOutcome> outcomes = new ArrayList<>();
			entries.addAll(rideAlongs(ctx, profile, outcomes, wu));

			// 9 to 10. classify and commit under the release's lease
			String tei = TeaTei.UUID == profile.getTei() && StringUtils.isNotEmpty(profile.getTeiDomain())
					? "tei://" + profile.getTeiDomain() + "/uuid/" + publicationUuid : null;
			Commit commit = new Commit(rd.getUuid(), rd.getOrg(), ctx.cd().getUuid(), ctx.cd().getType(),
					publicationUuid, resolved, cascadeOf, aggregates, tei, entries, comment, wu);
			return proxy().commitPublication(held, commit, outcomes);
		} catch (TeaPublishRefusedException e) {
			archiveOrphans(ctx, orphans, wu);
			throw e;
		} catch (RelizaException | RuntimeException e) {
			archiveOrphans(ctx, orphans, wu);
			log.error("TEA publication of release {}: the act failed after the aggregate was stored", ctx.label(), e);
			throw new TeaPublishRefusedException(TeaPublishRefusal.GENERATION_FAILED, rd.getUuid(),
					"Release " + ctx.label() + ": publishing on TEA failed: " + e.getMessage());
		}
	}

	/**
	 * The publication uuid of a release with no head (3.3 step 2, D16): the {@code teaPublication} system
	 * tag of the first bound row carrying the system tag {@code teaAggregate=true}, used only when it is
	 * free or this release's own (no head row under it and no stored version of another release or
	 * organization), else a fresh uuid with one WARN; a fresh uuid when no such row exists. A free uuid
	 * whose stored versions are this release's own is the heal of a head that went missing.
	 */
	private UUID recoveredPublicationUuid(ReleaseContext ctx) {
		ReleaseData rd = ctx.rd();
		Optional<ArtifactData> bound = syntheticArtifactService.findByTags(rd,
				Map.of(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true"));
		if (bound.isEmpty()) return UUID.randomUUID();
		Optional<String> tag = systemTagValue(bound.get(), CommonVariables.TEA_PUBLICATION_TAG_KEY);
		if (tag.isEmpty()) return UUID.randomUUID();
		UUID recovered;
		try {
			recovered = UUID.fromString(tag.get());
		} catch (IllegalArgumentException e) {
			log.error("TEA publication of release {}: the publication tag of aggregate {} is not a uuid; a fresh "
					+ "publication uuid is used", ctx.label(), bound.get().getUuid(), e);
			return UUID.randomUUID();
		}
		if (publicationUuidIsFreeOrOwn(recovered, rd.getOrg(), rd.getUuid())) return recovered;
		UUID fresh = UUID.randomUUID();
		log.warn("TEA publication of release {}: aggregate {} names publication uuid {}, which is held by another "
				+ "release; publishing under a fresh publication uuid {}", ctx.label(), bound.get().getUuid(), recovered,
				fresh);
		return fresh;
	}

	/** No head row under {@code uuid} and no stored version under it of another organization or release (D16). */
	private boolean publicationUuidIsFreeOrOwn(UUID uuid, UUID org, UUID release) {
		return publicationRepository.findById(uuid).isEmpty() && collectionRepository.countForeign(uuid, org, release) == 0;
	}

	/**
	 * The named aggregate row of (release, {@code name}) (3.1, D1): the head's list entry of that name,
	 * else the first bound row carrying the system tags {@code teaAggregate=true} and
	 * {@code teaProfileName=<name>}, else none (a fresh row).
	 */
	private ArtifactData aggregateOf(Optional<TeaPublicationData> head, ReleaseData rd, String name) {
		Optional<UUID> listed = head.flatMap(h -> h.getAggregateArtifacts().stream()
				.filter(a -> null != a && name.equals(a.profileName()) && null != a.artifact()).findFirst())
				.map(TeaAggregateArtifact::artifact);
		if (listed.isPresent()) {
			Optional<ArtifactData> ad = artifactService.getArtifactData(listed.get());
			if (ad.isPresent()) return ad.get();
		}
		return syntheticArtifactService.findByTags(rd, Map.of(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true",
				CommonVariables.TEA_PROFILE_NAME_TAG_KEY, name)).orElse(null);
	}

	/** The merge request of a recipe: the four adapters, the structure and the two metadata switches. */
	public static PublicationMergeRequest mergeRequestOf(SbomProfileData recipe) {
		return new PublicationMergeRequest(recipe.belongsToFilter(), recipe.tldOnly(), recipe.ignoreDev(),
				recipe.getStructure(), recipe.excludeCoverageTypes(),
				ExportMetadataOptions.fromCallerInput(SbomSupportMetadata.INCLUDE == recipe.getSupportMetadata(),
						SbomInternalMetadata.INCLUDE == recipe.getInternalMetadata()));
	}

	/**
	 * An act that fails after an upload leaves the aggregates it created and no release holds: archive
	 * each (3.5 step 7), best effort; the act's refusal stands either way. A re-publish's in-place bump
	 * is on a bound row and is never an orphan.
	 */
	private void archiveOrphans(ReleaseContext ctx, Orphans orphans, WhoUpdated wu) {
		for (UUID orphan : orphans.artifacts) {
			log.warn("TEA publication of release {}: archiving the unbound aggregate {} of the failed act", ctx.label(),
					orphan);
			try {
				artifactService.archiveArtifact(orphan, wu);
			} catch (RuntimeException e) {
				log.error("TEA publication of release {}: archiving the unbound aggregate {} failed", ctx.label(), orphan,
						e);
			}
		}
	}

	private JsonNode generate(ReleaseContext ctx, PublicationMergeRequest mr, WhoUpdated wu)
			throws TeaPublishRefusedException {
		try {
			return releaseService.generateMergedBomForPublication(ctx.rd(), mr, wu);
		} catch (MergedSbomUnavailableException e) {
			TeaPublishRefusal r = switch (e.reason()) {
				case NO_SBOM_ARTIFACTS -> TeaPublishRefusal.NO_SBOM_ARTIFACTS;
				case CHILD_MERGE_FAILED -> TeaPublishRefusal.CHILD_MERGE_FAILED;
			};
			throw new TeaPublishRefusedException(r, ctx.rd().getUuid(), e.getMessage(), e.children());
		} catch (RelizaException e) {
			log.error("TEA publication of release {}: generating the aggregate failed", ctx.label(), e);
			throw new TeaPublishRefusedException(TeaPublishRefusal.GENERATION_FAILED, ctx.rd().getUuid(),
					"Release " + ctx.label() + ": generating the TEA aggregate failed: " + e.getMessage());
		}
	}

	/**
	 * Step 7: one named aggregate's entry (task TEA-28, 3.3 step 3). An equal content digest system tag
	 * reuses the stored revision and uploads nothing; otherwise a new revision goes up under the
	 * aggregate's own serial number with the version plus one (version 1 and a fresh serial the first
	 * time), and what rebom stored is checked against what was sent. The entry is named after the
	 * profile (D3) and records the profile it was shaped by.
	 */
	private TeaPublishedEntry aggregateEntry(ReleaseContext ctx, TeaNamedRecipe named, ArtifactData existing,
			UUID publicationUuid, JsonNode document, String digest, Orphans orphans, WhoUpdated wu)
			throws TeaPublishRefusedException, RelizaException {
		UUID release = ctx.rd().getUuid();
		String profileName = named.name();
		ArtifactData stored;
		if (null != existing && systemTagValue(existing, CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY)
				.filter(digest::equals).isPresent()) {
			stored = existing;
		} else {
			UUID serial;
			int version;
			if (null != existing && null != existing.getInternalBom()) {
				serial = existing.getInternalBom().id();
				version = parseRevision(existing.getVersion()).orElse(0) + 1;
			} else {
				serial = UUID.randomUUID();
				version = 1;
			}
			ObjectNode doc = (ObjectNode) document.deepCopy();
			doc.put("serialNumber", "urn:uuid:" + serial);
			doc.put("version", version);
			JsonNode metadata = doc.get("metadata");
			ObjectNode md = metadata instanceof ObjectNode m ? m : doc.putObject("metadata");
			md.put("timestamp", Instant.now().toString());
			byte[] bytes = Utils.OM.writeValueAsBytes(doc);
			String fileName = ctx.cd().getName() + "-" + ctx.rd().getVersion() + "-" + profileName
					+ "-tea-aggregate.cdx.json";
			List<TagRecord> tags = new ArrayList<>(List.of(
					new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", Removable.NO),
					new TagRecord(CommonVariables.TEA_PROFILE_NAME_TAG_KEY, profileName, Removable.NO),
					new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, publicationUuid.toString(), Removable.NO),
					new TagRecord(CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY, digest, Removable.NO)));
			ArtifactDto dto = ArtifactDto.builder().uuid(null == existing ? null : existing.getUuid())
					.type(ArtifactType.BOM).bomFormat(BomFormat.CYCLONEDX).displayIdentifier(fileName)
					.storedIn(StoredIn.REARM).tags(tags).build();
			OrganizationData od = getOrganizationService.getOrganizationData(ctx.rd().getOrg()).orElse(null);
			String preferredPurl = SidPurlUtils.pickPreferredPurl(ctx.rd().getIdentifiers())
					.map(RearmIdentifier::getIdValue).orElse(null);
			RebomOptions options = new RebomOptions(ctx.cd().getName(), null == od ? null : od.getName(),
					ctx.rd().getVersion(), ArtifactBelongsTo.SYNTHETIC, null, StripBom.FALSE, preferredPurl);
			UUID written;
			try {
				// uploaded only: the commit binds it, in the act's one write to the release row
				written = syntheticArtifactService.upload(release, dto, new NamedBytes(bytes, fileName), options, wu);
			} catch (RelizaException | RuntimeException e) {
				log.error("TEA publication of release {}: storing the aggregate {} failed", ctx.label(), profileName, e);
				throw new TeaPublishRefusedException(TeaPublishRefusal.GENERATION_FAILED, release,
						"Release " + ctx.label() + ": storing the TEA aggregate " + profileName + " failed: "
								+ e.getMessage());
			}
			if (null == existing) orphans.artifacts.add(written);
			stored = artifactService.getArtifactData(written).orElse(null);
			if (null == stored || null == stored.getInternalBom() || !serial.equals(stored.getInternalBom().id())
					|| parseRevision(stored.getVersion()).orElse(-1) != version) {
				throw new TeaPublishRefusedException(TeaPublishRefusal.GENERATION_FAILED, release,
						"Release " + ctx.label() + ": rebom stored the aggregate under another serial or version "
								+ "(sent " + serial + " version " + version + ")");
			}
		}
		Optional<String> sha = digestOf(stored, DigestScope.AS_UPLOADED);
		if (sha.isEmpty()) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.GENERATION_FAILED, release,
					"Release " + ctx.label() + ": the TEA aggregate " + stored.getUuid()
							+ " has no retained upload digest, so its bytes cannot be served as published");
		}
		int revision = parseRevision(stored.getVersion()).orElseThrow(() -> new TeaPublishRefusedException(
				TeaPublishRefusal.GENERATION_FAILED, release, "Release " + ctx.label() + ": the TEA aggregate "
						+ stored.getUuid() + " has a version that is not an integer"));
		UUID teaUuid = null != stored.getTeaUuid() ? stored.getTeaUuid() : artifactService.ensureTeaUuid(stored.getUuid(), wu);
		List<TeaChecksumDocument> checksums = List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256,
				sha.get().toLowerCase()));
		// D3: the TEA name is the bare profile name; a revision an earlier version froze is carried verbatim
		TeaArtifactDocument frozen = new TeaArtifactDocument(teaUuid, revision, profileName, TeaArtifactType.BOM,
				instantOf(stored.getUpdatedDate()), List.of(new TeaArtifactFormatDocument(CYCLONEDX_JSON_MEDIA_TYPE, null,
						null, null, checksums)));
		SbomProfileData recipe = named.recipe();
		return new TeaPublishedEntry(stored.getUuid(), teaUuid, revision, TeaArtifactOrigin.SYNTHETIC,
				TeaArtifactType.BOM, profileName, CYCLONEDX_JSON_MEDIA_TYPE, checksums, null, null, null, frozen,
				profileName, null == recipe ? null : recipe.getUuid(), null == recipe ? null : recipe.getRevision());
	}

	/** Step 8: the release-level inventory per {@code rawArtifacts}, in list order; a skip never fails the publish. */
	private List<TeaPublishedEntry> rideAlongs(ReleaseContext ctx, TeaProfileData profile,
			List<TeaRideAlongOutcome> outcomes, WhoUpdated wu) throws RelizaException {
		List<TeaPublishedEntry> out = new ArrayList<>();
		if (null == profile.getRawArtifacts() || TeaProfileData.TeaRawArtifacts.NONE == profile.getRawArtifacts()) {
			return out;
		}
		boolean nonBom = TeaProfileData.TeaRawArtifacts.NON_BOM == profile.getRawArtifacts();
		List<UUID> inventory = ctx.rd().getArtifacts();
		if (null == inventory || inventory.isEmpty()) return out;
		Map<UUID, ArtifactData> byId = new HashMap<>();
		artifactService.getArtifactDataList(inventory).forEach(ad -> byId.put(ad.getUuid(), ad));
		for (UUID id : new LinkedHashSet<>(inventory)) {
			ArtifactData ad = byId.get(id);
			if (null == ad) {
				log.warn("TEA publication of release {}: inventory artifact {} not found; not published", ctx.label(), id);
				continue;
			}
			if (nonBom && ArtifactType.BOM == ad.getType()) continue;
			RideAlong r = rideAlong(ad);
			if (null != r.skip()) {
				log.warn("TEA publication of release {}: artifact {} ({}) not published: {}", ctx.label(), ad.getUuid(),
						ad.getDisplayIdentifier(), r.skip());
				outcomes.add(new TeaRideAlongOutcome(ad.getUuid(), r.skip(), r.detail()));
				continue;
			}
			UUID teaUuid = null != ad.getTeaUuid() ? ad.getTeaUuid() : artifactService.ensureTeaUuid(ad.getUuid(), wu);
			TeaArtifactType type = TeaTransformerService.transformArtifactTypeToTea(ad.getType());
			String mediaType = mediaTypeOf(ad);
			SignatureRide sig = artifactService.getArtifactSignature(ad).map(s -> signatureRide(ctx, ad, s, outcomes))
					.orElse(SignatureRide.NONE);
			TeaArtifactDocument frozen = new TeaArtifactDocument(teaUuid, r.revision(), ad.getDisplayIdentifier(), type,
					instantOf(ad.getUpdatedDate()), List.of(new TeaArtifactFormatDocument(mediaType, null, r.url(),
							sig.url(), r.checksums())));
			out.add(new TeaPublishedEntry(ad.getUuid(), teaUuid, r.revision(), TeaArtifactOrigin.REAL, type,
					ad.getDisplayIdentifier(), mediaType, r.checksums(), r.url(), sig.artifact(), sig.revision(),
					frozen));
			outcomes.add(new TeaRideAlongOutcome(ad.getUuid(), TeaRideAlongStatus.PUBLISHED, null));
		}
		return out;
	}

	/**
	 * The signature a ride-along format carries (task TEA-20): the row it came from and, for a row stored
	 * externally, the frozen {@code signatureUrl}; {@link #NONE} when the format publishes unsigned.
	 */
	record SignatureRide(UUID artifact, Integer revision, String url) {
		static final SignatureRide NONE = new SignatureRide(null, null, null);
	}

	/**
	 * Task TEA-20: a signature stored in ReARM is served by the signature endpoint, as before; one stored
	 * {@code EXTERNALLY} is published as the format's {@code signatureUrl} when its first non-blank link passes
	 * {@link TeaExternalUrl#problemOf}, and is otherwise skipped with its own outcome row (before the format's
	 * row) and one WARN that names no url. A skipped signature never refuses the publish.
	 */
	private static SignatureRide signatureRide(ReleaseContext ctx, ArtifactData ad, ArtifactData s,
			List<TeaRideAlongOutcome> outcomes) {
		Integer revision = parseRevision(s.getVersion()).orElse(null);
		if (StoredIn.EXTERNALLY != s.getStoredIn()) return new SignatureRide(s.getUuid(), revision, null);
		String link = null == s.getDownloadLinks() ? null : s.getDownloadLinks().stream()
				.filter(l -> null != l && StringUtils.isNotBlank(l.getUri())).map(l -> l.getUri()).findFirst()
				.orElse(null);
		String named = "signature " + s.getDisplayIdentifier() + " (" + s.getUuid() + ")";
		TeaRideAlongStatus skip;
		String detail;
		if (null == link) {
			skip = TeaRideAlongStatus.SKIPPED_NO_DOWNLOAD_LINK;
			detail = named + " is stored externally without a download link";
		} else {
			Optional<TeaExternalUrl.Problem> problem = TeaExternalUrl.problemOf(link);
			if (problem.isEmpty()) return new SignatureRide(s.getUuid(), revision, link);
			skip = TeaRideAlongStatus.SKIPPED_LINK_NOT_HTTP_URL;
			detail = named + ": " + TeaExternalUrl.describe(link, problem.get());
		}
		log.warn("TEA publication of release {}: signature {} ({}) of artifact {} not published: {}", ctx.label(),
				s.getUuid(), s.getDisplayIdentifier(), ad.getUuid(), skip);
		outcomes.add(new TeaRideAlongOutcome(s.getUuid(), skip, detail));
		return SignatureRide.NONE;
	}

	/** Whether one artifact row can ride along, and with what revision, url and checksums. */
	record RideAlong(TeaRideAlongStatus skip, String detail, int revision, String url, List<TeaChecksumDocument> checksums) {
		static RideAlong skipped(TeaRideAlongStatus s, String detail) {
			return new RideAlong(s, detail, 0, null, List.of());
		}
	}

	static RideAlong rideAlong(ArtifactData ad) {
		if (ad.isSignatureType()) {
			return RideAlong.skipped(TeaRideAlongStatus.SKIPPED_SIGNATURE_TYPE,
					"published as the signature of the artifact it signs");
		}
		Optional<Integer> revision = parseRevision(ad.getVersion());
		if (revision.isEmpty()) {
			return RideAlong.skipped(TeaRideAlongStatus.SKIPPED_VERSION_NOT_INTEGER,
					"version " + ad.getVersion() + " is not an integer");
		}
		boolean storedInRearm = StoredIn.REARM == ad.getStoredIn()
				|| (null == ad.getStoredIn() && null != ad.getInternalBom());
		if (storedInRearm) {
			if (null != ad.getInternalBom()) {
				Optional<String> sha = digestOf(ad, DigestScope.AS_UPLOADED);
				if (sha.isEmpty()) {
					return RideAlong.skipped(TeaRideAlongStatus.SKIPPED_NO_RAW_BYTES,
							"uploaded before raw bytes were retained, so its bytes cannot be served as published");
				}
				return new RideAlong(null, null, revision.get(), null,
						List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, sha.get().toLowerCase())));
			}
			List<TeaChecksumDocument> declared = checksums(ad, DigestScope.ORIGINAL_FILE);
			if (declared.isEmpty()) {
				return RideAlong.skipped(TeaRideAlongStatus.SKIPPED_NO_CHECKSUM, "no checksum of the stored file");
			}
			return new RideAlong(null, null, revision.get(), null, declared);
		}
		String url = null == ad.getDownloadLinks() ? null : ad.getDownloadLinks().stream()
				.filter(l -> null != l && StringUtils.isNotBlank(l.getUri())).map(l -> l.getUri()).findFirst()
				.orElse(null);
		if (null == url) {
			return RideAlong.skipped(TeaRideAlongStatus.SKIPPED_NO_DOWNLOAD_LINK, "stored externally without a download link");
		}
		// task TEA-19: only the first non-blank link is the candidate, checked before the checksum
		Optional<TeaExternalUrl.Problem> problem = TeaExternalUrl.problemOf(url);
		if (problem.isPresent()) {
			return RideAlong.skipped(TeaRideAlongStatus.SKIPPED_LINK_NOT_HTTP_URL,
					TeaExternalUrl.describe(url, problem.get()));
		}
		List<TeaChecksumDocument> declared = checksums(ad, DigestScope.ORIGINAL_FILE);
		if (declared.isEmpty()) {
			return RideAlong.skipped(TeaRideAlongStatus.SKIPPED_NO_CHECKSUM,
					"stored externally without a declared checksum, which TEA requires beside a url");
		}
		return new RideAlong(null, null, revision.get(), url, declared);
	}

	private static List<TeaChecksumDocument> checksums(ArtifactData ad, DigestScope scope) {
		if (null == ad.getDigestRecords()) return List.of();
		return ad.getDigestRecords().stream()
				// TEA 1.0.0 has no MD5 (task TEA-6): a digest it cannot express is never published
				.filter(d -> null != d && scope == d.scope() && null != d.algo() && d.algo().toTea().isPresent()
						&& StringUtils.isNotBlank(d.digest()))
				.map(d -> new TeaChecksumDocument(d.algo().toTea().get(), d.digest().toLowerCase()))
				.distinct().toList();
	}

	private static Optional<String> digestOf(ArtifactData ad, DigestScope scope) {
		if (null == ad.getDigestRecords()) return Optional.empty();
		return ad.getDigestRecords().stream()
				.filter(d -> null != d && scope == d.scope() && DigestAlgorithm.SHA_256 == d.algo()
						&& StringUtils.isNotBlank(d.digest()))
				.map(DigestRecord::digest).findFirst();
	}

	/**
	 * The media type TEA announces: the stored media type tag resolved as the 0.4.0 transformer
	 * resolves it, with the "application/" its CycloneDX values lack, else octet-stream.
	 */
	static String mediaTypeOf(ArtifactData ad) {
		Optional<String> tag = tagValue(ad, CommonVariables.MEDIA_TYPE_FIELD);
		if (tag.isEmpty() || StringUtils.isBlank(tag.get())) return OCTET_STREAM;
		String resolved = TeaTransformerService.resolveMediaType(ad.getBomFormat(), tag.get());
		if (StringUtils.isBlank(resolved)) return OCTET_STREAM;
		return resolved.startsWith("vnd.") ? "application/" + resolved : resolved;
	}

	static Optional<Integer> parseRevision(String version) {
		if (null == version) return Optional.empty();
		try {
			return Optional.of(Integer.parseInt(version.strip()));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private static Optional<String> tagValue(ArtifactData ad, String key) {
		if (null == ad.getTags()) return Optional.empty();
		return ad.getTags().stream().filter(t -> key.equals(t.key())).map(TagRecord::value).findFirst();
	}

	/**
	 * The value of the first system tag ({@code Removable.NO}) with {@code key} (task TEA-28, D15): every
	 * TEA key the engine reads goes through here or through {@code findByTags}, so a user's tag, always
	 * YES, never names a publication uuid or an unchanged digest.
	 */
	static Optional<String> systemTagValue(ArtifactData ad, String key) {
		if (null == ad.getTags()) return Optional.empty();
		return ad.getTags().stream().filter(t -> null != t && Removable.NO == t.removable() && key.equals(t.key()))
				.map(TagRecord::value).findFirst();
	}

	private static String instantOf(ZonedDateTime zdt) {
		return (null == zdt ? Instant.now() : zdt.toInstant()).toString();
	}

	/** A byte resource with a filename, which the multipart upload needs. */
	static final class NamedBytes extends ByteArrayResource {
		private final String filename;

		NamedBytes(byte[] bytes, String filename) {
			super(bytes);
			this.filename = filename;
		}

		@Override
		public String getFilename() {
			return filename;
		}
	}

	/** Everything the commit step needs, computed before it. */
	record Commit(UUID release, UUID org, UUID component, ComponentType componentType, UUID publicationUuid,
			TeaResolvedProfile resolved, UUID cascadeOf, List<TeaAggregateArtifact> aggregateArtifacts, String tei,
			List<TeaPublishedEntry> entries, String comment, WhoUpdated wu) {}

	/** The head row, the release row and the release's component row, read fresh in a transaction. */
	public record HeadAndRelease(Optional<TeaPublication> head, Release release, Component component) {}

	/**
	 * The head row and the release row re-read from the database (3.5 steps 0b and 10, 3.6).
	 * Under open-in-view the request's persistence context may already hold both from before the
	 * lease (the fetcher's authorization read), and a plain find would hand those instances back;
	 * {@code refresh} makes what another request committed meanwhile the state the act builds on.
	 * The release's component is loaded through its repository and refreshed after the release
	 * (round 4, T-10 point 6), so the archive gates judge the row as it is, not the instance the
	 * fetcher's authorization check or the cascade's step b loaded before the lease.
	 * Called through the proxy right after the lease is taken, and through {@code this} at the
	 * start of each commit, inside the commit's transaction. Takes no lock.
	 */
	@Transactional
	public HeadAndRelease readFresh(UUID release) throws RelizaException {
		Optional<TeaPublication> head = publicationRepository.findByRelease(release);
		head.ifPresent(entityManager::refresh);
		Release r = sharedReleaseService.getRelease(release).orElseThrow(() -> new TeaPublishRefusedException(
				TeaPublishRefusal.RELEASE_NOT_FOUND, release, "Release " + release + " not found"));
		entityManager.refresh(r);
		ReleaseData rd = ReleaseData.dataFromRecord(r);
		Component c = componentRepository.findById(rd.getComponent()).orElseThrow(() -> new TeaPublishRefusedException(
				TeaPublishRefusal.RELEASE_NOT_FOUND, release, "Component " + rd.getComponent() + " of release "
						+ rd.getVersion() + " (" + release + ") not found"));
		entityManager.refresh(c);
		return new HeadAndRelease(head, r, c);
	}

	/**
	 * Steps 9 and 10, under the release's lease {@code held}: re-read the head and the release,
	 * classify the entries against the latest stored collection, insert the next version when
	 * anything changed, upsert the head, and make the act's one write to the release row (the
	 * aggregate's bind and the release event) on the row as it is now. The next version follows
	 * the highest stored one, so a head that fell behind its collections heals.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Act commitPublication(Lease held, Commit c, List<TeaRideAlongOutcome> rideAlongs) throws RelizaException {
		Objects.requireNonNull(held, "a TEA commit runs under the release's lease");
		HeadAndRelease fresh = readFresh(c.release());
		// the archive gates again, on the rows read in this transaction and before any write
		// (T-10 point 7): a release or component archived since the gate refuses the act
		contextOf(c.release(), fresh);
		Optional<TeaPublication> headRow = fresh.head();
		TeaPublicationData before = headRow.map(TeaPublicationData::dataFromRecord).orElse(null);
		UUID pubUuid = null != before ? before.getUuid() : c.publicationUuid();
		if (null == before && !publicationUuidIsFreeOrOwn(pubUuid, c.org(), c.release())) {
			// D16 (F-5): never a version or a head under another release's or organization's uuid; nothing
			// is written, and the caller archives the act's unbound aggregates
			throw new TeaPublishRefusedException(TeaPublishRefusal.GENERATION_FAILED, c.release(), "Release "
					+ c.release() + ": publication uuid " + pubUuid + " is held by another release");
		}
		int storedVersion = collectionRepository.maxVersion(pubUuid);
		int headVersion = null == before ? 0 : before.getLatestVersion();
		if (null != before && storedVersion != headVersion) {
			log.warn("TEA publication {} of release {}: the head records collection version {} but version {} is "
					+ "stored; continuing from the higher", pubUuid, c.release(), headVersion, storedVersion);
		}
		int latestVersion = Math.max(headVersion, storedVersion);
		TeaPublishedCollectionData latest = storedVersion < 1 ? null
				: collectionRepository.findVersion(pubUuid, storedVersion).map(TeaPublishedCollectionData::dataFromRecord)
						.orElse(null);
		List<TeaPublishedEntry> previous = null == latest ? null : latest.getEntries();
		// D13: an entry the latest version lacks is carried from the oldest version naming its TEA id and revision
		Function<TeaPublishedEntry, Optional<TeaPublishedEntry>> history = storedVersion >= 1
				? e -> frozenEntryOf(pubUuid, e.teaUuid(), e.revision())
				: e -> Optional.empty();
		List<TeaPublishedEntry> entries = TeaCollectionAssembler.carryForward(previous, c.entries(), history);
		TeaCollectionAssembler.Changes changes = TeaCollectionAssembler.classify(previous, entries);
		boolean wasHidden = null != before && TeaPublicationState.HIDDEN == before.getState();
		ZonedDateTime now = ZonedDateTime.now();

		TeaPublishedCollectionData written = null;
		int version = latestVersion;
		if (!changes.isEmpty()) {
			version = latestVersion + 1;
			TeaCollectionUpdateReasonType reason = TeaCollectionAssembler.updateReason(changes).orElseThrow();
			String comment = TeaCollectionAssembler.updateComment(changes, c.comment());
			TeaCollectionBelongsToType belongsTo = ComponentType.PRODUCT == c.componentType()
					? TeaCollectionBelongsToType.PRODUCT_RELEASE : TeaCollectionBelongsToType.COMPONENT_RELEASE;
			String document = TeaCollectionAssembler.document(pubUuid, version, now.toInstant().toString(), belongsTo,
					reason, comment, entries);
			TeaPublishedCollectionData cd = new TeaPublishedCollectionData();
			cd.setUuid(pubUuid);
			cd.setVersion(version);
			cd.setOrg(c.org());
			cd.setRelease(c.release());
			cd.setBelongsTo(belongsTo);
			cd.setUpdateReasonType(reason);
			cd.setUpdateReasonComment(StringUtils.isEmpty(comment) ? null : comment);
			cd.setProfileRevision(c.resolved().profile().getRevision());
			cd.setEntries(entries);
			TeaPublishedCollection row = new TeaPublishedCollection();
			row.setUuid(pubUuid);
			row.setVersion(version);
			row.setOrg(c.org());
			row.setRelease(c.release());
			row.setCreatedDate(now);
			row.setCreatedBy(c.wu().getLastUpdatedBy());
			row.setRecordData(cd.toRecordData());
			row.setDocument(document);
			row.setDocumentSha256(TeaCollectionAssembler.sha256(document));
			written = TeaPublishedCollectionData.dataFromRecord(collectionRepository.save(row));
		}

		// a missing head with nothing new (the heal of D16) falls through and writes the head at the stored version
		if (changes.isEmpty() && !wasHidden && null != before) {
			TeaPublicationData current = before;
			ReleaseUpdateEvent handOver = null;
			if (headDrifted(before, c) || before.getLatestVersion() != latestVersion) {
				// Nothing to publish, but what the head records about the last publish moved: the profile
				// row or its revision, the TEI, a cascade child now published directly, or a cascade child
				// another product took over. The head says so; no collection version is written, and a
				// release event only for the takeover (3.7 step i), so the History tab shows the hand-over.
				UUID oldOwner = before.getCascadeOf();
				boolean takeover = null != oldOwner && null != c.cascadeOf() && !oldOwner.equals(c.cascadeOf());
				before.setLatestVersion(latestVersion);
				applyPublishFacts(before, c);
				current = saveHead(headRow, before, c.wu());
				if (takeover) {
					handOver = new ReleaseUpdateEvent(ReleaseUpdateScope.TEA_PUBLICATION, ReleaseUpdateAction.CHANGED,
							"with " + ownerLabel(oldOwner), "with " + ownerLabel(c.cascadeOf()), pubUuid, c.comment(), now,
							c.wu());
				}
			}
			writeRelease(fresh.release(), c, handOver);
			return new Act(current, latest, TeaPublishOutcome.UNCHANGED, rideAlongs, false);
		}

		TeaPublicationData head = null == before ? new TeaPublicationData() : before;
		head.setUuid(pubUuid);
		head.setOrg(c.org());
		head.setRelease(c.release());
		head.setComponent(c.component());
		head.setComponentType(c.componentType());
		head.setState(TeaPublicationState.PUBLISHED);
		head.setLatestVersion(version);
		applyPublishFacts(head, c);
		if (null == head.getFirstPublishedDate()) head.setFirstPublishedDate(now);
		head.setLastPublishedDate(now);
		head.setLastPublishedBy(c.wu().getLastUpdatedBy());
		TeaPublicationData saved = saveHead(headRow, head, c.wu());

		ReleaseUpdateEvent event;
		if (null == before) {
			event = new ReleaseUpdateEvent(ReleaseUpdateScope.TEA_PUBLICATION, ReleaseUpdateAction.ADDED, null,
					"v" + version, pubUuid, c.comment(), now, c.wu());
		} else if (wasHidden) {
			event = new ReleaseUpdateEvent(ReleaseUpdateScope.TEA_PUBLICATION, ReleaseUpdateAction.CHANGED, "HIDDEN",
					"PUBLISHED v" + version, pubUuid, c.comment(), now, c.wu());
		} else {
			event = new ReleaseUpdateEvent(ReleaseUpdateScope.TEA_PUBLICATION, ReleaseUpdateAction.CHANGED,
					"v" + latestVersion, "v" + version, pubUuid, c.comment(), now, c.wu());
		}
		writeRelease(fresh.release(), c, event);
		TeaPublishOutcome outcome = null == before ? TeaPublishOutcome.PUBLISHED
				: (null == written ? TeaPublishOutcome.UNCHANGED : TeaPublishOutcome.REPUBLISHED);
		TeaPublishedCollectionData collection = null != written ? written : latest;
		return new Act(saved, collection, outcome, rideAlongs, true);
	}

	/**
	 * The frozen entry of TEA id {@code teaUuid} at {@code revision} in the OLDEST version of publication
	 * {@code publication} naming that pair (task TEA-28, D13): the version the artifact endpoint serves it
	 * from, so the listed and the served object are one.
	 */
	Optional<TeaPublishedEntry> frozenEntryOf(UUID publication, UUID teaUuid, int revision) {
		if (null == publication || null == teaUuid) return Optional.empty();
		String needle = "[{\"teaUuid\":\"" + teaUuid + "\",\"revision\":" + revision + "}]";
		return collectionRepository.findOldestNaming(publication, needle).map(TeaPublishedCollectionData::dataFromRecord)
				.flatMap(cd -> cd.getEntries().stream()
						.filter(e -> teaUuid.equals(e.teaUuid()) && revision == e.revision()).findFirst());
	}

	/** What every publish records on the head about the profile applied and the aggregates. */
	private static void applyPublishFacts(TeaPublicationData head, Commit c) {
		TeaProfileData profile = c.resolved().profile();
		head.setProfile(profile.getUuid());
		head.setProfileRevision(profile.getRevision());
		head.setProfileSource(c.resolved().source());
		head.setProfileSourceObject(c.resolved().sourceObject());
		head.setCascadeOf(c.cascadeOf());
		head.setAggregateArtifacts(c.aggregateArtifacts());
		head.setTei(c.tei());
	}

	private static boolean headDrifted(TeaPublicationData head, Commit c) {
		TeaProfileData profile = c.resolved().profile();
		return !java.util.Objects.equals(head.getProfile(), profile.getUuid())
				|| head.getProfileRevision() != profile.getRevision()
				|| head.getProfileSource() != c.resolved().source()
				|| !java.util.Objects.equals(head.getProfileSourceObject(), c.resolved().sourceObject())
				|| !java.util.Objects.equals(head.getCascadeOf(), c.cascadeOf())
				|| !java.util.Objects.equals(head.getAggregateArtifacts(), c.aggregateArtifacts())
				|| !java.util.Objects.equals(head.getTei(), c.tei());
	}

	/** Upserts the head row from {@code d}: audit record and revision bump on an update. */
	private TeaPublicationData saveHead(Optional<TeaPublication> existing, TeaPublicationData d, WhoUpdated wu) {
		TeaPublication e;
		if (existing.isPresent()) {
			e = existing.get();
			auditService.createAndSaveAuditRecord(TableName.TEA_PUBLICATION, e);
			e.setRevision(e.getRevision() + 1);
			e.setLastUpdatedDate(ZonedDateTime.now());
		} else {
			e = new TeaPublication();
			e.setUuid(d.getUuid());
		}
		e.setOrg(d.getOrg());
		e.setRelease(d.getRelease());
		e.setComponent(d.getComponent());
		e.setState(d.getState());
		e.setLatestVersion(d.getLatestVersion());
		e.setProfile(d.getProfile());
		e.setProfileRevision(d.getProfileRevision());
		e.setCascadeOf(d.getCascadeOf());
		e.setRecordData(d.toRecordData());
		e = (TeaPublication) WhoUpdated.injectWhoUpdatedData(e, wu);
		return TeaPublicationData.dataFromRecord(publicationRepository.save(e));
	}

	/**
	 * The act's one write to the release row (3.5 step 10), on the row the commit refreshed in its
	 * transaction: each aggregate's bind staged on it, then the release event when the act records
	 * one, saved once with no trigger (part 6's output event must not re-enter processRelease from
	 * its own publish) and no collection resolve (nothing in the inventory changed). Nothing staged
	 * and no event: no save.
	 */
	private void writeRelease(Release release, Commit c, ReleaseUpdateEvent event) throws RelizaException {
		ReleaseData rd = ReleaseData.dataFromRecord(release);
		boolean staged = false;
		for (TeaAggregateArtifact a : c.aggregateArtifacts()) {
			staged |= syntheticArtifactService.stageBind(rd, a.artifact(), c.wu());
		}
		if (null != event) rd.addUpdateEvent(event);
		if (staged || null != event) ossReleaseService.saveRelease(release, rd, c.wu(), false, AcollectionMode.SKIP);
	}

	/**
	 * "<component name> <version>" of a publication's release: how the History tab and the page
	 * name the product a cascade child is published with; the publication uuid when the release or
	 * its component is gone.
	 */
	public String ownerLabel(UUID publication) {
		if (null == publication) return null;
		Optional<ReleaseData> rd = getPublication(publication).flatMap(p -> sharedReleaseService.getReleaseData(p.getRelease()));
		if (rd.isEmpty()) return "publication " + publication;
		String name = getComponentService.getComponentData(rd.get().getComponent()).map(ComponentData::getName)
				.orElse(String.valueOf(rd.get().getComponent()));
		return name + " " + rd.get().getVersion();
	}

	// ------------------------------------------------------------------ cascade

	/**
	 * Step 3.7: each component release of a product, in list order, best effort per child; nested
	 * products cascade with the same product profile and the same {@code productRecipes}, the named
	 * recipes the top product's publish used (tasks TEA-27 F-3, TEA-28). The cascade never reads or
	 * writes outside the product's organization (step b). The product's names and recipes shape each
	 * child's aggregates; the child's own resolution is the gate (DISABLED or CONFLICT refuses it, step
	 * e; its own profile names that do not resolve refuse it, TEA-28 3.4 step 1) and the cap
	 * (rawArtifacts and minimumLifecycle are the narrower of the two, steps g and h; internalMetadata
	 * per name, {@link #cascadeRecipes}; visibility is capped at request time, 3.9). A child has one
	 * owner, {@code cascadeOf}: another product's publish leaves it alone while that owner is published
	 * and still lists it (step f).
	 */
	private void cascade(ReleaseContext product, TeaPublicationData productPublication, TeaResolvedProfile resolved,
			List<TeaNamedRecipe> productRecipes, String comment, WhoUpdated wu, int depth, List<TeaChildPublishResult> out,
			WaitBudget b) {
		boolean withProduct = TeaProductComponents.PUBLISH_WITH_PRODUCT == resolved.profile().getProductComponents();
		if (depth > MAX_CASCADE_DEPTH) {
			log.warn("TEA publication of product release {}: cascade deeper than {}; not descending further",
					product.label(), MAX_CASCADE_DEPTH);
			return;
		}
		UUID org = product.rd().getOrg();
		for (ParentRelease pr : product.rd().getParentReleases()) {
			UUID childUuid = pr.getRelease();
			// a. no release named
			if (null == childUuid) continue;
			// b. the organization boundary: a foreign or missing child is "not found", nothing touched
			ReleaseData crd = sharedReleaseService.getReleaseData(childUuid).orElse(null);
			ComponentData ccd = null == crd ? null : getComponentService.getComponentData(crd.getComponent()).orElse(null);
			if (null == crd || null == ccd || !org.equals(crd.getOrg()) || !org.equals(ccd.getOrg())) {
				log.warn("TEA publication of product release {}: component release {} is missing or not of the "
						+ "product's organization; refused as not found", product.label(), childUuid);
				out.add(new TeaChildPublishResult(childUuid, null, null, TeaChildOutcome.REFUSED,
						TeaPublishRefusal.RELEASE_NOT_FOUND, "not found", null));
				continue;
			}
			String name = ccd.getName();
			String version = crd.getVersion();
			// c. the component's TEA id, in both modes; a failure refuses the child, the product stands
			try {
				mintComponentId(ccd.getUuid(), childUuid, name + " " + version + " (" + childUuid + ")", wu);
			} catch (TeaPublishRefusedException e) {
				out.add(new TeaChildPublishResult(childUuid, name, version, TeaChildOutcome.REFUSED, e.reason(),
						e.getMessage(), null));
				continue;
			}
			// d. PRODUCT_ONLY publishes no child
			if (!withProduct) continue;
			// the child's in-process lock and lease on the product's budget, then its fresh rows:
			// steps e to i run on them
			ReentrantLock childLock = releaseLock(childUuid);
			try {
				lockOrRefuse(childLock, childUuid, b);
			} catch (TeaPublishRefusedException e) {
				log.warn("TEA publication of product release {}: component release {} {} ({}) refused: {}",
						product.label(), name, version, childUuid, e.getMessage());
				out.add(new TeaChildPublishResult(childUuid, name, version, TeaChildOutcome.REFUSED, e.reason(),
						e.getMessage(), null));
				continue;
			}
			Optional<TeaPublicationData> head = Optional.empty();
			try (Lease childLease = acquireOrRefuse(childUuid, b)) {
				HeadAndRelease fresh = proxy().readFresh(childUuid);
				ReleaseContext child = contextOf(childUuid, fresh);
				logWait(childLease, child);
				head = fresh.head().map(TeaPublicationData::dataFromRecord);
				// e. the gate: the child's own resolution, never with a recorded fallback
				TeaResolvedProfile own = teaProfileResolver.resolveForComponent(child.cd());
				checkProfile(null == own ? CONFLICT_UNKNOWN : own, child);
				// f. the head: hidden, direct, or owned by another product that still holds it
				if (head.isPresent() && TeaPublicationState.HIDDEN == head.get().getState()) {
					out.add(new TeaChildPublishResult(childUuid, name, version, TeaChildOutcome.SKIPPED_HIDDEN, null,
							"hidden from TEA; re-publish it to lift the hide", head.get().getUuid()));
					continue;
				}
				if (head.isPresent() && null == head.get().getCascadeOf()) {
					out.add(new TeaChildPublishResult(childUuid, name, version, TeaChildOutcome.SKIPPED_DIRECT, null,
							"published directly under its own TEA profile", head.get().getUuid()));
					continue;
				}
				if (head.isPresent() && !productPublication.getUuid().equals(head.get().getCascadeOf())) {
					Optional<String> holder = holdingOwner(head.get(), org);
					if (holder.isPresent()) {
						out.add(new TeaChildPublishResult(childUuid, name, version,
								TeaChildOutcome.SKIPPED_OTHER_PRODUCT, null, "published with product " + holder.get(),
								head.get().getUuid()));
						continue;
					}
					log.info("TEA publication of product release {}: component release {} {} ({}) taken over from "
							+ "publication {}, which no longer holds it", product.label(), name, version, childUuid,
							head.get().getCascadeOf());
				}
				// g. the stricter of the two minimum lifecycles
				checkLifecycle(child, stricterMinimum(resolved.profile().getMinimumLifecycle(),
						own.profile().getMinimumLifecycle()));
				// the child's own names resolve at the child, before anything of it is generated; the product's
				// names and recipes shape it, each capped by the child (task TEA-28, 3.4)
				List<TeaNamedRecipe> childRecipes = cascadeRecipes(productRecipes, child, own);
				// h. the product's shape knobs, the narrower exposure knobs
				TeaResolvedProfile knobs = new TeaResolvedProfile(resolved.status(), resolved.source(),
						resolved.sourceObject(), cascadeSettings(resolved.profile(), own.profile()),
						resolved.conflictingPerspectives());
				// i. steps 5 to 11 with this product as the owner, under the child's lease
				Act act = publishCore(childLease, child, head, knobs, childRecipes, productPublication.getUuid(),
						comment, wu);
				TeaChildOutcome outcome = switch (act.outcome()) {
					case PUBLISHED -> TeaChildOutcome.PUBLISHED;
					case REPUBLISHED -> TeaChildOutcome.REPUBLISHED;
					case UNCHANGED -> TeaChildOutcome.UNCHANGED;
				};
				out.add(new TeaChildPublishResult(childUuid, name, version, outcome, null, null,
						act.publication().getUuid()));
				if (ComponentType.PRODUCT == child.cd().getType()) {
					cascade(child, act.publication(), resolved, productRecipes, comment, wu, depth + 1, out, b);
				}
			} catch (TeaPublishRefusedException e) {
				// j. a refusal or failure refuses the child; the product publish stands
				log.warn("TEA publication of product release {}: component release {} {} ({}) refused: {}",
						product.label(), name, version, childUuid, e.getMessage());
				out.add(new TeaChildPublishResult(childUuid, name, version, TeaChildOutcome.REFUSED, e.reason(),
						e.getMessage(), head.map(TeaPublicationData::getUuid).orElse(null)));
			} catch (RelizaException | RuntimeException e) {
				log.error("TEA publication of product release {}: component release {} failed", product.label(),
						childUuid, e);
				out.add(new TeaChildPublishResult(childUuid, name, version, TeaChildOutcome.REFUSED,
						TeaPublishRefusal.GENERATION_FAILED, e.getMessage(), head.map(TeaPublicationData::getUuid).orElse(null)));
			} finally {
				// the lease is closed by the try, before the in-process lock is let go
				childLock.unlock();
			}
		}
	}

	/** A resolution standing in for a missing one: refused as a conflict naming no perspective. */
	private static final TeaResolvedProfile CONFLICT_UNKNOWN = new TeaResolvedProfile(TeaResolutionStatus.CONFLICT,
			null, null, null, List.of());

	/**
	 * "<component name> <version>" of the product that still holds a cascade child (3.7 step f):
	 * its publication exists, is PUBLISHED, is of this organization, and its release still lists the
	 * child. Empty when the owner let go, which lets another product take the child over.
	 */
	private Optional<String> holdingOwner(TeaPublicationData childHead, UUID org) {
		Optional<TeaPublicationData> owner = getPublication(childHead.getCascadeOf());
		if (owner.isEmpty() || TeaPublicationState.PUBLISHED != owner.get().getState()
				|| !org.equals(owner.get().getOrg())) {
			return Optional.empty();
		}
		ReleaseData ord = sharedReleaseService.getReleaseData(owner.get().getRelease()).orElse(null);
		if (null == ord || !org.equals(ord.getOrg()) || ord.getParentReleases().stream()
				.noneMatch(pr -> childHead.getRelease().equals(pr.getRelease()))) {
			return Optional.empty();
		}
		String name = getComponentService.getComponentData(ord.getComponent()).map(ComponentData::getName)
				.orElse(String.valueOf(ord.getComponent()));
		return Optional.of(name + " " + ord.getVersion());
	}

	/**
	 * The TEA settings a cascade child is published under (3.7 step h; the channel half of TEA-5's
	 * cascadeKnobs, task TEA-27): a copy of the product's row, so the row's uuid and revision are the
	 * product's, with {@code rawArtifacts} the narrower of the two (NONE < NON_BOM < ALL).
	 * Publishing, visibility and the minimum lifecycle are left as the product's on the copy: the gate,
	 * the lifecycle check and the exposure read the live resolutions, never these.
	 */
	static TeaProfileData cascadeSettings(TeaProfileData product, TeaProfileData own) {
		TeaProfileData effective = product.copy();
		effective.setRawArtifacts(narrowerRawArtifacts(product.getRawArtifacts(), own.getRawArtifacts()));
		return effective;
	}

	/**
	 * The named recipes a cascade child is shaped by (task TEA-28, 3.4, D8): the product's names in the
	 * product's order, each the product's recipe capped by the child. In this order:
	 * <ol>
	 * <li>the child's own settings' names, resolved at the child by {@link #recipesFor}, the call its
	 * direct publish makes: a name that does not resolve refuses the child (PROFILE_NOT_FOUND, or
	 * PROFILE_CONFLICT) with the direct publish's text, whatever the product names; the only profile
	 * refusal the cascade raises;</li>
	 * <li>the child's own cap: EXCLUDE when any own recipe has internalMetadata EXCLUDE, else INCLUDE;</li>
	 * <li>per product name, the child's view of the name (its component, its own settings' perspective
	 * preferred): its internalMetadata caps the name when it RESOLVES, else the own cap applies; neither
	 * answer refuses.</li>
	 * </ol>
	 * One INFO line per child names the product names that fell back to the own cap.
	 */
	List<TeaNamedRecipe> cascadeRecipes(List<TeaNamedRecipe> productRecipes, ReleaseContext child,
			TeaResolvedProfile own) throws TeaPublishRefusedException {
		List<TeaNamedRecipe> ownRecipes = recipesFor(child, own);
		SbomInternalMetadata ownCap = ownRecipes.stream()
				.anyMatch(r -> SbomInternalMetadata.EXCLUDE == r.recipe().getInternalMetadata())
						? SbomInternalMetadata.EXCLUDE : SbomInternalMetadata.INCLUDE;
		UUID preferred = settingsPerspectiveOf(own);
		List<TeaNamedRecipe> out = new ArrayList<>();
		List<String> fellBack = new ArrayList<>();
		for (TeaNamedRecipe pr : productRecipes) {
			SbomProfileResolution r = sbomProfileResolver.resolveForComponent(child.cd(), pr.name(), null, preferred);
			SbomInternalMetadata cap;
			if (null != r && ScopedResolutionStatus.RESOLVED == r.status() && null != r.profile()) {
				cap = r.profile().getInternalMetadata();
			} else {
				cap = ownCap;
				fellBack.add(pr.name());
			}
			out.add(new TeaNamedRecipe(pr.name(), cascadeRecipe(pr.recipe(), cap)));
		}
		if (!fellBack.isEmpty()) {
			log.info("TEA publication of component release {}: product profile names {} do not resolve at the "
					+ "component; capped by its own TEA settings' names (internal metadata {})", child.label(), fellBack,
					ownCap);
		}
		return out;
	}

	/**
	 * The recipe of one product name on a cascade child (the shape half of TEA-5's cascadeKnobs, tasks
	 * TEA-27 and TEA-28): a copy of the product's recipe with {@code internalMetadata} INCLUDE only when
	 * the product's recipe and the child's {@code cap} both say INCLUDE, every other field the product's.
	 */
	static SbomProfileData cascadeRecipe(SbomProfileData product, SbomInternalMetadata cap) {
		SbomProfileData effective = product.copy();
		boolean include = SbomInternalMetadata.INCLUDE == product.getInternalMetadata()
				&& SbomInternalMetadata.INCLUDE == cap;
		effective.setInternalMetadata(include ? SbomInternalMetadata.INCLUDE : SbomInternalMetadata.EXCLUDE);
		return effective;
	}

	static TeaRawArtifacts narrowerRawArtifacts(TeaRawArtifacts a, TeaRawArtifacts b) {
		if (null == a || null == b) return TeaRawArtifacts.NONE;
		return a.ordinal() <= b.ordinal() ? a : b;
	}

	/** The stricter (more mature) of two minimum lifecycles; null stands for no minimum. */
	static ReleaseLifecycle stricterMinimum(ReleaseLifecycle a, ReleaseLifecycle b) {
		if (null == a) return b;
		if (null == b) return a;
		return ReleaseLifecycle.maturity(a) >= ReleaseLifecycle.maturity(b) ? a : b;
	}

	// ------------------------------------------------------------------ hide

	/**
	 * Stops serving the release on TEA: the state flips to HIDDEN, no collection row is touched and
	 * nothing is deleted. A hidden head is answered unchanged. The in-process lock, then the
	 * release's lease, are taken before the first read (3.6), so a hide waits for a publish in
	 * flight on any instance and hides what it wrote.
	 */
	public TeaPublicationData hide(UUID release, String comment, WhoUpdated wu) throws TeaPublishRefusedException {
		return hide(release, comment, wu, TeaActLane.INTERACTIVE);
	}

	/** The hide on the given lane's budget; part 6 passes BACKGROUND. */
	public TeaPublicationData hide(UUID release, String comment, WhoUpdated wu, TeaActLane lane)
			throws TeaPublishRefusedException {
		WaitBudget b = budgetFor(lane);
		ReentrantLock lock = releaseLock(release);
		lockOrRefuse(lock, release, b);
		try {
			return hideLocked(release, comment, wu, b);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * The hide under the in-process lock: the lease, the fresh reads, the commit; the lease is
	 * closed on every exit. Package-private for the race tests, as {@link #publishLocked}.
	 */
	TeaPublicationData hideLocked(UUID release, String comment, WhoUpdated wu) throws TeaPublishRefusedException {
		return hideLocked(release, comment, wu, budgetFor(TeaActLane.INTERACTIVE));
	}

	TeaPublicationData hideLocked(UUID release, String comment, WhoUpdated wu, WaitBudget b)
			throws TeaPublishRefusedException {
		WhoUpdated who = null == wu ? WhoUpdated.getAutoWhoUpdated() : wu;
		if (null == release) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.RELEASE_NOT_FOUND, null, "Release null not found");
		}
		try (Lease lease = acquireOrRefuse(release, b)) {
			HeadAndRelease fresh = proxy().readFresh(release);
			ReleaseData rd = ReleaseData.dataFromRecord(fresh.release());
			if (fresh.head().isEmpty()) {
				String name = getComponentService.getComponentData(rd.getComponent()).map(ComponentData::getName)
						.orElse(String.valueOf(rd.getComponent()));
				throw new TeaPublishRefusedException(TeaPublishRefusal.NOT_PUBLISHED, release,
						"Release " + name + " " + rd.getVersion() + " (" + release + ") is not published on TEA");
			}
			TeaPublicationData head = TeaPublicationData.dataFromRecord(fresh.head().get());
			if (TeaPublicationState.HIDDEN == head.getState()) return head;
			TeaPublicationData hidden = proxy().commitHide(lease, release, comment, who);
			log.info("TEA publication {} of release {} hidden (collection version {} kept)", hidden.getUuid(), release,
					hidden.getLatestVersion());
			return hidden;
		} catch (TeaPublishRefusedException e) {
			throw e;
		} catch (RelizaException e) {
			log.error("Hiding the TEA publication of release {} failed", release, e);
			throw new TeaPublishRefusedException(TeaPublishRefusal.GENERATION_FAILED, release,
					"Hiding the TEA publication of release " + release + " failed: " + e.getMessage());
		}
	}

	/**
	 * The hide's commit under the release's lease {@code held}, on the head and release rows
	 * re-read in its transaction; a head that is already HIDDEN once fresh is answered as is, with
	 * no save and no event. The REMOVED event is the act's one write to the release row.
	 */
	@Transactional(rollbackFor = Exception.class)
	public TeaPublicationData commitHide(Lease held, UUID release, String comment, WhoUpdated wu)
			throws RelizaException {
		Objects.requireNonNull(held, "a TEA commit runs under the release's lease");
		HeadAndRelease fresh = readFresh(release);
		Optional<TeaPublication> row = fresh.head();
		if (row.isEmpty()) {
			throw new TeaPublishRefusedException(TeaPublishRefusal.NOT_PUBLISHED, release,
					"Release " + release + " is not published on TEA");
		}
		TeaPublicationData d = TeaPublicationData.dataFromRecord(row.get());
		if (TeaPublicationState.HIDDEN == d.getState()) return d;
		ZonedDateTime now = ZonedDateTime.now();
		d.setState(TeaPublicationState.HIDDEN);
		d.setLastHiddenDate(now);
		TeaPublicationData saved = saveHead(row, d, wu);
		ReleaseData rd = ReleaseData.dataFromRecord(fresh.release());
		rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.TEA_PUBLICATION, ReleaseUpdateAction.REMOVED,
				"PUBLISHED v" + d.getLatestVersion(), "HIDDEN", saved.getUuid(), comment, now, wu));
		ossReleaseService.saveRelease(fresh.release(), rd, wu, false, AcollectionMode.SKIP);
		if (null != releaseChangeHook) releaseChangeHook.onReleaseTeaHidden(rd, saved);
		return saved;
	}

	// ------------------------------------------------------------------ notification and logging

	/** One published notification per act, on the release the act was invoked on, never per cascade child. */
	private void notifyPublished(UUID release, Act act, int childrenPublished) {
		if (null == releaseChangeHook) return;
		try {
			ReleaseData rd = sharedReleaseService.getReleaseData(release).orElse(null);
			if (null == rd) return;
			TeaExposureState exposure = exposureOf(act.publication()).state();
			releaseChangeHook.onReleaseTeaPublished(rd, act.publication(), act.publication().getLatestVersion(), exposure,
					childrenPublished);
		} catch (RuntimeException e) {
			log.error("TEA publication of release {}: writing the published notification failed", release, e);
		}
	}

	private static void logAct(ReleaseContext ctx, Act act, List<TeaChildPublishResult> children) {
		long published = act.rideAlongs().stream().filter(r -> TeaRideAlongStatus.PUBLISHED == r.status()).count();
		long skipped = act.rideAlongs().size() - published;
		String aggregates = null == act.collection() ? "" : act.collection().getEntries().stream()
				.filter(e -> TeaArtifactOrigin.SYNTHETIC == e.origin()).map(e -> e.name() + " r" + e.revision())
				.collect(Collectors.joining(", "));
		long childPublished = children.stream().filter(c -> TeaChildOutcome.PUBLISHED == c.outcome()
				|| TeaChildOutcome.REPUBLISHED == c.outcome()).count();
		long childRefused = children.stream().filter(c -> TeaChildOutcome.REFUSED == c.outcome()).count();
		log.info("TEA publish of release {}: publication {} {} at collection version {}, aggregates [{}], "
				+ "ride-alongs {} published {} skipped, component releases {} published {} skipped {} refused",
				ctx.label(), null == act.publication() ? null : act.publication().getUuid(), act.outcome(),
				null == act.publication() ? 0 : act.publication().getLatestVersion(), aggregates, published, skipped,
				childPublished, children.size() - childPublished - childRefused, childRefused);
	}

	// ------------------------------------------------------------------ exposure

	/**
	 * What a publication is right now: visibility is live, content is frozen (3.9). The first row
	 * that applies wins: hidden; the release missing, archived or below ASSEMBLED; the component
	 * missing; the component's own resolution in conflict or disabled; then, for a cascade child,
	 * the owner product's exposure caps the child's own (PRIVATE narrower than PUBLIC, a concealed
	 * owner conceals the child). No row is ever written.
	 */
	public TeaPublicationExposure exposureOf(TeaPublicationData p) {
		return exposureOf(p, 0);
	}

	private static TeaPublicationExposure concealed(TeaConcealReason reason, ComponentData cd, TeaResolvedProfile resolved,
			TeaPublicationData p, TeaPublicationData cappedBy) {
		return new TeaPublicationExposure(TeaExposureState.CONCEALED, reason, cd, resolved, p, cappedBy);
	}

	private TeaPublicationExposure exposureOf(TeaPublicationData p, int depth) {
		// 1. hidden
		if (null == p || TeaPublicationState.HIDDEN == p.getState()) {
			return concealed(TeaConcealReason.HIDDEN, null, null, p, null);
		}
		// 2. the release: missing, archived, or no longer assembled
		ReleaseData rd = null == p.getRelease() ? null : sharedReleaseService.getReleaseData(p.getRelease()).orElse(null);
		if (null == rd) {
			log.warn("TEA publication {}: release {} is missing; concealed", p.getUuid(), p.getRelease());
			return concealed(TeaConcealReason.RELEASE_MISSING, null, null, p, null);
		}
		if (ReleaseStatus.ARCHIVED == rd.getStatus()) {
			return concealed(TeaConcealReason.RELEASE_ARCHIVED, null, null, p, null);
		}
		if (ReleaseLifecycle.maturity(rd.getLifecycle()) < ReleaseLifecycle.maturity(ReleaseLifecycle.ASSEMBLED)) {
			return concealed(TeaConcealReason.LIFECYCLE_BELOW_ASSEMBLED, null, null, p, null);
		}
		// 3. the publication's own component, of its organization
		Optional<ComponentData> cd = getComponentService.getComponentData(p.getComponent())
				.filter(c -> null != c.getOrg() && c.getOrg().equals(p.getOrg()));
		if (cd.isEmpty()) {
			log.warn("TEA publication {} of release {}: component {} is missing; concealed", p.getUuid(), p.getRelease(),
					p.getComponent());
			return concealed(TeaConcealReason.COMPONENT_MISSING, null, null, p, null);
		}
		// 4. the component's own resolution; the recorded row is the conflict fallback of a direct publication only
		TeaResolvedProfile own = teaProfileResolver.resolveForComponent(cd.get(),
				null == p.getCascadeOf() ? p.getProfile() : null);
		if (null == own || TeaResolutionStatus.CONFLICT == own.status() || null == own.profile()) {
			return concealed(TeaConcealReason.PROFILE_CONFLICT, cd.get(), own, p, null);
		}
		if (TeaPublishing.DISABLED == own.profile().getPublishing()) {
			return concealed(TeaConcealReason.PUBLISHING_DISABLED, cd.get(), own, p, null);
		}
		TeaExposureState ownState = TeaVisibility.PUBLIC == own.profile().getVisibility() ? TeaExposureState.PUBLIC
				: TeaExposureState.PRIVATE;
		if (null == p.getCascadeOf()) {
			return new TeaPublicationExposure(ownState, null, cd.get(), own, p, null);
		}
		// 5. the owner cap
		Optional<TeaPublicationData> owner = depth >= MAX_CASCADE_DEPTH ? Optional.empty()
				: getPublication(p.getCascadeOf()).filter(o -> p.getOrg().equals(o.getOrg()));
		if (owner.isEmpty()) {
			log.warn("TEA publication {} of release {} was cascaded from publication {}, which is missing; concealed",
					p.getUuid(), p.getRelease(), p.getCascadeOf());
			return concealed(TeaConcealReason.PRODUCT_MISSING, cd.get(), own, p, null);
		}
		TeaPublicationExposure ownerExposure = exposureOf(owner.get(), depth + 1);
		if (TeaExposureState.CONCEALED == ownerExposure.state()) {
			return concealed(TeaConcealReason.PRODUCT_CONCEALED, cd.get(), own, p, owner.get());
		}
		if (TeaExposureState.PUBLIC == ownState && TeaExposureState.PRIVATE == ownerExposure.state()) {
			TeaProfileData capped = own.profile().copy();
			capped.setVisibility(TeaVisibility.PRIVATE);
			TeaResolvedProfile cappedResolution = new TeaResolvedProfile(own.status(), own.source(), own.sourceObject(),
					capped, own.conflictingPerspectives());
			return new TeaPublicationExposure(TeaExposureState.PRIVATE, null, cd.get(), cappedResolution, p, owner.get());
		}
		return new TeaPublicationExposure(ownState, null, cd.get(), own, p, owner.get());
	}

	/**
	 * How many PUBLISHED releases resolve, now, to the profile of this scope: the count the PUBLIC
	 * confirmation states. A head counts when its own component resolves to the scope; a cascade
	 * child counts also when its owner's component (recursively) does, at most once per head. Each
	 * component is resolved once, without the recorded fallback.
	 */
	public int countPublishedResolvingTo(UUID org, TeaProfileScope scope, UUID object) {
		if (null == org || null == scope) return 0;
		List<TeaPublicationHeadView> heads = publicationRepository.listHeadsOfOrg(org, TeaPublicationState.PUBLISHED);
		if (heads.isEmpty()) return 0;
		Map<UUID, Optional<TeaResolvedProfile>> resolvedByComponent = new HashMap<>();
		Map<UUID, List<UUID>> ownersOfPublication = new HashMap<>();
		int count = 0;
		for (TeaPublicationHeadView h : heads) {
			List<UUID> governing = new ArrayList<>();
			if (null != h.getComponent()) governing.add(h.getComponent());
			governing.addAll(cascadeChainComponents(h.getUuid(), h.getCascadeOf(), ownersOfPublication, 0));
			boolean counts = governing.stream().distinct().anyMatch(component -> resolvedByComponent
					.computeIfAbsent(component, c -> getComponentService.getComponentData(c)
							.filter(cd -> org.equals(cd.getOrg())).map(cd -> teaProfileResolver.resolveForComponent(cd)))
					.filter(r -> TeaResolutionStatus.CONFLICT != r.status()).filter(r -> countsFor(scope, object, r))
					.isPresent());
			if (counts) count++;
		}
		return count;
	}

	/** The components of a head's owner chain, nearest owner first; empty for a direct publication. */
	private List<UUID> cascadeChainComponents(UUID publication, UUID cascadeOf, Map<UUID, List<UUID>> memo, int depth) {
		if (null == cascadeOf) return List.of();
		if (memo.containsKey(publication)) return memo.get(publication);
		List<UUID> out = new ArrayList<>();
		if (depth < MAX_CASCADE_DEPTH) {
			Optional<TeaPublication> owner = publicationRepository.findById(cascadeOf);
			if (owner.isPresent()) {
				if (null != owner.get().getComponent()) out.add(owner.get().getComponent());
				out.addAll(cascadeChainComponents(owner.get().getUuid(), owner.get().getCascadeOf(), memo, depth + 1));
			}
		}
		memo.put(publication, out);
		return out;
	}

	private static boolean countsFor(TeaProfileScope scope, UUID object, TeaResolvedProfile r) {
		return switch (scope) {
			case COMPONENT -> TeaProfileSource.COMPONENT == r.source() && null != object && object.equals(r.sourceObject());
			case PERSPECTIVE -> (TeaProfileSource.PERSPECTIVE == r.source()
					|| TeaProfileSource.FOLLOWED_PERSPECTIVE == r.source()) && null != object
					&& object.equals(r.sourceObject());
			case ORGANIZATION -> TeaProfileSource.ORGANIZATION == r.source() || TeaProfileSource.DEFAULT == r.source();
		};
	}

	// ------------------------------------------------------------------ preview and URLs

	/** Steps 1 to 4 of a publish without writing anything, plus what the page lists. */
	public TeaPublishPreview preview(UUID release) {
		Optional<TeaPublicationData> head = getPublicationOfRelease(release);
		List<TeaPublishedCollectionData> collections = head.map(h -> listCollections(h.getUuid())).orElse(List.of());
		ReleaseData rd = null == release ? null : sharedReleaseService.getReleaseData(release).orElse(null);
		ComponentData cd = null == rd ? null : getComponentService.getComponentData(rd.getComponent()).orElse(null);
		TeaCollectionBelongsToType kind = null != cd && ComponentType.PRODUCT == cd.getType()
				? TeaCollectionBelongsToType.PRODUCT_RELEASE : TeaCollectionBelongsToType.COMPONENT_RELEASE;
		String apiBase = null == rd ? null : apiBaseOf(rd.getOrg());
		String publicUrl = head.map(h -> publicUrl(apiBase, h)).orElse(null);
		TeaResolvedProfile resolved = null == cd ? null : teaProfileResolver.resolveForComponent(cd);
		TeaVisibility visibility = null == resolved || null == resolved.profile() ? null : resolved.profile().getVisibility();
		boolean cascades = TeaCollectionBelongsToType.PRODUCT_RELEASE == kind && null != resolved
				&& null != resolved.profile()
				&& TeaProductComponents.PUBLISH_WITH_PRODUCT == resolved.profile().getProductComponents();
		int cascadeChildren = cascades ? rd.getParentReleases().size() : 0;
		Integer revision = null == resolved || null == resolved.profile() ? null : resolved.profile().getRevision();
		TeaPublishRefusal refusal = null;
		String message = null;
		try {
			ReleaseContext ctx = loadRelease(release);
			checkMode(TeaPublishMode.PUBLISH, head, ctx);
			checkProfile(resolved, ctx);
			checkLifecycle(ctx, resolved.profile());
			// the publish's recipe check, every name in order (tasks TEA-27 F-5, TEA-28)
			recipesFor(ctx, resolved);
		} catch (TeaPublishRefusedException e) {
			refusal = e.reason();
			message = e.getMessage();
		}
		return new TeaPublishPreview(release, kind, null == refusal, refusal, message, visibility,
				null == resolved ? null : resolved.source(), null == resolved ? null : resolved.sourceObject(), apiBase,
				publicUrl, cascades, cascadeChildren, revision, head.orElse(null), collections,
				null == resolved ? List.of() : profileNamesOf(resolved.profile()),
				// the effective switch on its own: the refusal names one reason, and an earlier one (archived,
				// already published) hides PUBLISHING_DISABLED, so the page cannot read it from there
				null == resolved || null == resolved.profile() ? null : resolved.profile().getPublishing());
	}

	/** The organization's TEA API base, null while it has no TEA id. */
	public String apiBaseOf(UUID org) {
		try {
			TeaOrgDiscovery d = teaProfileService.orgDiscovery(org);
			return null == d ? null : d.apiBase();
		} catch (RelizaException e) {
			log.error("TEA API base of organization {} could not be read", org, e);
			return null;
		}
	}

	/** {@code <apiBase>/v1.0.0/productRelease/<uuid>} or {@code .../componentRelease/<uuid>}; null without an API base. */
	public static String publicUrl(String apiBase, TeaPublicationData p) {
		if (null == apiBase || null == p || null == p.getUuid()) return null;
		String kind = ComponentType.PRODUCT == p.getComponentType() ? "productRelease" : "componentRelease";
		return apiBase + "/v1.0.0/" + kind + "/" + p.getUuid();
	}
}
