/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.AnalyticsDtos.VegaDateValue;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.CveSearchResultDto.ComponentWithBranches;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.AnalyticsMetricsRepository;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.dao.DateCountDao;
import io.reliza.repositories.dao.ReleasesPerBranchDao;
import io.reliza.repositories.dao.ReleasesPerComponentDao;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.saas.PerspectiveService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-75 (architecture round 2, test 4.1): the org-wide release analytics of the home page
 * (releases per day, most active components and branches, by org, perspective and product, and
 * the day-click list behind the releases-per-day chart) leave out the releases of BOARD_DOCUMENT
 * components; a component without a kind key stays counted; the per-component variants and the
 * other lists built by the same grouping still show a document component's rounds.
 */
@SpringBootTest(classes = {App.class})
public class AnalyticsExcludeDocumentReleasesIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AtomicInteger VERSION = new AtomicInteger();

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private ReleaseService releaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private PerspectiveService perspectiveService;
	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private AnalyticsMetricsRepository analyticsMetricsRepository;
	@Autowired private ComponentRepository componentRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	/** Two releases on {@code sw}, two rounds on {@code doc}, one release on the kind-less {@code legacy}. */
	private record Rig(Organization org, UUID sw, UUID doc, UUID legacy, UUID perspective, List<UUID> swReleases,
			List<UUID> docReleases, ZonedDateTime cutOff) {}

	private Rig rig() throws RelizaException {
		ZonedDateTime cutOff = ZonedDateTime.now().minusDays(1);
		Organization org = testInitializer.obtainOrganization();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Component sw = componentService.createComponent("tpf75-sw-" + tag, org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU);
		Component doc = componentService.createBoardDocumentComponent(CreateComponentDto.builder()
				.name("tpf75-doc-" + tag).organization(org.getUuid())
				.type(ComponentType.COMPONENT).kind(ComponentKind.BOARD_DOCUMENT)
				.versionSchema("Micro").featureBranchVersioning("Branch.Micro").build(), WU);
		Component legacy = componentService.createComponent("tpf75-legacy-" + tag, org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		jdbcTemplate.update("UPDATE rearm.components SET record_data = record_data - 'kind' WHERE uuid = ?",
				legacy.getUuid());
		List<String> legacyKind = componentRepository.findKindOf(legacy.getUuid());
		assertEquals(1, legacyKind.size(), legacyKind.toString());
		assertNull(legacyKind.get(0), "the legacy component has no kind key");

		List<UUID> swReleases = List.of(release(org, sw), release(org, sw));
		List<UUID> docReleases = List.of(release(org, doc), release(org, doc));
		release(org, legacy);

		UUID perspective = perspectiveService.createPerspective(org.getUuid(), "TPF75-" + tag, WU).getUuid();
		for (Component c : List.of(sw, doc, legacy)) {
			componentService.setPerspectives(c.getUuid(), List.of(perspective), WU);
		}
		return new Rig(org, sw.getUuid(), doc.getUuid(), legacy.getUuid(), perspective, swReleases, docReleases,
				cutOff);
	}

	private UUID release(Organization org, Component c) throws RelizaException {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("3.0." + VERSION.incrementAndGet())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU)
				.getUuid();
	}

	private static long sum(List<DateCountDao> rows) {
		return rows.stream().mapToLong(DateCountDao::getNum).sum();
	}

	private static Map<UUID, Long> byComponent(List<ReleasesPerComponentDao> rows) {
		return rows.stream().collect(Collectors.toMap(ReleasesPerComponentDao::getComponentuuid,
				ReleasesPerComponentDao::getRlzcount));
	}

	/** Release count per component over the branch rows. */
	private static Map<UUID, Long> branchCountsByComponent(List<ReleasesPerBranchDao> rows) {
		Map<UUID, Long> out = new HashMap<>();
		rows.forEach(r -> out.merge(r.getComponentuuid(), r.getRlzcount(), Long::sum));
		return out;
	}

	private static Set<UUID> components(List<ComponentWithBranches> list) {
		return list.stream().map(ComponentWithBranches::uuid).collect(Collectors.toSet());
	}

	private static void assertSoftwareOnly(Rig r, Map<UUID, Long> counts, String what) {
		assertEquals(Map.of(r.sw(), 2L, r.legacy(), 1L), counts, what);
	}

	@Test
	public void theOrgReleasesPerDayCountLeavesDocumentRoundsOut() throws RelizaException {
		Rig r = rig();
		assertEquals(3L, sum(releaseRepository.countReleasesOfOrgByDate(r.org().getUuid().toString(), r.cutOff(),
				"UTC")), "sw 2 + legacy 1; the two document rounds are not counted");
	}

	@Test
	public void thePerspectiveReleasesPerDayCountLeavesDocumentRoundsOut() throws RelizaException {
		Rig r = rig();
		List<VegaDateValue> rows = releaseService.getReleaseCreateOverTimeAnalyticsByPerspective(r.perspective(),
				r.cutOff());
		assertEquals(3L, rows.stream().mapToLong(VegaDateValue::num).sum(), rows.toString());
	}

	@Test
	public void theMostActiveByOrgLeaveDocumentComponentsOut() throws RelizaException {
		Rig r = rig();
		String org = r.org().getUuid().toString();
		assertSoftwareOnly(r, byComponent(analyticsMetricsRepository.analyticsComponentsWithMostReleases(r.cutOff(),
				"COMPONENT", 10, org)), "most active components");
		assertSoftwareOnly(r, branchCountsByComponent(analyticsMetricsRepository.analyticsBranchesWithMostReleases(
				r.cutOff(), "COMPONENT", 10, org)), "most active branches");
	}

	@Test
	public void theMostActiveByPerspectiveLeaveDocumentComponentsOut() throws RelizaException {
		Rig r = rig();
		String org = r.org().getUuid().toString();
		String p = r.perspective().toString();
		assertSoftwareOnly(r, byComponent(analyticsMetricsRepository.analyticsComponentsWithMostReleasesByPerspective(
				r.cutOff(), "COMPONENT", 10, org, p)), "most active components by perspective");
		assertSoftwareOnly(r, branchCountsByComponent(analyticsMetricsRepository
				.analyticsBranchesWithMostReleasesByPerspective(r.cutOff(), "COMPONENT", 10, org, p)),
				"most active branches by perspective");
	}

	@Test
	public void theMostActiveByProductLeaveDocumentComponentsOut() throws RelizaException {
		Rig r = rig();
		String org = r.org().getUuid().toString();
		List<UUID> all = List.of(r.sw(), r.doc(), r.legacy());
		assertSoftwareOnly(r, byComponent(analyticsMetricsRepository.analyticsComponentsWithMostReleasesByProduct(
				r.cutOff(), "COMPONENT", 10, org, all)), "most active components by product");
		assertSoftwareOnly(r, branchCountsByComponent(analyticsMetricsRepository
				.analyticsBranchesWithMostReleasesByProduct(r.cutOff(), "COMPONENT", 10, org, all)),
				"most active branches by product");
	}

	@Test
	public void theDayClickListLeavesDocumentComponentsOut() throws RelizaException {
		Rig r = rig();
		ZonedDateTime start = ZonedDateTime.now().minusHours(1);
		ZonedDateTime end = ZonedDateTime.now().plusMinutes(5);
		Set<UUID> byOrg = components(sharedReleaseService.findReleasesByTimeFrame(r.org().getUuid(), start, end, null));
		assertEquals(Set.of(r.sw(), r.legacy()), byOrg, "by org");
		Set<UUID> byPerspective = components(sharedReleaseService.findReleasesByTimeFrame(r.org().getUuid(), start,
				end, r.perspective()));
		assertEquals(Set.of(r.sw(), r.legacy()), byPerspective, "by perspective");
	}

	@Test
	public void thePerComponentVariantsStillShowTheRounds() throws RelizaException {
		Rig r = rig();
		ZonedDateTime start = ZonedDateTime.now().minusHours(1);
		ZonedDateTime end = ZonedDateTime.now().plusMinutes(5);
		List<ComponentWithBranches> list = sharedReleaseService.findReleasesByTimeFrameAndComponent(r.doc(), start, end);
		assertEquals(Set.of(r.doc()), components(list));
		assertEquals(2, list.get(0).branches().stream().mapToInt(b -> b.releases().size()).sum(), list.toString());
		assertEquals(2L, sum(releaseRepository.countReleasesOfComponentByDate(r.doc().toString(), r.cutOff(), "UTC")));
	}

	@Test
	public void theOtherGroupedListsKeepDocumentComponents() throws RelizaException {
		Rig r = rig();
		Set<UUID> ids = Set.of(r.docReleases().get(0), r.docReleases().get(1), r.swReleases().get(0));
		List<ComponentWithBranches> list = sharedReleaseService.findReleaseDatasByReleaseIds(ids, r.org().getUuid());
		Set<UUID> shown = components(list);
		assertEquals(Set.of(r.doc(), r.sw()), shown, "the three-argument overload keeps document components");
		assertTrue(shown.contains(r.doc()));
		assertFalse(shown.contains(r.legacy()));
	}
}
