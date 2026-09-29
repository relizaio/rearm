/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.github.packageurl.PackageURL;

import io.reliza.common.Utils;
import io.reliza.common.VersRanges;
import io.reliza.common.VersRanges.Placement;
import io.reliza.dto.FixedIn;
import io.reliza.dto.FixedIn.FixedInVerdict;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;

/**
 * Which version fixes a finding, from the affected ranges on the finding's
 * vulnerability record. Pure: the caller loads the record, this class only
 * compares.
 *
 * <p>The finding's package is its package URL without version, qualifiers
 * and subpath, and the ranges of every identity that names the same package
 * count, the way Dependency-Track matched the finding against them. CPE
 * identities never match a package URL finding.
 *
 * <p>Except for distribution releases. Debian's advisories give one row per
 * release ({@code ?distro=bookworm}, {@code ?distro=trixie}), each with that
 * release's own fix, and a package never moves to another release's version.
 * So when the package's rows name releases, only the rows of the finding's
 * own release count, with the rows that name none. A finding whose release
 * the advisory does not list gets no answer, and one whose package URL names
 * no release gets one only when every release gives the same: both are
 * {@link FixedInVerdict#UNCOMPARABLE}.
 */
public final class FixedInResolver {

	private static final String DISTRO_QUALIFIER = "distro";
	private static final String DEBIAN_NAMESPACE = "debian";

	/**
	 * Debian release numbers, as SBOM tools write them ({@code distro=debian-12},
	 * {@code distro=debian-12.5}), to the codenames Debian's advisories use. A
	 * new Debian release, about every two years, needs its line here; until then
	 * its findings read as naming a release the advisory does not list.
	 */
	private static final Map<String, String> DEBIAN_CODENAMES = Map.of(
			"9", "stretch", "10", "buster", "11", "bullseye", "12", "bookworm",
			"13", "trixie", "14", "forky", "15", "duke");

	private FixedInResolver() {}

	/**
	 * @param findingPurl the finding's package URL, with its version
	 * @param ranges      the record's affected ranges; null or empty when never fetched or none published
	 * @param vulnId      for the log line when a range is left out
	 */
	public static FixedIn resolve(String findingPurl, List<AffectedRange> ranges, String vulnId) {
		PackageURL finding = Utils.parsePurlOrNull(findingPurl);
		String coordinate = Utils.purlCoordinateBase(findingPurl);
		if (finding == null || coordinate == null || ranges == null || ranges.isEmpty()) {
			return FixedIn.of(FixedInVerdict.NO_RANGE_DATA);
		}
		// the package's rows by the release they name; the rows that name none apart
		Map<String, List<AffectedRange>> byRelease = new LinkedHashMap<>();
		List<AffectedRange> general = new ArrayList<>();
		for (AffectedRange r : ranges) {
			if (r == null || !coordinate.equals(Utils.purlCoordinateBase(r.getIdentity()))) continue;
			String release = releaseOf(Utils.parsePurlOrNull(r.getIdentity()));
			if (release == null) general.add(r);
			else byRelease.computeIfAbsent(release, k -> new ArrayList<>()).add(r);
		}
		if (byRelease.isEmpty()) {
			return general.isEmpty() ? FixedIn.of(FixedInVerdict.NO_RANGE_DATA) : fixedIn(findingPurl, general, vulnId);
		}
		String release = releaseOf(finding);
		if (release != null) {
			List<AffectedRange> own = byRelease.get(release);
			return own == null ? FixedIn.of(FixedInVerdict.UNCOMPARABLE) : fixedIn(findingPurl, with(own, general), vulnId);
		}
		// no release named: only an answer every release gives
		FixedIn agreed = null;
		Set<UpstreamSource> sources = EnumSet.noneOf(UpstreamSource.class);
		Set<String> identities = new LinkedHashSet<>();
		for (List<AffectedRange> rows : byRelease.values()) {
			FixedIn f = fixedIn(findingPurl, with(rows, general), vulnId);
			if (agreed != null && !sameAnswer(agreed, f)) return FixedIn.of(FixedInVerdict.UNCOMPARABLE);
			agreed = f;
			sources.addAll(f.sources());
			identities.addAll(f.identities());
		}
		return new FixedIn(agreed.version(), agreed.verdict(), agreed.versionEndIncluding(), List.copyOf(sources),
				List.copyOf(identities));
	}

	private static FixedIn fixedIn(String findingPurl, List<AffectedRange> ranges, String vulnId) {
		Placement p = VersRanges.place(findingPurl, ranges, vulnId);
		List<String> identities = ranges.stream().map(AffectedRange::getIdentity).distinct().toList();
		return switch (p.position()) {
			case ENDS_BEFORE -> new FixedIn(p.bound(), FixedInVerdict.FIXED_IN, null, p.sources(), identities);
			case ENDS_AT -> new FixedIn(null, FixedInVerdict.FIXED_AFTER, p.bound(), p.sources(), identities);
			case UNBOUNDED -> new FixedIn(null, FixedInVerdict.NO_FIX_AVAILABLE, null, p.sources(), identities);
			case OUTSIDE -> new FixedIn(null, FixedInVerdict.NOT_IN_ADVISORY_RANGE, null, List.of(), identities);
			case UNPLACEABLE -> FixedIn.of(FixedInVerdict.UNCOMPARABLE);
		};
	}

	private static List<AffectedRange> with(List<AffectedRange> rows, List<AffectedRange> general) {
		if (general.isEmpty()) return rows;
		List<AffectedRange> out = new ArrayList<>(rows);
		out.addAll(general);
		return out;
	}

	private static boolean sameAnswer(FixedIn a, FixedIn b) {
		return a.verdict() == b.verdict() && Objects.equals(a.version(), b.version())
				&& Objects.equals(a.versionEndIncluding(), b.versionEndIncluding());
	}

	/**
	 * The distribution release a package URL names in its {@code distro}
	 * qualifier, lower-cased; for Debian without a {@code debian-} prefix and
	 * with a release number turned into its codename. Null when it names none.
	 */
	static String releaseOf(PackageURL purl) {
		if (purl == null || purl.getQualifiers() == null) return null;
		String distro = StringUtils.trimToNull(purl.getQualifiers().get(DISTRO_QUALIFIER));
		if (distro == null) return null;
		distro = distro.toLowerCase(Locale.ROOT);
		if (!DEBIAN_NAMESPACE.equals(purl.getNamespace())) return distro;
		String release = StringUtils.trimToNull(StringUtils.removeStart(distro, DEBIAN_NAMESPACE + "-"));
		if (release == null) return null;
		String major = StringUtils.substringBefore(release, ".");
		return StringUtils.isNumeric(major) ? DEBIAN_CODENAMES.getOrDefault(major, release) : release;
	}
}
