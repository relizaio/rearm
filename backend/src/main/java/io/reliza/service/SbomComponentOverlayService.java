/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.common.SpdxLicenseExpressions;
import io.reliza.common.VendoredIdentifier;
import io.reliza.model.OverlayKind;
import io.reliza.model.ReleaseData;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentData;
import io.reliza.model.SbomComponentOverlay;
import io.reliza.model.SbomComponentOverlayAudit;
import io.reliza.model.SbomComponentOverlayData;
import io.reliza.model.SbomComponentOverlayPatch;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.model.SupportBulkActKind;
import io.reliza.model.SupportBulkOutcome;
import io.reliza.model.SupportBulkResult;
import io.reliza.model.SupportBulkSelector;
import io.reliza.model.SupportTextBounds;
import io.reliza.repositories.SbomComponentOverlayAuditRepository;
import io.reliza.repositories.SbomComponentOverlayRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.service.SbomComponentService.SupportBulkRun;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * The manual overlays of an organization (FDAW-15 3.3, 3.8): the third-party supplier and licence of an
 * identified component, the description of a keyless vendored node, the supplier sweep, the vendored support
 * save, and the fingerprint readers. Every write runs after the fetcher's gate, re-bounds its inputs as its
 * first statement (the invariant of what is stored), and takes the per-key advisory lock before it reads or
 * creates, so a second writer of a key waits for the first commit and finds the row: no unique-violation path
 * exists and none is caught. A current-state change commits only with its audit row.
 */
@Slf4j
@Service
public class SbomComponentOverlayService {

	static final String NOT_FOUND = "sbom component not found";
	static final String ROOT_REFUSED = "a root component's supplier and licence are declared on the component, not overlaid";
	static final String VENDORED_ONLY = "description and origin are for vendored components; this component is"
			+ " identified by its uploaded SBOM";
	static final String SAY_SOMETHING = "a description must say something: supply an origin, a supplier, a licence"
			+ " or a description";
	static final String DESCRIPTION_NOT_FOUND = "vendored description not found";
	static final String DESCRIBE_FIRST = "describe the component before attesting its support";
	static final String REASON_REQUIRED = "removing a description needs a reason";
	static final String COMPONENT_NOT_IN_ORG = "component not found in this organization";
	private static final String FAILED_ROW = "could not be written; see server logs for this component";

	@Autowired private SbomComponentOverlayRepository overlayRepository;
	@Autowired private SbomComponentOverlayAuditRepository auditRepository;
	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private SupportBulkActService supportBulkActService;

	/** So the sweep reaches {@link #applySupplierIsolated} through the proxy and picks up its REQUIRES_NEW. */
	@Autowired @Lazy private SbomComponentOverlayService self;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * The supplier and licence of an identified component, field by field, or all four on a row already
	 * described as vendored. Answers the row, null when no row exists and the patch changed nothing.
	 */
	@Transactional(rollbackFor = Exception.class)
	public SbomComponentOverlay setComponentOverlay(UUID sbomComponentUuid, SbomComponentOverlayPatch unbounded,
			String unboundedReason, UUID assertedBy) {
		SbomComponentOverlayPatch patch = SupportTextBounds.boundedOverlay(unbounded);
		String reason = boundedReason(unboundedReason);
		SbomComponent sc = sbomComponentRepository.findById(sbomComponentUuid)
				.orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));
		if (SbomComponentData.dataFromRecord(sc).root()) throw new IllegalArgumentException(ROOT_REFUSED);
		UUID org = sc.getOrg();
		String key = sc.getCanonicalPurl();
		lock(org, key);
		SbomComponentOverlay row = overlayRepository.findByOrgAndCanonicalPurl(org, key).orElse(null);
		if ((null != patch.description() || null != patch.origin())
				&& (null == row || OverlayKind.VENDORED != row.getKind())) {
			throw new IllegalArgumentException(VENDORED_ONLY);
		}
		return write(row, org, key, OverlayKind.COMPONENT, null, null, canonicalLicence(patch), reason, assertedBy);
	}

	/**
	 * The sweep (3.3 item 2): resolved by the B2 selector, refused over the cap before any row is loaded, the
	 * act minted here, each row decided and written in its own transaction under the per-key lock, one
	 * receipt. Touches neither {@code sbom_component_support} nor its audit.
	 */
	public SupportBulkRun bulkSetSupplierBySelector(UUID org, SupportBulkSelector selector, UUID releaseUuid,
			Collection<UUID> releaseComponents, String supplier, String reason, UUID assertedBy) {
		return bulkSetSupplierBySelector(org, selector, releaseUuid, releaseComponents, supplier, reason, assertedBy,
				() -> {});
	}

	/** As above; {@code afterResolution} runs between the selector's resolution and the loop, for a test. */
	SupportBulkRun bulkSetSupplierBySelector(UUID org, SupportBulkSelector selector, UUID releaseUuid,
			Collection<UUID> releaseComponents, String unboundedSupplier, String unboundedReason, UUID assertedBy,
			Runnable afterResolution) {
		String supplier = SupportTextBounds.boundedRequiredSupplier(unboundedSupplier);
		String reason = boundedReason(unboundedReason);
		List<UUID> ids = sbomComponentService.resolveSupportSelector(org, selector, releaseComponents);
		if (ids.size() > SbomComponentService.BULK_SUPPORT_MAX_IDS) {
			throw new IllegalArgumentException(SbomComponentService.supplierSelectorOverCap(selector, null != releaseUuid));
		}
		afterResolution.run();
		UUID act = UUID.randomUUID();
		Map<UUID, SbomComponent> rows = new LinkedHashMap<>();
		sbomComponentRepository.findAllById(ids).forEach(sc -> rows.put(sc.getUuid(), sc));
		List<SupportBulkResult> results = new ArrayList<>(ids.size());
		for (UUID uuid : ids) {
			SbomComponent sc = rows.get(uuid);
			if (null == sc || !org.equals(sc.getOrg())) {
				results.add(SupportBulkResult.failed(uuid, COMPONENT_NOT_IN_ORG));
				continue;
			}
			try {
				SupportBulkOutcome outcome = self.applySupplierIsolated(sc.getCanonicalPurl(), org, supplier, act,
						assertedBy, reason);
				results.add(SupportBulkOutcome.APPLIED == outcome ? SupportBulkResult.applied(uuid)
						: SupportBulkResult.skipped(uuid, outcome));
			} catch (IllegalArgumentException e) {
				results.add(SupportBulkResult.failed(uuid, e.getMessage()));
			} catch (RuntimeException e) {
				log.error("Bulk supplier write failed for sbom component {}: {}", uuid, e.getMessage(), e);
				results.add(SupportBulkResult.failed(uuid, FAILED_ROW));
				// The bulk support loop's reason: a failed flush poisons the request's shared context.
				entityManager.clear();
			}
		}
		int applied = (int) results.stream().filter(r -> SupportBulkOutcome.APPLIED == r.outcome()).count();
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("supplier", supplier);
		values.put("reason", reason);
		supportBulkActService.record(new SupportBulkActService.Receipt(act, org, SupportBulkActKind.SUPPLIER, releaseUuid,
				null, selector.receipt(releaseUuid), ids.stream().map(UUID::toString).toList(), applied, assertedBy, values));
		return new SupportBulkRun(act, ids.size(), results);
	}

	/**
	 * One row of a sweep. The skip is decided here, under the lock, so a supplier written since the selector
	 * was resolved is kept; a sweep never overwrites a stored value.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public SupportBulkOutcome applySupplierIsolated(String key, UUID org, String supplier, UUID act, UUID assertedBy,
			String reason) {
		lock(org, key);
		SbomComponentOverlay row = overlayRepository.findByOrgAndCanonicalPurl(org, key).orElse(null);
		if (null != row && null != row.getRecordData().supplier() && !row.getRecordData().supplier().isBlank()) {
			return SupportBulkOutcome.SKIPPED_HAS_SUPPLIER;
		}
		if (null == row) row = newRow(org, key, OverlayKind.COMPONENT, null, null);
		SbomComponentOverlayData d = row.getRecordData();
		store(row, new SbomComponentOverlayData(supplier, d.licence(), d.description(), d.origin(), d.name(), d.version(),
				assertedBy, now(), act), reason, assertedBy, act);
		return SupportBulkOutcome.APPLIED;
	}

	/**
	 * Describes a keyless node (3.3 item 3): the key is minted from the name and version, the row is created
	 * with kind {@code VENDORED} or patched. No {@code sbom_components} row is born here, and whether a document
	 * holds the node is not verified: a description of a node no document carries is inert.
	 */
	@Transactional(rollbackFor = Exception.class)
	public SbomComponentOverlay describeVendored(ReleaseData rd, String unboundedName, String unboundedVersion,
			SbomComponentOverlayPatch unbounded, String unboundedReason, UUID assertedBy) {
		String name = SupportTextBounds.boundedRequiredName(unboundedName);
		String version = SupportTextBounds.boundedNodeVersion(unboundedVersion);
		SbomComponentOverlayPatch patch = SupportTextBounds.boundedOverlay(unbounded);
		String reason = boundedReason(unboundedReason);
		UUID org = rd.getOrg();
		String key = VendoredIdentifier.mint(org, name, version);
		lock(org, key);
		SbomComponentOverlay row = overlayRepository.findByOrgAndCanonicalPurl(org, key).orElse(null);
		if (!saysSomethingAfter(row, patch)) throw new IllegalArgumentException(SAY_SOMETHING);
		if (null != row && OverlayKind.COMPONENT == row.getKind()) row = promoted(row, name, version, reason, assertedBy);
		return write(row, org, key, OverlayKind.VENDORED, name, version, canonicalLicence(patch), reason, assertedBy);
	}

	/** Whether the row, with the patch applied, still holds one non-blank field: an empty description asserts nothing. */
	private static boolean saysSomethingAfter(SbomComponentOverlay row, SbomComponentOverlayPatch patch) {
		SbomComponentOverlayData d = null == row ? SbomComponentOverlayData.empty() : row.getRecordData();
		return Stream.of(patched(d.supplier(), patch.supplier()), patched(d.licence(), patch.licence()),
				patched(d.description(), patch.description()), patched(d.origin(), patch.origin()))
				.anyMatch(v -> null != v && !v.isBlank());
	}

	/**
	 * A {@code COMPONENT} row on a minted key becomes the description: a born row outlives a removal, and a supplier
	 * written on it in between would otherwise leave a key no verb can describe, attest or remove again.
	 */
	private SbomComponentOverlay promoted(SbomComponentOverlay row, String name, String version, String reason,
			UUID assertedBy) {
		SbomComponentOverlayData d = row.getRecordData();
		row.setKind(OverlayKind.VENDORED);
		return store(row, new SbomComponentOverlayData(d.supplier(), d.licence(), d.description(), d.origin(), name, version,
				assertedBy, now(), d.supplierBulkAct()), reason, assertedBy, null);
	}

	/**
	 * Removes a description (3.3 item 4): the audit row goes first, with the last after-image and the next
	 * revision, and the next create of the key continues from it. A born component row and its support row stay.
	 */
	@Transactional(rollbackFor = Exception.class)
	public void removeVendoredDescription(ReleaseData rd, String unboundedName, String unboundedVersion,
			String unboundedReason, UUID assertedBy) {
		String name = SupportTextBounds.boundedRequiredName(unboundedName);
		String version = SupportTextBounds.boundedNodeVersion(unboundedVersion);
		String reason = requiredRemovalReason(unboundedReason);
		UUID org = rd.getOrg();
		String key = VendoredIdentifier.mint(org, name, version);
		lock(org, key);
		SbomComponentOverlay row = overlayRepository.findByOrgAndCanonicalPurl(org, key)
				.filter(o -> OverlayKind.VENDORED == o.getKind())
				.orElseThrow(() -> new IllegalArgumentException(DESCRIPTION_NOT_FOUND));
		audit(row, row.getRevision() + 1, row.getRecordData(), reason, assertedBy, null, true);
		overlayRepository.delete(row);
	}

	/** The reason of a removal: bounded, and required. */
	public static String requiredRemovalReason(String reason) {
		String bounded = boundedReason(reason);
		if (null == bounded) throw new IllegalArgumentException(REASON_REQUIRED);
		return bounded;
	}

	/**
	 * The first attestation of a described vendored component and every later one (3.3 item 5): the
	 * {@code sbom_components} row is born here, with its support row, in this one transaction, so a refused
	 * request rolls the row back. Not the retrying write: the retry discards the persistence context, which an
	 * enclosing transaction cannot afford. Answers the row's uuid.
	 */
	@Transactional(rollbackFor = Exception.class)
	public UUID saveVendoredSupport(ReleaseData rd, String unboundedName, String unboundedVersion,
			SupportAttestationRequest request, UUID assertedBy) {
		String name = SupportTextBounds.boundedRequiredName(unboundedName);
		String version = SupportTextBounds.boundedNodeVersion(unboundedVersion);
		UUID org = rd.getOrg();
		String key = VendoredIdentifier.mint(org, name, version);
		lock(org, key);
		overlayRepository.findByOrgAndCanonicalPurl(org, key).filter(o -> OverlayKind.VENDORED == o.getKind())
				.orElseThrow(() -> new IllegalArgumentException(DESCRIBE_FIRST));
		UUID uuid = sbomComponentService.ensureVendoredComponent(org, key, name, version);
		sbomComponentService.setSbomComponentSupport(uuid, request, assertedBy);
		return uuid;
	}

	/** The overlays of {@code keys}, by key. */
	public Map<String, SbomComponentOverlay> overlaysFor(UUID org, Collection<String> keys) {
		if (keys.isEmpty()) return Map.of();
		return overlayRepository.findByOrgAndCanonicalPurlIn(org, keys).stream()
				.collect(Collectors.toMap(SbomComponentOverlay::getCanonicalPurl, o -> o, (a, b) -> a));
	}

	public Optional<SbomComponentOverlay> vendoredOverlay(UUID org, String key) {
		return overlayRepository.findByOrgAndCanonicalPurl(org, key).filter(o -> OverlayKind.VENDORED == o.getKind());
	}

	/** The organization's vendored descriptions of a stored name; the caller gates ORGANIZATION WRITE. */
	public List<SbomComponentOverlay> vendoredDescriptionsByName(UUID org, String name) {
		return overlayRepository.findVendoredByOrgAndName(org, name);
	}

	/**
	 * {@code overlay:<key>=<revision>} per recorded key, named by the key, with the revision of the overlay of
	 * the chosen row (3.8, F-1), 0 when no row exists. {@code facts} is the map the caller already computed.
	 */
	public List<String> fingerprintLines(UUID org, Collection<String> keys, Map<String, ComponentSupportFacts> facts) {
		Function<String, String> rowKeyOf = OverlayBomInjector.rowKeyOf(facts);
		Map<String, SbomComponentOverlay> rows = overlaysFor(org, keys.stream().map(rowKeyOf).collect(Collectors.toSet()));
		return keys.stream().map(key -> {
			SbomComponentOverlay row = rows.get(rowKeyOf.apply(key));
			return "overlay:" + key + "=" + (null == row ? 0 : row.getRevision());
		}).toList();
	}

	/** The minted keys of the document's still-keyless describable nodes, so a later description is caught. */
	public List<String> vendoredKeysOf(UUID org, JsonNode pinnedDocument) {
		Set<String> keys = new LinkedHashSet<>();
		OverlayBomInjector.overlayNodes(pinnedDocument).forEach(node -> VendoredIdentifier.ofNode(org, node)
				.map(VendoredIdentifier.Vendored::key).filter(Objects::nonNull).ifPresent(keys::add));
		return List.copyOf(keys);
	}

	/** The overlay audit rows of the keys' chosen rows after {@code builtAt}; removals carry a row too. */
	public long countAuditSince(UUID org, Collection<String> keys, Map<String, ComponentSupportFacts> facts,
			ZonedDateTime builtAt) {
		if (keys.isEmpty()) return 0;
		Set<String> rowKeys = keys.stream().map(OverlayBomInjector.rowKeyOf(facts)).collect(Collectors.toSet());
		return auditRepository.countByOrgAndCanonicalPurlInAndAssertedDateAfter(org, rowKeys, builtAt);
	}

	private void lock(UUID org, String key) {
		overlayRepository.lockKey(AdvisoryLockKey.SBOM_COMPONENT_KEY_WRITE.getQueryVal(), org + "|" + key);
	}

	private static String boundedReason(String reason) {
		return SupportTextBounds.bounded(reason, "reason", SupportTextBounds.MAX_REASON_LENGTH);
	}

	/** The patch with a non-blank licence in canonical form, or refused. */
	private static SbomComponentOverlayPatch canonicalLicence(SbomComponentOverlayPatch p) {
		if (null == p.licence() || p.licence().isEmpty()) return p;
		try {
			return new SbomComponentOverlayPatch(p.supplier(), SpdxLicenseExpressions.canonical(p.licence()),
					p.description(), p.origin());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("licence invalid: " + e.getMessage());
		}
	}

	/** Null leaves the stored value, an empty string clears it, a value sets it. */
	private static String patched(String stored, String patch) {
		if (null == patch) return stored;
		return patch.isEmpty() ? null : patch;
	}

	/** A patch that changes no stored field writes nothing: no row, no revision, no audit. */
	private SbomComponentOverlay write(SbomComponentOverlay existing, UUID org, String key, OverlayKind kindOfNewRow,
			String name, String version, SbomComponentOverlayPatch patch, String reason, UUID assertedBy) {
		SbomComponentOverlayData d = null == existing ? SbomComponentOverlayData.empty() : existing.getRecordData();
		String supplier = patched(d.supplier(), patch.supplier());
		String licence = patched(d.licence(), patch.licence());
		String description = patched(d.description(), patch.description());
		String origin = patched(d.origin(), patch.origin());
		boolean supplierChanged = !Objects.equals(supplier, d.supplier());
		if (!supplierChanged && Objects.equals(licence, d.licence()) && Objects.equals(description, d.description())
				&& Objects.equals(origin, d.origin())) {
			return existing;
		}
		SbomComponentOverlay row = null == existing ? newRow(org, key, kindOfNewRow, name, version) : existing;
		SbomComponentOverlayData stored = row.getRecordData();
		return store(row, new SbomComponentOverlayData(supplier, licence, description, origin, stored.name(), stored.version(),
				assertedBy, now(), supplierChanged ? null : d.supplierBulkAct()), reason, assertedBy, null);
	}

	/** A created row starts at the audit's largest revision for the key, so a removed key never repeats one (F-3). */
	private SbomComponentOverlay newRow(UUID org, String key, OverlayKind kind, String name, String version) {
		SbomComponentOverlay row = new SbomComponentOverlay();
		row.setOrg(org);
		row.setCanonicalPurl(key);
		row.setKind(kind);
		row.setRevision(auditRepository.maxRevision(org, key));
		row.setRecordData(new SbomComponentOverlayData(null, null, null, null, name, version, null, null, null));
		return row;
	}

	private SbomComponentOverlay store(SbomComponentOverlay row, SbomComponentOverlayData data, String reason,
			UUID assertedBy, UUID bulkAct) {
		row.setRevision(row.getRevision() + 1);
		row.setRecordData(data);
		row.setLastUpdatedDate(ZonedDateTime.now());
		SbomComponentOverlay saved = overlayRepository.save(row);
		audit(saved, saved.getRevision(), data, reason, assertedBy, bulkAct, false);
		return saved;
	}

	private void audit(SbomComponentOverlay row, int revision, SbomComponentOverlayData data, String reason,
			UUID assertedBy, UUID bulkAct, boolean removed) {
		SbomComponentOverlayAudit audit = new SbomComponentOverlayAudit();
		audit.setOverlay(row.getUuid());
		audit.setOrg(row.getOrg());
		audit.setCanonicalPurl(row.getCanonicalPurl());
		audit.setKind(row.getKind());
		audit.setRevision(revision);
		audit.setRecordData(data);
		audit.setAssertedBy(assertedBy);
		audit.setReason(reason);
		audit.setBulkAct(bulkAct);
		audit.setRemoved(removed);
		auditRepository.save(audit);
	}

	private static String now() {
		return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
	}
}
