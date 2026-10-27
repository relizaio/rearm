/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SettingsScope;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishPreview;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.saas.PerspectiveService;

/**
 * Task TEA-28, design 4.13 (threat-model mitigation M2), against the test database: the product's names and
 * recipes shape every child it carries, each name capped by the child's view of it, else by the child's own
 * names; only a child's own names refuse it (F-1, F-4). The shape is read off the recorded merge options
 * ({@code tldOnly}); internal metadata off the stored aggregate, whose merged components carry
 * {@link #INTERNAL_MARKER}, which an EXCLUDE strips.
 */
class TeaPublicationCascadeProfilesIntegrationTest extends TeaPublicationIntegrationTestBase {

	@Autowired JdbcTemplate jdbc;
	@Autowired PerspectiveService perspectiveService;

	private static TeaChildPublishResult childOf(TeaPublishResult r, UUID release) {
		return r.children().stream().filter(c -> release.equals(c.release())).findFirst().orElseThrow();
	}

	private List<String> aggregateNames(UUID release) {
		TeaPublicationData head = teaPublicationService.getPublicationOfRelease(release).orElseThrow();
		return teaPublicationService.getLatestCollection(head.getUuid()).orElseThrow().getEntries().stream()
				.filter(e -> TeaArtifactOrigin.SYNTHETIC == e.origin()).map(TeaPublishedEntry::name).toList();
	}

	private UUID aggregate(UUID release, String name) {
		return TeaAggregates.aggregateOf(teaPublicationService.getPublicationOfRelease(release).orElseThrow(), name);
	}

	/** The purl a top-level-only merge of a child answers, so its aggregate shows the shape it was generated with. */
	private static final String TLD_PURL = "pkg:generic/tea28-top-level-only@1";

	/** Whether the aggregate row's stored document was generated top-level only. */
	private boolean shapedTopLevelOnly(UUID aggregateRow) {
		UUID serial = artifactService.getArtifactData(aggregateRow).orElseThrow().getInternalBom().id();
		return uploaded.get(serial).toString().contains(TLD_PURL);
	}

	/** Whether any aggregate was uploaded for {@code release} (the mocked merge names its document after it). */
	private boolean anyAggregateOf(UUID release) {
		return uploaded.values().stream().anyMatch(d -> d.toString().contains("release-" + release));
	}

	private World child(String purl) throws Exception {
		World w = componentRelease(purl);
		internalMarked.add(w.release());
		tldOnlyPurls.put(w.release(), List.of(TLD_PURL));
		return w;
	}

	private UUID productOf(List<UUID> children) throws Exception {
		UUID p = product(children);
		internalMarked.add(p);
		return p;
	}

	/** A component OVERRIDE TEA row naming {@code names}, ENABLED, PRIVATE, NONE. */
	private void componentRow(UUID component, String... names) throws Exception {
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.COMPONENT, component,
				profileInput(TeaPublishing.ENABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.NONE,
						TeaProductComponents.PUBLISH_WITH_PRODUCT).mode(TeaComponentProfileMode.OVERRIDE)
						.profiles(List.of(names)).build(), TeaCaller.ORG_ADMIN, WU);
	}

	/** The INFO lines of the engine while {@code act} runs. */
	private List<String> infoLines(ThrowingRunnable act) throws Exception {
		List<String> lines = Collections.synchronizedList(new ArrayList<>());
		AbstractAppender a = new AbstractAppender("tea28-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				lines.add(event.getMessage().getFormattedMessage());
			}
		};
		a.start();
		org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(TeaPublicationService.class);
		Level before = logger.getLevel();
		Configurator.setLevel(TeaPublicationService.class.getName(), Level.INFO);
		logger.addAppender(a);
		try {
			act.run();
		} finally {
			logger.removeAppender(a);
			Configurator.setLevel(TeaPublicationService.class.getName(), before);
			a.stop();
		}
		return lines;
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	@Test
	void aChildsOwnTopNarrowsTheProductsTopAndAChildWithoutOneGetsTheProducts() throws Exception {
		saveOrgRecipe();
		saveSbomProfile(SettingsScope.ORGANIZATION, null, "top", SbomDependencyDepth.TOP_LEVEL_ONLY,
				SbomInternalMetadata.INCLUDE);
		saveOrgProfileNaming(TeaVisibility.PRIVATE, TeaRawArtifacts.NONE, "default", "top");
		World narrowed = child("pkg:generic/a@1.0.0");
		World plain = child("pkg:generic/b@1.0.0");
		saveSbomProfile(SettingsScope.COMPONENT, narrowed.component(), "top", SbomDependencyDepth.FULL,
				SbomInternalMetadata.EXCLUDE);
		componentRow(narrowed.component(), "default");
		UUID product = productOf(List.of(narrowed.release(), plain.release()));

		TeaPublishResult r = publish(product, TeaPublishMode.PUBLISH);

		for (World c : List.of(narrowed, plain)) {
			assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, c.release()).outcome());
			assertEquals(List.of("default", "top"), aggregateNames(c.release()));
			assertTrue(shapedTopLevelOnly(aggregate(c.release(), "top")), "the product's top shape");
			assertFalse(shapedTopLevelOnly(aggregate(c.release(), "default")));
		}
		assertFalse(keptInternalMarker(aggregate(narrowed.release(), "top")), "the child's own top narrows it");
		assertTrue(keptInternalMarker(aggregate(plain.release(), "top")), "the product's INCLUDE stands");
		assertTrue(keptInternalMarker(aggregate(product, "top")));
	}

	@Test
	void aProductLocalProfileShapesEveryChildCappedByEachChildsOwnNames() throws Exception {
		saveOrgProfileNaming(TeaVisibility.PRIVATE, TeaRawArtifacts.NONE, "default");
		saveSbomProfile(SettingsScope.ORGANIZATION, null, "default", SbomDependencyDepth.FULL, SbomInternalMetadata.INCLUDE);
		World excluding = child("pkg:generic/a@1.0.0");
		World including = child("pkg:generic/b@1.0.0");
		saveSbomProfile(SettingsScope.COMPONENT, excluding.component(), "default", SbomDependencyDepth.FULL,
				SbomInternalMetadata.EXCLUDE);
		UUID product = productOf(List.of(excluding.release(), including.release()));
		UUID productComponent = stored(product).getComponent();
		saveSbomProfile(SettingsScope.COMPONENT, productComponent, "local", SbomDependencyDepth.TOP_LEVEL_ONLY,
				SbomInternalMetadata.INCLUDE);
		componentRow(productComponent, "default", "local");

		TeaPublishResult r = publish(product, TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(List.of("default", "local"), aggregateNames(product));
		for (World c : List.of(excluding, including)) {
			assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, c.release()).outcome(), "no child refused");
			assertEquals(List.of("default", "local"), aggregateNames(c.release()));
			assertTrue(shapedTopLevelOnly(aggregate(c.release(), "local")), "local is the product's recipe");
		}
		assertFalse(keptInternalMarker(aggregate(excluding.release(), "local")), "capped by its own default EXCLUDE");
		assertTrue(keptInternalMarker(aggregate(including.release(), "local")), "the product's value");
	}

	@Test
	void aChildInTwoPerspectivesWhoseOwnSettingsDoNotNameTheProductsNameIsPublishedUnderItsOwnCap() throws Exception {
		assumeFalse(LicensingConstants.isOssEdition(), "perspectives are ReARM Pro");
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World c = child("pkg:generic/a@1.0.0");
		UUID p1 = perspectiveService.createPerspective(org.getUuid(), "tea28-p1-" + UUID.randomUUID(), WU).getUuid();
		UUID p2 = perspectiveService.createPerspective(org.getUuid(), "tea28-p2-" + UUID.randomUUID(), WU).getUuid();
		saveSbomProfile(SettingsScope.PERSPECTIVE, p1, "top", SbomDependencyDepth.FULL, SbomInternalMetadata.INCLUDE);
		saveSbomProfile(SettingsScope.PERSPECTIVE, p2, "top", SbomDependencyDepth.TOP_LEVEL_ONLY,
				SbomInternalMetadata.INCLUDE);
		perspectiveService.setPerspectivesOnComponent(c.component(), List.of(p1, p2), TeaCaller.ORG_ADMIN, WU);
		UUID product = productOf(List.of(c.release()));
		UUID productComponent = stored(product).getComponent();
		saveSbomProfile(SettingsScope.COMPONENT, productComponent, "top", SbomDependencyDepth.TOP_LEVEL_ONLY,
				SbomInternalMetadata.INCLUDE);
		componentRow(productComponent, "default", "top");

		List<TeaPublishResult> out = new ArrayList<>();
		List<String> info = infoLines(() -> out.add(publish(product, TeaPublishMode.PUBLISH)));

		TeaPublishResult r = out.get(0);
		assertEquals(List.of("default", "top"), aggregateNames(product));
		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, c.release()).outcome());
		assertEquals(List.of("default", "top"), aggregateNames(c.release()));
		assertTrue(shapedTopLevelOnly(aggregate(c.release(), "top")), "the product's top shape, not P1's FULL");
		assertFalse(keptInternalMarker(aggregate(c.release(), "top")), "the child's own cap: the organization's default EXCLUDE");
		assertEquals(1, info.stream().filter(l -> l.contains("product profile names [top] do not resolve")).count(), info.toString());
	}

	@Test
	void underASharedRowTheTwoPerspectiveChildIsRefusedOverItsOwnTopAndTheRemedyPublishesIt() throws Exception {
		assumeFalse(LicensingConstants.isOssEdition(), "perspectives are ReARM Pro");
		saveOrgRecipe();
		saveSbomProfile(SettingsScope.ORGANIZATION, null, "top", SbomDependencyDepth.TOP_LEVEL_ONLY,
				SbomInternalMetadata.INCLUDE);
		saveOrgProfileNaming(TeaVisibility.PRIVATE, TeaRawArtifacts.NONE, "default", "top");
		World twoPerspectives = child("pkg:generic/a@1.0.0");
		World none = child("pkg:generic/b@1.0.0");
		UUID p1 = perspectiveService.createPerspective(org.getUuid(), "tea28-p1-" + UUID.randomUUID(), WU).getUuid();
		UUID p2 = perspectiveService.createPerspective(org.getUuid(), "tea28-p2-" + UUID.randomUUID(), WU).getUuid();
		saveSbomProfile(SettingsScope.PERSPECTIVE, p1, "top", SbomDependencyDepth.FULL, SbomInternalMetadata.INCLUDE);
		saveSbomProfile(SettingsScope.PERSPECTIVE, p2, "top", SbomDependencyDepth.FULL, SbomInternalMetadata.EXCLUDE);
		perspectiveService.setPerspectivesOnComponent(twoPerspectives.component(), List.of(p1, p2), TeaCaller.ORG_ADMIN,
				WU);
		UUID product = productOf(List.of(twoPerspectives.release(), none.release()));

		TeaPublishResult r = publish(product, TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(List.of("default", "top"), aggregateNames(product));
		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, none.release()).outcome());
		assertEquals(List.of("default", "top"), aggregateNames(none.release()));
		TeaChildPublishResult refused = childOf(r, twoPerspectives.release());
		assertEquals(TeaChildOutcome.REFUSED, refused.outcome());
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, refused.refusal());
		assertTrue(refused.detail().contains("SBOM profile top resolves to a conflict between perspectives"),
				refused.detail());
		assertFalse(anyAggregateOf(twoPerspectives.release()), "nothing of the refused child is uploaded");
		assertTrue(publicationRepository.findByRelease(twoPerspectives.release()).isEmpty());
		assertTrue(stored(twoPerspectives.release()).getSyntheticArtifacts().isEmpty());
		TeaPublishPreview preview = teaPublicationService.preview(twoPerspectives.release());
		assertFalse(preview.canPublish());
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, preview.refusal());
		assertEquals(refused.detail(), preview.refusalMessage(), "the direct publish agrees");

		// the remedy of the refusal text: define it on the component
		saveSbomProfile(SettingsScope.COMPONENT, twoPerspectives.component(), "top", SbomDependencyDepth.FULL,
				SbomInternalMetadata.EXCLUDE);
		TeaPublishResult again = publish(product, TeaPublishMode.REPUBLISH);
		assertEquals(TeaChildOutcome.PUBLISHED, childOf(again, twoPerspectives.release()).outcome());
		assertEquals(List.of("default", "top"), aggregateNames(twoPerspectives.release()));
		assertTrue(shapedTopLevelOnly(aggregate(twoPerspectives.release(), "top")), "the product's top shape");
		assertFalse(keptInternalMarker(aggregate(twoPerspectives.release(), "top")), "the child's view narrows it");
	}

	@Test
	void aChildWhoseOwnNameNoLongerResolvesIsRefusedAndTheOthersStand() throws Exception {
		saveOrgRecipe();
		saveSbomProfile(SettingsScope.ORGANIZATION, null, "gone", SbomDependencyDepth.FULL, SbomInternalMetadata.EXCLUDE);
		saveOrgProfileNaming(TeaVisibility.PRIVATE, TeaRawArtifacts.NONE, "default");
		World broken = child("pkg:generic/a@1.0.0");
		World fine = child("pkg:generic/b@1.0.0");
		componentRow(broken.component(), "default", "gone");
		assertEquals(1, jdbc.update("DELETE FROM rearm.sbom_profiles WHERE org = ? AND name = 'gone'", org.getUuid()));
		UUID product = productOf(List.of(broken.release(), fine.release()));

		TeaPublishResult r = publish(product, TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, fine.release()).outcome());
		TeaChildPublishResult refused = childOf(r, broken.release());
		assertEquals(TeaChildOutcome.REFUSED, refused.outcome());
		assertEquals(TeaPublishRefusal.PROFILE_NOT_FOUND, refused.refusal());
		assertTrue(refused.detail().contains("SBOM profile gone, named by the component"), refused.detail());
		assertFalse(anyAggregateOf(broken.release()), "nothing of the refused child is uploaded");
		assertNull(teaPublicationService.getPublicationOfRelease(broken.release()).orElse(null));
		assertTrue(stored(broken.release()).getSyntheticArtifacts().isEmpty(), "no row of that child written");
	}
}
