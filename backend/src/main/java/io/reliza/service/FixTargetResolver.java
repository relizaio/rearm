/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.packageurl.PackageURL;

import io.reliza.common.Utils;
import io.reliza.common.VersRanges;
import io.reliza.dto.ComponentFixTargets;
import io.reliza.dto.ComponentFixTargets.FixTarget;
import io.reliza.dto.FixedIn;
import io.reliza.dto.FixedIn.FixedInVerdict;
import io.reliza.dto.LatestFixVerdict;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;

/**
 * Which of the fix versions a component's advisories name fixes which of its
 * findings. Each candidate is placed in every finding's ranges the way
 * {@link FixedInResolver} places the component's own version, per
 * distribution release included, so a candidate fixes a finding when it is
 * out of that finding's ranges. Pure: the caller loads the records.
 *
 * <p>Only findings whose ranges contain the component's own version count;
 * one with no range data, or matched by another rule, is fixed by no
 * candidate as far as this can tell, and so is one with a range that cannot
 * be read (a candidate inside it would read as out of it).
 */
public final class FixTargetResolver {

	/**
	 * The most placements one component may cost, its candidates times its
	 * findings; past it the component gets no targets rather than a slow read.
	 */
	static final int MAX_PLACEMENTS = 20_000;

	/** The most placements one metrics object may cost; the components past it get no targets. */
	static final int MAX_PLACEMENTS_PER_METRICS = 100_000;

	/**
	 * The most placements one request may cost across its metrics objects (a
	 * list of releases selecting the field); past it they get no targets.
	 */
	public static final int MAX_PLACEMENTS_PER_REQUEST = 200_000;

	private static final Set<FixedInVerdict> IN_RANGE = EnumSet.of(
			FixedInVerdict.FIXED_IN, FixedInVerdict.FIXED_AFTER, FixedInVerdict.NO_FIX_AVAILABLE);

	private FixTargetResolver() {}

	/** A finding of the component: its vulnerability and the affected ranges on its record. */
	public record ComponentFinding(String vulnId, List<AffectedRange> ranges) {}

	/** The vulnerability ids of the metrics' findings, by the package URL each finding carries, as it carries it. */
	public static Map<String, Set<String>> vulnIdsByPurl(ReleaseMetricsDto metrics) {
		return vulnIdsByPurlOfRows(metrics == null ? null : metrics.getVulnerabilityDetails());
	}

	/** The same over any set of finding rows. */
	public static Map<String, Set<String>> vulnIdsByPurlOfRows(List<VulnerabilityDto> rows) {
		Map<String, Set<String>> idsByPurl = new LinkedHashMap<>();
		if (rows == null) return idsByPurl;
		for (VulnerabilityDto v : rows) {
			if (v == null || v.vulnId() == null || !Utils.isPurl(v.purl())) continue;
			idsByPurl.computeIfAbsent(v.purl(), k -> new LinkedHashSet<>()).add(v.vulnId());
		}
		return idsByPurl;
	}

	/** The fix targets of a metrics object's components, and the placements they cost. */
	public record AllTargets(List<ComponentFixTargets> targets, int placements) {}

	/**
	 * The fix targets of every component of {@code idsByPurl}, leaving out the
	 * components with none and those past the budget: the components later in
	 * the findings' order, not the costliest.
	 *
	 * @param rangesById the affected ranges of each vulnerability's record; absent or null for none
	 * @param budget     the most placements to spend, at most {@link #MAX_PLACEMENTS_PER_METRICS}
	 */
	public static AllTargets resolveAll(Map<String, Set<String>> idsByPurl,
			Map<String, List<AffectedRange>> rangesById, int budget) {
		List<ComponentFixTargets> out = new ArrayList<>();
		int left = Math.min(budget, MAX_PLACEMENTS_PER_METRICS);
		for (Map.Entry<String, Set<String>> e : idsByPurl.entrySet()) {
			List<ComponentFinding> findings = e.getValue().stream()
					.map(id -> new ComponentFinding(id, rangesById.get(id))).toList();
			Resolved r = resolve(e.getKey(), findings, Math.max(0, Math.min(left, MAX_PLACEMENTS)));
			left -= r.placements();
			if (!r.targets().targets().isEmpty()) out.add(r.targets());
		}
		return new AllTargets(List.copyOf(out), Math.min(budget, MAX_PLACEMENTS_PER_METRICS) - left);
	}

	/**
	 * @param purl     the component's package URL, with its version
	 * @param findings the component's findings
	 */
	public static ComponentFixTargets resolve(String purl, List<ComponentFinding> findings) {
		return resolve(purl, findings, MAX_PLACEMENTS).targets();
	}

	private record Resolved(ComponentFixTargets targets, int placements) {}

	private static Resolved resolve(String purl, List<ComponentFinding> findings, int maxPlacements) {
		PackageURL component = Utils.parsePurlOrNull(purl);
		String coordinate = Utils.purlCoordinateBase(purl);
		String major = component == null ? null : VersRanges.majorOf(component.getVersion());
		if (component == null || coordinate == null) return new Resolved(new ComponentFixTargets(purl, major, List.of()), 0);
		// the findings the component's own version is affected by, with only the
		// component's own rows, and the fixes they name
		Map<String, List<AffectedRange>> fixable = new LinkedHashMap<>();
		Set<String> candidates = new LinkedHashSet<>();
		for (ComponentFinding f : findings) {
			if (f == null || f.vulnId() == null || fixable.containsKey(f.vulnId()) || f.ranges() == null) continue;
			List<AffectedRange> own = f.ranges().stream()
					.filter(r -> r != null && coordinate.equals(Utils.purlCoordinateBase(r.getIdentity())))
					.toList();
			FixedIn now = FixedInResolver.resolve(purl, own, f.vulnId());
			if (!IN_RANGE.contains(now.verdict())) continue;
			if (now.verdict() == FixedInVerdict.FIXED_IN) candidates.add(now.version());
			// only the rows a candidate is placed against (the finding's own release's) must read
			List<AffectedRange> placed = own.stream().filter(r -> now.identities().contains(r.getIdentity())).toList();
			if (VersRanges.boundsReadable(purl, placed)) fixable.put(f.vulnId(), own);
		}
		long placements = (long) candidates.size() * fixable.size();
		if (candidates.isEmpty() || fixable.isEmpty() || placements > maxPlacements) {
			return new Resolved(new ComponentFixTargets(purl, major, List.of()), 0);
		}
		List<FixTarget> targets = new ArrayList<>();
		for (String candidate : VersRanges.ascending(purl, List.copyOf(candidates)).orElse(List.of())) {
			String at = withVersion(component, candidate);
			if (at == null) continue;
			List<String> fixes = fixable.entrySet().stream()
					.filter(e -> FixedInResolver.resolve(at, e.getValue(), e.getKey()).verdict()
							== FixedInVerdict.NOT_IN_ADVISORY_RANGE)
					.map(Map.Entry::getKey)
					.toList();
			if (!fixes.isEmpty()) {
				targets.add(new FixTarget(candidate, major != null && major.equals(VersRanges.majorOf(candidate)), fixes));
			}
		}
		return new Resolved(new ComponentFixTargets(purl, major, List.copyOf(targets)), (int) placements);
	}

	/**
	 * Whether moving the component at {@code purl} to {@code version} fixes the finding, by the
	 * rules the fix targets follow: the finding's ranges (only the component's own rows, per
	 * distribution release included) must contain the component's version and be readable, and
	 * {@code version} fixes it when it is out of them ({@link LatestFixVerdict}).
	 *
	 * @param ranges the affected ranges on the finding's record; null or empty for none
	 */
	public static LatestFixVerdict fixedAt(String purl, String vulnId, List<AffectedRange> ranges, String version) {
		PackageURL component = Utils.parsePurlOrNull(purl);
		String coordinate = Utils.purlCoordinateBase(purl);
		if (component == null || coordinate == null || component.getVersion() == null || version == null) {
			return LatestFixVerdict.UNCOMPARABLE;
		}
		if (ranges == null || ranges.isEmpty()) return LatestFixVerdict.NO_RANGE_DATA;
		List<String> order = VersRanges.ascending(purl, List.of(component.getVersion(), version)).orElse(null);
		if (order == null) return LatestFixVerdict.UNCOMPARABLE;
		if (order.size() != 2 || !version.equals(order.get(1))) return LatestFixVerdict.NOT_ABOVE_CURRENT;
		List<AffectedRange> own = ranges.stream()
				.filter(r -> r != null && coordinate.equals(Utils.purlCoordinateBase(r.getIdentity())))
				.toList();
		FixedIn now = FixedInResolver.resolve(purl, own, vulnId);
		switch (now.verdict()) {
		case NO_RANGE_DATA:
			return LatestFixVerdict.NO_RANGE_DATA;
		case NOT_IN_ADVISORY_RANGE:
			return LatestFixVerdict.NOT_IN_ADVISORY_RANGE;
		case UNCOMPARABLE:
			return LatestFixVerdict.UNCOMPARABLE;
		default:
			break;
		}
		List<AffectedRange> placed = own.stream().filter(r -> now.identities().contains(r.getIdentity())).toList();
		String at = withVersion(component, version);
		if (!VersRanges.boundsReadable(purl, placed) || at == null) return LatestFixVerdict.UNCOMPARABLE;
		FixedInVerdict then = FixedInResolver.resolve(at, own, vulnId).verdict();
		if (then == FixedInVerdict.NOT_IN_ADVISORY_RANGE) return LatestFixVerdict.FIXES;
		return IN_RANGE.contains(then) ? LatestFixVerdict.DOES_NOT_FIX : LatestFixVerdict.UNCOMPARABLE;
	}

	/** The component's package URL at {@code version}, qualifiers kept; null when it cannot be built. */
	private static String withVersion(PackageURL component, String version) {
		try {
			return Utils.setVersionOnPurl(component, version).canonicalize();
		} catch (RelizaException e) {
			return null;
		}
	}
}
