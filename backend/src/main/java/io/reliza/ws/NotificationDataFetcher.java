/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.ws;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.AuthorizationStatus;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.dto.notifications.MarkAllNotificationsReadResult;
import io.reliza.model.NotificationChannelGroup;
import io.reliza.model.dto.notifications.NotificationChannelGroupData;
import io.reliza.model.dto.notifications.NotificationChannelGroupInput;
import io.reliza.model.dto.notifications.NotificationChannelGroupResult;
import io.reliza.model.dto.notifications.NotificationChannelInput;
import io.reliza.model.dto.notifications.NotificationChannelResult;
import io.reliza.model.dto.notifications.NotificationDeliveriesPage;
import io.reliza.model.EmailDigestPolicy;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.dto.notifications.NotificationDeliveryResult;
import io.reliza.model.NotificationDeliveryStatus;
import io.reliza.model.dto.notifications.NotificationInboxItem;
import io.reliza.model.dto.notifications.NotificationInboxPage;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.dto.notifications.NotificationOutboxEventResult;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.FilterConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RateLimitConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionInput;
import io.reliza.model.dto.notifications.NotificationSubscriptionResult;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationChannelGroupRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.service.AuditService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;
import io.reliza.service.NotificationChannelGroupService;
import io.reliza.service.NotificationChannelService;
import io.reliza.service.NotificationInboxFormatter;
import io.reliza.service.NotificationReadService;
import io.reliza.service.NotificationSubscriptionService;
import io.reliza.service.SyntheticEventService;
import io.reliza.service.SyntheticEventTemplates.Template;
import lombok.extern.slf4j.Slf4j;

/**
 * GraphQL surface for the notifications framework — Phases 2d + 2e.
 *
 * <p>Mutations (Phase 2d):
 * <ul>
 *   <li>{@code injectSyntheticEvent} — operator-only; exercises the full
 *       pipeline (outbox → fan-out → channel dispatch → delivery row)
 *       end-to-end with a curated template.</li>
 *   <li>{@code testNotificationChannel} — org-admin; pings one specific
 *       channel via a channel-test-target outbox row so the customer can
 *       verify the webhook is reachable before relying on subscription
 *       routing.</li>
 * </ul>
 *
 * <p>Queries (Phase 2e):
 * <ul>
 *   <li>{@code notificationOutboxEvent(uuid)} — single event lookup.</li>
 *   <li>{@code notificationDelivery(uuid)} — single delivery lookup.</li>
 *   <li>{@code notificationDeliveries(orgUuid, filters, limit, offset)} —
 *       paginated listing with optional filters.</li>
 *   <li>{@code notificationSubscriptions(orgUuid)} — read-only list.</li>
 *   <li>{@code notificationChannels(orgUuid)} — read-only list with
 *       encrypted secrets stripped.</li>
 * </ul>
 *
 * <p>All queries are org-admin scoped — the data fetcher resolves the
 * org from the entity uuid first, then authorizes against it. A caller
 * cannot guess uuids in another tenant.
 *
 * <p>Mutations run through {@link SyntheticEventService}, which writes
 * the outbox row with {@code origin = SYNTHETIC}. The dispatch itself
 * happens on the next fan-out tick (up to 5s later); the mutation
 * returns the persisted outbox event so the caller can poll deliveries
 * by event uuid for the actual webhook result.
 */
@Slf4j
@DgsComponent
public class NotificationDataFetcher {

	/** Default page size when no limit is provided on listing queries. */
	private static final int DEFAULT_PAGE_SIZE = 50;

	/**
	 * Hard cap on the page size a caller can request. A hostile client
	 * asking for limit=1_000_000 would otherwise OOM the JSON serializer;
	 * a runaway UI bug could thrash the DB. 500 leaves room for big
	 * "show recent" pages while keeping single-roundtrip work bounded.
	 */
	private static final int MAX_PAGE_SIZE = 500;

	@Autowired private AuthorizationService authorizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private UserService userService;
	@Autowired private SyntheticEventService syntheticEventService;
	@Autowired private IntegrationRepository integrationRepo;
	@Autowired private NotificationDeliveryRepository deliveryRepo;
	@Autowired private NotificationOutboxEventRepository outboxRepo;
	@Autowired private NotificationSubscriptionRepository subscriptionRepo;
	@Autowired private NotificationChannelGroupRepository channelGroupRepo;
	@Autowired private NotificationChannelService channelService;
	@Autowired private NotificationSubscriptionService subscriptionService;
	@Autowired private NotificationChannelGroupService channelGroupService;
	@Autowired private NotificationReadService readService;
	@Autowired private NotificationInboxFormatter inboxFormatter;
	@Autowired private AuditService auditService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "injectSyntheticEvent")
	public NotificationOutboxEventResult injectSyntheticEvent(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("template") String templateName) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		if (templateName == null) throw new RelizaException("template is required");
		Template template = parseTemplate(templateName);
		WhoUpdated wu = authorizeOrgAdmin(orgUuid);
		NotificationOutboxEvent saved = syntheticEventService.inject(orgUuid, template);
		// Audit the operator action. Outbox rows are append-only (no
		// revision bumps in normal flow), so this audit captures the
		// initial-insert state — that's the operator-traceability the
		// SyntheticEventService class javadoc TODO promised.
		auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_OUTBOX_EVENT, saved);
		log.info("injectSyntheticEvent by user={} org={} template={} -> event {}",
				wu != null ? wu.getLastUpdatedBy() : null, orgUuid, template, saved.getUuid());
		return toResult(saved);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "testNotificationChannel")
	public NotificationOutboxEventResult testNotificationChannel(
			@InputArgument("channelUuid") UUID channelUuid) throws RelizaException {
		if (channelUuid == null) throw new RelizaException("channelUuid is required");
		// Resolve the channel's org before authorizing — the caller must
		// be admin of the channel's tenant, not whichever org they happen
		// to think it belongs to.
		Optional<Integration> oChannel = integrationRepo.findById(channelUuid);
		if (oChannel.isEmpty() || !isNotificationChannel(oChannel.get())) {
			throw new RelizaException("Channel not found: " + channelUuid);
		}
		UUID channelOrg = extractChannelOrg(oChannel.get());
		if (channelOrg == null) throw new RelizaException("Channel " + channelUuid + " has malformed org binding");
		WhoUpdated wu = authorizeOrgAdmin(channelOrg);
		NotificationOutboxEvent saved = syntheticEventService.injectChannelTest(channelOrg, channelUuid);
		auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_OUTBOX_EVENT, saved);
		log.info("testNotificationChannel by user={} org={} channel={} -> event {}",
				wu != null ? wu.getLastUpdatedBy() : null, channelOrg, channelUuid, saved.getUuid());
		return toResult(saved);
	}

	/**
	 * Resolve the JWT-bound user + authorize org-admin tier. Returns
	 * the {@link WhoUpdated} captured from the user so callers can
	 * propagate audit attribution. Mirrors the WebhookDataFetcher
	 * pattern; when a third fetcher needs this, extract to a shared
	 * helper at the package level.
	 */
	private WhoUpdated authorizeOrgAdmin(UUID orgUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(
				oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION,
				orgUuid, List.of(ro), CallType.ADMIN);
		return WhoUpdated.getWhoUpdated(oud.get());
	}

	private static UUID extractChannelOrg(Integration channel) {
		try {
			IntegrationData data = IntegrationData.dataFromRecord(channel);
			return data != null ? data.getOrg() : null;
		} catch (RuntimeException e) {
			log.warn("Failed to parse channel {} record_data while authorizing test: {}",
					channel.getUuid(), e.getMessage());
			return null;
		}
	}

	/**
	 * Channel discriminator for the notification-channel surface. Since
	 * Phase 2b-1 channels share the {@code integrations} table with CI
	 * integrations (DEPENDENCYTRACK / GITHUB / …) and the legacy "base"
	 * Slack/Teams rows. A row is a notification channel only when it
	 * carries a non-null {@code name} AND a notification-destination type.
	 * Pre-2b-1 these mutations hit the {@code notification_channels} table
	 * exclusively, so a CI-integration uuid was always "not found"; this
	 * guard preserves that isolation now that {@code findById} sees every
	 * integration in the org. Unparseable rows are treated as non-channels.
	 */
	private static boolean isNotificationChannel(Integration integration) {
		try {
			IntegrationData d = IntegrationData.dataFromRecord(integration);
			return d != null && d.getName() != null
					&& IntegrationData.NOTIFICATION_DESTINATION_TYPES.contains(d.getType());
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Map the schema enum value onto the service-layer template via the
	 * tolerant parser that lives on the enum itself
	 * ({@link Template#parse}). Anything not in the enum gets rejected
	 * at the GraphQL parser layer before reaching this fetcher; the
	 * explicit catch keeps the error message readable if someone passes
	 * a value that's been renamed.
	 */
	private static Template parseTemplate(String templateName) throws RelizaException {
		try {
			return Template.parse(templateName);
		} catch (IllegalArgumentException e) {
			throw new RelizaException(e.getMessage());
		}
	}

	/** Project the entity onto the typed GraphQL output record. */
	private static NotificationOutboxEventResult toResult(NotificationOutboxEvent event) {
		return new NotificationOutboxEventResult(
				event.getUuid(),
				event.getOrg(),
				event.getEventType() != null ? event.getEventType().name() : null,
				event.getStatus() != null ? event.getStatus().name() : null,
				event.getOccurredAt() != null
						? event.getOccurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
						: null,
				event.getOrigin() != null ? event.getOrigin().name() : null,
				event.getDedupKey(),
				event.getChannelTestTarget());
	}

	// ------------------------------------------------------------------
	// Phase 2e read queries
	// ------------------------------------------------------------------

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationOutboxEvent")
	public NotificationOutboxEventResult getNotificationOutboxEvent(
			@InputArgument("uuid") UUID uuid) throws RelizaException {
		if (uuid == null) return null;
		Optional<NotificationOutboxEvent> oEvent = outboxRepo.findById(uuid);
		if (oEvent.isEmpty()) return null;
		NotificationOutboxEvent event = oEvent.get();
		// Authorize against the event's org so a caller fishing UUIDs
		// across tenants gets a clean rejection rather than data.
		authorizeOrgAdmin(event.getOrg());
		return toResult(event);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationDelivery")
	public NotificationDeliveryResult getNotificationDelivery(
			@InputArgument("uuid") UUID uuid) throws RelizaException {
		if (uuid == null) return null;
		Optional<NotificationDelivery> oDelivery = deliveryRepo.findById(uuid);
		if (oDelivery.isEmpty()) return null;
		NotificationDelivery delivery = oDelivery.get();
		authorizeOrgAdmin(delivery.getOrg());
		return toDeliveryResult(delivery);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationDeliveries")
	public NotificationDeliveriesPage getNotificationDeliveries(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("eventUuid") UUID eventUuid,
			@InputArgument("channelUuid") UUID channelUuid,
			@InputArgument("subscriptionUuid") UUID subscriptionUuid,
			@InputArgument("status") String status,
			@InputArgument("origin") String origin,
			@InputArgument("limit") Integer limit,
			@InputArgument("offset") Integer offset) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		authorizeOrgAdmin(orgUuid);
		// Re-validate the enum strings server-side. The GraphQL parser
		// rejects typos before the fetcher fires (schema declares typed
		// enums), but a typed enum miss could still slip through if a
		// future caller bypasses the schema (e.g. raw HTTP, programmatic
		// agent). Round-trip through the Java enum's *_VALUE constants
		// per coding_principles.md so a typo here lands as a 400-ish
		// RelizaException instead of a silent zero-row response.
		String validatedStatus = normalizeStatusFilter(status);
		String validatedOrigin = normalizeOriginFilter(origin);
		int effLimit = clampLimit(limit);
		int effOffset = offset != null && offset > 0 ? offset : 0;
		List<NotificationDelivery> rows = deliveryRepo.findFilteredPage(
				orgUuid, eventUuid, channelUuid, subscriptionUuid, validatedStatus, validatedOrigin, effLimit, effOffset);
		long total = deliveryRepo.countFiltered(
				orgUuid, eventUuid, channelUuid, subscriptionUuid, validatedStatus, validatedOrigin);
		List<NotificationDeliveryResult> items = rows.stream()
				.map(NotificationDataFetcher::toDeliveryResult)
				.toList();
		return new NotificationDeliveriesPage(items, total, effLimit, effOffset);
	}

	/**
	 * Validate an inbound status filter against the Java enum, returning
	 * the canonical {@code *_VALUE} string for the repository's SQL
	 * comparison. A typo on the typed schema is caught by GraphQL; this
	 * is the belt-and-suspenders for non-schema callers.
	 */
	private static String normalizeStatusFilter(String raw) throws RelizaException {
		if (raw == null) return null;
		try {
			return NotificationDeliveryStatus.valueOf(raw).name();
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Unknown delivery status filter: " + raw);
		}
	}

	private static String normalizeOriginFilter(String raw) throws RelizaException {
		if (raw == null) return null;
		try {
			return NotificationDeliveryOrigin.valueOf(raw).name();
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Unknown delivery origin filter: " + raw);
		}
	}

	/**
	 * Validate the optional event-type filter on the inbox query. The
	 * GraphQL parser already rejects schema-typed enum typos before
	 * the fetcher fires; this is belt-and-suspenders for non-schema
	 * callers (raw HTTP, programmatic agent) and lets the repo
	 * predicate compare against the canonical {@code .name()} string.
	 */
	private static String normalizeEventTypeFilter(String raw) throws RelizaException {
		if (raw == null) return null;
		try {
			return io.reliza.model.NotificationEventType.valueOf(raw).name();
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Unknown event-type filter: " + raw);
		}
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationSubscriptions")
	public List<NotificationSubscriptionResult> listNotificationSubscriptions(
			@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		authorizeOrgAdmin(orgUuid);
		List<NotificationSubscription> rows = subscriptionRepo.findByOrg(orgUuid);
		if (rows == null) return Collections.emptyList();
		return rows.stream()
				.map(NotificationDataFetcher::toSubscriptionResult)
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationChannels")
	public List<NotificationChannelResult> listNotificationChannels(
			@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		authorizeOrgAdmin(orgUuid);
		List<Integration> rows = integrationRepo.listIntegrationsByOrg(orgUuid.toString());
		if (rows == null) return Collections.emptyList();
		return rows.stream()
				.map(NotificationDataFetcher::toChannelResult)
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	/**
	 * Cap the page size and apply the default. Negative + zero asks for
	 * "the default"; unbounded-large asks gets clamped to MAX_PAGE_SIZE.
	 * Done at the fetcher layer (not the repo) so the cap is enforced
	 * uniformly across any future caller.
	 */
	private static int clampLimit(Integer limit) {
		if (limit == null || limit <= 0) return DEFAULT_PAGE_SIZE;
		return Math.min(limit, MAX_PAGE_SIZE);
	}

	private static NotificationDeliveryResult toDeliveryResult(NotificationDelivery d) {
		return new NotificationDeliveryResult(
				d.getUuid(),
				d.getOrg(),
				d.getOutboxEventUuid(),
				d.getSubscriptionUuid(),
				d.getChannelUuid(),
				d.getStatus() != null ? d.getStatus().name() : null,
				d.getOrigin() != null ? d.getOrigin().name() : null,
				d.getDedupKey(),
				d.getAttemptCount(),
				d.getNextAttemptAt() != null
						? d.getNextAttemptAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
						: null,
				d.getSentAt() != null
						? d.getSentAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
						: null,
				d.getLastError(),
				d.getCreatedDate() != null
						? d.getCreatedDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
						: null);
	}

	/**
	 * Project a subscription onto the read-side result. The complex
	 * nested config (filter, routes, rateLimit) goes out as JSON
	 * strings — full typed schema lands with Phase 3 CRUD. Subscriptions
	 * whose {@code record_data} fails to parse are filtered out by the
	 * caller (null return signals "skip").
	 */
	private static NotificationSubscriptionResult toSubscriptionResult(NotificationSubscription sub) {
		try {
			NotificationSubscriptionData d = Utils.OM.convertValue(
					sub.getRecordData(), NotificationSubscriptionData.class);
			if (d == null) return null;
			return new NotificationSubscriptionResult(
					sub.getUuid(),
					d.org(),
					d.resourceGroup(),
					d.name(),
					d.status() != null ? d.status().name() : null,
					d.eventTypes() != null
							? d.eventTypes().stream().map(Enum::name).toList()
							: Collections.emptyList(),
					d.filter() != null ? Utils.OM.writeValueAsString(d.filter()) : null,
					d.routes() != null ? Utils.OM.writeValueAsString(d.routes()) : null,
					d.dedupWindowMinutes(),
					d.rateLimit() != null ? Utils.OM.writeValueAsString(d.rateLimit()) : null,
					sub.getRevision());
		} catch (RuntimeException e) {
			log.warn("Failed to render subscription {} for read: {}", sub.getUuid(), e.getMessage());
			return null;
		}
	}

	/**
	 * Project a channel onto the read-side result. Two-layer credential
	 * hygiene: {@code encryptedSecret} AND {@code configData} are both
	 * omitted, since the latter can carry webhook URLs that are
	 * themselves auth credentials. Phase 3 channel CRUD will split
	 * configData into typed credential-vs-display fields; v1 stays
	 * conservative.
	 */
	private static NotificationChannelResult toChannelResult(Integration channel) {
		try {
			IntegrationData d = IntegrationData.dataFromRecord(channel);
			if (d == null) return null;
			// Channel discriminator: only integration rows that carry a
			// non-null name AND a notification-destination type are
			// notification channels. Legacy CI/base integration rows (null
			// name) are silently skipped so the list query — which scans
			// every integration in the org — never leaks a CI integration
			// into the channels surface.
			if (d.getName() == null
					|| !IntegrationData.NOTIFICATION_DESTINATION_TYPES.contains(d.getType())) {
				return null;
			}
			// Digest policy + recipients ride on the read surface for
			// EMAIL channels only — digest with defaults applied (absent
			// config = ROLLING/PT24H) so the UI renders the effective
			// behaviour, recipients because inbox addresses are display
			// data, not credentials. The configData embargo stays in
			// force for every other type.
			String digestMode = null;
			String digestInterval = null;
			List<String> emailRecipients = null;
			if (d.getType() == IntegrationData.IntegrationType.EMAIL) {
				EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(d.getParameters());
				digestMode = policy.mode().name();
				digestInterval = policy.interval().toString();
				emailRecipients = EmailDigestPolicy.extractRecipients(d.getParameters());
			}
			return new NotificationChannelResult(
					channel.getUuid(),
					d.getOrg(),
					d.getResourceGroup(),
					d.getName(),
					IntegrationData.toChannelTypeName(d.getType()),
					Boolean.FALSE.equals(d.getIsEnabled()) ? "DISABLED" : "ENABLED",
					channel.getRevision(),
					digestMode,
					digestInterval,
					emailRecipients,
					d.getDisabledReason());
		} catch (RuntimeException e) {
			log.warn("Failed to render channel {} for read: {}", channel.getUuid(), e.getMessage());
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Phase 3: single-entity read queries (filling the Phase 2e gap)
	// ------------------------------------------------------------------

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationSubscription")
	public NotificationSubscriptionResult getNotificationSubscription(
			@InputArgument("uuid") UUID uuid) throws RelizaException {
		if (uuid == null) return null;
		Optional<NotificationSubscription> oSub = subscriptionRepo.findById(uuid);
		if (oSub.isEmpty()) return null;
		NotificationSubscription sub = oSub.get();
		UUID org = extractSubscriptionOrg(sub);
		if (org == null) throw new RelizaException("Subscription " + uuid + " has malformed org binding");
		authorizeOrgAdmin(org);
		return toSubscriptionResult(sub);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationChannel")
	public NotificationChannelResult getNotificationChannel(
			@InputArgument("uuid") UUID uuid) throws RelizaException {
		if (uuid == null) return null;
		Optional<Integration> oChannel = integrationRepo.findById(uuid);
		if (oChannel.isEmpty() || !isNotificationChannel(oChannel.get())) return null;
		Integration channel = oChannel.get();
		UUID org = extractChannelOrg(channel);
		if (org == null) throw new RelizaException("Channel " + uuid + " has malformed org binding");
		authorizeOrgAdmin(org);
		return toChannelResult(channel);
	}

	// ------------------------------------------------------------------
	// Phase 3: channel CRUD
	// ------------------------------------------------------------------

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "upsertNotificationChannel")
	public NotificationChannelResult upsertNotificationChannel(
			@InputArgument("input") Map<String, Object> input) throws RelizaException {
		if (input == null) throw new RelizaException("input is required");
		NotificationChannelInput parsed = convertInput(input, NotificationChannelInput.class, "channel");
		if (parsed.org() == null) throw new RelizaException("org is required");
		if (StringUtils.isBlank(parsed.name())) throw new RelizaException("name is required");
		if (parsed.type() == null) throw new RelizaException("type is required");

		WhoUpdated wu = authorizeOrgAdmin(parsed.org());
		// For update, double-check the row's existing org matches the
		// caller's claim — prevents a malicious caller from "moving" a
		// channel into their org by passing a foreign uuid. Reject if
		// the row exists but has unparseable org binding (rather than
		// silently allowing the overwrite).
		if (parsed.uuid() != null) {
			Optional<Integration> existing = integrationRepo.findById(parsed.uuid());
			if (existing.isPresent()) {
				if (!isNotificationChannel(existing.get())) {
					// The uuid resolves to a CI/base integration, not a
					// notification channel — refuse to overwrite it (pre-2b-1
					// this row lived in a different table and was unreachable).
					throw new RelizaException("Channel " + parsed.uuid() + " not found");
				}
				UUID existingOrg = extractChannelOrg(existing.get());
				if (existingOrg == null) {
					throw new RelizaException("Channel " + parsed.uuid()
							+ " has malformed org binding — refusing to update");
				}
				if (!existingOrg.equals(parsed.org())) {
					throw new RelizaException("Channel " + parsed.uuid()
							+ " does not belong to org " + parsed.org());
				}
			}
		}

		// Map the GraphQL string enums onto the IntegrationData seed: the
		// channel type via the MS_TEAMS<->MSTEAMS-aware helper, and the
		// status string onto the boolean isEnabled flag (default enabled
		// when unspecified). Secret + parameters are resolved server-side
		// from the per-type config inputs.
		IntegrationType seedType = IntegrationData.fromChannelTypeName(parsed.type());
		if (seedType == null) throw new RelizaException("Unknown channel type: " + parsed.type());
		IntegrationData seed = new IntegrationData();
		seed.setOrg(parsed.org());
		seed.setResourceGroup(parsed.resourceGroup());
		seed.setName(parsed.name());
		seed.setType(seedType);
		seed.setIsEnabled(!CHANNEL_STATUS_DISABLED.equals(parsed.status()));
		seed.setParameters(new java.util.HashMap<>());

		Integration saved = channelService.upsertChannel(
				parsed.uuid(), parsed.expectedRevision(), seed,
				parsed.slackConfig(), parsed.webhookConfig(),
				parsed.emailConfig(), parsed.teamsConfig(),
				parsed.sentinelConfig(),
				wu);
		log.info("upsertNotificationChannel by user={} org={} channel={} type={}",
				wu != null ? wu.getLastUpdatedBy() : null, parsed.org(), saved.getUuid(), parsed.type());
		return toChannelResult(saved);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setNotificationChannelStatus")
	public NotificationChannelResult setNotificationChannelStatus(
			@InputArgument("uuid") UUID uuid,
			@InputArgument("status") String statusName) throws RelizaException {
		if (uuid == null) throw new RelizaException("uuid is required");
		// Validate + map the GraphQL status string onto the boolean the
		// service takes. Round-trips through the canonical ENABLED/DISABLED
		// values so a non-schema caller's typo lands as a clean error.
		boolean enabled = parseChannelEnabled(statusName);
		Optional<Integration> oChannel = integrationRepo.findById(uuid);
		if (oChannel.isEmpty() || !isNotificationChannel(oChannel.get())) throw new RelizaException("Channel not found: " + uuid);
		UUID channelOrg = extractChannelOrg(oChannel.get());
		if (channelOrg == null) throw new RelizaException("Channel " + uuid + " has malformed org binding");
		WhoUpdated wu = authorizeOrgAdmin(channelOrg);
		Integration saved = channelService.setChannelStatus(uuid, enabled, wu);
		return toChannelResult(saved);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteNotificationChannel")
	public Boolean deleteNotificationChannel(@InputArgument("uuid") UUID uuid) throws RelizaException {
		if (uuid == null) throw new RelizaException("uuid is required");
		Optional<Integration> oChannel = integrationRepo.findById(uuid);
		if (oChannel.isEmpty() || !isNotificationChannel(oChannel.get())) return Boolean.FALSE;
		UUID channelOrg = extractChannelOrg(oChannel.get());
		if (channelOrg == null) throw new RelizaException("Channel " + uuid + " has malformed org binding");
		authorizeOrgAdmin(channelOrg);
		channelService.deleteChannel(uuid);
		return Boolean.TRUE;
	}

	// ------------------------------------------------------------------
	// Phase 3: subscription CRUD
	// ------------------------------------------------------------------

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "upsertNotificationSubscription")
	public NotificationSubscriptionResult upsertNotificationSubscription(
			@InputArgument("input") Map<String, Object> input) throws RelizaException {
		if (input == null) throw new RelizaException("input is required");
		NotificationSubscriptionInput parsed = convertInput(
				input, NotificationSubscriptionInput.class, "subscription");
		if (parsed.org() == null) throw new RelizaException("org is required");

		WhoUpdated wu = authorizeOrgAdmin(parsed.org());
		// Cross-tenant probe rejection on update: reject if the row
		// exists but belongs to a different org OR has unparseable org
		// binding (the malformed-org case is treated as "refuse to
		// reauthorize" rather than silently allowing the overwrite).
		if (parsed.uuid() != null) {
			Optional<NotificationSubscription> existing = subscriptionRepo.findById(parsed.uuid());
			if (existing.isPresent()) {
				UUID existingOrg = extractSubscriptionOrg(existing.get());
				if (existingOrg == null) {
					throw new RelizaException("Subscription " + parsed.uuid()
							+ " has malformed org binding — refusing to update");
				}
				if (!existingOrg.equals(parsed.org())) {
					throw new RelizaException("Subscription " + parsed.uuid()
							+ " does not belong to org " + parsed.org());
				}
			}
		}

		NotificationSubscriptionData seed = inputToSubscriptionData(parsed);
		NotificationSubscription saved = subscriptionService.upsertSubscription(
				parsed.uuid(), parsed.expectedRevision(), seed, wu);
		log.info("upsertNotificationSubscription by user={} org={} sub={}",
				wu != null ? wu.getLastUpdatedBy() : null, parsed.org(), saved.getUuid());
		return toSubscriptionResult(saved);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setNotificationSubscriptionStatus")
	public NotificationSubscriptionResult setNotificationSubscriptionStatus(
			@InputArgument("uuid") UUID uuid,
			@InputArgument("status") String statusName) throws RelizaException {
		if (uuid == null) throw new RelizaException("uuid is required");
		NotificationSubscriptionStatus status = parseStatusEnum(
				NotificationSubscriptionStatus.class, statusName);
		Optional<NotificationSubscription> oSub = subscriptionRepo.findById(uuid);
		if (oSub.isEmpty()) throw new RelizaException("Subscription not found: " + uuid);
		UUID subOrg = extractSubscriptionOrg(oSub.get());
		if (subOrg == null) throw new RelizaException("Subscription " + uuid + " has malformed org binding");
		WhoUpdated wu = authorizeOrgAdmin(subOrg);
		NotificationSubscription saved = subscriptionService.setSubscriptionStatus(uuid, status, wu);
		return toSubscriptionResult(saved);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteNotificationSubscription")
	public Boolean deleteNotificationSubscription(@InputArgument("uuid") UUID uuid) throws RelizaException {
		if (uuid == null) throw new RelizaException("uuid is required");
		Optional<NotificationSubscription> oSub = subscriptionRepo.findById(uuid);
		if (oSub.isEmpty()) return Boolean.FALSE;
		UUID subOrg = extractSubscriptionOrg(oSub.get());
		if (subOrg == null) throw new RelizaException("Subscription " + uuid + " has malformed org binding");
		authorizeOrgAdmin(subOrg);
		subscriptionService.deleteSubscription(uuid);
		return Boolean.TRUE;
	}

	/**
	 * Resolve the org of a subscription row by parsing its
	 * {@code record_data} JSONB. Mirrors {@link #extractChannelOrg}
	 * and is used to authorize updates / deletes against the row's
	 * actual tenant rather than the caller's claim.
	 */
	private static UUID extractSubscriptionOrg(NotificationSubscription sub) {
		if (sub == null || sub.getRecordData() == null) return null;
		try {
			NotificationSubscriptionData d = Utils.OM.convertValue(
					sub.getRecordData(), NotificationSubscriptionData.class);
			return d != null ? d.org() : null;
		} catch (RuntimeException e) {
			log.warn("Failed to parse subscription {} record_data while authorizing: {}",
					sub.getUuid(), e.getMessage());
			return null;
		}
	}

	/**
	 * Map the typed input record onto the JSONB-persisted
	 * {@link NotificationSubscriptionData}. Jackson has already coerced
	 * enums from String → typed; this step just unpacks the
	 * presetConfigJson blob into the typed map shape the data class
	 * expects, and translates the {@code *Input} nested records into
	 * their {@code Config} counterparts.
	 */
	private static NotificationSubscriptionData inputToSubscriptionData(
			NotificationSubscriptionInput input) throws RelizaException {
		FilterConfig filter = toFilterConfig(input.filter());
		List<RouteConfig> routes = toRouteConfigs(input.routes());
		RateLimitConfig rateLimit = toRateLimitConfig(input.rateLimit());
		List<io.reliza.model.NotificationEventType> eventTypes = input.eventTypes() != null
				? input.eventTypes() : List.of();

		return new NotificationSubscriptionData(
				input.org(), input.resourceGroup(), input.name(), input.status(),
				eventTypes, filter, routes, input.dedupWindowMinutes(), rateLimit);
	}

	private static FilterConfig toFilterConfig(NotificationSubscriptionInput.FilterInput in)
			throws RelizaException {
		if (in == null) return null;
		Map<String, Object> presetConfig = null;
		if (StringUtils.isNotBlank(in.presetConfigJson())) {
			try {
				presetConfig = Utils.OM.readValue(in.presetConfigJson(), Map.class);
			} catch (Exception e) {
				throw new RelizaException("filter.presetConfigJson is not valid JSON: "
						+ e.getMessage());
			}
		}
		return new FilterConfig(in.mode(), presetConfig, in.celExpression());
	}

	private static List<RouteConfig> toRouteConfigs(
			List<NotificationSubscriptionInput.RouteInput> ins) {
		if (ins == null || ins.isEmpty()) return List.of();
		return ins.stream()
				.filter(java.util.Objects::nonNull)
				.map(r -> new RouteConfig(
						r.whenSeverityAtLeast(), r.andEnvIn(), r.andLifecycleIn(),
						r.channels() != null ? r.channels() : List.of(),
						// Phase 12: perspectives passes through as-is.
						// Null preserves "no perspective gate" on routes
						// authored without the field; RouteConfig and the
						// fan-out gate treat null/empty identically.
						r.perspectives(),
						// Phase 13b: channelGroups passes through as-is.
						// Null preserves "no group expansion" on routes
						// authored without the field.
						r.channelGroups()))
				.toList();
	}

	private static RateLimitConfig toRateLimitConfig(
			NotificationSubscriptionInput.RateLimitInput in) {
		if (in == null) return null;
		return new RateLimitConfig(in.maxPerWindow(), in.windowMinutes());
	}

	/**
	 * Parse a setStatus mutation's status string into the typed enum.
	 * The GraphQL parser has already rejected schema-level typos; this
	 * is the belt-and-suspenders for non-schema callers (raw HTTP,
	 * programmatic agents) so a bad value lands as a clean
	 * {@link RelizaException} rather than {@code IllegalArgumentException}.
	 */
	private static <E extends Enum<E>> E parseStatusEnum(Class<E> klass, String raw)
			throws RelizaException {
		if (raw == null) throw new RelizaException("status is required");
		try {
			return Enum.valueOf(klass, raw);
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Unknown status: " + raw);
		}
	}

	/**
	 * Map the channel-status GraphQL string ("ENABLED" / "DISABLED") onto
	 * the boolean {@code isEnabled} flag the channel service takes. The
	 * channel "status" enum was folded into Integration's boolean
	 * isEnabled in Phase 2b-1; this keeps the GraphQL surface contract
	 * (ENABLED/DISABLED) intact while validating against non-schema
	 * callers' typos.
	 */
	private static boolean parseChannelEnabled(String raw) throws RelizaException {
		if (raw == null) throw new RelizaException("status is required");
		if (CHANNEL_STATUS_ENABLED.equals(raw)) return true;
		if (CHANNEL_STATUS_DISABLED.equals(raw)) return false;
		throw new RelizaException("Unknown status: " + raw);
	}

	/**
	 * GraphQL channel-status enum value names. The channel "status" enum was
	 * folded into Integration's boolean {@code isEnabled} in Phase 2b-1; these
	 * pin the on-the-wire contract values the boundary maps to/from the bool.
	 */
	private static final String CHANNEL_STATUS_ENABLED = "ENABLED";
	private static final String CHANNEL_STATUS_DISABLED = "DISABLED";

	/**
	 * Deserialize an inbound GraphQL input map into a typed record.
	 * Matches the {@code WebhookData} / {@code ApprovalPolicyDto}
	 * precedent: hand the JSON to Jackson, let it coerce the enums +
	 * UUIDs, and surface any parse failure as a RelizaException so the
	 * caller gets a clean error rather than a Jackson stack trace.
	 */
	private static <T> T convertInput(Map<String, Object> input, Class<T> target, String entityLabel)
			throws RelizaException {
		try {
			return Utils.OM.convertValue(input, target);
		} catch (IllegalArgumentException e) {
			throw new RelizaException("Invalid " + entityLabel + " input: " + e.getMessage());
		}
	}

	// ------------------------------------------------------------------
	// Phase 13b: channel-group CRUD
	// ------------------------------------------------------------------

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationChannelGroup")
	public NotificationChannelGroupResult getNotificationChannelGroup(
			@InputArgument("uuid") UUID uuid) throws RelizaException {
		if (uuid == null) return null;
		Optional<NotificationChannelGroup> oGroup = channelGroupRepo.findById(uuid);
		if (oGroup.isEmpty()) return null;
		NotificationChannelGroup group = oGroup.get();
		UUID groupOrg = extractGroupOrg(group);
		if (groupOrg == null) throw new RelizaException("Channel group " + uuid + " has malformed org binding");
		authorizeOrgAdmin(groupOrg);
		return toGroupResult(group);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationChannelGroups")
	public List<NotificationChannelGroupResult> listNotificationChannelGroups(
			@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		authorizeOrgAdmin(orgUuid);
		List<NotificationChannelGroup> rows = channelGroupService.listByOrg(orgUuid);
		if (rows == null) return Collections.emptyList();
		return rows.stream()
				.map(NotificationDataFetcher::toGroupResult)
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "upsertNotificationChannelGroup")
	public NotificationChannelGroupResult upsertNotificationChannelGroup(
			@InputArgument("input") Map<String, Object> input) throws RelizaException {
		if (input == null) throw new RelizaException("input is required");
		NotificationChannelGroupInput parsed = convertInput(
				input, NotificationChannelGroupInput.class, "channel group");
		if (parsed.org() == null) throw new RelizaException("org is required");
		if (StringUtils.isBlank(parsed.name())) throw new RelizaException("name is required");

		WhoUpdated wu = authorizeOrgAdmin(parsed.org());
		// Cross-tenant probe rejection on update: if the row exists but
		// belongs to a different org OR has unparseable org binding,
		// refuse rather than silently allowing the overwrite. Mirrors
		// the channel + subscription patterns above.
		if (parsed.uuid() != null) {
			Optional<NotificationChannelGroup> existing = channelGroupRepo.findById(parsed.uuid());
			if (existing.isPresent()) {
				UUID existingOrg = extractGroupOrg(existing.get());
				if (existingOrg == null) {
					throw new RelizaException("Channel group " + parsed.uuid()
							+ " has malformed org binding — refusing to update");
				}
				if (!existingOrg.equals(parsed.org())) {
					throw new RelizaException("Channel group " + parsed.uuid()
							+ " does not belong to org " + parsed.org());
				}
			}
		}

		NotificationChannelGroupData seed = new NotificationChannelGroupData(
				parsed.org(), parsed.resourceGroup(), parsed.name(),
				parsed.channels() != null ? parsed.channels() : List.of());
		NotificationChannelGroup saved = channelGroupService.upsertGroup(
				parsed.uuid(), parsed.expectedRevision(), seed, wu);
		log.info("upsertNotificationChannelGroup by user={} org={} group={} channelCount={}",
				wu != null ? wu.getLastUpdatedBy() : null, parsed.org(), saved.getUuid(),
				seed.channels().size());
		return toGroupResult(saved);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteNotificationChannelGroup")
	public Boolean deleteNotificationChannelGroup(@InputArgument("uuid") UUID uuid) throws RelizaException {
		if (uuid == null) throw new RelizaException("uuid is required");
		Optional<NotificationChannelGroup> oGroup = channelGroupRepo.findById(uuid);
		if (oGroup.isEmpty()) return Boolean.FALSE;
		UUID groupOrg = extractGroupOrg(oGroup.get());
		if (groupOrg == null) throw new RelizaException("Channel group " + uuid + " has malformed org binding");
		authorizeOrgAdmin(groupOrg);
		channelGroupService.deleteGroup(uuid);
		return Boolean.TRUE;
	}

	/** Mirrors {@link #extractChannelOrg}; tolerant of malformed JSONB. */
	private static UUID extractGroupOrg(NotificationChannelGroup group) {
		if (group == null || group.getRecordData() == null) return null;
		try {
			NotificationChannelGroupData d = Utils.OM.convertValue(
					group.getRecordData(), NotificationChannelGroupData.class);
			return d != null ? d.org() : null;
		} catch (RuntimeException e) {
			log.warn("Failed to parse channel group {} record_data while authorizing: {}",
					group.getUuid(), e.getMessage());
			return null;
		}
	}

	/**
	 * Project the entity onto the typed GraphQL output record. Returns
	 * null on unparseable record_data — the list-rendering caller drops
	 * nulls so one bad row doesn't poison the listing.
	 */
	private static NotificationChannelGroupResult toGroupResult(NotificationChannelGroup group) {
		try {
			NotificationChannelGroupData d = Utils.OM.convertValue(
					group.getRecordData(), NotificationChannelGroupData.class);
			if (d == null) return null;
			return new NotificationChannelGroupResult(
					group.getUuid(),
					d.org(),
					d.resourceGroup(),
					d.name(),
					d.channels() != null ? d.channels() : Collections.emptyList(),
					group.getRevision(),
					group.getCreatedDate() != null
							? group.getCreatedDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
							: null,
					group.getLastUpdatedDate() != null
							? group.getLastUpdatedDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
							: null);
		} catch (RuntimeException e) {
			log.warn("Failed to render channel group {} for read: {}", group.getUuid(), e.getMessage());
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Phase 14 — Inbox MVP (visibility-filtered listing + read-state).
	// notifications-framework.md §8.1.
	// ------------------------------------------------------------------

	/**
	 * Looser auth than {@link #authorizeOrgAdmin}: accepts any READ
	 * permission on the org. Used by the inbox listing surface so a
	 * resource-group / perspective member can see their own filtered
	 * view without needing org-admin tier.
	 */
	private InboxAuth authorizeOrgMember(UUID orgUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UserData userData = oud.get();
		boolean isOrgAdmin = computeIsOrgAdmin(userData, orgUuid);
		List<UUID> perspectives = computeUserPerspectives(userData, orgUuid);
		List<UUID> componentUuids = computeUserComponentUuids(userData, orgUuid);
		// Inbox visibility gate: a caller may legitimately reach the inbox
		// through ANY of the visibility arms the model below builds on. We
		// deliberately do NOT use the ORGANIZATION-scoped
		// isUserAuthorizedForObjectGraphQL check here: that path FORBIDs a
		// PERSPECTIVE/COMPONENT-scoped permission against the broader
		// ORGANIZATION scope (objectType.ordinal() > permission scope
		// ordinal), so a perspective- or component-only org member would
		// get an opaque "Not authorized" even though their filtered inbox
		// view is well-defined. Gate on the same helpers used to build
		// InboxAuth, plus an org-wide READ arm so an org-READ-only
		// non-admin keeps today's behavior (passes, then sees an
		// empty/targeted-only inbox) rather than newly 403-ing. The
		// mark-read mutations remain protected by assertDeliveryVisible,
		// and the visibility SQL returns zero rows for a true non-member,
		// so this widening can't expose deliveries the caller may not see.
		boolean orgWideRead = authorizationService
				.isUserAuthorizedOrgWide(userData, orgUuid, CallType.READ)
				== AuthorizationStatus.AUTHORIZED;
		if (!isOrgAdmin && perspectives.isEmpty() && componentUuids.isEmpty() && !orgWideRead) {
			throw new RelizaException("Not authorized");
		}
		return new InboxAuth(userData.getUuid(), isOrgAdmin, perspectives, componentUuids,
				WhoUpdated.getWhoUpdated(userData));
	}

	/**
	 * Captured snapshot of the JWT-bound user's inbox-scope context: their
	 * uuid (for mark-read junction), whether they hold org-admin tier on
	 * this org (the "see all org deliveries" gate), the set of perspective
	 * UUIDs they're a member of (the "see deliveries whose payload
	 * perspectives intersect mine" gate), and the set of component UUIDs
	 * they hold a COMPONENT-scoped permission on (the "see deliveries for
	 * my components' releases" component-team gate).
	 */
	private record InboxAuth(UUID userUuid, boolean isOrgAdmin, List<UUID> perspectives,
			List<UUID> componentUuids, WhoUpdated wu) {}

	/**
	 * Org-admin tier = any permission at scope=ORGANIZATION with
	 * type=ADMIN. Mirrors the authorization service's own admin check so
	 * the inbox visibility branch stays in lockstep with the rest of the
	 * auth model.
	 */
	private static boolean computeIsOrgAdmin(UserData userData, UUID orgUuid) {
		if (userData == null || orgUuid == null) return false;
		var perms = userData.getOrgPermissions(orgUuid);
		if (perms == null) return false;
		for (UserPermission p : perms) {
			if (p == null) continue;
			if (p.getScope() == PermissionScope.ORGANIZATION
					&& p.getType() == PermissionType.ADMIN) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The user's perspective membership on this org = the set of
	 * {@code object} UUIDs on permissions where
	 * {@code scope == PERSPECTIVE}. Used to intersect against an event's
	 * {@code affectedReleases[*].perspectives} at SQL time.
	 *
	 * <p>Note: this maps the design doc's "resource-group membership"
	 * concept onto the actual {@code PermissionScope.PERSPECTIVE} the
	 * codebase ships (the doc's "resource_group" doesn't exist as a
	 * scope or payload field — see slice 5 plan §0 in the memory
	 * {@code notifications-slice5-inbox-plan.md} for the reconciliation).
	 */
	private static List<UUID> computeUserPerspectives(UserData userData, UUID orgUuid) {
		if (userData == null || orgUuid == null) return Collections.emptyList();
		var perms = userData.getOrgPermissions(orgUuid);
		if (perms == null) return Collections.emptyList();
		List<UUID> out = new java.util.ArrayList<>();
		for (UserPermission p : perms) {
			if (p == null) continue;
			if (p.getScope() == PermissionScope.PERSPECTIVE && p.getObject() != null) {
				out.add(p.getObject());
			}
		}
		return out;
	}

	/**
	 * The user's component-team membership on this org = the set of
	 * {@code object} UUIDs on permissions where {@code scope == COMPONENT}.
	 * A COMPONENT-scoped permission is exactly what puts a user on a
	 * component's derived team, so this is the membership the inbox
	 * "component-team" arm intersects against an event's
	 * {@code affectedReleases[*].componentUuid} at SQL time. (Product
	 * decision 2026-06-26: being on a component's team surfaces that
	 * component's release notifications in the inbox, independent of
	 * perspective membership.)
	 */
	private static List<UUID> computeUserComponentUuids(UserData userData, UUID orgUuid) {
		if (userData == null || orgUuid == null) return Collections.emptyList();
		var perms = userData.getOrgPermissions(orgUuid);
		if (perms == null) return Collections.emptyList();
		List<UUID> out = new java.util.ArrayList<>();
		for (UserPermission p : perms) {
			if (p == null) continue;
			if (p.getScope() == PermissionScope.COMPONENT && p.getObject() != null) {
				out.add(p.getObject());
			}
		}
		return out;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationInbox")
	public NotificationInboxPage getNotificationInbox(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("unreadOnly") Boolean unreadOnly,
			@InputArgument("status") String status,
			@InputArgument("eventType") String eventType,
			@InputArgument("limit") Integer limit,
			@InputArgument("offset") Integer offset) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		InboxAuth ia = authorizeOrgMember(orgUuid);
		String validatedStatus = normalizeStatusFilter(status);
		String validatedEventType = normalizeEventTypeFilter(eventType);
		boolean effUnreadOnly = unreadOnly == null ? true : unreadOnly;
		int effLimit = clampLimit(limit);
		int effOffset = offset != null && offset > 0 ? offset : 0;
		String perspectivesArray = NotificationReadService.toPgUuidArrayLiteral(ia.perspectives());
		String componentUuidsArray = NotificationReadService.toPgUuidArrayLiteral(ia.componentUuids());
		List<NotificationDelivery> rows = deliveryRepo.findInboxPage(
				orgUuid, ia.userUuid(), ia.isOrgAdmin(),
				perspectivesArray, componentUuidsArray, effUnreadOnly, validatedStatus, validatedEventType,
				effLimit, effOffset);
		long total = deliveryRepo.countInbox(
				orgUuid, ia.userUuid(), ia.isOrgAdmin(),
				perspectivesArray, componentUuidsArray, effUnreadOnly, validatedStatus, validatedEventType);
		// unreadCount carries the always-unread total irrespective of the
		// unreadOnly filter so the badge stays correct when the user
		// flips the filter off. Honors the eventType filter so the badge
		// reflects "unread of THIS event type" when the operator's
		// scoping to one.
		long unread = effUnreadOnly
				? total
				: deliveryRepo.countInbox(
						orgUuid, ia.userUuid(), ia.isOrgAdmin(),
						perspectivesArray, componentUuidsArray, /*unreadOnly*/ true, validatedStatus,
						validatedEventType);
		// Bulk-resolve read-state (uuids + per-row read_at timestamps)
		// for the page slice in one IN-query — avoids N+1 against
		// notification_reads and gives the projector real first-read
		// anchors instead of a sentinel.
		var deliveryUuids = rows.stream().map(NotificationDelivery::getUuid).toList();
		var readAtMap = readService.findReadAtForUser(ia.userUuid(), deliveryUuids);
		// Resolve outbox events for eventType / severity lift via
		// findAllById — single round-trip vs per-uuid loop.
		var outboxUuids = rows.stream()
				.map(NotificationDelivery::getOutboxEventUuid)
				.distinct()
				.toList();
		Map<UUID, NotificationOutboxEvent> outboxByUuid = new java.util.HashMap<>();
		outboxRepo.findAllById(outboxUuids).forEach(e -> outboxByUuid.put(e.getUuid(), e));
		// Bulk-resolve channel display names for the page in one IN-query.
		// A notification channel is an Integration row; its user-facing
		// name lives on IntegrationData.name. Mirroring the outbox
		// resolve avoids N+1 and, crucially, does NOT route through the
		// admin-only channel-list query the non-admin bell would
		// otherwise have to call to label a row. A uuid that no longer
		// resolves (deleted channel) simply yields no map entry, so the
		// item gets a null channelName.
		var channelUuids = rows.stream()
				.map(NotificationDelivery::getChannelUuid)
				.filter(java.util.Objects::nonNull)
				.distinct()
				.toList();
		Map<UUID, ChannelInfo> channelByUuid = new java.util.HashMap<>();
		integrationRepo.findAllById(channelUuids).forEach(i -> {
			IntegrationData cd = IntegrationData.dataFromRecord(i);
			channelByUuid.put(i.getUuid(),
					new ChannelInfo(cd.getName(), cd.getIsEnabled(), cd.getDisabledReason()));
		});
		List<NotificationInboxItem> items = rows.stream()
				.map(d -> toInboxItem(d, outboxByUuid.get(d.getOutboxEventUuid()), readAtMap.get(d.getUuid()),
						d.getChannelUuid() != null ? channelByUuid.get(d.getChannelUuid()) : null))
				.filter(java.util.Objects::nonNull)
				.toList();
		return new NotificationInboxPage(items, total, unread, effLimit, effOffset);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "notificationUnreadCount")
	public int getNotificationUnreadCount(
			@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		InboxAuth ia = authorizeOrgMember(orgUuid);
		long count = readService.countUnread(ia.userUuid(), orgUuid, ia.perspectives(),
				ia.componentUuids(), ia.isOrgAdmin());
		// Schema declares Int; safe cast — an org with 2.1B unread
		// notifications is a different problem than overflow.
		return (int) Math.min(count, Integer.MAX_VALUE);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "markNotificationRead")
	public NotificationInboxItem markNotificationRead(
			@InputArgument("deliveryUuid") UUID deliveryUuid) throws RelizaException {
		if (deliveryUuid == null) throw new RelizaException("deliveryUuid is required");
		Optional<NotificationDelivery> oDelivery = deliveryRepo.findById(deliveryUuid);
		if (oDelivery.isEmpty()) throw new RelizaException("Delivery not found");
		NotificationDelivery delivery = oDelivery.get();
		InboxAuth ia = authorizeOrgMember(delivery.getOrg());
		assertDeliveryVisible(delivery, ia);
		var read = readService.markRead(ia.userUuid(), deliveryUuid, ia.wu());
		NotificationOutboxEvent event = outboxRepo.findById(delivery.getOutboxEventUuid()).orElse(null);
		// Resolve the channel display state without the admin gate, same as
		// the page path. Null when the channel was deleted.
		ChannelInfo channelInfo = delivery.getChannelUuid() != null
				? integrationRepo.findById(delivery.getChannelUuid())
						.map(IntegrationData::dataFromRecord)
						.map(cd -> new ChannelInfo(cd.getName(), cd.getIsEnabled(), cd.getDisabledReason()))
						.orElse(null)
				: null;
		return toInboxItem(delivery, event, read.getReadAt(), channelInfo);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "markNotificationUnread")
	public Boolean markNotificationUnread(
			@InputArgument("deliveryUuid") UUID deliveryUuid) throws RelizaException {
		if (deliveryUuid == null) throw new RelizaException("deliveryUuid is required");
		Optional<NotificationDelivery> oDelivery = deliveryRepo.findById(deliveryUuid);
		if (oDelivery.isEmpty()) throw new RelizaException("Delivery not found");
		NotificationDelivery delivery = oDelivery.get();
		InboxAuth ia = authorizeOrgMember(delivery.getOrg());
		assertDeliveryVisible(delivery, ia);
		return readService.markUnread(ia.userUuid(), deliveryUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "markAllNotificationsRead")
	public MarkAllNotificationsReadResult markAllNotificationsRead(
			@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		InboxAuth ia = authorizeOrgMember(orgUuid);
		// Fetch MARK_ALL_CAP+1 unread+visible deliveries via the inbox
		// query: the (cap+1)-th row, if present, is the cheap signal
		// that "there's more left after this sweep" — we never sweep
		// it, just check the size. This call goes straight to the
		// repo, NOT through clampLimit() — so MARK_ALL_CAP+1 is the
		// literal LIMIT and isn't silently truncated by MAX_PAGE_SIZE.
		// Invariant: MARK_ALL_CAP <= MAX_PAGE_SIZE so an admin who
		// asks the regular inbox query for the same page size sees
		// the same row ceiling.
		assert MARK_ALL_CAP <= MAX_PAGE_SIZE : "MARK_ALL_CAP must not exceed MAX_PAGE_SIZE";
		String perspectivesArray = NotificationReadService.toPgUuidArrayLiteral(ia.perspectives());
		String componentUuidsArray = NotificationReadService.toPgUuidArrayLiteral(ia.componentUuids());
		List<NotificationDelivery> unreadRows = deliveryRepo.findInboxPage(
				orgUuid, ia.userUuid(), ia.isOrgAdmin(),
				perspectivesArray, componentUuidsArray, /*unreadOnly*/ true, /*status*/ null, /*eventType*/ null,
				/*limit*/ MARK_ALL_CAP + 1, /*offset*/ 0);
		boolean hasMore = unreadRows.size() > MARK_ALL_CAP;
		var sweepUuids = unreadRows.stream()
				.limit(MARK_ALL_CAP)
				.map(NotificationDelivery::getUuid)
				.toList();
		int created = readService.markManyRead(ia.userUuid(), sweepUuids, ia.wu());
		return new MarkAllNotificationsReadResult(created, hasMore);
	}

	/** Cap on a single mark-all-read sweep. Larger inboxes need multiple calls. */
	private static final int MARK_ALL_CAP = 500;

	/**
	 * Reject a mark-read on a delivery the caller can't see under the
	 * inbox visibility rule, even if the JWT happens to grant some other
	 * permission on the org. Defends against UUID-fishing for read-state
	 * mutations.
	 */
	private void assertDeliveryVisible(NotificationDelivery delivery, InboxAuth ia) throws RelizaException {
		String perspectivesArray = NotificationReadService.toPgUuidArrayLiteral(ia.perspectives());
		String componentUuidsArray = NotificationReadService.toPgUuidArrayLiteral(ia.componentUuids());
		boolean visible = deliveryRepo.existsDeliveryVisibleToUser(
				delivery.getOrg(), delivery.getUuid(), ia.userUuid(), ia.isOrgAdmin(),
				perspectivesArray, componentUuidsArray);
		if (!visible) {
			// Same generic message whether the row exists (in another
			// tenant) or doesn't — avoids the existence-oracle a "not
			// found" vs "not visible" split would create.
			throw new RelizaException("Delivery not found");
		}
	}

	/**
	 * Resolved display state of the channel addressed by an inbox row. Null
	 * (the whole ref) when the row is channel-less or the channel was deleted;
	 * a present ref carries the name plus the enabled/auto-disable state so the
	 * UI can distinguish a disabled channel from a deleted one.
	 */
	// Package-private (not private) so the same-package unit test can build one
	// when reflecting toInboxItem.
	record ChannelInfo(String name, Boolean enabled, String disabledReason) {}

	/**
	 * Project a delivery row plus its outbox event onto the inbox item
	 * type. Returns null on unparseable record_data — the caller's
	 * filter-non-nulls step drops one bad row from a page without
	 * poisoning the response.
	 *
	 * <p>{@code readAtTs} is the actual first-read timestamp from
	 * {@code notification_reads} (null when unread for this user). The
	 * earlier "use createdDate as a sentinel" compromise was honest in
	 * code but misleading on the wire — a UI showing "you read this 3
	 * days ago" would actually display the delivery's createdDate. The
	 * fetcher now passes the real value via
	 * {@link NotificationReadService#findReadAtForUser}.
	 *
	 * <p>{@code channel} is the server-resolved {@link ChannelInfo} (display
	 * name plus enabled / auto-disable state) for the delivery's channel, or
	 * null when the delivery has no channel or the channel has been deleted.
	 * Carried on the item so the non-admin bell never has to hit the admin-only
	 * channel-list query, and so the UI can distinguish a disabled /
	 * misconfigured channel from a deleted one.
	 */
	private NotificationInboxItem toInboxItem(NotificationDelivery d, NotificationOutboxEvent event,
			java.time.ZonedDateTime readAtTs, ChannelInfo channel) {
		if (d == null) return null;
		String eventType = event != null && event.getEventType() != null
				? event.getEventType().name() : null;
		String severity = extractSeverity(event);
		NotificationInboxFormatter.InboxRendering rendering = inboxFormatter.format(event);
		String payloadJson = serializePayload(event);
		return new NotificationInboxItem(
				d.getUuid(),
				d.getOrg(),
				d.getOutboxEventUuid(),
				d.getSubscriptionUuid(),
				d.getChannelUuid(),
				channel != null ? channel.name() : null,
				channel != null ? channel.enabled() : null,
				channel != null ? channel.disabledReason() : null,
				d.getStatus() != null ? d.getStatus().name() : null,
				d.getOrigin() != null ? d.getOrigin().name() : null,
				d.getDedupKey(),
				d.getAttemptCount(),
				d.getNextAttemptAt() != null
						? d.getNextAttemptAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null,
				d.getSentAt() != null
						? d.getSentAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null,
				d.getLastError(),
				d.getCreatedDate() != null
						? d.getCreatedDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null,
				readAtTs != null
						? readAtTs.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
						: null,
				eventType,
				severity,
				rendering.title(),
				rendering.description(),
				payloadJson);
	}

	/**
	 * Hard ceiling on the serialized inbox payload. A normal vuln/release
	 * payload is a few KB; a runaway (e.g. a vuln affecting thousands of
	 * releases) would bloat every inbox query response since the page
	 * ships one payloadJson per row. Past the cap we return a small
	 * sentinel object instead of the full payload, so the UI can render a
	 * "payload too large" affordance rather than the page silently hauling
	 * megabytes. The sentinel is valid JSON ({@code _truncated:true}) so
	 * the UI's JSON.parse() path stays uniform.
	 */
	private static final int PAYLOAD_JSON_MAX_BYTES = 64 * 1024;

	/**
	 * Serialize the outbox event's structured payload for the UI. JSON
	 * string (not nested object) so the inbox schema doesn't have to
	 * enumerate every event type — UI parses with JSON.parse() when it
	 * wants to surface specific fields (release uuid for deep-link,
	 * vuln id for cross-reference). Null when payload is missing or
	 * unparseable; a truncation sentinel past {@link #PAYLOAD_JSON_MAX_BYTES}.
	 */
	private static String serializePayload(NotificationOutboxEvent event) {
		if (event == null || event.getRecordData() == null) return null;
		try {
			String json = io.reliza.common.Utils.OM.writeValueAsString(event.getRecordData());
			if (json != null
					&& json.getBytes(StandardCharsets.UTF_8).length > PAYLOAD_JSON_MAX_BYTES) {
				log.info("Inbox payload for outbox event {} exceeds {} bytes; returning truncation sentinel",
						event.getUuid(), PAYLOAD_JSON_MAX_BYTES);
				return "{\"_truncated\":true,\"_maxBytes\":" + PAYLOAD_JSON_MAX_BYTES + "}";
			}
			return json;
		} catch (Exception e) {
			log.debug("Inbox payload serialization failed for outbox event {}: {}",
					event.getUuid(), e.getMessage());
			return null;
		}
	}

	/**
	 * Best-effort severity lift from the outbox event payload, validated
	 * against the typed {@link NotificationSeverity} enum. Returns null
	 * for missing-or-unknown values; the schema field is nullable so a
	 * stale payload can't crash the page query at DGS enum-coercion
	 * time. Same belt-and-suspenders pattern this fetcher already uses
	 * for the delivery status filter via {@link #normalizeStatusFilter}.
	 */
	private static String extractSeverity(NotificationOutboxEvent event) {
		if (event == null || event.getRecordData() == null) return null;
		Object raw = event.getRecordData().get("severity");
		if (raw == null) return null;
		try {
			return NotificationSeverity.valueOf(raw.toString()).name();
		} catch (IllegalArgumentException e) {
			log.debug("Outbox event {} has out-of-enum severity '{}'; projecting null",
					event.getUuid(), raw);
			return null;
		}
	}

}
