/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.UserData;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.repositories.SystemInfoRepository;
import io.reliza.ws.RelizaConfigProps;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * TEA custom domains (TPF-142, architecture D4-D6): the allow-list of assignable hosts (the
 * relizaprops.teaCustomDomains property plus the baseuri host), the one host normalisation, and the
 * host -> organization map on the system_info row, assigned and unassigned by a global
 * administrator under the row lock.
 */
@Slf4j
@Service
public class TeaHostAssignmentService {

	/** One assignable (or stale) host with its assignment; the GraphQL TeaHostAssignment one to one. */
	public record TeaHostAssignment(String host, UUID org, String orgName, boolean isBaseuriHost, boolean inAllowList,
			TeaServing teaServing) {}

	static final String ORGANIZATION_NOT_FOUND = "Organization not found";
	static final String NOT_A_BARE_HOST = "host must be a bare lowercase DNS name";

	/**
	 * The RFC 1035 label rule, the pattern of SidPurlUtils.DOMAIN_LABEL in its lowercase form (the
	 * value is lower-cased before it is matched). Applied to every label, the only label of a dotless
	 * value included (architecture D5, F-10).
	 */
	private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
	/** An IPv4 literal: digits and dots only. Never a custom host and never the assignable baseuri host. */
	private static final Pattern IPV4_LITERAL = Pattern.compile("^[0-9.]+$");
	/** One {@code :port} suffix: digits after the only {@code :} in the value. */
	private static final Pattern PORT_SUFFIX = Pattern.compile("^([^:]*):[0-9]+$");
	private static final int MAX_HOST_LENGTH = 253;

	private final RelizaConfigProps relizaConfigProps;
	private final SystemInfoService systemInfoService;
	private final SystemInfoRepository systemInfoRepository;
	private final OrganizationRepository organizationRepository;
	private final GetOrganizationService getOrganizationService;

	/** The listed custom hosts, minus the baseuri host. */
	private volatile Set<String> customHosts = Set.of();
	/** The baseuri host, when it is a DNS name. */
	private volatile Optional<String> baseuriHost = Optional.empty();
	/** Map values that are not uuids, so the ERROR is logged once per host. */
	private final Set<String> corruptValues = ConcurrentHashMap.newKeySet();

	TeaHostAssignmentService(RelizaConfigProps relizaConfigProps, SystemInfoService systemInfoService,
			SystemInfoRepository systemInfoRepository, OrganizationRepository organizationRepository,
			GetOrganizationService getOrganizationService) {
		this.relizaConfigProps = relizaConfigProps;
		this.systemInfoService = systemInfoService;
		this.systemInfoRepository = systemInfoRepository;
		this.organizationRepository = organizationRepository;
		this.getOrganizationService = getOrganizationService;
	}

	/**
	 * Parses the allow-list once (architecture D5): every comma-separated entry is normalised, and
	 * an entry that does not normalise fails startup; blank entries and duplicates collapse; an entry
	 * equal to the baseuri host is dropped from the custom set (it is the baseuri entry).
	 */
	@PostConstruct
	void init() {
		Optional<String> base = Optional.empty();
		String baseuri = null == relizaConfigProps ? null : relizaConfigProps.getBaseuri();
		if (null != baseuri && !baseuri.isBlank()) {
			try {
				base = normalizeHost(URI.create(baseuri.trim()).getHost());
			} catch (IllegalArgumentException e) {
				log.error("relizaprops.baseuri is not a URI; the instance host is not assignable as a TEA domain", e);
			}
		}
		Set<String> custom = new TreeSet<>();
		String raw = null == relizaConfigProps ? null : relizaConfigProps.getTeaCustomDomains();
		if (null != raw) {
			for (String entry : raw.split(",")) {
				if (entry.isBlank()) continue;
				String host = normalizeHost(entry).orElseThrow(() -> new IllegalStateException(
						"relizaprops.teaCustomDomains entry is not a bare hostname: " + entry.trim()));
				custom.add(host);
			}
		}
		base.ifPresent(custom::remove);
		this.baseuriHost = base;
		this.customHosts = Collections.unmodifiableSet(custom);
		log.info("TEA custom domains: {} listed, instance host {}", custom.size(), base.orElse("not assignable"));
	}

	/**
	 * The one host normalisation (architecture D5): trim, strip one trailing dot, strip one
	 * {@code :port} suffix, lower-case in Locale.ROOT, then the value only when it is a DNS name: 1 to
	 * 253 characters, every label matching the RFC 1035 label rule, and not an IPv4 literal. Anything
	 * else is empty: non-ASCII, a scheme, a path, a wildcard, a brace, a quote, a comma, an IPv6
	 * literal, an empty or over-long label. {@code localhost} normalises to itself.
	 */
	public static Optional<String> normalizeHost(String raw) {
		if (null == raw) return Optional.empty();
		String h = raw.trim();
		// non-ASCII is refused before lower-casing, which could map some code points onto ASCII letters
		for (int i = 0; i < h.length(); i++) {
			if (h.charAt(i) > 127) return Optional.empty();
		}
		if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
		var port = PORT_SUFFIX.matcher(h);
		if (port.matches()) h = port.group(1);
		h = h.toLowerCase(Locale.ROOT);
		if (h.isEmpty() || h.length() > MAX_HOST_LENGTH || IPV4_LITERAL.matcher(h).matches()) return Optional.empty();
		for (String label : h.split("\\.", -1)) {
			if (!DNS_LABEL.matcher(label).matches()) return Optional.empty();
		}
		return Optional.of(h);
	}

	/** Whether the host is a listed custom domain (not the baseuri host): where only TEA is served. */
	public boolean isCustomHost(String host) {
		return normalizeHost(host).map(customHosts::contains).orElse(false);
	}

	/** Whether the host is the instance's own (baseuri) host. */
	public boolean isBaseuriHost(String host) {
		Optional<String> h = normalizeHost(host);
		return h.isPresent() && h.equals(baseuriHost);
	}

	/** Whether the host may be assigned: a listed custom host or the baseuri host. */
	public boolean isAssignable(String host) {
		return isCustomHost(host) || isBaseuriHost(host);
	}

	/**
	 * The fresh map under the system_info row lock (architecture D6), inside the caller's
	 * transaction: the row is created first when absent, then one native FOR UPDATE read both locks
	 * it and answers the committed map (never a copy the persistence context holds). Held to the end
	 * of the caller's transaction; assign, unassign and OrganizationService.setTeaServing serialise here.
	 */
	Map<String, String> lockedAssignments() {
		systemInfoService.getTeaHostAssignments();
		return parseMap(systemInfoRepository.currentTeaHostAssignmentsForUpdate());
	}

	private static Map<String, String> parseMap(Optional<String> text) {
		if (text.isEmpty() || text.get().isBlank()) return new LinkedHashMap<>();
		@SuppressWarnings("unchecked")
		Map<String, Object> raw = Utils.OM.readValue(text.get(), LinkedHashMap.class);
		Map<String, String> out = new LinkedHashMap<>();
		if (null != raw) raw.forEach((k, v) -> out.put(k, null == v ? null : String.valueOf(v)));
		return out;
	}

	/**
	 * Every assignable host in order (custom hosts sorted, the baseuri host last), then every map
	 * entry whose host is no longer assignable (listed with inAllowList false so it can be
	 * unassigned; it is never served). Read from the unlocked map.
	 */
	public List<TeaHostAssignment> list() {
		Map<String, String> map = systemInfoService.getTeaHostAssignments();
		List<String> hosts = new ArrayList<>(customHosts);
		baseuriHost.ifPresent(hosts::add);
		Set<String> stale = new TreeSet<>(map.keySet());
		stale.removeAll(hosts);
		hosts.addAll(stale);
		List<TeaHostAssignment> out = new ArrayList<>();
		for (String host : hosts) out.add(row(host, map.get(host)));
		return out;
	}

	private TeaHostAssignment row(String host, String holder) {
		UUID org = parseOrg(host, holder).orElse(null);
		Optional<OrganizationData> od = null == org ? Optional.empty() : getOrganizationService.getOrganizationData(org);
		return new TeaHostAssignment(host, org, od.map(OrganizationData::getName).orElse(null), isBaseuriHost(host),
				isAssignable(host), od.map(OrganizationData::effectiveTeaServing).orElse(null));
	}

	/** The map value as a uuid; a value that is not one reads as absent and is logged once per host. */
	private Optional<UUID> parseOrg(String host, String value) {
		if (null == value) return Optional.empty();
		try {
			return Optional.of(UUID.fromString(value));
		} catch (IllegalArgumentException e) {
			if (corruptValues.add(host)) {
				log.error("TEA host assignment of {} does not name an organization uuid; read as unassigned", host, e);
			}
			return Optional.empty();
		}
	}

	/** The organization the host is assigned to; absent for a host that is not assignable, whatever the map holds. */
	public Optional<UUID> orgOfHost(String host) {
		Optional<String> h = normalizeHost(host);
		if (h.isEmpty() || !isAssignable(h.get())) return Optional.empty();
		return parseOrg(h.get(), systemInfoService.getTeaHostAssignments().get(h.get()));
	}

	/** The assignable host the organization holds (the host it is served on and advertises), from the unlocked map. */
	public Optional<String> hostOfOrg(UUID org) {
		return hostOfOrg(systemInfoService.getTeaHostAssignments(), org);
	}

	/** {@link #hostOfOrg(UUID)} over a given map (setTeaServing passes the locked one). */
	public Optional<String> hostOfOrg(Map<String, String> map, UUID org) {
		return rawHostOfOrg(map, org).filter(this::isAssignable);
	}

	/** The key of the map whose value is the organization, listed or not. */
	private static Optional<String> rawHostOfOrg(Map<String, String> map, UUID org) {
		if (null == map || null == org) return Optional.empty();
		String value = org.toString();
		return map.entrySet().stream().filter(e -> value.equalsIgnoreCase(e.getValue())).map(Map.Entry::getKey)
				.sorted().findFirst();
	}

	/**
	 * Assign a host to an organization (architecture D6), global administrators only (the caller
	 * checks). Refused: a host that does not normalise, a host outside the allow-list that is not the
	 * instance host, a host assigned to another organization, an absent or archived organization, an
	 * organization that already holds another host (listed or not). Idempotent for the same pair.
	 */
	@Transactional
	public TeaHostAssignment assign(String rawHost, UUID org, UserData admin) throws RelizaException {
		String host = normalizeHost(rawHost).orElseThrow(() -> new RelizaException(NOT_A_BARE_HOST));
		if (!isAssignable(host)) {
			throw new RelizaException("Host " + host + " is not in the TEA custom domain allow-list "
					+ "(relizaprops.teaCustomDomains) and is not the instance host");
		}
		if (null == org) throw new RelizaException(ORGANIZATION_NOT_FOUND);
		Map<String, String> map = lockedAssignments();
		String holder = map.get(host);
		if (null != holder) {
			if (org.toString().equalsIgnoreCase(holder)) return row(host, holder);
			throw new RelizaException("Host " + host + " is already assigned to organization " + nameOf(host, holder));
		}
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(org);
		if (od.isEmpty() || StatusEnum.ARCHIVED == od.get().getStatus()) throw new RelizaException(ORGANIZATION_NOT_FOUND);
		Optional<String> other = rawHostOfOrg(map, org);
		if (other.isPresent() && !host.equals(other.get())) {
			throw new RelizaException("Organization " + od.get().getName() + " already holds host " + other.get()
					+ "; unassign it first");
		}
		systemInfoService.assignTeaHost(host, org);
		log.info("TEA host {} assigned to organization {} by {}", host, org, null == admin ? null : admin.getUuid());
		return row(host, org.toString());
	}

	/**
	 * Clear a host's assignment (architecture D6), global administrators only (the caller checks).
	 * Refused while the holder is live and its stored mode is DOMAIN (the invariant "DOMAIN implies an
	 * assigned host" is kept by refusing, not by writing the organization); a holder that is archived
	 * or gone does not hold the host. The holder's status and mode are read after the lock, from the
	 * stored row.
	 */
	@Transactional
	public boolean unassign(String rawHost, UserData admin) throws RelizaException {
		Optional<String> h = normalizeHost(rawHost);
		String shown = h.orElse(null == rawHost ? "" : rawHost.trim());
		if (h.isEmpty()) throw new RelizaException("Host " + shown + " is not assigned");
		String host = h.get();
		Map<String, String> map = lockedAssignments();
		if (!map.containsKey(host)) throw new RelizaException("Host " + host + " is not assigned");
		Optional<UUID> holder = parseOrg(host, map.get(host));
		if (holder.isPresent()) {
			Optional<String> status = organizationRepository.currentStatusForUpdate(holder.get());
			Optional<String> serving = organizationRepository.currentTeaServingForUpdate(holder.get());
			boolean archived = status.isPresent() && StatusEnum.ARCHIVED.name().equals(status.get());
			if (!archived && serving.isPresent() && TeaServing.DOMAIN.name().equals(serving.get())) {
				throw new RelizaException("Organization " + nameOf(host, map.get(host)) + " serves TEA on " + host
						+ "; set its TEA serving to NONE or LEGACY_UUID first");
			}
		}
		systemInfoService.unassignTeaHost(host);
		log.info("TEA host {} unassigned from organization {} by {}", host, map.get(host), null == admin ? null : admin.getUuid());
		return true;
	}

	/** The holder's name, which the global administrator may read; the stored value when the row is gone. */
	private String nameOf(String host, String holder) {
		return parseOrg(host, holder).flatMap(getOrganizationService::getOrganizationData).map(OrganizationData::getName)
				.orElse(holder);
	}

	/** The listed custom hosts (tests and the list). */
	Set<String> customHosts() {
		return customHosts;
	}

	/** The baseuri host, when assignable. */
	public Optional<String> baseuriHost() {
		return baseuriHost;
	}

	/** Every assignable host, custom hosts sorted then the baseuri host. */
	Set<String> assignableHosts() {
		Set<String> out = new LinkedHashSet<>(customHosts);
		baseuriHost.ifPresent(out::add);
		return out;
	}
}
