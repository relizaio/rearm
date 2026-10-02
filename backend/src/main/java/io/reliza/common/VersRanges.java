/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import com.github.packageurl.PackageURL;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.Semver.SemverType;

import io.github.nscuro.versatile.Comparator;
import io.github.nscuro.versatile.Constraint;
import io.github.nscuro.versatile.Vers;
import io.github.nscuro.versatile.VersException;
import io.github.nscuro.versatile.VersionFactory;
import io.github.nscuro.versatile.spi.InvalidVersionException;
import io.github.nscuro.versatile.spi.Version;
import io.github.nscuro.versatile.spi.VersionProvider;
import io.github.nscuro.versatile.version.KnownVersioningSchemes;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.versioning.VersionUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * vers ranges (package-url vers-spec) built from the affected ranges stored
 * on a vulnerability record, and where a version falls in them. This is the
 * only class that uses versatile ({@code io.github.nscuro:versatile-core}),
 * the vers implementation Dependency-Track 4 and 5 decide "affected" with,
 * so versions are ordered here as Dependency-Track ordered them. versatile is
 * 0.x and often changes its API: keep every use of it in this class.
 *
 * <p>Nothing is stored. The record keeps the source's own bounds, and vers is
 * derived from them on read. The ranges of one package become one vers
 * range, their union, ordered by the scheme the package URL type selects. A
 * range with a bound that is not a version under that scheme is left out on
 * its own; the other ranges still count. A comparison that fails (several
 * orderings throw on a number past {@code 2^31}) leaves the package without
 * a placement and without a vers line, and is logged at ERROR once per
 * scheme, vulnerability and package.
 *
 * <p>The {@code generic} ordering (Dependency-Track's, quirks included) reads a
 * leading {@code v} as text, so {@code v9} sorts above {@code v10}: under that
 * scheme only, a {@code v} before a digit is dropped from versions and bounds
 * before they are compared (a vers line shows {@code 1.2} for {@code v1.2}).
 * Past the numbers a version starts with, generic reads it its own way: it
 * compares a part with a letter as text ({@code 8u92} above {@code 8u212}),
 * drops words ({@code 1.0.0-edge} equals {@code 1.0.0}), puts a pre-release
 * above its release, and tells {@code 4} from {@code 4.0.0}. Those quirks are
 * refused rather than reproduced: a version is placed only when every two of
 * it and the bounds are told apart by those leading numbers, or are the same
 * numbers with nothing after ({@code 1.0.0-rc1} is placed against
 * {@code 2.0.0}, not against {@code 1.0.0}). Other schemes are compared as
 * they are.
 *
 * <p>A vers line spells versions as versatile writes them (composer
 * {@code 3.0.0.0} for {@code 3.0.0}); a fix version keeps the source's
 * spelling.
 *
 * <p>A version or bound longer than {@value #MAX_VERSION_LENGTH} characters
 * is not read: the pypi, composer and gem orderings parse recursively and
 * overflow the stack on a few thousand characters, and a version comes from
 * a user's SBOM.
 *
 * <p>versatile has no ordering for the vers scheme {@code semver} and would
 * fall back to {@code generic}, which puts {@code 1.0.0-rc.1} above
 * {@code 1.0.0}. {@link SemverVersionProvider} gives it the Semantic
 * Versioning 2.0.0 order; package URL types never select it (they map to their
 * ecosystems' own schemes), a product's own release versions do (see
 * {@link ReleaseScheme}).
 *
 * <p>Public methods never throw.
 */
@Slf4j
public final class VersRanges {

	/** Schemes versatile ships an ordering for; any other package is ordered as {@code generic}. */
	private static final Set<String> BUILT_IN_SCHEMES = Set.of(
			KnownVersioningSchemes.SCHEME_APK, KnownVersioningSchemes.SCHEME_CARGO,
			KnownVersioningSchemes.SCHEME_COMPOSER, KnownVersioningSchemes.SCHEME_DEBIAN,
			KnownVersioningSchemes.SCHEME_GEM, KnownVersioningSchemes.SCHEME_GENERIC,
			KnownVersioningSchemes.SCHEME_GOLANG, KnownVersioningSchemes.SCHEME_MAVEN,
			KnownVersioningSchemes.SCHEME_NPM, KnownVersioningSchemes.SCHEME_NUGET,
			KnownVersioningSchemes.SCHEME_PYPI, KnownVersioningSchemes.SCHEME_RPM);

	/** The vers scheme of Semantic Versioning; versatile names no constant for it, having no ordering of its own. */
	static final String SCHEME_SEMVER = "semver";

	/**
	 * A Semantic Versioning 2.0.0 version, the expression semver.org gives:
	 * no {@code v}, three numbers without leading zeros, an optional
	 * pre-release and build.
	 */
	private static final Pattern SEMVER_2 = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
			+ "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
			+ "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

	/** Schemes whose package URLs may carry the epoch in a qualifier rather than in the version. */
	private static final Set<String> EPOCH_SCHEMES = Set.of(
			KnownVersioningSchemes.SCHEME_RPM, KnownVersioningSchemes.SCHEME_DEBIAN);

	private static final String EPOCH_QUALIFIER = "epoch";

	/** The longest version or bound that is read; real ones are far shorter. */
	static final int MAX_VERSION_LENGTH = 256;

	/** The most digits in a number {@code generic} compares as one: fewer than ten, so below {@code 2^31}. */
	private static final int MAX_NUMBER_DIGITS = 9;

	/**
	 * How many distinct failures are logged at ERROR per process, each once.
	 * Enough to show a broken ordering; past it, one ERROR says so and the
	 * rest go to DEBUG.
	 */
	static final int FAILURES_LOGGED_LIMIT = 20;

	/** Failures already logged at ERROR. */
	private static final Set<FailureKey> failuresLogged = ConcurrentHashMap.newKeySet();

	/** Whether reaching {@link #FAILURES_LOGGED_LIMIT} has been logged. */
	private static final AtomicBoolean failureLimitLogged = new AtomicBoolean();

	/** A {@code v} before a digit, as in {@code v1.2.3}. */
	private static final Pattern LEADING_V = Pattern.compile("^[vV](?=\\d)");

	/*
	 * versatile looks up a scheme's ordering with ServiceLoader on the calling
	 * thread's context class loader, and caches it per scheme once found. Under
	 * the Spring Boot launcher that loader sees the application's jars only on
	 * threads the application started (the common fork-join pool's threads
	 * have the system class loader, which does not), so a first lookup on such
	 * a thread would find nothing. Look every scheme up once here, with this
	 * class's own loader.
	 */
	static {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(VersRanges.class.getClassLoader());
		try {
			for (String scheme : Stream.concat(BUILT_IN_SCHEMES.stream(), Stream.of(SCHEME_SEMVER)).toList()) {
				try {
					VersionFactory.forScheme(scheme, "1.0.0");
				} catch (IllegalArgumentException e) {
					// the scheme's ordering is cached before it parses the version
				}
			}
		} catch (RuntimeException e) {
			log.error("Could not load the vers version schemes", e);
		} finally {
			thread.setContextClassLoader(previous);
		}
	}

	private VersRanges() {}

	/**
	 * Where a version falls in the union of one package's ranges.
	 *
	 * @param position where it falls
	 * @param bound    the end of the range it falls in, for {@code ENDS_BEFORE} and {@code ENDS_AT}
	 * @param sources  sources whose ranges contain the version, in {@link UpstreamSource} order
	 */
	public record Placement(Position position, String bound, List<UpstreamSource> sources) {

		public enum Position {
			/**
			 * The version, or every bound, is not a version under the package's
			 * scheme; {@code generic} would misorder it; or a comparison failed.
			 */
			UNPLACEABLE,
			/** In none of the ranges. */
			OUTSIDE,
			/** In a range that ends before {@code bound}, the first version outside it. */
			ENDS_BEFORE,
			/** In a range whose last version is {@code bound}; an exact version ends at itself. */
			ENDS_AT,
			/** In a range with no end. */
			UNBOUNDED
		}

		static Placement of(Position position) {
			return new Placement(position, null, List.of());
		}
	}

	/**
	 * The vers scheme for a package URL type: {@code generic} when versatile
	 * has no ordering of its own for it, which is also how versatile would
	 * order it (and asking versatile for such a scheme rescans its providers on
	 * every call).
	 */
	static String schemeFor(String purlType) {
		return KnownVersioningSchemes.fromPurlType(purlType).filter(BUILT_IN_SCHEMES::contains)
				.orElse(KnownVersioningSchemes.SCHEME_GENERIC);
	}

	/**
	 * One vers range per package URL identity, in the order the identities
	 * first appear. Ranges are grouped by their identity as stored,
	 * qualifiers included, so Debian's per-release rows stay apart. CPE
	 * identities have no vers scheme and are skipped, as is an identity none
	 * of whose ranges yields an interval.
	 *
	 * @param vulnId for the log line when a range is left out
	 */
	public static Map<String, String> versByIdentity(List<AffectedRange> ranges, String vulnId) {
		Map<String, List<AffectedRange>> byIdentity = new LinkedHashMap<>();
		if (ranges != null) {
			for (AffectedRange r : ranges) {
				if (r == null || !Utils.isPurl(r.getIdentity())) continue;
				byIdentity.computeIfAbsent(r.getIdentity(), k -> new ArrayList<>()).add(r);
			}
		}
		Map<String, String> out = new LinkedHashMap<>();
		byIdentity.forEach((identity, group) ->
				toVersString(identity, group, vulnId).ifPresent(vers -> out.put(identity, vers)));
		return out;
	}

	/**
	 * The union of the ranges of the package {@code purl} names, as a
	 * canonical vers string such as {@code vers:pypi/>=1.8|<1.8.19|>=1.11|<1.11.11},
	 * or {@code vers:deb/*} when every version is affected. Empty when no range
	 * yields an interval, {@code generic} would misorder the bounds, or
	 * {@code purl} is not a package URL.
	 */
	static Optional<String> toVersString(String purl, List<AffectedRange> sameIdentityRanges, String vulnId) {
		PackageURL p = Utils.parsePurlOrNull(purl);
		if (p == null) return Optional.empty();
		String scheme = schemeFor(p.getType());
		try {
			if (genericMisorders(scheme, null, sameIdentityRanges)) return Optional.empty();
			return toVers(scheme, intervalsOf(scheme, sameIdentityRanges, vulnId)).map(Vers::toString);
		} catch (RuntimeException e) {
			logFailure("Could not build a vers range", scheme, vulnId, purl, e);
			return Optional.empty();
		}
	}

	/**
	 * Where the version of {@code purl} falls in the union of the ranges of
	 * its package: the interval that contains it gives the position, and its
	 * upper bound the bound. An rpm or deb epoch given as a qualifier counts
	 * as part of the version.
	 *
	 * @param purl   the package URL, with its version
	 * @param vulnId for the log line when a range is left out
	 */
	public static Placement place(String purl, List<AffectedRange> sameIdentityRanges, String vulnId) {
		PackageURL p = Utils.parsePurlOrNull(purl);
		String version = p == null ? null : versionOf(p);
		if (version == null) return Placement.of(Placement.Position.UNPLACEABLE);
		String scheme = schemeFor(p.getType());
		try {
			Version tested;
			try {
				tested = read(scheme, version);
			} catch (IllegalArgumentException | VersException e) {
				return Placement.of(Placement.Position.UNPLACEABLE);
			}
			// after read, which refuses a version too long to check
			if (genericMisorders(scheme, version, sameIdentityRanges)) return Placement.of(Placement.Position.UNPLACEABLE);
			version = comparable(scheme, version);
			List<Interval> rows = intervalsOf(scheme, sameIdentityRanges, vulnId);
			Optional<Vers> vers = toVers(scheme, rows);
			if (vers.isEmpty()) return Placement.of(Placement.Position.UNPLACEABLE);
			if (!vers.get().contains(version)) return Placement.of(Placement.Position.OUTSIDE);
			for (Vers interval : vers.get().split()) {
				if (!interval.contains(version)) continue;
				List<UpstreamSource> sources = sourcesContaining(rows, tested);
				Constraint end = interval.constraints().getLast();
				String written = Objects.toString(end.version());
				return switch (end.comparator()) {
					case LESS_THAN -> new Placement(Placement.Position.ENDS_BEFORE,
							asWritten(scheme, written, false, sameIdentityRanges), sources);
					// an exact version is a range that ends at itself
					case LESS_THAN_OR_EQUAL, EQUAL -> new Placement(Placement.Position.ENDS_AT,
							asWritten(scheme, written, true, sameIdentityRanges), sources);
					case GREATER_THAN, GREATER_THAN_OR_EQUAL, WILDCARD, NOT_EQUAL ->
						new Placement(Placement.Position.UNBOUNDED, null, sources);
				};
			}
			// not reached: split() covers exactly the versions the whole range contains
			return Placement.of(Placement.Position.OUTSIDE);
		} catch (RuntimeException e) {
			logFailure("Could not place version " + version, scheme, vulnId, purl, e);
			return Placement.of(Placement.Position.UNPLACEABLE);
		}
	}

	/**
	 * {@code versions} in ascending order under the scheme of the package
	 * {@code purl} names, one spelling of each (the first given) where two are
	 * the same version. Empty when one is not a version under that scheme, is
	 * too long to read, or, under {@code generic}, two are not ordered by their
	 * leading numbers (see the class comment).
	 */
	public static Optional<List<String>> ascending(String purl, List<String> versions) {
		PackageURL p = Utils.parsePurlOrNull(purl);
		if (p == null || versions == null || versions.stream().anyMatch(Objects::isNull)) return Optional.empty();
		String scheme = schemeFor(p.getType());
		if (KnownVersioningSchemes.SCHEME_GENERIC.equals(scheme) && !allOrderedByNumbers(scheme, versions)) return Optional.empty();
		Map<String, Version> read = new LinkedHashMap<>();
		try {
			for (String v : versions) read.put(v, read(scheme, v));
		} catch (IllegalArgumentException | VersException e) {
			return Optional.empty();
		}
		// sorted outside that catch: a comparison that fails is not an unreadable version
		try {
			List<String> sorted = new ArrayList<>(read.keySet());
			sorted.sort((a, b) -> read.get(a).compareTo(read.get(b)));
			List<String> out = new ArrayList<>();
			for (String v : sorted) {
				if (out.isEmpty() || read.get(out.getLast()).compareTo(read.get(v)) != 0) out.add(v);
			}
			return Optional.of(List.copyOf(out));
		} catch (RuntimeException e) {
			logFailure("Could not order versions", scheme, null, purl, e);
			return Optional.empty();
		}
	}

	/**
	 * Whether every range of the package {@code purl} names has a type and
	 * bounds that are versions under the package's scheme: when one does not,
	 * {@link #place} leaves it out, so a version it would contain can come out
	 * {@code OUTSIDE}.
	 */
	public static boolean boundsReadable(String purl, List<AffectedRange> sameIdentityRanges) {
		PackageURL p = Utils.parsePurlOrNull(purl);
		if (p == null) return false;
		if (sameIdentityRanges == null) return true;
		String scheme = schemeFor(p.getType());
		for (AffectedRange r : sameIdentityRanges) {
			if (r == null) continue;
			if (r.getRangeType() == null) return false;
			try {
				RangeBounds.of(scheme, r);
			} catch (IllegalArgumentException | VersException e) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The first number of a version, after a leading {@code v} and an epoch
	 * ({@code 1:9.2p1} is on 9), without leading zeros; null when it starts
	 * with no number.
	 */
	public static String majorOf(String version) {
		if (version == null) return null;
		String v = LEADING_V.matcher(version).replaceFirst("");
		int colon = v.indexOf(':');
		if (colon > 0 && v.substring(0, colon).chars().allMatch(c -> c >= '0' && c <= '9')) v = v.substring(colon + 1);
		int end = 0;
		while (end < v.length() && v.charAt(end) >= '0' && v.charAt(end) <= '9') end++;
		if (end == 0) return null;
		int start = 0;
		while (start < end - 1 && v.charAt(start) == '0') start++;
		return v.substring(start, end);
	}

	/** What a failure is logged once for. */
	record FailureKey(String scheme, String vulnId, String pkg) {}

	/**
	 * The vers scheme a product's own release versions are written in: its
	 * version schema decides, not the look of a version. Semantic Versioning
	 * when the schema is {@code semver} or shaped like it
	 * ({@code Major.Minor.Patch} with optional modifier and metadata, see
	 * {@link VersionUtils#isSchemaSemver}); {@code generic} for every other
	 * schema (calendar, four-part, branch-named). With no schema set, Semantic
	 * Versioning: a version that is not one is still written as
	 * {@code generic} (see {@link VersRanges#releaseVers}).
	 */
	public enum ReleaseScheme {
		SEMVER(SCHEME_SEMVER), GENERIC(KnownVersioningSchemes.SCHEME_GENERIC);

		private final String scheme;

		ReleaseScheme(String scheme) {
			this.scheme = scheme;
		}

		public static ReleaseScheme ofVersionSchema(String versionSchema) {
			return StringUtils.isBlank(versionSchema) || VersionUtils.isSchemaSemver(versionSchema) ? SEMVER : GENERIC;
		}
	}

	/**
	 * One release version as a vers line, as {@code vers:semver/1.2.3}.
	 * {@code generic} when the scheme is that, or when the version is not one
	 * under it (a feature branch's {@code feature-x.0} of a semver product).
	 * Never null for a non-null version.
	 */
	public static String releaseVers(ReleaseScheme scheme, String version) {
		if (version == null) return null;
		String s = readable(scheme.scheme, version) ? scheme.scheme : KnownVersioningSchemes.SCHEME_GENERIC;
		try {
			return Vers.builder(s).withConstraint(Comparator.EQUAL, version).build().toString();
		} catch (RuntimeException e) {
			// generic reads every version; this is the spelling the line had before versatile wrote it
			return "vers:" + s + "/" + version;
		}
	}

	/**
	 * A product's release versions in the order of its {@link ReleaseScheme},
	 * read and sorted once, to name runs of them as vers ranges. Versions that
	 * are not ones under the scheme cannot be inside a range in it; one that
	 * starts with numbers still keeps a range from forming around them.
	 *
	 * @param allVersions every release version of the product
	 */
	public static ReleaseOrder releaseOrder(ReleaseScheme scheme, Collection<String> allVersions) {
		String s = scheme.scheme;
		List<ReadVersion> sorted = new ArrayList<>();
		List<Version> numberedOutsiders = new ArrayList<>();
		boolean numbersOnly = true;
		if (allVersions != null) {
			for (String v : allVersions.stream().filter(Objects::nonNull).distinct().toList()) {
				NumericStart start = NumericStart.of(LEADING_V.matcher(v).replaceFirst(""));
				try {
					sorted.add(new ReadVersion(v, read(s, v)));
				} catch (IllegalArgumentException | VersException e) {
					Version asNumbers = start == null ? null : leadingNumbers(s, start);
					if (asNumbers != null) numberedOutsiders.add(asNumbers);
					continue;
				}
				if (NumericStart.of(comparable(s, v)) == null) numbersOnly = false;
			}
		}
		try {
			sorted.sort((a, b) -> a.version().compareTo(b.version()));
		} catch (RuntimeException e) {
			logFailure("Could not order release versions", s, null, null, e);
			return new ReleaseOrder(scheme, List.of(), List.of(), false);
		}
		boolean usable = numbersOnly || scheme != ReleaseScheme.GENERIC;
		return new ReleaseOrder(scheme, List.copyOf(sorted), List.copyOf(numberedOutsiders), usable);
	}

	/**
	 * A version that is not one under the scheme, but starts with numbers
	 * ({@code v1.2.5}, {@code 1.2.5.1} of a semver product), as the version
	 * those numbers make ({@code 1.2.5}, three of them, a missing one 0). A
	 * consumer that reads versions leniently could place it there, so a range
	 * around it is not written. Null when the numbers are no version either.
	 */
	private static Version leadingNumbers(String scheme, NumericStart start) {
		List<String> numbers = new ArrayList<>(start.numbers().subList(0, Math.min(3, start.numbers().size())));
		while (numbers.size() < 3) numbers.add("0");
		try {
			return read(scheme, numbers.stream().map(n -> String.valueOf(Integer.parseInt(n))).reduce((a, b) -> a + "." + b).orElseThrow());
		} catch (IllegalArgumentException | VersException e) {
			return null;
		}
	}

	private record ReadVersion(String spelling, Version version) {}

	/** See {@link VersRanges#releaseOrder}. */
	public static final class ReleaseOrder {

		private final ReleaseScheme scheme;
		private final List<ReadVersion> sorted;
		/** Versions that are not ones under the scheme, as their leading numbers: see {@link VersRanges#leadingNumbers}. */
		private final List<Version> numberedOutsiders;
		/**
		 * False when the order cannot be trusted to name a run: it could not be
		 * sorted, or, under {@code generic}, a version does not start with
		 * numbers (see the class comment).
		 */
		private final boolean usable;

		private ReleaseOrder(ReleaseScheme scheme, List<ReadVersion> sorted, List<Version> numberedOutsiders, boolean usable) {
			this.scheme = scheme;
			this.sorted = sorted;
			this.numberedOutsiders = numberedOutsiders;
			this.usable = usable;
		}

		/**
		 * {@code versions} as one vers range, {@code >=lowest|<=highest}, when
		 * they are a run of the product's versions: no other falls between the
		 * lowest and the highest, so the range names these and no other
		 * release. Empty when there are fewer than two, one is not a version
		 * under the scheme, another release that is not one under the scheme
		 * starts with numbers inside the range ({@code v1.2.5} between
		 * {@code 1.2.3} and {@code 1.2.6}), or, under {@code generic}, two of
		 * them or a neighbour of the run are not ordered by their leading
		 * numbers.
		 */
		public Optional<String> rangeOf(Collection<String> versions) {
			if (!usable || versions == null || versions.stream().anyMatch(Objects::isNull)) return Optional.empty();
			Set<String> named = new LinkedHashSet<>(versions);
			if (named.size() < 2) return Optional.empty();
			String s = scheme.scheme;
			try {
				Version lowest = null;
				Version highest = null;
				String lowestSpelling = null;
				String highestSpelling = null;
				for (String v : named) {
					Version read = read(s, v);
					if (lowest == null || read.compareTo(lowest) < 0) {
						lowest = read;
						lowestSpelling = v;
					}
					if (highest == null || read.compareTo(highest) > 0) {
						highest = read;
						highestSpelling = v;
					}
				}
				// builds of one version (1.0.0+b1, 1.0.0+b2): a range names a version once
				if (lowest.compareTo(highest) == 0) return Optional.empty();
				int lo = firstAtLeast(lowest);
				int hi = firstAbove(highest) - 1;
				for (int i = lo; i <= hi; i++) {
					if (!named.contains(sorted.get(i).spelling())) return Optional.empty();
				}
				for (Version outsider : numberedOutsiders) {
					if (outsider.compareTo(lowest) >= 0 && outsider.compareTo(highest) <= 0) return Optional.empty();
				}
				if (ReleaseScheme.GENERIC == scheme) {
					List<String> compared = new ArrayList<>(named);
					if (lo > 0) compared.add(sorted.get(lo - 1).spelling());
					if (hi + 1 < sorted.size()) compared.add(sorted.get(hi + 1).spelling());
					if (!allOrderedByNumbers(s, compared)) return Optional.empty();
				}
				String range = Vers.builder(s)
						.withConstraint(Comparator.GREATER_THAN_OR_EQUAL, comparable(s, lowestSpelling))
						.withConstraint(Comparator.LESS_THAN_OR_EQUAL, comparable(s, highestSpelling))
						.build().toString();
				// the builder does not validate; only a line that parses back is written
				Vers.parse(range);
				return Optional.of(range);
			} catch (IllegalArgumentException | VersException e) {
				return Optional.empty();
			} catch (RuntimeException e) {
				logFailure("Could not build a release vers range", scheme.scheme, null, null, e);
				return Optional.empty();
			}
		}

		/** The index of the first version not below {@code v}. */
		private int firstAtLeast(Version v) {
			int lo = 0;
			int hi = sorted.size();
			while (lo < hi) {
				int mid = (lo + hi) >>> 1;
				if (sorted.get(mid).version().compareTo(v) < 0) lo = mid + 1;
				else hi = mid;
			}
			return lo;
		}

		/** The index of the first version above {@code v}. */
		private int firstAbove(Version v) {
			int lo = 0;
			int hi = sorted.size();
			while (lo < hi) {
				int mid = (lo + hi) >>> 1;
				if (sorted.get(mid).version().compareTo(v) <= 0) lo = mid + 1;
				else hi = mid;
			}
			return lo;
		}
	}

	/** Whether {@code version} is a version under the scheme. */
	private static boolean readable(String scheme, String version) {
		try {
			read(scheme, version);
			return true;
		} catch (IllegalArgumentException | VersException e) {
			return false;
		}
	}

	/**
	 * Logs a failure to build or place in a package's ranges: at ERROR the
	 * first time for its scheme, vulnerability and package, at DEBUG after
	 * that. The same data fails the same way on every read of its findings (a
	 * version can come from a crafted SBOM), and an ERROR each time, or each
	 * time window, would be an alert nobody can clear.
	 */
	private static void logFailure(String failure, String scheme, String vulnId, String purl, RuntimeException e) {
		String pkg = Utils.purlCoordinateBase(purl);
		if (firstFailure(new FailureKey(scheme, vulnId, pkg))) {
			log.error("{} of scheme {} in the ranges of {} for {}; repeats are logged at DEBUG", failure, scheme, vulnId, pkg, e);
		} else {
			log.debug("{} of scheme {} in the ranges of {} for {}: {}", failure, scheme, vulnId, pkg, e.toString());
		}
	}

	/**
	 * Whether a failure is the first with its key, while fewer than
	 * {@value #FAILURES_LOGGED_LIMIT} have been logged. The first new key past
	 * the limit logs that the rest go to DEBUG.
	 */
	static boolean firstFailure(FailureKey key) {
		if (failuresLogged.contains(key)) return false;
		if (failuresLogged.size() >= FAILURES_LOGGED_LIMIT) {
			if (failureLimitLogged.compareAndSet(false, true)) {
				log.error("{} distinct vers range failures logged since start; further ones are logged at DEBUG",
						FAILURES_LOGGED_LIMIT);
			}
			return false;
		}
		return failuresLogged.add(key);
	}

	/** Forgets the failures logged, for tests. */
	static void forgetLoggedFailures() {
		failuresLogged.clear();
		failureLimitLogged.set(false);
	}

	/** A version as versatile gets it: under {@code generic}, without a leading {@code v} before a digit. */
	private static String comparable(String scheme, String version) {
		if (version == null || !KnownVersioningSchemes.SCHEME_GENERIC.equals(scheme)) return version;
		return LEADING_V.matcher(version).replaceFirst("");
	}

	/**
	 * Whether, under {@code generic}, two of the version and the bounds of the
	 * ranges are ordered by more than their leading numbers (see the class
	 * comment). Ranges without bounds compare nothing, so there it does not
	 * matter; a bound too long to read is left out with its range.
	 *
	 * @param version the version to place, or null to check the bounds alone
	 */
	private static boolean genericMisorders(String scheme, String version, List<AffectedRange> ranges) {
		if (!KnownVersioningSchemes.SCHEME_GENERIC.equals(scheme)) return false;
		List<String> bounds = boundsOf(ranges).filter(b -> b.length() <= MAX_VERSION_LENGTH).toList();
		if (bounds.isEmpty()) return false;
		Stream<String> versions = version == null ? bounds.stream() : Stream.concat(Stream.of(version), bounds.stream());
		return !allOrderedByNumbers(scheme, versions.toList());
	}

	/** Whether {@code generic} orders every two of {@code versions} as their leading numbers do. */
	private static boolean allOrderedByNumbers(String scheme, List<String> versions) {
		List<NumericStart> compared = new ArrayList<>();
		for (String v : versions.stream().map(v -> comparable(scheme, v)).distinct().toList()) {
			NumericStart start = NumericStart.of(v);
			if (start == null) return false;
			compared.add(start);
		}
		for (int i = 0; i < compared.size(); i++) {
			for (int j = i + 1; j < compared.size(); j++) {
				if (!orderedByNumbers(compared.get(i), compared.get(j))) return false;
			}
		}
		return true;
	}

	/**
	 * The numbers a version starts with, as {@code 1.2.3} of {@code 1.2.3-rc1},
	 * and what follows them.
	 * {@code generic} reads a run of digits as one part and splits at anything
	 * else, so these numbers are its first parts: it drops an Ubuntu revision,
	 * but only from what follows them.
	 */
	private record NumericStart(List<String> numbers, String rest) {

		/**
		 * Null when {@code generic} would not compare the numbers as written:
		 * there are none ({@code main}), one is past {@code 2^31}, or text is
		 * glued on or follows other than a {@code -} or {@code _} ({@code 8u92},
		 * {@code 1.0rc1}, {@code 1.0.RELEASE}, {@code 1.0+5}, {@code 2.4~1}, an
		 * epoch {@code 1:2.0}).
		 */
		static NumericStart of(String version) {
			int end = 0;
			for (int i = 0; i < version.length(); i++) {
				char c = version.charAt(i);
				if (c >= '0' && c <= '9') {
					end = i + 1;
				} else if (c != '.' || i == 0 || end != i) {
					// a dot only between digits
					break;
				}
			}
			if (end == 0) return null;
			String rest = version.substring(end);
			if (!rest.isEmpty() && rest.charAt(0) != '-' && rest.charAt(0) != '_') return null;
			List<String> numbers = List.of(version.substring(0, end).split("\\."));
			if (numbers.stream().anyMatch(n -> n.length() > MAX_NUMBER_DIGITS)) return null;
			return new NumericStart(numbers, rest);
		}
	}

	/**
	 * Whether {@code generic} orders {@code a} and {@code b} as their leading
	 * numbers do: one of those numbers tells them apart, or they are the same
	 * numbers with nothing after, give or take the one trailing {@code 0}
	 * generic also ignores ({@code 1.0} and {@code 1.0.0}, not {@code 1.0.0.0}).
	 */
	private static boolean orderedByNumbers(NumericStart a, NumericStart b) {
		int shared = Math.min(a.numbers().size(), b.numbers().size());
		for (int i = 0; i < shared; i++) {
			if (Integer.parseInt(a.numbers().get(i)) != Integer.parseInt(b.numbers().get(i))) return true;
		}
		NumericStart shorter = a.numbers().size() <= b.numbers().size() ? a : b;
		NumericStart longer = shorter == a ? b : a;
		// 1.5-alpha.10 against 1.5.2, or 1.0.0-rc1 against 1.0.0: what follows decides
		if (!shorter.rest().isEmpty()) return false;
		List<String> more = longer.numbers().subList(shared, longer.numbers().size());
		// 1.0.1-rc1 against 1.0: a number decides
		if (more.stream().anyMatch(n -> Integer.parseInt(n) != 0)) return true;
		return longer.rest().isEmpty() && (more.isEmpty() || more.equals(List.of("0")));
	}

	/**
	 * An end bound as the source wrote it: versatile writes some versions its
	 * own way (composer {@code 3.0.0.0} for {@code 3.0.0}, pypi {@code 1.0rc1}
	 * for {@code 1.0-rc1}), and {@link #comparable} drops a {@code v}.
	 */
	private static String asWritten(String scheme, String bound, boolean inclusive, List<AffectedRange> ranges) {
		if (ranges == null) return bound;
		return ranges.stream()
				.filter(r -> r != null && r.getRangeType() != null)
				.map(r -> switch (r.getRangeType()) {
					case EXACT -> inclusive ? r.getExactVersion() : null;
					case RANGE -> inclusive ? r.getVersionEndIncluding() : r.getVersionEndExcluding();
				})
				.filter(b -> b != null && bound.equals(spelledByVersatile(scheme, b)))
				.findFirst().orElse(bound);
	}

	/** How versatile writes {@code version}; null when it is not a version under the scheme. */
	private static String spelledByVersatile(String scheme, String version) {
		try {
			return read(scheme, version).toString();
		} catch (IllegalArgumentException | VersException e) {
			return null;
		}
	}

	/** The bounds the readable ranges are built from, as the source wrote them. */
	private static Stream<String> boundsOf(List<AffectedRange> ranges) {
		if (ranges == null) return Stream.empty();
		return ranges.stream()
				.filter(r -> r != null && r.getRangeType() != null)
				.flatMap(r -> switch (r.getRangeType()) {
					case EXACT -> Stream.of(r.getExactVersion());
					case RANGE -> Stream.of(r.getVersionStartIncluding(), r.getVersionStartExcluding(),
							r.getVersionEndIncluding(), r.getVersionEndExcluding());
				})
				.filter(Objects::nonNull);
	}

	/** The version of a package URL, with an rpm or deb epoch qualifier folded in; null when it has none. */
	private static String versionOf(PackageURL p) {
		String version = StringUtils.trimToNull(p.getVersion());
		if (version == null || version.contains(":") || !EPOCH_SCHEMES.contains(schemeFor(p.getType()))) return version;
		String epoch = p.getQualifiers() == null ? null : StringUtils.trimToNull(p.getQualifiers().get(EPOCH_QUALIFIER));
		return epoch == null ? version : epoch + ":" + version;
	}

	/** One end of an interval. */
	private record Bound(Version version, boolean inclusive) {}

	/** One interval of versions; a null bound leaves that side open. */
	private record Interval(Bound lower, Bound upper, List<UpstreamSource> sources) {

		boolean contains(Version v) {
			if (lower != null) {
				int c = v.compareTo(lower.version());
				if (c < 0 || (c == 0 && !lower.inclusive())) return false;
			}
			if (upper != null) {
				int c = v.compareTo(upper.version());
				if (c > 0 || (c == 0 && !upper.inclusive())) return false;
			}
			return true;
		}

		boolean isEmpty() {
			if (lower == null || upper == null) return false;
			int c = lower.version().compareTo(upper.version());
			return c > 0 || (c == 0 && !(lower.inclusive() && upper.inclusive()));
		}
	}

	/**
	 * The intervals of the ranges one by one. A range that yields none, or
	 * has a bound that is not a version, is left out; a comparison that fails
	 * throws.
	 */
	private static List<Interval> intervalsOf(String scheme, List<AffectedRange> ranges, String vulnId) {
		List<Interval> out = new ArrayList<>();
		if (ranges == null) return out;
		for (AffectedRange r : ranges) {
			// a range without a type came with a version type nobody here can read
			if (r == null || r.getRangeType() == null) continue;
			RangeBounds bounds;
			try {
				bounds = RangeBounds.of(scheme, r);
			} catch (IllegalArgumentException | VersException e) {
				log.debug("Left an affected range of {} out of the vers range of {}: {}", r.getIdentity(), vulnId,
						e.getMessage());
				continue;
			}
			// compared outside the try: a comparison that fails is not an unreadable bound
			Interval i = intervalOf(r, bounds);
			if (i != null) out.add(i);
		}
		return out;
	}

	/** A range's bounds read as versions; null where the range has none. */
	private record RangeBounds(Bound exactVersion, Bound versionStartIncluding, Bound versionStartExcluding,
			Bound versionEndIncluding, Bound versionEndExcluding) {

		/** Throws {@link IllegalArgumentException} or {@link VersException} when a bound is not a version. */
		static RangeBounds of(String scheme, AffectedRange r) {
			return switch (r.getRangeType()) {
				case EXACT -> new RangeBounds(bound(scheme, r.getExactVersion(), true), null, null, null, null);
				case RANGE -> new RangeBounds(null,
						bound(scheme, r.getVersionStartIncluding(), true),
						bound(scheme, r.getVersionStartExcluding(), false),
						bound(scheme, r.getVersionEndIncluding(), true),
						bound(scheme, r.getVersionEndExcluding(), false));
			};
		}
	}

	private static Interval intervalOf(AffectedRange r, RangeBounds b) {
		List<UpstreamSource> sources = r.getSources() == null ? List.of() : r.getSources();
		return switch (r.getRangeType()) {
			case EXACT -> b.exactVersion() == null ? null : new Interval(b.exactVersion(), b.exactVersion(), sources);
			case RANGE -> {
				Interval i = new Interval(tighter(b.versionStartIncluding(), b.versionStartExcluding(), Side.LOWER),
						tighter(b.versionEndIncluding(), b.versionEndExcluding(), Side.UPPER), sources);
				yield i.isEmpty() ? null : i;
			}
		};
	}

	private static Bound bound(String scheme, String version, boolean inclusive) {
		return version == null ? null : new Bound(read(scheme, version), inclusive);
	}

	/**
	 * A version under the scheme; throws {@link IllegalArgumentException} or
	 * {@link VersException} when it is not one, or is too long to read.
	 */
	private static Version read(String scheme, String version) {
		if (version.length() > MAX_VERSION_LENGTH) {
			throw new IllegalArgumentException("a version of " + version.length() + " characters");
		}
		try {
			return VersionFactory.forScheme(scheme, comparable(scheme, version));
		} catch (IndexOutOfBoundsException e) {
			// the golang parser throws StringIndexOutOfBoundsException on "v" or "1.0+"
			throw new IllegalArgumentException("not a " + scheme + " version: " + e, e);
		}
	}

	/** Which end of an interval a bound is, and the sign that makes a tighter bound compare higher. */
	private enum Side {
		LOWER(1), UPPER(-1);

		private final int sign;

		Side(int sign) {
			this.sign = sign;
		}
	}

	/** Of two bounds on the same side, the one that lets fewer versions in. */
	private static Bound tighter(Bound a, Bound b, Side side) {
		if (a == null) return b;
		if (b == null) return a;
		int c = a.version().compareTo(b.version()) * side.sign;
		if (c != 0) return c > 0 ? a : b;
		return a.inclusive() ? b : a;
	}

	/**
	 * The union of {@code rows}, as disjoint intervals in ascending order.
	 * Two intervals that both exclude the version they meet at stay apart,
	 * so that version then appears twice in the vers string ({@code >1|<2|>2});
	 * Dependency-Track's package ranges start inclusive, so this does not
	 * arise from its data.
	 */
	private static List<Interval> union(List<Interval> rows) {
		List<Interval> sorted = new ArrayList<>(rows);
		sorted.sort(VersRanges::compareLower);
		List<Interval> out = new ArrayList<>();
		Interval current = null;
		for (Interval next : sorted) {
			if (current == null) {
				current = next;
			} else if (joins(current, next)) {
				current = new Interval(current.lower(), higherUpper(current.upper(), next.upper()), List.of());
			} else {
				out.add(current);
				current = next;
			}
		}
		if (current != null) out.add(current);
		return out;
	}

	/** Lower bounds in ascending order; an open lower bound first, an inclusive one before an exclusive one. */
	private static int compareLower(Interval a, Interval b) {
		if (a.lower() == null || b.lower() == null) {
			return Boolean.compare(a.lower() != null, b.lower() != null);
		}
		int c = a.lower().version().compareTo(b.lower().version());
		return c != 0 ? c : Boolean.compare(!a.lower().inclusive(), !b.lower().inclusive());
	}

	/** Whether {@code next}, which starts no lower than {@code current}, overlaps or adjoins it. */
	private static boolean joins(Interval current, Interval next) {
		if (current.upper() == null || next.lower() == null) return true;
		int c = current.upper().version().compareTo(next.lower().version());
		return c > 0 || (c == 0 && (current.upper().inclusive() || next.lower().inclusive()));
	}

	private static Bound higherUpper(Bound a, Bound b) {
		if (a == null || b == null) return null;
		int c = a.version().compareTo(b.version());
		if (c != 0) return c > 0 ? a : b;
		return a.inclusive() ? a : b;
	}

	private static Optional<Vers> toVers(String scheme, List<Interval> rows) {
		List<Interval> union = union(rows);
		if (union.isEmpty()) return Optional.empty();
		Vers.Builder builder = Vers.builder(scheme);
		for (Interval i : union) {
			if (i.lower() == null && i.upper() == null) {
				// every version: the union is this one interval
				return Optional.of(Vers.builder(scheme).withConstraint(Comparator.WILDCARD, null).build());
			}
			if (i.lower() != null && i.upper() != null && i.lower().version().compareTo(i.upper().version()) == 0) {
				// a non-empty interval that starts where it ends is one version; the
				// end's spelling, which a fix is looked up by
				builder.withConstraint(Comparator.EQUAL, i.upper().version().toString());
				continue;
			}
			if (i.lower() != null) {
				builder.withConstraint(i.lower().inclusive() ? Comparator.GREATER_THAN_OR_EQUAL : Comparator.GREATER_THAN,
						i.lower().version().toString());
			}
			if (i.upper() != null) {
				builder.withConstraint(i.upper().inclusive() ? Comparator.LESS_THAN_OR_EQUAL : Comparator.LESS_THAN,
						i.upper().version().toString());
			}
		}
		return Optional.of(builder.build());
	}

	private static List<UpstreamSource> sourcesContaining(List<Interval> rows, Version v) {
		Set<UpstreamSource> sources = EnumSet.noneOf(UpstreamSource.class);
		for (Interval row : rows) {
			if (!row.contains(v)) continue;
			row.sources().stream().filter(Objects::nonNull).forEach(sources::add);
		}
		return List.copyOf(sources);
	}

	/**
	 * Semantic Versioning 2.0.0 for the vers scheme {@code semver}, which
	 * versatile lacks. Registered in {@code META-INF/services} above the
	 * built-in priority. A version is one only when it matches the
	 * semver.org expression: {@code v1.2.3}, {@code 1.2} and {@code 01.2.3}
	 * are not semantic versions and are refused, never normalised. Ordered by
	 * semver4j (on the classpath through versatile), which ignores build
	 * metadata as the specification says: {@code 1.0.0+b1} and
	 * {@code 1.0.0+b2} are the same version in a range.
	 */
	public static final class SemverVersionProvider implements VersionProvider {

		@Override
		public int priority() {
			return PRIORITY_BUILTIN + 1;
		}

		@Override
		public boolean supportsScheme(String scheme) {
			return SCHEME_SEMVER.equals(scheme);
		}

		@Override
		public Version getVersion(String scheme, String version) {
			return new SemverVersion(scheme, version);
		}
	}

	private static final class SemverVersion extends Version {

		private final Semver semver;

		SemverVersion(String scheme, String version) {
			super(scheme, version);
			if (version == null || version.length() > MAX_VERSION_LENGTH || !SEMVER_2.matcher(version).matches()) {
				throw new InvalidVersionException(version, "not a semantic version");
			}
			try {
				this.semver = new Semver(version, SemverType.STRICT);
			} catch (RuntimeException e) {
				// a number past 2^31
				throw new InvalidVersionException(version, "not a semantic version semver4j can read", e);
			}
		}

		@Override
		public boolean isStable() {
			return semver.getSuffixTokens().length == 0;
		}

		@Override
		public int compareTo(Version other) {
			if (!(other instanceof SemverVersion o)) {
				throw new IllegalArgumentException("cannot compare a semver version with " + other);
			}
			return semver.isLowerThan(o.semver) ? -1 : semver.isGreaterThan(o.semver) ? 1 : 0;
		}
	}
}
