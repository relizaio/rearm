/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.TeaPublicationRepository;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.saas.PerspectiveService;

/**
 * The fixtures of the TEA join tests of a board's document components (tasks TEA-14 and TEA-26): the
 * board's perspectives with their TEA rows, a publication the component carries, the pre-map board
 * adopting a legacy component, the board's refusal text, and a capture of ComponentService's WARN
 * line and AgentDocumentService's "adopted" INFO line. Split out of
 * AgentDocumentComponentsTeaJoinIntegrationTest by task TEA-26 round 2, when the pre-check's own cases
 * took a class of their own.
 */
abstract class AgentDocumentComponentsTeaJoinTestBase extends AgentDocumentComponentsTestBase {

	protected static final String WARN_TEXT = "joins the perspectives of its board and resolves to the PUBLIC TEA profile of";

	@Autowired protected PerspectiveService perspectiveService;
	@Autowired protected TeaProfileService teaProfileService;
	@Autowired protected TeaProfileResolver teaProfileResolver;
	@Autowired protected SharedReleaseService sharedReleaseService;
	@Autowired protected TeaPublicationService teaPublicationService;
	@Autowired protected TeaPublicationRepository teaPublicationRepository;
	@Autowired protected OssReleaseService ossReleaseService;
	@Autowired protected BoardPerspectiveService boardPerspectiveService;
	@Autowired protected OssPerspectiveService ossPerspectiveService;

	protected CapturingAppender logs;
	private Level componentLevelBefore;
	private Level documentLevelBefore;

	/** In-memory appender on ComponentService's and AgentDocumentService's loggers; only the level and the message matter. */
	protected static class CapturingAppender extends AbstractAppender {
		protected final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	private static org.apache.logging.log4j.core.Logger logger(Class<?> c) {
		return (org.apache.logging.log4j.core.Logger) LogManager.getLogger(c);
	}

	@BeforeEach
	void capture() {
		logs = new CapturingAppender();
		logs.start();
		componentLevelBefore = logger(ComponentService.class).getLevel();
		documentLevelBefore = logger(AgentDocumentService.class).getLevel();
		logger(ComponentService.class).addAppender(logs);
		logger(AgentDocumentService.class).addAppender(logs);
		// attach first, then set the level: without a configuration the logger answers ERROR only
		Configurator.setLevel(ComponentService.class.getName(), Level.WARN);
		Configurator.setLevel(AgentDocumentService.class.getName(), Level.INFO);
	}

	@AfterEach
	void release() {
		logger(ComponentService.class).removeAppender(logs);
		logger(AgentDocumentService.class).removeAppender(logs);
		Configurator.setLevel(ComponentService.class.getName(), componentLevelBefore);
		Configurator.setLevel(AgentDocumentService.class.getName(), documentLevelBefore);
		logs.stop();
	}

	protected List<String> widenings(UUID component) {
		return logs.events.stream().filter(e -> e.getLevel() == Level.WARN)
				.map(e -> e.getMessage().getFormattedMessage())
				.filter(m -> m.contains(WARN_TEXT) && m.contains(component.toString())).toList();
	}

	/** AgentDocumentService's INFO lines saying the component was adopted (design 3.3, section 4). */
	protected List<String> adoptedLines(UUID component) {
		return logs.events.stream().filter(e -> e.getLevel() == Level.INFO)
				.filter(e -> AgentDocumentService.class.getName().equals(e.getLoggerName()))
				.map(e -> e.getMessage().getFormattedMessage())
				.filter(m -> m.contains("adopted component " + component)).toList();
	}

	/** A real perspective holding the target, set on the board, whose TEA profile is ENABLED PUBLIC. */
	protected UUID publicPerspective(Rig r) throws RelizaException {
		return publicPerspective(r, TeaVisibility.PUBLIC);
	}

	/** As {@link #publicPerspective(Rig)}, with the perspective's row ENABLED at {@code visibility}. */
	protected UUID publicPerspective(Rig r, TeaVisibility visibility) throws RelizaException {
		return publicPerspective(r, visibility, List.of());
	}

	/**
	 * As {@link #publicPerspective(Rig, TeaVisibility)}, beside {@code others}, perspectives already on
	 * the board and holding the target: the three-argument setPerspectives and the board's set both
	 * replace the set, so the new perspective is added to them.
	 */
	protected UUID publicPerspective(Rig r, TeaVisibility visibility, List<UUID> others) throws RelizaException {
		UUID p = perspectiveService.createPerspective(r.org().getUuid(), "tea14-doc-" + UUID.randomUUID(), WU).getUuid();
		List<UUID> set = new ArrayList<>(others);
		set.add(p);
		componentService.setPerspectives(r.target().getUuid(), set, WU);
		agentBoardService.setPerspectives(r.board().getUuid(), set, x -> true, WU);
		teaProfileService.saveProfile(r.org().getUuid(), TeaProfileScope.PERSPECTIVE, p,
				TeaProfileServiceTestBase.full().toBuilder().publishing(TeaPublishing.ENABLED)
						.visibility(visibility).build(), TeaCaller.ORG_ADMIN, WU);
		return p;
	}

	/** A real perspective of the organization, not on the board, whose TEA profile is ENABLED PRIVATE (row 9). */
	protected UUID privatePerspective(Rig r) throws RelizaException {
		return offBoardPerspective(r, TeaVisibility.PRIVATE);
	}

	/** A real perspective of the organization, not on the board, whose TEA profile is ENABLED at {@code visibility}. */
	protected UUID offBoardPerspective(Rig r, TeaVisibility visibility) throws RelizaException {
		UUID q = perspectiveService.createPerspective(r.org().getUuid(), "tea26-off-" + UUID.randomUUID(), WU).getUuid();
		teaProfileService.saveProfile(r.org().getUuid(), TeaProfileScope.PERSPECTIVE, q,
				TeaProfileServiceTestBase.full().toBuilder().publishing(TeaPublishing.ENABLED)
						.visibility(visibility).build(), TeaCaller.ORG_ADMIN, WU);
		return q;
	}

	/**
	 * A publication the component carries, private today: the organization row ENABLED PRIVATE, an
	 * ASSEMBLED release of the component and a PUBLISHED head over it recorded against that row.
	 * Answers the publication's uuid.
	 */
	protected UUID privatePublication(Rig r, UUID component) throws RelizaException {
		UUID org = r.org().getUuid();
		TeaProfileData orgRow = teaProfileService.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				TeaProfileServiceTestBase.full().toBuilder().publishing(TeaPublishing.ENABLED)
						.visibility(TeaVisibility.PRIVATE).build(), TeaCaller.ORG_ADMIN, WU);
		UUID branch = branchService.getBaseBranchOfComponent(component).orElseThrow().getUuid();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component).branch(branch)
				.org(org).status(ReleaseData.ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT)
				.version("1.0." + Math.abs(UUID.randomUUID().hashCode() % 100000)).build(), WU).getUuid();
		ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.ASSEMBLED, WU);
		ComponentData cd = component(component);
		TeaPublication p = new TeaPublication();
		p.setUuid(UUID.randomUUID());
		p.setOrg(org);
		p.setRelease(release);
		p.setComponent(component);
		p.setState(TeaPublicationState.PUBLISHED);
		p.setLatestVersion(1);
		p.setProfile(orgRow.getUuid());
		TeaPublicationData d = new TeaPublicationData();
		d.setUuid(p.getUuid());
		d.setOrg(org);
		d.setRelease(release);
		d.setComponent(component);
		d.setComponentType(cd.getType());
		d.setState(TeaPublicationState.PUBLISHED);
		d.setLatestVersion(1);
		d.setProfile(orgRow.getUuid());
		p.setRecordData(d.toRecordData());
		teaPublicationRepository.save(p);
		return p.getUuid();
	}

	protected TeaExposureState exposure(UUID publication) {
		return teaPublicationService.exposureOf(teaPublicationService.getPublication(publication).orElseThrow()).state();
	}

	/** Row 1's fixture: a pre-map board on a new target, its PUBLIC perspective, then the legacy component and its publication. */
	protected record Adopting(Rig r, UUID p, UUID legacy, UUID pub) {}

	protected Adopting adopting(TeaVisibility visibility) throws RelizaException {
		return adopting(testInitializer.obtainOrganization(), visibility, List.of());
	}

	/**
	 * As {@link #adopting(TeaVisibility)} in {@code org}, with the board's set {@code others} followed
	 * by {@code p}: {@code others} are perspectives of {@code org}, created by the caller, that hold the
	 * target and sit on the board ahead of {@code p}.
	 */
	protected Adopting adopting(Organization org, TeaVisibility visibility, List<UUID> others) throws RelizaException {
		Component target = newTarget(org);
		Rig r = legacy(rig(org, target, "Adopting " + UUID.randomUUID()));
		UUID p = publicPerspective(r, visibility, others);
		UUID legacy = legacyComponent(target, org);
		UUID pub = privatePublication(r, legacy);
		return new Adopting(r, p, legacy, pub);
	}

	protected String perspectiveName(UUID perspective) {
		return ossPerspectiveService.teaPerspectiveRef(perspective).orElseThrow().name();
	}

	protected void assertNotAdopted(Adopting a, Set<UUID> before, int releases) {
		assertNotAdopted(a, before, releases, TeaVisibility.PRIVATE, TeaExposureState.PRIVATE);
	}

	/** Nothing written by a refused adoption; the component resolves and exposes as it did before. */
	protected void assertNotAdopted(Adopting a, Set<UUID> before, int releases, TeaVisibility visibility,
			TeaExposureState exposure) {
		var map = board(a.r()).getDocumentComponents();
		assertNull(null == map ? null : map.get(ARCH), "nothing recorded in the board's map");
		assertEquals(releases, sharedReleaseService.listReleaseDatasOfComponent(a.legacy(), 10, 0).size(),
				"no document round on the component");
		assertEquals(before, memberships(a.legacy()), "the component's perspectives are unchanged");
		assertEquals(visibility, visibility(a.legacy()));
		assertEquals(exposure, exposure(a.pub()));
		assertTrue(widenings(a.legacy()).isEmpty(), logs.events.toString());
		assertTrue(adoptedLines(a.legacy()).isEmpty(), "a refused adoption logs no adopted line: " + logs.events);
	}

	/**
	 * The board's text (design 3.3, 3.4): it names the component, the specification, the board, the
	 * profile source and, as the remedy, the board's perspective the component is not in yet.
	 */
	protected void assertAdoptRefusal(Adopting a, RelizaException refused) {
		assertAdoptRefusal(a, refused, List.of(a.p()));
	}

	/**
	 * As {@link #assertAdoptRefusal(Adopting, RelizaException)}, the remedy naming exactly
	 * {@code remedy}, in the board's order: the board's real perspectives the component is not yet in
	 * (design 3.3, labels over real minus the stored set).
	 */
	protected void assertAdoptRefusal(Adopting a, RelizaException refused, List<UUID> remedy) {
		String m = refused.getMessage();
		ComponentData cd = component(a.legacy());
		assertTrue(m.startsWith("adopting component " + cd.getName()), m);
		assertTrue(m.contains(a.legacy().toString()), m);
		assertTrue(m.contains("ARCHITECTURE"), m);
		assertTrue(m.contains(board(a.r()).getName()), m);
		assertTrue(m.contains("PUBLIC TEA profile of perspective " + perspectiveName(a.p())), m);
		assertTrue(m.endsWith("; only an organization admin can make that change: have one add the component to "
				+ String.join(", ", remedy.stream().map(this::perspectiveName).toList()) + " first, then publish again"), m);
	}

	protected Set<UUID> memberships(UUID component) {
		Set<UUID> ps = component(component).getPerspectives();
		return null == ps ? Set.of() : ps;
	}

	protected TeaVisibility visibility(UUID component) {
		ComponentData cd = component(component);
		TeaProfileResolver.TeaResolvedProfile resolved = teaProfileResolver.resolveForComponent(cd);
		return null == resolved.profile() ? null : resolved.profile().getVisibility();
	}
}
