/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.reliza.dto.ComponentFixTargets;
import io.reliza.dto.ComponentFixTargets.FixTarget;
import io.reliza.dto.LatestFixVerdict;
import io.reliza.model.VulnerabilityRecordData.AffectedIdentityType;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.VulnerabilityRecordData.AffectedRangeType;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.service.FixTargetResolver.AllTargets;
import io.reliza.service.FixTargetResolver.ComponentFinding;

class FixTargetResolverTest {

	private static final String LOG4J = "pkg:maven/org.apache.logging.log4j/log4j-core";

	private static AffectedRange range(String identity, String startIncl, String endExcl) {
		AffectedRange r = new AffectedRange();
		r.setIdentityType(AffectedIdentityType.PURL);
		r.setIdentity(identity);
		r.setRangeType(AffectedRangeType.RANGE);
		r.setVersionStartIncluding(startIncl);
		r.setVersionEndExcluding(endExcl);
		r.setSources(List.of(UpstreamSource.OSV));
		return r;
	}

	private static ComponentFinding finding(String vulnId, AffectedRange... ranges) {
		return new ComponentFinding(vulnId, List.of(ranges));
	}

	private static List<String> versions(ComponentFixTargets t) {
		return t.targets().stream().map(FixTarget::version).toList();
	}

	@Test
	void eachFixVersionListsTheFindingsItFixesLowestFirst() {
		ComponentFixTargets t = FixTargetResolver.resolve(LOG4J + "@2.14.1", List.of(
				finding("A", range(LOG4J, null, "2.15.0")),
				finding("B", range(LOG4J, null, "2.12.2"), range(LOG4J, "2.13.0", "2.16.0")),
				finding("C", range(LOG4J, "2.13.0", "2.17.1")),
				// no fix named: every later version is affected
				finding("D", range(LOG4J, "2.0", null)),
				// no range for this package: nothing can say it is fixed
				finding("E", range("pkg:maven/other/pkg", null, "9.0"))));
		assertEquals("2", t.major());
		assertEquals(List.of(
				new FixTarget("2.15.0", true, List.of("A")),
				new FixTarget("2.16.0", true, List.of("A", "B")),
				new FixTarget("2.17.1", true, List.of("A", "B", "C"))), t.targets());
	}

	@Test
	void aFixInsideAnotherAdvisorysRangeDoesNotFixIt() {
		// G affects 2.14.1 and again [2.16.0, 2.16.2): B's fix 2.16.0 lands in it
		ComponentFixTargets t = FixTargetResolver.resolve(LOG4J + "@2.14.1", List.of(
				finding("B", range(LOG4J, null, "2.16.0")),
				finding("G", range(LOG4J, null, "2.14.5"), range(LOG4J, "2.16.0", "2.16.2"))));
		assertEquals(List.of(
				new FixTarget("2.14.5", true, List.of("G")),
				new FixTarget("2.16.0", true, List.of("B"))), t.targets());
	}

	@Test
	void aDebianComponentIsOnlyOfferedItsOwnReleasesFix() {
		String openssl = "pkg:deb/debian/openssl?arch=source&distro=";
		ComponentFixTargets t = FixTargetResolver.resolve("pkg:deb/debian/openssl@3.0.20-1~deb12u2?distro=debian-12", List.of(
				finding("X", range(openssl + "bookworm", null, "3.0.22-1~deb12u1"), range(openssl + "trixie", null, "3.5.7-1~deb13u2"),
						range(openssl + "forky", null, "3.6.4-1"))));
		assertEquals(List.of("3.0.22-1~deb12u1"), versions(t));
		assertEquals("3", t.major());
	}

	@Test
	void aFixOnAnotherMajorVersionIsMarked() {
		String django = "pkg:pypi/django";
		ComponentFixTargets t = FixTargetResolver.resolve(django + "@2.0.1", List.of(
				finding("A", range(django, null, "2.0.3")),
				finding("B", range(django, "2.0", "5.2.17"))));
		assertEquals(List.of(
				new FixTarget("2.0.3", true, List.of("A")),
				new FixTarget("5.2.17", false, List.of("A", "B"))), t.targets());
	}

	/** {@code findings} findings over {@code fixes} distinct fix versions: that many times more placements. */
	private static List<ComponentFinding> costly(String purl, int findings, int fixes) {
		List<ComponentFinding> out = new ArrayList<>();
		for (int i = 0; i < findings; i++) out.add(finding(purl + "#" + i, range(purl, null, "3.0." + (i % fixes + 1))));
		return out;
	}

	@Test
	void aComponentTooCostlyToWorkOutGetsNoTargets() {
		// 100 fixes x 200 findings is the limit; one finding more is past it
		assertEquals(20_000, FixTargetResolver.MAX_PLACEMENTS);
		assertEquals(100, FixTargetResolver.resolve(LOG4J + "@2.14.1", costly(LOG4J, 200, 100)).targets().size());
		assertTrue(FixTargetResolver.resolve(LOG4J + "@2.14.1", costly(LOG4J, 201, 100)).targets().isEmpty());
	}

	@Test
	void theComponentsPastAListsBudgetGetNoTargets() {
		// five components at the limit each spend the list's budget; the sixth gets none
		Map<String, Set<String>> idsByPurl = new LinkedHashMap<>();
		Map<String, List<AffectedRange>> rangesById = new HashMap<>();
		for (int c = 0; c < 6; c++) {
			String pkg = "pkg:maven/g/a" + c;
			Set<String> ids = new LinkedHashSet<>();
			for (ComponentFinding f : costly(pkg, 200, 100)) {
				ids.add(f.vulnId());
				rangesById.put(f.vulnId(), f.ranges());
			}
			idsByPurl.put(pkg + "@2.0", ids);
		}
		AllTargets all = FixTargetResolver.resolveAll(idsByPurl, rangesById, Integer.MAX_VALUE);
		assertEquals(5, all.targets().size());
		assertEquals("pkg:maven/g/a4@2.0", all.targets().getLast().purl());
		assertEquals(FixTargetResolver.MAX_PLACEMENTS_PER_METRICS, all.placements());
		// what is left of a request's budget bounds a list's
		assertEquals(1, FixTargetResolver.resolveAll(idsByPurl, rangesById, 20_000).targets().size());
		// a budget already overspent spends nothing
		AllTargets none = FixTargetResolver.resolveAll(idsByPurl, rangesById, -5);
		assertTrue(none.targets().isEmpty());
		assertEquals(0, none.placements());
	}

	@Test
	void aComponentAfterOneTooCostlyStillGetsTargets() {
		Map<String, Set<String>> idsByPurl = new LinkedHashMap<>();
		Map<String, List<AffectedRange>> rangesById = new HashMap<>();
		for (ComponentFinding f : costly(LOG4J, 201, 100)) {
			idsByPurl.computeIfAbsent(LOG4J + "@2.14.1", k -> new LinkedHashSet<>()).add(f.vulnId());
			rangesById.put(f.vulnId(), f.ranges());
		}
		idsByPurl.put("pkg:npm/a@1.0.0", Set.of("SMALL"));
		rangesById.put("SMALL", List.of(range("pkg:npm/a", null, "1.0.1")));
		AllTargets all = FixTargetResolver.resolveAll(idsByPurl, rangesById, Integer.MAX_VALUE);
		assertEquals(List.of("pkg:npm/a@1.0.0"), all.targets().stream().map(ComponentFixTargets::purl).toList());
		assertEquals(1, all.placements());
	}

	@Test
	void versionsGenericCannotOrderGetNoTargets() {
		String tool = "pkg:github/acme/tool";
		// 1.0.0-rc1 and 1.0.0: only the text after the numbers tells them apart
		ComponentFixTargets t = FixTargetResolver.resolve(tool + "@0.9", List.of(
				finding("A", range(tool, null, "1.0.0-rc1")), finding("B", range(tool, null, "1.0.0"))));
		assertTrue(t.targets().isEmpty());
	}

	@Test
	void aFindingAffectedUpToAVersionIsFixedByAHigherOne() {
		AffectedRange throughTwo = range(LOG4J, null, null);
		throughTwo.setVersionEndIncluding("2.15.0");
		ComponentFixTargets t = FixTargetResolver.resolve(LOG4J + "@2.14.1", List.of(
				finding("A", range(LOG4J, null, "2.16.0")), new ComponentFinding("F", List.of(throughTwo))));
		assertEquals(List.of(new FixTarget("2.16.0", true, List.of("A", "F"))), t.targets());
	}

	@Test
	void aFindingWhoseReleaseTheAdvisoryDoesNotListIsFixedByNothing() {
		String openssl = "pkg:deb/debian/openssl?arch=source&distro=";
		ComponentFixTargets t = FixTargetResolver.resolve("pkg:deb/debian/openssl@3.0.20-1~deb12u2?distro=debian-12", List.of(
				finding("X", range(openssl + "bookworm", null, "3.0.22-1~deb12u1")),
				finding("Y", range(openssl + "trixie", null, "3.5.7-1~deb13u2"))));
		assertEquals(List.of(new FixTarget("3.0.22-1~deb12u1", true, List.of("X"))), t.targets());
	}

	@Test
	void anUnreadableRangeOfAnotherReleaseDoesNotStopAFix() {
		// a bookworm finding is placed against bookworm's rows only
		String openssl = "pkg:deb/debian/openssl?arch=source&distro=";
		ComponentFixTargets t = FixTargetResolver.resolve("pkg:deb/debian/openssl@3.0.20-1~deb12u2?distro=debian-12", List.of(
				finding("X", range(openssl + "bookworm", null, "3.0.22-1~deb12u1"), range(openssl + "trixie", null, "garbage version!"))));
		assertEquals(List.of(new FixTarget("3.0.22-1~deb12u1", true, List.of("X"))), t.targets());
	}

	@Test
	void debianAndRpmVersionsKeepTheirEpochs() {
		String openssh = "pkg:deb/debian/openssh?arch=source&distro=bookworm";
		ComponentFixTargets deb = FixTargetResolver.resolve("pkg:deb/debian/openssh@1:9.2p1-2+deb12u2?distro=debian-12", List.of(
				finding("A", range(openssh, null, "1:9.2p1-2+deb12u3")),
				finding("B", range(openssh, null, "1:9.2p1-2+deb12u10"))));
		assertEquals(List.of(
				new FixTarget("1:9.2p1-2+deb12u3", true, List.of("A")),
				new FixTarget("1:9.2p1-2+deb12u10", true, List.of("A", "B"))), deb.targets());
		String openssl = "pkg:rpm/redhat/openssl";
		ComponentFixTargets rpm = FixTargetResolver.resolve(openssl + "@1.1.1k-11.el8_9?epoch=1", List.of(
				finding("R", range(openssl, null, "1:1.1.1k-12.el8_9"))));
		assertEquals(List.of(new FixTarget("1:1.1.1k-12.el8_9", true, List.of("R"))), rpm.targets());
	}

	@Test
	void aFindingWithARangeThatCannotBeReadIsFixedByNothing() {
		// F's second range is left out when placing, so 2.0.5 would read as out of F
		String foo = "pkg:pypi/foo";
		ComponentFixTargets t = FixTargetResolver.resolve(foo + "@1.0", List.of(
				finding("A", range(foo, null, "2.0.5")),
				finding("F", range(foo, null, "1.5"), range(foo, "2.0", "2.1 garbage!!"))));
		assertEquals(List.of(new FixTarget("2.0.5", false, List.of("A"))), t.targets());
	}

	@Test
	void aComponentThatIsNotAPackageUrlGetsNoTargets() {
		assertTrue(FixTargetResolver.resolve("not a purl", List.of(finding("A", range(LOG4J, null, "2.15.0")))).targets().isEmpty());
		assertTrue(FixTargetResolver.resolve(LOG4J + "@2.14.1", List.of(new ComponentFinding("A", null))).targets().isEmpty());
	}

	@Test
	void aMetricsObjectsFindingsGroupByTheirPackageUrl() {
		ReleaseMetricsDto metrics = new ReleaseMetricsDto();
		metrics.setVulnerabilityDetails(new LinkedList<>(List.of(
				new VulnerabilityDto(LOG4J + "@2.14.1", "A", null, null, null, null, null, null, null, null, null, null, null, null, null),
				new VulnerabilityDto(LOG4J + "@2.14.1", "B", null, null, null, null, null, null, null, null, null, null, null, null, null),
				new VulnerabilityDto("src/Main.java", "C", null, null, null, null, null, null, null, null, null, null, null, null, null),
				new VulnerabilityDto(LOG4J + "@2.17.0", "A", null, null, null, null, null, null, null, null, null, null, null, null, null))));
		assertEquals(Map.of(LOG4J + "@2.14.1", Set.of("A", "B"), LOG4J + "@2.17.0", Set.of("A")),
				FixTargetResolver.vulnIdsByPurl(metrics));
		assertTrue(FixTargetResolver.vulnIdsByPurl(null).isEmpty());
	}

	@Test
	void aLatestVersionFixesAFindingWhenItIsOutOfItsRanges() {
		String at = LOG4J + "@2.14.1";
		List<AffectedRange> fixedBy215 = List.of(range(LOG4J, null, "2.15.0"));
		assertEquals(LatestFixVerdict.FIXES, FixTargetResolver.fixedAt(at, "A", fixedBy215, "2.26.1"));
		// a later affected range: latest >= the fix version would have said fixed
		List<AffectedRange> twoRanges = List.of(range(LOG4J, null, "2.15.0"), range(LOG4J, "2.20.0", "2.27.0"));
		assertEquals(LatestFixVerdict.DOES_NOT_FIX, FixTargetResolver.fixedAt(at, "B", twoRanges, "2.26.1"));
		// no end: every later version is affected
		assertEquals(LatestFixVerdict.DOES_NOT_FIX, FixTargetResolver.fixedAt(at, "C", List.of(range(LOG4J, "2.0", null)), "2.26.1"));
	}

	@Test
	void aLatestVersionThatIsNotAboveTheComponentsOwnFixesNothing() {
		List<AffectedRange> ranges = List.of(range(LOG4J, null, "2.15.0"));
		assertEquals(LatestFixVerdict.NOT_ABOVE_CURRENT, FixTargetResolver.fixedAt(LOG4J + "@2.14.1", "A", ranges, "2.14.1"));
		// a registry can report a version below a private build: a downgrade is no fix
		assertEquals(LatestFixVerdict.NOT_ABOVE_CURRENT, FixTargetResolver.fixedAt(LOG4J + "@2.14.1", "A", ranges, "2.0.0"));
	}

	@Test
	void aLatestVersionSaysNothingWithoutRangesThatHoldTheComponent() {
		assertEquals(LatestFixVerdict.NO_RANGE_DATA, FixTargetResolver.fixedAt(LOG4J + "@2.14.1", "A", List.of(), "2.26.1"));
		assertEquals(LatestFixVerdict.NO_RANGE_DATA, FixTargetResolver.fixedAt(LOG4J + "@2.14.1", "A", null, "2.26.1"));
		// the component's version is outside the ranges: the finding was matched by another rule
		assertEquals(LatestFixVerdict.NOT_IN_ADVISORY_RANGE,
				FixTargetResolver.fixedAt(LOG4J + "@2.20.0", "A", List.of(range(LOG4J, null, "2.15.0")), "2.26.1"));
		// another package's range only
		assertEquals(LatestFixVerdict.NO_RANGE_DATA, FixTargetResolver.fixedAt(LOG4J + "@2.14.1", "A",
				List.of(range("pkg:maven/other/pkg", null, "9.0")), "2.26.1"));
		assertEquals(LatestFixVerdict.UNCOMPARABLE,
				FixTargetResolver.fixedAt(LOG4J + "@2.14.1", "A", List.of(range(LOG4J, null, "2.15.0")), null));
	}

	@Test
	void anUncomparableGenericVersionSaysNothing() {
		String generic = "pkg:generic/acme/tool";
		// generic orders a pre-release above the release: the placement is refused
		assertEquals(LatestFixVerdict.UNCOMPARABLE, FixTargetResolver.fixedAt(generic + "@1.0.0-rc1", "A",
				List.of(range(generic, null, "1.0.0")), "2.0.0"));
		// generic with numbers only still answers
		assertEquals(LatestFixVerdict.FIXES, FixTargetResolver.fixedAt(generic + "@1.0.0", "A",
				List.of(range(generic, null, "1.2.0")), "2.0.0"));
	}
}
