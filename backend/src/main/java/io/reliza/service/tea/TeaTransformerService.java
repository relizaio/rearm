/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.VersRanges;
import io.reliza.common.VersRanges.ReleaseOrder;
import io.reliza.common.VersRanges.ReleaseScheme;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.Link;
import io.reliza.model.ParentRelease;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.VariantData;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaChecksum;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaCleEvent;
import io.reliza.model.tea.TeaCleEventType;
import io.reliza.model.tea.TeaCleVersionSpecifier;
import io.reliza.model.tea.TeaComponent;
import io.reliza.model.tea.TeaComponentRef;
import io.reliza.model.tea.TeaComponentRelease;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.TeaProduct;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.model.tea.TeaReleaseDistribution;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.TeaPublicationService;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.VariantService;
import io.reliza.ws.tea.TeaJson;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders TEA 1.0.0 objects (task TEA-6): products and components from live naming, product and
 * component releases from a publication head plus live naming, distributions from outbound
 * deliverables, and CLE documents. Collections are never rendered here: they are served as TEA-5
 * stored them.
 */
@Slf4j
@Service
public class TeaTransformerService {

	/** The namespace of the name-based distribution ids: stable, and never an internal uuid on the wire. */
	static final String DISTRIBUTION_ID_NAMESPACE = "rearm-tea-distribution:";
	private static final Pattern HEX_DIGEST = Pattern.compile("^[0-9a-f]{40,128}$");

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private VariantService variantService;

	@Autowired
	private GetDeliverableService getDeliverableService;

	@Autowired
	private TeaPublicationService teaPublicationService;

	/**
	 * TEA-boundary filter: keep only identifiers whose type name exists in the
	 * code-generated {@link TeaIdentifierType} (CPE/TEI/PURL/COMPLIANCE_DOCUMENT);
	 * ReARM-internal types (UDI, UDI_DI, UDI_PI, SERIAL, LOT) never leave via TEA.
	 */
	public static List<TeaIdentifier> toTeaIdentifiers(List<RearmIdentifier> identifiers) {
		List<TeaIdentifier> out = new LinkedList<>();
		if (identifiers == null) return out;
		for (RearmIdentifier ri : identifiers) {
			if (ri == null || ri.getIdType() == null || ri.getIdValue() == null) continue;
			TeaIdentifierType teaType;
			try {
				teaType = TeaIdentifierType.fromValue(ri.getIdType().name());
			} catch (IllegalArgumentException e) {
				continue;
			}
			TeaIdentifier ti = new TeaIdentifier();
			ti.setIdType(teaType);
			ti.setIdValue(ri.getIdValue());
			out.add(ti);
		}
		return out;
	}

	/** {@link #toTeaIdentifiers} without COMPLIANCE_DOCUMENT, which is valid on components and component releases only. */
	static List<TeaIdentifier> withoutComplianceDocuments(List<TeaIdentifier> ids) {
		return new ArrayList<>(ids.stream().filter(i -> TeaIdentifierType.COMPLIANCE_DOCUMENT != i.getIdType()).toList());
	}

	public TeaProduct transformProductToTea(ComponentData cd) {
		if (cd.getType() != ComponentType.PRODUCT) {
			throw new IllegalArgumentException("Wrong component type");
		}
		TeaProduct tp = new TeaProduct();
		tp.setUuid(cd.getTeaUuid());
		tp.setName(cd.getName());
		tp.setIdentifiers(withoutComplianceDocuments(toTeaIdentifiers(cd.getIdentifiers())));
		return tp;
	}

	public TeaComponent transformComponentToTea(ComponentData cd) {
		if (cd.getType() != ComponentType.COMPONENT) {
			throw new IllegalArgumentException("Wrong component type");
		}
		TeaComponent tc = new TeaComponent();
		tc.setUuid(cd.getTeaUuid());
		tc.setName(cd.getName());
		tc.setIdentifiers(new ArrayList<>(toTeaIdentifiers(cd.getIdentifiers())));
		return tc;
	}

	/** A UTC instant truncated to seconds. */
	static OffsetDateTime utcSeconds(ZonedDateTime z) {
		return null == z ? null : z.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
	}

	/** When the release was created in TEA: its first publish; the release's creation for a head that lacks one. */
	static OffsetDateTime createdInTea(TeaPublicationData head, ReleaseData rd) {
		return utcSeconds(null != head.getFirstPublishedDate() ? head.getFirstPublishedDate() : rd.getCreatedDate());
	}

	public TeaProductRelease transformProductReleaseToTea(TeaPublicationData head, ReleaseData rd, ComponentData cd,
			List<TeaComponentRef> components) {
		TeaProductRelease tpr = new TeaProductRelease();
		tpr.setUuid(head.getUuid());
		tpr.setProduct(cd.getTeaUuid());
		tpr.setProductName(cd.getName());
		tpr.setVersion(rd.getVersion());
		tpr.setCreatedDate(createdInTea(head, rd));
		tpr.setReleaseDate(utcSeconds(rd.getCreatedDate()));
		tpr.setPreRelease(null);
		tpr.setIdentifiers(releaseIdentifiers(head, rd, false));
		tpr.setComponents(null == components ? new ArrayList<>() : new ArrayList<>(components));
		return tpr;
	}

	public TeaComponentRelease transformComponentReleaseToTea(TeaPublicationData head, ReleaseData rd, ComponentData cd) {
		TeaComponentRelease tcr = new TeaComponentRelease();
		tcr.setUuid(head.getUuid());
		tcr.setComponent(cd.getTeaUuid());
		tcr.setComponentName(cd.getName());
		tcr.setVersion(rd.getVersion());
		tcr.setCreatedDate(createdInTea(head, rd));
		tcr.setReleaseDate(utcSeconds(rd.getCreatedDate()));
		tcr.setPreRelease(null);
		tcr.setIdentifiers(releaseIdentifiers(head, rd, true));
		tcr.setDistributions(distributionsOf(rd));
		return tcr;
	}

	/**
	 * The stamped TEI first (TEA-5), then the release's TEA identifiers: COMPLIANCE_DOCUMENT only
	 * where allowed, and never a second copy of the stamped TEI.
	 */
	public List<TeaIdentifier> releaseIdentifiers(TeaPublicationData head, ReleaseData rd, boolean complianceDocumentsAllowed) {
		List<TeaIdentifier> out = new ArrayList<>();
		String stamped = null == head ? null : head.getTei();
		if (StringUtils.isNotEmpty(stamped)) out.add(new TeaIdentifier(TeaIdentifierType.TEI, stamped));
		List<TeaIdentifier> own = toTeaIdentifiers(rd.getIdentifiers());
		if (!complianceDocumentsAllowed) own = withoutComplianceDocuments(own);
		for (TeaIdentifier i : own) {
			if (TeaIdentifierType.TEI == i.getIdType() && null != stamped && stamped.equals(i.getIdValue())) continue;
			out.add(i);
		}
		return out;
	}

	/**
	 * The components of a product release: one ref per parent release of the product's own
	 * organization whose component has a TEA id, pinned to the child's release only when that
	 * child's head is PUBLISHED and {@code served} answers true for it. A product never points a
	 * reader at a release the reader is refused.
	 */
	public List<TeaComponentRef> componentRefs(ReleaseData rd, ComponentData product, Predicate<TeaPublicationData> served) {
		List<TeaComponentRef> out = new ArrayList<>();
		if (null == rd.getParentReleases()) return out;
		for (ParentRelease pr : rd.getParentReleases()) {
			if (null == pr || null == pr.getRelease()) continue;
			Optional<ReleaseData> child = sharedReleaseService.getReleaseData(pr.getRelease());
			if (child.isEmpty()) continue;
			Optional<ComponentData> childCd = getComponentService.getComponentData(child.get().getComponent());
			if (childCd.isEmpty() || !product.getOrg().equals(childCd.get().getOrg())) continue;
			if (null == childCd.get().getTeaUuid()) {
				log.debug("TEA product release {}: child release {} has no TEA component id yet; skipped", rd.getUuid(),
						child.get().getUuid());
				continue;
			}
			TeaComponentRef ref = new TeaComponentRef(childCd.get().getTeaUuid());
			teaPublicationService.getPublicationOfRelease(child.get().getUuid())
					.filter(h -> TeaPublicationState.PUBLISHED == h.getState() && served.test(h))
					.ifPresent(h -> ref.setRelease(h.getUuid()));
			out.add(ref);
		}
		return out;
	}

	/** The distributions of a release, from its base variant's outbound deliverables; null when there are none. */
	public List<TeaReleaseDistribution> distributionsOf(ReleaseData rd) {
		VariantData vd = variantService.getBaseVariantForRelease(rd);
		if (null == vd || null == vd.getOutboundDeliverables() || vd.getOutboundDeliverables().isEmpty()) return null;
		List<TeaReleaseDistribution> out = new ArrayList<>();
		for (UUID del : vd.getOutboundDeliverables()) {
			getDeliverableService.getDeliverableData(del).ifPresent(dd -> out.add(distributionOf(dd)));
		}
		return out.isEmpty() ? null : out;
	}

	static UUID distributionIdOf(UUID deliverable) {
		return UUID.nameUUIDFromBytes((DISTRIBUTION_ID_NAMESPACE + deliverable).getBytes(StandardCharsets.UTF_8));
	}

	private TeaReleaseDistribution distributionOf(DeliverableData dd) {
		TeaReleaseDistribution trd = new TeaReleaseDistribution(distributionIdOf(dd.getUuid()));
		trd.setDescription(StringUtils.isEmpty(dd.getNotes()) ? null : dd.getNotes());
		List<TeaIdentifier> ids = toTeaIdentifiers(dd.getIdentifiers());
		trd.setIdentifiers(ids.isEmpty() ? null : new ArrayList<>(ids));
		trd.setChecksums(null);
		trd.setSignatureUrl(null);
		if (null != dd.getSoftwareMetadata()) {
			List<TeaChecksum> checksums = new ArrayList<>();
			for (TeaChecksum c : transformDigestRecordToTeaChecksum(dd.getSoftwareMetadata().getDigestRecords())) {
				String v = null == c.getAlgValue() ? "" : c.getAlgValue().toLowerCase(Locale.ROOT);
				if (!HEX_DIGEST.matcher(v).matches()) continue;
				checksums.add(new TeaChecksum(c.getAlgType(), v));
			}
			trd.setChecksums(checksums.isEmpty() ? null : checksums);
			List<Link> links = dd.getSoftwareMetadata().getDownloadLinks();
			if (null != links && !links.isEmpty() && null != links.get(0)) trd.setUrl(admittedUri(links.get(0).getUri()));
		}
		return trd;
	}

	/**
	 * The link as a URI when TeaExternalUrl admits it (an absolute http(s) RFC 3986 URI, no user information, at
	 * most 4096 octets), else null; no trim (task TEA-19). Nothing is logged: this is a served read.
	 */
	static URI admittedUri(String link) {
		return null == link || TeaExternalUrl.problemOf(link).isPresent() ? null : URI.create(link);
	}

	/** The TEA artifact type of a ReARM artifact type; public for the publication engine (task TEA-5). */
	public static TeaArtifactType transformArtifactTypeToTea (ArtifactType rearmAT) {
		TeaArtifactType tat = null;
		
		switch (rearmAT) {
			case BOM:
				tat = TeaArtifactType.BOM;
				break;
			case ATTESTATION:
				tat = TeaArtifactType.ATTESTATION;
				break;
			case VDR:
			case VEX:
			case BOV:
				tat = TeaArtifactType.VULNERABILITIES;
				break;
			case USER_DOCUMENT:
			case DEVELOPMENT_DOCUMENT:
			case PROJECT_DOCUMENT:
			case MARKETING_DOCUMENT:
			case TEST_REPORT:
			case SARIF:
			case SIGNED_PAYLOAD:
			case OTHER:
			case SIGNATURE:
			case PUBLIC_KEY:
			case CERTIFICATE_X_509:
			case CERTIFICATE_PGP:
			case RISK_ASSESSMENT:
			case CODE_SCANNING_RESULT:
				tat = TeaArtifactType.OTHER;
				break;
			case BUILD_META:
				tat = TeaArtifactType.BUILD_META;
				break;
			case CERTIFICATION:
				tat = TeaArtifactType.CERTIFICATION;
				break;
			case FORMULATION:
				tat = TeaArtifactType.FORMULATION;
				break;
			case LICENSE:
				tat = TeaArtifactType.LICENSE;
				break;
			case RELEASE_NOTES:
				tat = TeaArtifactType.RELEASE_NOTES;
				break;
			case SECURITY_TXT:
				tat = TeaArtifactType.SECURITY_TXT;
				break;
			case THREAT_MODEL:
				tat = TeaArtifactType.THREAT_MODEL;
				break;
		}
		
		return tat;
	}
	
	/** The media type TEA announces for an artifact's stored media type tag; public for the publication engine (task TEA-5). */
	public static String resolveMediaType(BomFormat bomFormat, String initialType) {
		String resolvedType;
		if ("application/json".equals(initialType) && bomFormat == BomFormat.CYCLONEDX) {
			resolvedType = "vnd.cyclonedx+json";
		} else if ("application/xml".equals(initialType) && bomFormat == BomFormat.CYCLONEDX){
			resolvedType = "vnd.cyclonedx+xml";
		} else {
			resolvedType = initialType;
		}
		return resolvedType;
	}

	/**
	 * The checksums TEA publishes for one artifact format.
	 *
	 * <p>AS_UPLOADED when the artifact has one, ORIGINAL_FILE otherwise. AS_UPLOADED is a digest
	 * we took over the bytes the publisher sent us; ORIGINAL_FILE is a digest someone told us
	 * about, or -- on a rebom-stored BOM -- one derived from rebom's own copy of the document,
	 * which is not the uploaded file. Where both exist the second is the weaker claim, and a TEA
	 * format entry carries no way to say which is which: emitting both would hand a verifier two
	 * sha256 values for one file and no way to choose. So the stronger claim wins outright.
	 *
	 * <p>Artifacts uploaded before retention, externally-stored artifacts and deliverables have
	 * no AS_UPLOADED record and are published exactly as they were. A digest TEA 1.0.0 cannot
	 * express (MD5) is dropped.
	 */
	private List<TeaChecksum> transformDigestRecordToTeaChecksum (Collection<DigestRecord> digestRecords) {
		List<TeaChecksum> tcList = new LinkedList<>();
		if (null != digestRecords && !digestRecords.isEmpty()) {
			boolean hasAsUploaded = digestRecords.stream().anyMatch(d -> d.scope() == DigestScope.AS_UPLOADED);
			DigestScope publishScope = hasAsUploaded ? DigestScope.AS_UPLOADED : DigestScope.ORIGINAL_FILE;
			digestRecords.stream().filter(d -> d.scope() == publishScope && null != d.algo()).forEach(d ->
				d.algo().toTea().ifPresent(t -> {
					TeaChecksum tc = new TeaChecksum();
					tc.setAlgType(t);
					tc.setAlgValue(d.digest());
					tcList.add(tc);
				}));
		}
		return tcList;
	}

	/**
	 * Single-release CLE: events derived from this release's own LIFECYCLE
	 * updateEvents, plus a synthetic {@code released} when the release was
	 * created directly at GA (no <GA→GA transition exists in history). Used
	 * by the {@code /componentRelease/{uuid}/cle} endpoint and as the
	 * building block for the component-level aggregate.
	 */
	public TeaCle transformReleaseToCle(ReleaseData rd) {
		String versionSchema = null == rd.getComponent() ? null
				: getComponentService.getComponentData(rd.getComponent()).map(ComponentData::getVersionSchema).orElse(null);
		List<TeaCleEvent> events = new LinkedList<>(buildReleaseCleCandidates(rd,
				ReleaseScheme.ofVersionSchema(versionSchema)));
		// Single release scope — no minute-merging across releases. Just
		// renumber + sort newest-first by effective.
		renumberAndSortNewestFirst(events);
		return withoutEmptyOptionalLists(new TeaCle(events));
	}

	/**
	 * Component-level CLE: chronological union of every release's LIFECYCLE
	 * events (one {@code released} per crossing &lt; GA → ≥ GA, plus the
	 * post-GA transitions) merged by (eventType, minute). Component-level
	 * NAME changes from {@code component.updateEvents} surface as
	 * {@code componentRenamed} events. {@code released} stays one event per
	 * release per spec (single {@code version} field, not a range).
	 */
	public TeaCle transformComponentToCle(ComponentData cd) {
		return transformComponentOrProductToCle(cd);
	}

	public TeaCle transformProductToCle(ComponentData cd) {
		return transformComponentOrProductToCle(cd);
	}

	/**
	 * The internal export's derivation: every release of the component (the GraphQL
	 * {@code exportComponentCleManual}). The TEA endpoints use the overload over the releases
	 * served to the caller instead.
	 */
	private TeaCle transformComponentOrProductToCle(ComponentData cd) {
		// Was 300, which silently dropped the OLDEST releases -- exactly the ones
		// carrying end-of-support/end-of-life history -- while presenting the result as
		// a complete document.
		// This is a BOUND, not unboundedness, and the distinction is deliberate.
		// listReleaseDatasOfComponent documents limit < 1 as an "ALL" sentinel, but that
		// sentinel is UNUSABLE through this path: FIND_ALL_RELEASES_OF_COMPONENT renders
		// it as LIMIT cast('ALL' as bigint), which Postgres rejects outright
		// (invalid input syntax for type bigint). Every other caller passes a positive
		// limit, so nothing had reached that branch before. Passing 0 here would throw at
		// runtime while the mocked unit tests stayed green.
		// So: 100000, matching OssReleaseService:1601. It does not enforce the CLE spec's
		// 100,000-EVENT limit -- the cap is on RELEASES and each yields 1..N events -- it
		// simply sits far enough above real data to be inert. Pagination (index + next)
		// is the actual fix and is tracked on board task t20260831-115702-13297; see also
		// the quadratic versions[] dedupe in mergeNonReleasedByMinute, which this cap was
		// previously masking.
		List<ReleaseData> releases = sharedReleaseService.listReleaseDatasOfComponent(cd.getUuid(), 100000, 0);
		return transformComponentOrProductToCle(cd, releases);
	}

	/**
	 * Component- or product-level CLE over the given releases only (task TEA-6): the TEA
	 * {@code /product/{uuid}/cle} and {@code /component/{uuid}/cle} pass the releases of the heads
	 * served to the caller, so a release the caller may not see contributes no event.
	 */
	public TeaCle transformComponentOrProductToCle(ComponentData cd, List<ReleaseData> releases) {
		List<TeaCleEvent> all = new ArrayList<>();
		ReleaseScheme scheme = ReleaseScheme.ofVersionSchema(cd.getVersionSchema());
		for (ReleaseData rd : releases) {
			List<TeaCleEvent> candidates = buildReleaseCleCandidates(rd, scheme);
			all.addAll(candidates);
		}
		all.addAll(buildComponentRenameCandidates(cd));
		List<TeaCleEvent> merged = mergeNonReleasedByMinute(all);
		rangeContiguousVersions(merged, VersRanges.releaseOrder(scheme,
				releases.stream().map(ReleaseData::getVersion).toList()));
		renumberAndSortNewestFirst(merged);
		return withoutEmptyOptionalLists(new TeaCle(merged));
	}

	/**
	 * Convert a release's LIFECYCLE history into TEA CLE event candidates.
	 * <ul>
	 *   <li>Crossing {@code <GA → ≥GA} ⇒ a {@code released} event whose
	 *       {@code version} is the release version.</li>
	 *   <li>Any other lifecycle change ⇒ the matching post-release CLE
	 *       event type (END_OF_*, etc.) carrying a single-version
	 *       {@code versions} entry.</li>
	 *   <li>Release was minted directly at ≥ GA via CLI (no LIFECYCLE
	 *       events in history) ⇒ synthesize a {@code released} event at
	 *       {@code release.createdDate}.</li>
	 *   <li>No LIFECYCLE transition produced an endOfSupport/endOfLife event,
	 *       but the release declares {@code eos}/{@code eol} -- synthesize
	 *       one from the date, so a declared-but-not-yet-workflow-transitioned
	 *       window still gets disclosed (see the DATE-DERIVED FALLBACK note
	 *       below).</li>
	 * </ul>
	 * Each candidate is stamped with id=-1; callers renumber after merging.
	 */
	private List<TeaCleEvent> buildReleaseCleCandidates(ReleaseData rd, ReleaseScheme scheme) {
		List<TeaCleEvent> out = new LinkedList<>();
		boolean sawAnyLifecycleEvent = false;
		boolean sawReleasedEvent = false;
		boolean sawEndOfSupportEvent = false;
		boolean sawEndOfLifeEvent = false;
		if (rd.getUpdateEvents() != null) {
			for (var ue : rd.getUpdateEvents()) {
				if (ue.rus() != ReleaseUpdateScope.LIFECYCLE) continue;
				sawAnyLifecycleEvent = true;
				ReleaseLifecycle oldLc = parseLifecycle(ue.oldValue());
				ReleaseLifecycle newLc = parseLifecycle(ue.newValue());
				if (newLc == null) continue;
				OffsetDateTime ts = ue.date().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
				if (isCrossingIntoGa(oldLc, newLc)) {
					out.add(makeReleasedEvent(rd.getVersion(), ts));
					sawReleasedEvent = true;
				} else {
					TeaCleEventType cleType = mapLifecycleToCleEventType(newLc);
					if (cleType == null) continue;
					out.add(makeVersionEvent(cleType, rd.getVersion(), scheme, ts));
					if (cleType == TeaCleEventType.END_OF_SUPPORT) sawEndOfSupportEvent = true;
					if (cleType == TeaCleEventType.END_OF_LIFE) sawEndOfLifeEvent = true;
				}
			}
		}
		// Direct-create-at-GA fallback: if the release has no LIFECYCLE
		// events at all *and* its current lifecycle is ≥ GA (e.g.
		// `rearm addrelease --lifecycle ASSEMBLED` then never transitioned,
		// or — more typically — `--lifecycle GA` minted directly), still
		// emit a single `released` keyed at createdDate so the CLE story
		// reflects reality.
		if (!sawAnyLifecycleEvent && !sawReleasedEvent
				&& rd.getLifecycle() != null
				&& rd.getLifecycle().ordinal() >= ReleaseLifecycle.GENERAL_AVAILABILITY.ordinal()
				&& rd.getCreatedDate() != null) {
			OffsetDateTime ts = rd.getCreatedDate().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
			out.add(makeReleasedEvent(rd.getVersion(), ts));
		}
		// DATE-DERIVED FALLBACK: a device that only declares eos/eol (governed via
		// updateRelease's SUPPORT_WINDOW path, board t20260830-164643-29472) without
		// ever recording a matching LIFECYCLE transition would otherwise publish NO
		// endOfSupport/endOfLife event until someone manually flips the release's
		// overall workflow lifecycle -- by which time a past date is history, not
		// disclosure. The LIFECYCLE transition, when one exists, is authoritative (an
		// explicit workflow action beats a declared date) and is left exactly as
		// before; this only fills the gap when there is none, so it can never produce
		// two endOfSupport (or two endOfLife) events for one release.
		if ((!sawEndOfSupportEvent && null != rd.getEos()) || (!sawEndOfLifeEvent && null != rd.getEol())) {
			// Computed once and threaded through both calls below -- it's a linear
			// scan of updateEvents, and a release can need both an eos- and an
			// eol-derived event, so recomputing it per call would scan the same
			// list twice for no reason.
			ZonedDateTime assessedAt = rd.getSupportWindowLastAssessed();
			if (!sawEndOfSupportEvent && null != rd.getEos()) {
				out.add(makeDateDerivedSupportEvent(TeaCleEventType.END_OF_SUPPORT, rd.getEos(), assessedAt, rd, scheme));
			}
			if (!sawEndOfLifeEvent && null != rd.getEol()) {
				out.add(makeDateDerivedSupportEvent(TeaCleEventType.END_OF_LIFE, rd.getEol(), assessedAt, rd, scheme));
			}
		}
		return out;
	}

	/**
	 * A CLE Version Event synthesized directly from a declared {@code eos}/{@code eol}
	 * date rather than a LIFECYCLE transition. Per the spec, {@code effective} is when
	 * the event takes effect (the declared date itself -- future-dated is exactly the
	 * intent, per spec 3.1) and {@code published} is when the event was first
	 * published; the closest fact we have for "published" is when the support window
	 * was last touched ({@code assessedAt}, the SUPPORT_WINDOW update event date),
	 * falling back to the release's own createdDate, and then (since createdDate is
	 * itself nullable -- {@code RelizaDataParent.createdDate} defaults to null) to
	 * effective, for a window declared before that provenance existed (a legacy
	 * release, or one whose window was set before board #29472 shipped).
	 */
	private TeaCleEvent makeDateDerivedSupportEvent(TeaCleEventType type, LocalDate date,
			ZonedDateTime assessedAt, ReleaseData rd, ReleaseScheme scheme) {
		OffsetDateTime effective = date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
		ZonedDateTime publishedSource = null != assessedAt ? assessedAt : rd.getCreatedDate();
		OffsetDateTime published = null != publishedSource
				? publishedSource.toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS)
				: effective;
		return makeVersionEvent(type, rd.getVersion(), scheme, effective, published);
	}

	private List<TeaCleEvent> buildComponentRenameCandidates(ComponentData cd) {
		List<TeaCleEvent> out = new LinkedList<>();
		if (cd.getUpdateEvents() == null) return out;
		for (var ue : cd.getUpdateEvents()) {
			if (ue.cus() != ComponentData.ComponentUpdateScope.NAME) continue;
			if (ue.cua() != ComponentData.ComponentUpdateAction.CHANGED) continue;
			OffsetDateTime ts = ue.date().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
			TeaCleEvent ev = new TeaCleEvent(-1, TeaCleEventType.COMPONENT_RENAMED, utc(ts), utc(ts));
			// Per spec 7.8 — `identifiers[]` carries the NEW identifiers for
			// the component. The TEA enum doesn't include NAME (only
			// CPE/TEI/PURL/COMPLIANCE_DOCUMENT), so we snapshot the component's
			// TEA-exportable identifiers at emit time and put the
			// human-readable old→new name in description.
			List<TeaIdentifier> renameIds = toTeaIdentifiers(cd.getIdentifiers());
			if (!renameIds.isEmpty()) {
				ev.setIdentifiers(renameIds);
			}
			if (StringUtils.isNotEmpty(ue.newValue())) {
				ev.setDescription(StringUtils.isNotEmpty(ue.oldValue())
						? "Renamed: " + ue.oldValue() + " → " + ue.newValue()
						: "Renamed to: " + ue.newValue());
			}
			out.add(ev);
		}
		return out;
	}

	private TeaCleEvent makeReleasedEvent(String version, OffsetDateTime ts) {
		TeaCleEvent ev = new TeaCleEvent(-1, TeaCleEventType.RELEASED, utc(ts), utc(ts));
		// Spec 7.1 — released carries a single `version` field (string),
		// never the `versions` array. Don't merge across releases.
		ev.setVersion(version);
		return ev;
	}

	/**
	 * CLE requires ISO-8601 timestamps in UTC "ending in Z". @JsonFormat(shape=STRING)
	 * renders whatever offset the value carries, so a ZonedDateTime that arrived in a
	 * non-UTC zone would serialise as ...-04:00 and fail the spec (and
	 * coding_principles.md, which is categorical about the trailing Z). Normalise the
	 * INSTANT here, once, at every CLE event construction site.
	 */
	private static OffsetDateTime utc(OffsetDateTime odt) {
		return odt == null ? null : odt.withOffsetSameInstant(ZoneOffset.UTC);
	}

	private TeaCleEvent makeVersionEvent(TeaCleEventType type, String version, ReleaseScheme scheme,
			OffsetDateTime ts) {
		return makeVersionEvent(type, version, scheme, ts, ts);
	}

	private TeaCleEvent makeVersionEvent(TeaCleEventType type, String version, ReleaseScheme scheme,
			OffsetDateTime effective, OffsetDateTime published) {
		TeaCleEvent ev = new TeaCleEvent(-1, type, utc(effective), utc(published));
		// Spec 7.2-7.6 — Version Events use `versions[]` of VERS specifiers.
		// One specifier per concrete version here; mergeNonReleasedByMinute
		// unions the versions of releases that hit the same (type, minute), and
		// rangeContiguousVersions then names a run of them as one range.
		ev.setVersions(new ArrayList<>(List.of(versionSpecifierFor(version, scheme))));
		return ev;
	}

	/**
	 * One concrete version: the bare {@code version}, for consumers that read
	 * only that, and the same version as a vers line in {@code range}, in the
	 * scheme the product's version schema selects (see
	 * {@link ReleaseScheme}).
	 */
	private static TeaCleVersionSpecifier versionSpecifierFor(String version, ReleaseScheme scheme) {
		TeaCleVersionSpecifier s = new TeaCleVersionSpecifier();
		s.setVersion(version);
		s.setRange(VersRanges.releaseVers(scheme, version));
		return s;
	}

	/**
	 * A merged event whose versions are a run of the product's releases (no
	 * other release falls between the lowest and the highest) names them as
	 * one vers range, {@code >=lowest|<=highest}, in a single specifier with
	 * no bare {@code version}. Versions that are not a run, or that the
	 * scheme cannot order with confidence, keep one specifier each.
	 */
	private static void rangeContiguousVersions(List<TeaCleEvent> events, ReleaseOrder order) {
		for (TeaCleEvent ev : events) {
			if (ev.getVersions() == null || ev.getVersions().size() < 2) continue;
			List<String> versions = ev.getVersions().stream().map(TeaCleVersionSpecifier::getVersion).toList();
			order.rangeOf(versions).ifPresent(range -> {
				TeaCleVersionSpecifier run = new TeaCleVersionSpecifier();
				run.setRange(range);
				ev.setVersions(new ArrayList<>(List.of(run)));
			});
		}
	}

	private static boolean isCrossingIntoGa(ReleaseLifecycle oldLc, ReleaseLifecycle newLc) {
		if (newLc == null) return false;
		if (newLc.ordinal() < ReleaseLifecycle.GENERAL_AVAILABILITY.ordinal()) return false;
		// Old null = first lifecycle event we know of for this release. If
		// new ≥ GA, treat as a crossing.
		if (oldLc == null) return true;
		return oldLc.ordinal() < ReleaseLifecycle.GENERAL_AVAILABILITY.ordinal();
	}

	private static ReleaseLifecycle parseLifecycle(String s) {
		if (s == null) return null;
		try { return ReleaseLifecycle.valueOf(s); }
		catch (IllegalArgumentException e) { return null; }
	}

	/**
	 * Map a post-GA lifecycle state to its TEA CLE event type. Pre-GA states
	 * have no CLE counterpart — those changes don't surface in the CLE
	 * stream. CANCELLED / REJECTED are treated as terminal-end-of-life
	 * (per the spec, withdrawn is a meta event for revoking other events,
	 * not a lifecycle state).
	 */
	private TeaCleEventType mapLifecycleToCleEventType(ReleaseLifecycle lc) {
		if (lc == null) return null;
		return switch (lc) {
			case END_OF_MARKETING -> TeaCleEventType.END_OF_MARKETING;
			case END_OF_DISTRIBUTION -> TeaCleEventType.END_OF_DISTRIBUTION;
			case END_OF_SUPPORT -> TeaCleEventType.END_OF_SUPPORT;
			case END_OF_LIFE, CANCELLED, REJECTED -> TeaCleEventType.END_OF_LIFE;
			default -> null;
		};
	}

	/**
	 * Merge candidates of the same (TeaCleEventType, effective-truncated-to-minute)
	 * into a single TEA CLE event whose {@code versions[]} is the union
	 * of the source events' specifiers. {@code released} events are
	 * passed through unchanged (single-version field, never merged).
	 * COMPONENT_RENAMED stays one event per rename.
	 */
	private List<TeaCleEvent> mergeNonReleasedByMinute(List<TeaCleEvent> in) {
		Map<String, TeaCleEvent> bucket = new LinkedHashMap<>();
		List<TeaCleEvent> passthrough = new LinkedList<>();
		for (TeaCleEvent ev : in) {
			if (ev.getType() == TeaCleEventType.RELEASED
					|| ev.getType() == TeaCleEventType.COMPONENT_RENAMED) {
				passthrough.add(ev);
				continue;
			}
			OffsetDateTime minute = ev.getEffective() != null
					? ev.getEffective().truncatedTo(ChronoUnit.MINUTES)
					: null;
			// supportId is always null here today: the support-policy catalog was removed
			// (ai-plans/fda-readiness-1-plan.md P1) and no production path can set it, so
			// this key component is constant and non-discriminating. Kept ON PURPOSE so
			// that when policies return, two releases hitting the same (type, minute)
			// under DIFFERENT policies do not collapse into one event carrying only the
			// first release's supportId.
			// HONEST CAVEAT: that property is currently UNVERIFIED. The test that pinned
			// it (componentLevelMergeDoesNotCollapseDifferentSupportPoliciesAtTheSameMinute)
			// was removed with the catalog, because no reachable code path can produce two
			// differing supportIds to test with. Restore the test in the same change that
			// restores the catalog -- do not trust this comment as evidence.
			String key = ev.getType().toString() + "|" + minute + "|" + ev.getSupportId();
			TeaCleEvent existing = bucket.get(key);
			if (existing == null) {
				// First sighting at this (type, minute) — adopt as-is but
				// snap the effective timestamp down to the minute boundary
				// so consumers see a clean per-minute aggregate.
				if (minute != null) ev.setEffective(minute);
				// Snap published to ITS OWN minute, never to effective's -- a
				// date-derived support event (board t20260830-164643-29472 half B)
				// deliberately has published != effective (published = when the
				// window was asserted, effective = the future/past eos/eol date
				// itself), and collapsing published down to effective's minute here
				// would silently overwrite that with a nonsense far-future/past
				// "published" timestamp. Before date-derived events existed,
				// published always equalled effective already, so this produces the
				// same result for every event this method previously handled.
				if (ev.getPublished() != null) ev.setPublished(ev.getPublished().truncatedTo(ChronoUnit.MINUTES));
				bucket.put(key, ev);
			} else {
				// Subsequent -- merge versions[], and keep the EARLIER published.
				// Before date-derived events, published always equalled effective, so
				// whichever sighting "won" here was harmless. Now that a date-derived
				// event can have a genuinely different published than another event
				// sharing this key (e.g. two releases of one device family declaring
				// the SAME eos but asserted months apart -- the normal case, not an
				// edge case), first-encountered-wins is wrong on two counts: CLE
				// defines published as when the event was FIRST published, so the
				// correct pick is the earlier of the two; and "first encountered"
				// depends on DB return order (listReleaseDatasOfComponent), so the
				// same data could silently produce a different document across calls.
				if (ev.getPublished() != null) {
					OffsetDateTime candidatePublished = ev.getPublished().truncatedTo(ChronoUnit.MINUTES);
					if (existing.getPublished() == null || candidatePublished.isBefore(existing.getPublished())) {
						existing.setPublished(candidatePublished);
					}
				}
				// Merge versions[]. Avoid duplicates by comparing the bare version
				// field (which is what we set in versionSpecifierFor for both semver
				// and generic schemes).
				if (ev.getVersions() != null) {
					for (var spec : ev.getVersions()) {
						boolean dup = existing.getVersions() != null
								&& existing.getVersions().stream().anyMatch(s ->
										java.util.Objects.equals(s.getVersion(), spec.getVersion()));
						if (!dup) {
							if (existing.getVersions() == null) existing.setVersions(new ArrayList<>());
							existing.getVersions().add(spec);
						}
					}
				}
			}
		}
		List<TeaCleEvent> out = new LinkedList<>(bucket.values());
		out.addAll(passthrough);
		return out;
	}

	// Spec 6.1 -- `$schema` is a stable per-version URI; until ECMA publish a
	// canonical one, the spec uses cle.example.com as the documentation
	// placeholder. Match that so consumers can recognise the version.
	//
	// KNOWN DEVIATION, deliberately disclosed here rather than left implicit.
	// This URI identifies the version the document is SHAPED to; it is not a
	// claim that the document meets that version's conformance bar, and right now
	// it does not. CLE 1.0.0 states supportId "MUST be included" on endOfSupport
	// events, and since the support-policy catalog was removed (see
	// ai-plans/fda-readiness-1-plan.md P1) we never populate it, nor emit
	// definitions.support[] at all. Event ids are also recomputed per export
	// rather than persisted. The UI no longer advertises "CLE 1.0.0"
	// (relizaio/rearm#313); do not reinstate that claim, here or anywhere else,
	// until board t20260831-115702-299 and -13297 land.
	private static final String CLE_SCHEMA_URI = "https://cle.example.com/schema/cle-1.0.0.schema.json";

	/**
	 * Wrap a {@link TeaCle} (events list) with the CLE-spec-required
	 * top-level fields ({@code $schema}, {@code identifier},
	 * {@code updatedAt}) so the result is a self-contained CLE-1.0.0 JSON
	 * document. Used by the {@code exportComponentCleManual} /
	 * {@code exportReleaseCleManual} GraphQL queries.
	 *
	 * <p>The events are written with {@link TeaJson#MAPPER}: nulls left out and every instant a
	 * UTC date-time with the Z designator, which the generated models no longer carry as annotations.
	 *
	 * @param cle the events container produced by {@code transform*ToCle}
	 * @param identifiers PURL identifiers for the component this document
	 *                    describes; may be null/empty (then the field is
	 *                    emitted as an empty array)
	 */
	public tools.jackson.databind.JsonNode wrapAsCleDocument(TeaCle cle, java.util.List<RearmIdentifier> identifiers) {
		tools.jackson.databind.node.ObjectNode root = io.reliza.common.Utils.OM.createObjectNode();
		root.put("$schema", CLE_SCHEMA_URI);
		// identifier field — spec accepts string OR array. Always array for
		// uniformity. PURLs only, since spec ties this to the PURL format.
		tools.jackson.databind.node.ArrayNode idArr = root.putArray("identifier");
		if (identifiers != null) {
			for (var id : identifiers) {
				if (id == null) continue;
				if (id.getIdType() == RearmIdentifierType.PURL && id.getIdValue() != null) {
					idArr.add(id.getIdValue());
				}
			}
		}
		// Instant.toString() always renders seconds; OffsetDateTime.toString() OMITS them
		// when second-of-minute and nanos are both zero, so 1 export in 60 emitted
		// "2026-09-02T10:15Z" -- valid ISO-8601 but NOT RFC-3339, failing date-time
		// schema validation intermittently.
		root.put("updatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
		root.set("events", TeaJson.MAPPER.valueToTree(cle.getEvents()));
		if (cle.getDefinitions() != null) {
			root.set("definitions", TeaJson.MAPPER.valueToTree(cle.getDefinitions()));
		}
		return root;
	}

	private static void renumberAndSortNewestFirst(List<TeaCleEvent> events) {
		// Tiebreak on type when effective is equal (byte-identical, not just same
		// minute) -- otherwise List.sort's stability leaves the order, and so the
		// document-local id assignment, dependent on DB row order. Two date-derived
		// events sharing one instant is a real case: a release with eos == eol
		// produces exactly this (both land on date.atStartOfDay(UTC)).
		events.sort(Comparator.comparing(
					(TeaCleEvent e) -> e.getEffective(),
					Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(TeaCleEvent::getType, Comparator.nullsLast(Comparator.naturalOrder())));
		int id = 0;
		for (TeaCleEvent ev : events) ev.setId(id++);
	}

	/**
	 * CLE says to omit fields that do not belong to an event's type, and the generated model starts
	 * every list empty: an empty {@code versions}, {@code identifiers} or {@code references} is left out.
	 */
	static TeaCle withoutEmptyOptionalLists(TeaCle cle) {
		for (TeaCleEvent ev : cle.getEvents()) {
			if (null != ev.getVersions() && ev.getVersions().isEmpty()) ev.setVersions(null);
			if (null != ev.getIdentifiers() && ev.getIdentifiers().isEmpty()) ev.setIdentifiers(null);
			if (null != ev.getReferences() && ev.getReferences().isEmpty()) ev.setReferences(null);
		}
		return cle;
	}
}
