/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.TeaAuthorizationService;
import io.reliza.service.TeaAuthorizationService.TeaAccess;
import io.reliza.service.TeaAuthorizationService.TeaAuthState;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaProfileService;
import io.reliza.service.TeaProfileService.WellKnownEndpoint;
import io.reliza.service.TeaProfileService.WellKnownTea;
import io.reliza.service.TeaPublicationService;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationExposure;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

/**
 * The read model of the TEA 1.0.0 surface (task TEA-6): the one place that turns TEA-5's
 * publication heads and TEA-3's caller into a decision, so every controller applies the same
 * rule. Hidden, unpublished and unknown objects are the same CONCEAL; a product or component
 * appears when at least one of its published releases is served to the caller.
 */
@Slf4j
@Service
public class TeaSurfaceService {

	/** The organization a TEA base names. */
	public record TeaOrg(UUID org, UUID teaUuid, String apiBase) {}

	/** A published head with its live exposure and its release. */
	public record Head(TeaPublicationData publication, TeaPublicationExposure exposure, ReleaseData release) {}

	/** {@code value} is non-null exactly when {@code refusal} is SERVE. */
	public record Decision<T>(T value, TeaAccess refusal) {
		public static <T> Decision<T> serve(T value) {
			return new Decision<>(Objects.requireNonNull(value), TeaAccess.SERVE);
		}

		public static <T> Decision<T> refuse(TeaAccess refusal) {
			if (TeaAccess.SERVE == refusal) throw new IllegalArgumentException("SERVE is not a refusal");
			return new Decision<>(null, refusal);
		}

		public boolean served() {
			return TeaAccess.SERVE == refusal;
		}
	}

	/** A product or component the caller is served, with the heads served to the caller. */
	public record TeaObject(ComponentData component, List<Head> servedHeads) {}

	/** The instance discovery document, or the error and message it answers instead. */
	public record WellKnownAnswer(String document, TeaUnknownErrorType error, String message) {}

	static final String NO_TEA_ID_MESSAGE = "no organization has a TEA id yet; enable publishing on a TEA profile to mint one";
	static final String NOT_HOSTED_MESSAGE = "each organization hosts its own /.well-known/tea document on its TEI domain; "
			+ "see the organization's Transparency Exchange settings";
	/** A listing beyond this many results is logged: it needs the page-token task. */
	static final int LARGE_LISTING = 1000;

	@Autowired private TeaPublicationService teaPublicationService;
	@Autowired private TeaAuthorizationService teaAuthorizationService;
	@Autowired private OrganizationRepository organizationRepository;
	@Autowired private ComponentRepository componentRepository;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private UserService userService;

	// ------------------------------------------------------------------ the base

	/** The organization of a base segment: a TEA id of an organization that is not archived. */
	public Optional<TeaOrg> orgOfBase(String segment) {
		if (null == segment) return Optional.empty();
		UUID tea;
		try {
			tea = UUID.fromString(segment);
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		if (!tea.toString().equalsIgnoreCase(segment)) return Optional.empty();
		return organizationRepository.findByTeaUuid(tea.toString()).map(OrganizationData::orgDataFromDbRecord)
				.filter(od -> StatusEnum.ARCHIVED != od.getStatus() && null != od.getTeaUuid())
				.map(od -> new TeaOrg(od.getUuid(), od.getTeaUuid(), teaPublicationService.apiBaseOf(od.getUuid())));
	}

	// ------------------------------------------------------------------ one publication

	/** The decision for one publication and the exposure it was taken on (null when none was read). */
	public record Access(TeaAccess access, TeaPublicationExposure exposure) {}

	/** The decision for one publication: CONCEAL unless it is of this organization and not concealed, then TEA-3's. */
	public TeaAccess accessTo(TeaCaller caller, TeaOrg org, TeaPublicationData p) {
		return evaluate(caller, org, p).access();
	}

	/**
	 * The per-publication rule (task TEA-6 3.3; public for TEA-10's artifacts): CONCEAL unless the
	 * publication is of this organization and its exposure is not concealed, then TEA-3's decision on
	 * the governing component under the recorded resolution.
	 */
	public Access evaluate(TeaCaller caller, TeaOrg org, TeaPublicationData p) {
		if (null == p || null == org || !org.org().equals(p.getOrg())) return new Access(TeaAccess.CONCEAL, null);
		TeaPublicationExposure e = teaPublicationService.exposureOf(p);
		if (null == e || TeaExposureState.CONCEALED == e.state()) {
			log.debug("TEA publication {} concealed: {}", p.getUuid(), null == e ? null : e.concealedBecause());
			return new Access(TeaAccess.CONCEAL, e);
		}
		return new Access(teaAuthorizationService.accessToComponent(caller, org.org(), e.governingComponent(), e.resolved()), e);
	}

	/** SERVE when any is; else UNAUTHENTICATED, then INVALID_TOKEN; else CONCEAL (also for none). */
	public static TeaAccess strongest(Collection<TeaAccess> accesses) {
		if (null == accesses) return TeaAccess.CONCEAL;
		for (TeaAccess a : List.of(TeaAccess.SERVE, TeaAccess.UNAUTHENTICATED, TeaAccess.INVALID_TOKEN)) {
			if (accesses.contains(a)) return a;
		}
		return TeaAccess.CONCEAL;
	}

	public Decision<Head> productRelease(TeaCaller caller, TeaOrg org, UUID uuid) {
		return release(caller, org, uuid, ComponentType.PRODUCT);
	}

	public Decision<Head> componentRelease(TeaCaller caller, TeaOrg org, UUID uuid) {
		return release(caller, org, uuid, ComponentType.COMPONENT);
	}

	private Decision<Head> release(TeaCaller caller, TeaOrg org, UUID uuid, ComponentType kind) {
		Optional<TeaPublicationData> p = null == uuid ? Optional.empty() : teaPublicationService.getPublication(uuid);
		if (p.isEmpty() || null == org || !org.org().equals(p.get().getOrg()) || kind != p.get().getComponentType()) {
			return Decision.refuse(TeaAccess.CONCEAL);
		}
		Access ev = evaluate(caller, org, p.get());
		if (TeaAccess.SERVE != ev.access()) return Decision.refuse(ev.access());
		Optional<Head> h = headOf(p.get(), ev.exposure());
		return h.isPresent() ? Decision.serve(h.get()) : Decision.refuse(TeaAccess.CONCEAL);
	}

	private Optional<Head> headOf(TeaPublicationData p, TeaPublicationExposure e) {
		return sharedReleaseService.getReleaseData(p.getRelease()).map(rd -> new Head(p, e, rd));
	}

	// ------------------------------------------------------------------ objects with several publications

	public Decision<TeaObject> product(TeaCaller caller, TeaOrg org, UUID teaUuid) {
		return object(caller, org, teaUuid, ComponentType.PRODUCT);
	}

	public Decision<TeaObject> component(TeaCaller caller, TeaOrg org, UUID teaUuid) {
		return object(caller, org, teaUuid, ComponentType.COMPONENT);
	}

	private Decision<TeaObject> object(TeaCaller caller, TeaOrg org, UUID teaUuid, ComponentType kind) {
		if (null == teaUuid || null == org) return Decision.refuse(TeaAccess.CONCEAL);
		Optional<ComponentData> cd = componentRepository.findByTeaUuid(teaUuid.toString()).map(ComponentData::dataFromRecord)
				.filter(c -> org.org().equals(c.getOrg()) && kind == c.getType());
		if (cd.isEmpty()) return Decision.refuse(TeaAccess.CONCEAL);
		List<TeaAccess> accesses = new ArrayList<>();
		List<Head> served = new ArrayList<>();
		for (TeaPublicationData p : publishedOfComponent(org, cd.get().getUuid())) {
			Access ev = evaluate(caller, org, p);
			accesses.add(ev.access());
			if (TeaAccess.SERVE == ev.access()) headOf(p, ev.exposure()).ifPresent(served::add);
		}
		TeaAccess decision = strongest(accesses);
		if (TeaAccess.SERVE != decision || served.isEmpty()) {
			return Decision.refuse(TeaAccess.SERVE == decision ? TeaAccess.CONCEAL : decision);
		}
		return Decision.serve(new TeaObject(cd.get(), served));
	}

	private List<TeaPublicationData> publishedOfComponent(TeaOrg org, UUID component) {
		return teaPublicationService.listPublicationsOfComponent(org.org(), component).stream()
				.filter(p -> TeaPublicationState.PUBLISHED == p.getState()).toList();
	}

	// ------------------------------------------------------------------ listings

	/**
	 * The listing rule of TEA-3: an invalid token is refused before anything is read; otherwise the
	 * PUBLIC heads, plus the PRIVATE ones whose governing component the caller's key covers.
	 */
	public Decision<List<Head>> visibleHeads(TeaCaller caller, TeaOrg org, ComponentType type) {
		if (isInvalidToken(caller)) return Decision.refuse(TeaAccess.INVALID_TOKEN);
		List<TeaPublicationData> heads = teaPublicationService.listPublicationsOfOrg(org.org(), TeaPublicationState.PUBLISHED)
				.stream().filter(p -> type == p.getComponentType()).toList();
		return Decision.serve(visible(caller, org, heads));
	}

	/** The listing rule over the published heads of one component (its parent decision is the caller's). */
	public Decision<List<Head>> visibleHeadsOfComponent(TeaCaller caller, TeaOrg org, UUID component) {
		if (isInvalidToken(caller)) return Decision.refuse(TeaAccess.INVALID_TOKEN);
		return Decision.serve(visible(caller, org, publishedOfComponent(org, component)));
	}

	private static boolean isInvalidToken(TeaCaller caller) {
		return null != caller && TeaAuthState.INVALID_TOKEN == caller.state();
	}

	private List<Head> visible(TeaCaller caller, TeaOrg org, List<TeaPublicationData> heads) {
		boolean authenticated = null != caller && TeaAuthState.AUTHENTICATED == caller.state() && null != caller.principal();
		Set<UUID> covered = authenticated ? teaAuthorizationService.coveredComponentUuids(caller.principal(), org.org()) : Set.of();
		List<Head> out = new ArrayList<>();
		for (TeaPublicationData p : heads) {
			if (!org.org().equals(p.getOrg())) continue;
			TeaPublicationExposure e = teaPublicationService.exposureOf(p);
			if (null == e || TeaExposureState.CONCEALED == e.state()) {
				log.debug("TEA listing of organization {}: publication {} concealed: {}", org.org(), p.getUuid(),
						null == e ? null : e.concealedBecause());
				continue;
			}
			boolean keep = TeaExposureState.PUBLIC == e.state() || (authenticated && null != e.governingComponent()
					&& (null == covered || covered.contains(e.governingComponent().getUuid())));
			if (keep) headOf(p, e).ifPresent(out::add);
		}
		return out;
	}

	/** The distinct governing components of the heads, in first-seen order. */
	public static List<ComponentData> componentsOf(List<Head> heads) {
		Map<UUID, ComponentData> out = new LinkedHashMap<>();
		for (Head h : heads) {
			ComponentData cd = h.exposure().governingComponent();
			if (null != cd && null != cd.getTeaUuid()) out.putIfAbsent(cd.getUuid(), cd);
		}
		return new ArrayList<>(out.values());
	}

	/** Logs a listing too large for one page; the page-token task is its fix. */
	public static void warnIfLarge(TeaOrg org, String endpoint, int count) {
		if (count > LARGE_LISTING) {
			log.warn("TEA listing {} of organization {} answers {} results in one page", endpoint, org.org(), count);
		}
	}

	// ------------------------------------------------------------------ filters and sort, on rendered results

	/**
	 * Keeps the results carrying the identifier: both given, that type and that exact value;
	 * {@code idValue} alone, that value under any type; {@code idType} alone, any value of that type.
	 */
	public static <T> List<T> filterByIdentifier(List<T> items, Function<T, List<TeaIdentifier>> identifiers,
			TeaIdentifierType idType, String idValue) {
		if (null == idType && null == idValue) return items;
		return items.stream().filter(i -> {
			List<TeaIdentifier> ids = identifiers.apply(i);
			return null != ids && ids.stream().anyMatch(id -> (null == idType || idType == id.getIdType())
					&& (null == idValue || idValue.equals(id.getIdValue())));
		}).toList();
	}

	/** Sorts by the key, then by the uuid; both reversed for descending. Strings in codepoint order. */
	public static <T, K extends Comparable<? super K>> List<T> sorted(List<T> items, Function<T, K> key,
			Function<T, UUID> uuid, boolean descending) {
		Comparator<T> c = Comparator.comparing(key, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(i -> String.valueOf(uuid.apply(i)));
		List<T> out = new ArrayList<>(items);
		out.sort(descending ? c.reversed() : c);
		return out;
	}

	// ------------------------------------------------------------------ discovery

	/**
	 * The product releases of the organization a TEI names, newest first, decided over the
	 * candidates as one object: the served subset, or the strongest refusal. The TEI must have
	 * passed {@link TeaTei#identifierProblem()}; an identifier that does not parse finds nothing.
	 */
	public Decision<List<Head>> discover(TeaCaller caller, TeaOrg org, TeaTei tei) {
		Map<UUID, TeaPublicationData> candidates = new LinkedHashMap<>();
		// 1. the stamped TEI, character for character
		teaPublicationService.listPublicationsOfOrg(org.org(), TeaPublicationState.PUBLISHED).stream()
				.filter(p -> ComponentType.PRODUCT == p.getComponentType() && tei.tei().equals(p.getTei()))
				.forEach(p -> candidates.putIfAbsent(p.getUuid(), p));
		// 2. the live TEI identifier of a release
		for (TeaPublicationData p : publishedProductHeadsOf(org, RearmIdentifierType.TEI, tei.tei())) {
			candidates.putIfAbsent(p.getUuid(), p);
		}
		// 3. a uuid TEI names the publication
		tei.uuidIdentifier().flatMap(teaPublicationService::getPublication)
				.filter(p -> isPublishedProductOf(org, p) && domainMatches(p, tei.domain()))
				.ifPresent(p -> candidates.putIfAbsent(p.getUuid(), p));
		// 4. a purl TEI carries a purl
		tei.purlIdentifier().ifPresent(purl -> publishedProductHeadsOf(org, RearmIdentifierType.PURL, purl).stream()
				.filter(p -> domainMatches(p, tei.domain())).forEach(p -> candidates.putIfAbsent(p.getUuid(), p)));
		// 5. any other type: nothing
		return decideCandidates(caller, org, candidates.values());
	}

	/** The product releases whose release identifiers carry this purl. */
	public Decision<List<Head>> discoverByPurl(TeaCaller caller, TeaOrg org, String purl) {
		Map<UUID, TeaPublicationData> candidates = new LinkedHashMap<>();
		for (TeaPublicationData p : publishedProductHeadsOf(org, RearmIdentifierType.PURL, purl)) {
			candidates.putIfAbsent(p.getUuid(), p);
		}
		return decideCandidates(caller, org, candidates.values());
	}

	private List<TeaPublicationData> publishedProductHeadsOf(TeaOrg org, RearmIdentifierType type, String value) {
		List<TeaPublicationData> out = new ArrayList<>();
		for (ReleaseData rd : sharedReleaseService.findReleasesByOrgAndIdentifier(org.org(), type, value)) {
			teaPublicationService.getPublicationOfRelease(rd.getUuid()).filter(p -> isPublishedProductOf(org, p))
					.ifPresent(out::add);
		}
		return out;
	}

	private static boolean isPublishedProductOf(TeaOrg org, TeaPublicationData p) {
		return org.org().equals(p.getOrg()) && ComponentType.PRODUCT == p.getComponentType()
				&& TeaPublicationState.PUBLISHED == p.getState();
	}

	/**
	 * With TEI stamping on, the TEI's domain must be the profile's teiDomain (a DNS name, compared
	 * case-insensitively); with stamping off the domain is not checked.
	 */
	private boolean domainMatches(TeaPublicationData p, String domain) {
		TeaPublicationExposure e = teaPublicationService.exposureOf(p);
		if (null == e || null == e.resolved() || null == e.resolved().profile()) return false;
		TeaProfileData profile = e.resolved().profile();
		if (TeaProfileData.TeaTei.UUID != profile.getTei()) return true;
		return null != profile.getTeiDomain() && profile.getTeiDomain().equalsIgnoreCase(domain);
	}

	private Decision<List<Head>> decideCandidates(TeaCaller caller, TeaOrg org, Collection<TeaPublicationData> candidates) {
		List<TeaPublicationData> ordered = sorted(new ArrayList<>(candidates),
				(TeaPublicationData p) -> null == p.getFirstPublishedDate() ? null : p.getFirstPublishedDate().toInstant(),
				TeaPublicationData::getUuid, true);
		List<TeaAccess> accesses = new ArrayList<>();
		List<Head> served = new ArrayList<>();
		for (TeaPublicationData p : ordered) {
			Access ev = evaluate(caller, org, p);
			accesses.add(ev.access());
			if (TeaAccess.SERVE == ev.access()) headOf(p, ev.exposure()).ifPresent(served::add);
		}
		TeaAccess decision = strongest(accesses);
		if (TeaAccess.SERVE != decision || served.isEmpty()) {
			return Decision.refuse(TeaAccess.SERVE == decision ? TeaAccess.CONCEAL : decision);
		}
		return Decision.serve(served);
	}

	// ------------------------------------------------------------------ the instance discovery document

	/**
	 * The instance /.well-known/tea document. On OSS, one endpoint per organization with a TEA id
	 * that is not archived, ordered by url; elsewhere each organization hosts its own document.
	 */
	public WellKnownAnswer wellKnown() {
		if (InstallationType.OSS != userService.getInstallationType()) {
			return new WellKnownAnswer(null, TeaUnknownErrorType.NOT_IMPLEMENTED, NOT_HOSTED_MESSAGE);
		}
		List<WellKnownEndpoint> endpoints = organizationRepository.listWithTeaUuid().stream()
				.map(OrganizationData::orgDataFromDbRecord)
				.filter(od -> StatusEnum.ARCHIVED != od.getStatus() && null != od.getTeaUuid())
				.map(od -> teaPublicationService.apiBaseOf(od.getUuid())).filter(Objects::nonNull).sorted()
				.map(base -> new WellKnownEndpoint(base, List.of(TeaProfileService.TEA_API_VERSION), 1)).toList();
		if (endpoints.isEmpty()) return new WellKnownAnswer(null, TeaUnknownErrorType.OBJECT_UNKNOWN, NO_TEA_ID_MESSAGE);
		return new WellKnownAnswer(Utils.OM.writeValueAsString(new WellKnownTea(1, endpoints)), null, null);
	}

}
