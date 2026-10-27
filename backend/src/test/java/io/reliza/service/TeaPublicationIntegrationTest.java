/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.common.Utils.StripBom;
import io.reliza.model.AcollectionData;
import io.reliza.model.AcollectionData.VersionedArtifact;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ArtifactUploadResponseDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.OASResponseDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.RebomResponse;
import io.reliza.model.DigestAlgorithm;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.TeaPublicationRepository;
import io.reliza.repositories.TeaPublishedCollectionRepository;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;
import io.reliza.ws.oss.TestInitializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * TEA-5, section 4.8, against the test database: publish, re-publish, hide, the user and
 * programmatic surfaces, the cascade and the concurrency of round 1 on the shared fixture of
 * {@link TeaPublicationIntegrationTestBase}. Round 2's boundary, gate, owner, race and lifecycle
 * cases have their own classes.
 */
public class TeaPublicationIntegrationTest extends TeaPublicationIntegrationTestBase {

	@Autowired private PlatformTransactionManager transactionManager;

	// ---------------------------------------------------------------- 39

	@Test
	void publishWritesBothTablesAndTheGeneratedAggregate() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");

		TeaPublishResult r = publish(w.release(), TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		TeaPublication head = publicationRepository.findByRelease(w.release()).orElseThrow();
		assertEquals(TeaPublicationState.PUBLISHED, head.getState());
		assertEquals(1, head.getLatestVersion());
		assertNotNull(head.getProfile());
		TeaPublishedCollection row = collectionRepository.findVersion(head.getUuid(), 1).orElseThrow();
		assertEquals(Utils.bytesToHexSha256(row.getDocument().getBytes(StandardCharsets.UTF_8)), row.getDocumentSha256());
		assertNull(row.getSignature());

		ReleaseData rd = stored(w.release());
		UUID aggregate = TeaAggregates.defaultAggregate(TeaPublicationData.dataFromRecord(head));
		assertEquals(List.of(aggregate), rd.getSyntheticArtifacts());
		assertEquals(List.of(w.inventory()), rd.getArtifacts());
		ArtifactData ad = artifactService.getArtifactData(aggregate).orElseThrow();
		assertNotNull(ad.getTeaUuid());
		assertEquals("1", ad.getVersion());
		assertTrue(ad.getDigestRecords().stream().anyMatch(d -> DigestScope.AS_UPLOADED == d.scope()));
		assertEquals(ArtifactBelongsTo.SYNTHETIC, ad.getInternalBom().belongsTo());
		Set<String> keys = ad.getTags().stream().map(t -> t.key()).collect(Collectors.toSet());
		assertTrue(keys.containsAll(Set.of(CommonVariables.TEA_AGGREGATE_TAG_KEY, CommonVariables.TEA_PUBLICATION_TAG_KEY,
				CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY)), keys.toString());
		assertEquals(aggregate, artifactRepository.findByTeaUuid(ad.getTeaUuid().toString()).orElseThrow().getUuid());
		assertEquals(1, events(w.release(), ReleaseUpdateScope.SYNTHETIC_ARTIFACT).size());
		assertEquals(1, events(w.release(), ReleaseUpdateScope.TEA_PUBLICATION).size());
	}

	// ---------------------------------------------------------------- 40

	@Test
	void republishIsUnchangedThenBumpsTheAggregateKeepingItsHistory() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		UUID publication = publicationRepository.findByRelease(w.release()).orElseThrow().getUuid();

		assertEquals(TeaPublishOutcome.UNCHANGED, publish(w.release(), TeaPublishMode.REPUBLISH).outcome());
		assertEquals(1, collectionRepository.listByPublication(publication).size());

		mergedPurls.get(w.release()).add("pkg:generic/valve@2.0.0");
		TeaPublishResult r = publish(w.release(), TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.REPUBLISHED, r.outcome());
		assertEquals(2, r.collection().getVersion());
		UUID aggregate = TeaAggregates.defaultAggregate(r.publication());
		ArtifactData ad = artifactService.getArtifactData(aggregate).orElseThrow();
		assertEquals("2", ad.getVersion());
		assertEquals(1, ad.getPreviousVersions().size());
		assertEquals("1", ad.getPreviousVersions().get(0).version());
		ArtifactData v1 = artifactService.getArtifactDataByVersion(aggregate, 1).orElseThrow();
		assertEquals("1", v1.getVersion());
		assertTrue(v1.getDigestRecords().stream().anyMatch(d -> DigestScope.AS_UPLOADED == d.scope()));
		assertNotNull(ad.getTeaUuid(), "an in-place re-upload keeps the TEA id");
	}

	// ---------------------------------------------------------------- 41

	@Test
	@SuppressWarnings("unchecked")
	void theAggregateStaysOutOfEveryInventoryPath() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		UUID aggregate = TeaAggregates.defaultAggregate(publicationRepository.findByRelease(w.release())
				.map(TeaPublicationData::dataFromRecord).orElseThrow());
		UUID aggregateBom = artifactService.getArtifactData(aggregate).orElseThrow().getInternalBom().id();
		UUID inventoryBom = artifactService.getArtifactData(w.inventory()).orElseThrow().getInternalBom().id();

		releaseService.getReleaseBomId(w.release(), false, false, false, null, BomStructureType.FLAT, WU, null);
		assertFalse(mergeInputs.isEmpty());
		assertTrue(mergeInputs.stream().allMatch(ids -> ids.equals(List.of(inventoryBom))), mergeInputs.toString());
		assertTrue(mergeInputs.stream().noneMatch(ids -> ids.contains(aggregateBom)));

		AcollectionData collection = acollectionService.resolveReleaseCollection(w.release(), WU);
		assertEquals(Set.of(w.inventory()), collection.getArtifacts().stream().map(VersionedArtifact::artifactUuid)
				.collect(Collectors.toSet()));

		sbomComponentService.reconcileReleaseSbomComponents(w.release());
		List<ReleaseArtifactIndex> index = releaseArtifactIndexRepository.findByOrgAndReleaseUuid(org.getUuid(), w.release());
		assertFalse(index.stream().anyMatch(i -> aggregate.equals(i.getCanonicalArtifactUuid())), index.toString());

		ExecutionResult r = asUser(admin(), "query($r: ID!) { release(releaseUuid: $r) { artifactDetails { uuid } "
				+ "syntheticArtifactDetails { uuid teaUuid } teaPublication { uuid state latestVersion exposure } "
				+ "updateEvents { rus rua newValue } } }", vars("r", w.release().toString()));
		Map<String, Object> release = data(r, "release");
		assertEquals(List.of(Map.of("uuid", w.inventory().toString())), release.get("artifactDetails"));
		List<Map<String, Object>> generated = (List<Map<String, Object>>) release.get("syntheticArtifactDetails");
		assertEquals(1, generated.size());
		assertEquals(aggregate.toString(), generated.get(0).get("uuid"));
		assertNotNull(generated.get(0).get("teaUuid"));
		Map<String, Object> pub = (Map<String, Object>) release.get("teaPublication");
		assertEquals("PUBLISHED", pub.get("state"));
		assertEquals("PRIVATE", pub.get("exposure"));
		assertTrue(((List<Map<String, Object>>) release.get("updateEvents")).stream()
				.anyMatch(e -> "TEA_PUBLICATION".equals(e.get("rus")) && "v1".equals(e.get("newValue"))));
	}

	// ---------------------------------------------------------------- 42

	@Test
	@SuppressWarnings("unchecked")
	void theUserSurfacePublishesRefusesHidesAndLists() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UserData admin = admin();
		Map<String, Object> v = vars("r", w.release().toString());

		Map<String, Object> before = data(asUser(admin, VIEW, v), "releaseTeaPublicationView");
		assertEquals(Boolean.TRUE, before.get("canPublish"));
		assertNull(before.get("publication"));

		Map<String, Object> published = data(asUser(admin, PUBLISH, v), "publishReleaseOnTea");
		assertEquals("PUBLISHED", published.get("outcome"));
		ExecutionResult again = asUser(admin, PUBLISH, v);
		assertFalse(again.getErrors().isEmpty());
		assertTrue(again.getErrors().get(0).getMessage().contains("already published"), again.getErrors().toString());
		assertEquals("UNCHANGED", ((Map<String, Object>) data(asUser(admin, REPUBLISH, v), "republishReleaseOnTea"))
				.get("outcome"));

		Map<String, Object> after = data(asUser(admin, VIEW, v), "releaseTeaPublicationView");
		assertEquals("ALREADY_PUBLISHED", after.get("refusal"));
		Map<String, Object> pub = (Map<String, Object>) after.get("publication");

		data(asUser(admin, HIDE, v), "hideReleaseOnTea");
		Map<String, Object> hidden = (Map<String, Object>) ((Map<String, Object>) data(asUser(admin, VIEW, v),
				"releaseTeaPublicationView")).get("publication");
		assertEquals("HIDDEN", hidden.get("state"));
		assertEquals("CONCEALED", hidden.get("exposure"));

		List<Map<String, Object>> collections = data(asUser(admin, COLLECTIONS, vars("p", pub.get("uuid"))),
				"teaPublicationCollections");
		assertEquals(1, collections.size());
		assertNotNull(collections.get(0).get("document"));
		assertEquals(1, ((List<?>) collections.get(0).get("entries")).size());

		World draft = componentRelease("pkg:generic/draft@1.0.0");
		ossReleaseService.updateReleaseLifecycle(draft.release(), ReleaseLifecycle.DRAFT, WU);
		ExecutionResult refused = asUser(admin, PUBLISH, vars("r", draft.release().toString()));
		assertFalse(refused.getErrors().isEmpty());
		String message = refused.getErrors().get(0).getMessage();
		assertTrue(message.contains("DRAFT") && message.contains("ASSEMBLED"), message);
	}

	// ---------------------------------------------------------------- 43

	@Test
	void publishNeedsPublishExternallyOrOrganizationAdmin() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UserData writer = member(PermissionType.READ_ONLY);
		userService.setUserPermission(writer.getUuid(), org.getUuid(), PermissionScope.COMPONENT, w.component(),
				PermissionType.READ_WRITE, List.of(), null, WU);
		writer = userService.getUserData(writer.getUuid()).orElseThrow();

		ExecutionResult denied = asUser(writer, PUBLISH, vars("r", w.release().toString()));
		assertFalse(denied.getErrors().isEmpty(), "READ_WRITE without PUBLISH_EXTERNALLY must not publish");

		userService.setUserPermission(writer.getUuid(), org.getUuid(), PermissionScope.COMPONENT, w.component(),
				PermissionType.READ_WRITE, List.of(PermissionFunction.PUBLISH_EXTERNALLY), null, WU);
		writer = userService.getUserData(writer.getUuid()).orElseThrow();
		data(asUser(writer, PUBLISH, vars("r", w.release().toString())), "publishReleaseOnTea");

		World other = componentRelease("pkg:generic/other@1.0.0");
		data(asUser(admin(), PUBLISH, vars("r", other.release().toString())), "publishReleaseOnTea");

		World viaKey = componentRelease("pkg:generic/key@1.0.0");
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "tea-5", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.COMPONENT, viaKey.component(), PermissionType.READ_WRITE, Set.of(), null)), WU);
		ExecutionResult keyDenied = asKey(key, PUBLISH_PROG, vars("r", viaKey.release().toString()));
		assertFalse(keyDenied.getErrors().isEmpty(), "a key without PUBLISH_EXTERNALLY must not publish");
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.COMPONENT, viaKey.component(), PermissionType.READ_WRITE,
				Set.of(PermissionFunction.PUBLISH_EXTERNALLY), null)), WU);
		data(asKey(key, PUBLISH_PROG, vars("r", viaKey.release().toString())), "publishReleaseOnTeaProgrammatic");
	}

	// ---------------------------------------------------------------- 44

	@Test
	void aProductCascadesToItsComponentReleases() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World a = componentRelease("pkg:generic/a@1.0.0");
		World b = componentRelease("pkg:generic/b@1.0.0");
		UUID product = product(List.of(a.release(), b.release()));

		TeaPublishResult r = publish(product, TeaPublishMode.PUBLISH);

		assertEquals(List.of(TeaChildOutcome.PUBLISHED, TeaChildOutcome.PUBLISHED),
				r.children().stream().map(c -> c.outcome()).toList());
		UUID productPub = r.publication().getUuid();
		TeaPublicationData childA = teaPublicationService.getPublicationOfRelease(a.release()).orElseThrow();
		assertEquals(productPub, childA.getCascadeOf());
		assertEquals(productPub, teaPublicationService.getPublicationOfRelease(b.release()).orElseThrow().getCascadeOf());
		assertEquals(1, events(a.release(), ReleaseUpdateScope.TEA_PUBLICATION).size());
		assertEquals(TeaExposureState.PRIVATE, teaPublicationService.exposureOf(childA).state());
		assertEquals(a.component(), teaPublicationService.exposureOf(childA).governingComponent().getUuid(),
				"a cascade child is governed by its own component (round 2, 3.4)");
		assertEquals(productPub, teaPublicationService.exposureOf(childA).cappedBy().getUuid());

		teaPublicationService.hide(product, null, WU);
		assertEquals(TeaExposureState.CONCEALED, teaPublicationService.exposureOf(
				teaPublicationService.getPublicationOfRelease(a.release()).orElseThrow()).state());
		assertEquals(TeaPublicationState.PUBLISHED,
				teaPublicationService.getPublicationOfRelease(a.release()).orElseThrow().getState());

		ossReleaseService.updateReleaseLifecycle(a.release(), ReleaseLifecycle.DRAFT, WU);
		TeaPublishResult again = publish(product, TeaPublishMode.REPUBLISH);
		assertEquals(TeaChildOutcome.REFUSED, again.children().get(0).outcome());
		assertEquals(TeaChildOutcome.UNCHANGED, again.children().get(1).outcome());

		World direct = componentRelease("pkg:generic/d@1.0.0");
		publish(direct.release(), TeaPublishMode.PUBLISH);
		UUID second = product(List.of(direct.release()));
		assertEquals(TeaChildOutcome.SKIPPED_DIRECT, publish(second, TeaPublishMode.PUBLISH).children().get(0).outcome());

		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PRODUCT_ONLY);
		World lone = componentRelease("pkg:generic/l@1.0.0");
		UUID third = product(List.of(lone.release()));
		assertTrue(publish(third, TeaPublishMode.PUBLISH).children().isEmpty());
		assertTrue(teaPublicationService.getPublicationOfRelease(lone.release()).isEmpty());
	}

	// ---------------------------------------------------------------- 45

	@Test
	void theCountSeamCountsPublishedReleasesResolvingToTheOrganization() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UserData admin = admin();
		String q = "query($o: ID!) { teaProfileEditorView(org: $o, scope: ORGANIZATION) { publishedReleases } }";
		java.util.function.Supplier<Integer> count = () -> (Integer) ((Map<?, ?>) data(asUser(admin, q,
				vars("o", org.getUuid().toString())), "teaProfileEditorView")).get("publishedReleases");
		assertEquals(0, count.get());

		World a = componentRelease("pkg:generic/a@1.0.0");
		World b = componentRelease("pkg:generic/b@1.0.0");
		World c = componentRelease("pkg:generic/c@1.0.0");
		publish(a.release(), TeaPublishMode.PUBLISH);
		publish(b.release(), TeaPublishMode.PUBLISH);
		publish(c.release(), TeaPublishMode.PUBLISH);
		teaPublicationService.hide(c.release(), null, WU);
		World d = componentRelease("pkg:generic/d@1.0.0");
		UUID product = product(List.of(d.release()));
		publish(product, TeaPublishMode.PUBLISH);

		assertEquals(4, count.get(), "a and b, the product and its cascaded d; never the hidden c");
	}

	// ---------------------------------------------------------------- 46

	@Test
	void twoArtifactRowsCannotShareATeaId() throws Exception {
		UUID tea = UUID.randomUUID();
		UUID first = seedBom("first.cdx.json");
		UUID second = seedBom("second.cdx.json");
		// round 4 (T-10 point 4): a whole-record save never introduces an id, so ids go in by the mint statement
		Artifact a = sharedArtifactService.getArtifact(first).orElseThrow();
		ArtifactData ad = ArtifactData.dataFromRecord(a);
		ad.setTeaUuid(tea);
		sharedArtifactService.saveArtifactWithoutSnapshot(a, ad, WU);
		assertNull(artifactService.getArtifactData(first).orElseThrow().getTeaUuid(), "the funnel wrote no id");
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		assertEquals(1, (int) tx.<Integer>execute(st -> artifactRepository.mintTeaUuid(first, tea.toString())));

		Exception e = assertThrows(Exception.class,
				() -> tx.execute(st -> artifactRepository.mintTeaUuid(second, tea.toString())));
		Throwable t = e;
		boolean integrity = false;
		while (null != t) {
			if (t instanceof DataIntegrityViolationException || String.valueOf(t.getMessage()).contains("artifacts_tea_uuid")) {
				integrity = true;
				break;
			}
			t = t.getCause();
		}
		assertTrue(integrity, String.valueOf(e));
	}

	// ---------------------------------------------------------------- 47

	@Test
	void twoConcurrentPublishesOfOneReleaseWriteOneVersion() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<TeaPublishOutcome>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return publish(w.release(), TeaPublishMode.PUBLISH_OR_REPUBLISH).outcome();
				}));
			}
			start.countDown();
			List<TeaPublishOutcome> outcomes = new ArrayList<>();
			for (Future<TeaPublishOutcome> f : futures) outcomes.add(f.get(120, TimeUnit.SECONDS));
			Collections.sort(outcomes);
			assertEquals(List.of(TeaPublishOutcome.PUBLISHED, TeaPublishOutcome.UNCHANGED), outcomes);
		} finally {
			pool.shutdownNow();
		}
		TeaPublication head = publicationRepository.findByRelease(w.release()).orElseThrow();
		assertEquals(1, head.getLatestVersion());
		assertEquals(1, collectionRepository.listByPublication(head.getUuid()).size());
		assertEquals(1, stored(w.release()).getSyntheticArtifacts().size(), "one aggregate, uploaded once");
	}
}
