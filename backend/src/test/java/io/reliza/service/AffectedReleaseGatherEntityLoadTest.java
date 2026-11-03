/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.engine.spi.EntityKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Deliverable;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.Variant;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.repositories.MetricsVulnerabilityTestBase;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.repositories.VariantRepository;
import io.reliza.ws.App;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The affected-release gather reads release uuids only. The notification fan-out runs it chunk by
 * chunk inside one read-only transaction; when its statements returned full Release rows, a CVE in
 * thousands of releases kept every one of them (record_data and metrics jsonb included) in that
 * transaction's persistence context until the JVM ran out of heap.
 *
 * <p>Each case runs the gather in a read-only transaction, as the fan-out does, and then lists the
 * entities the Hibernate session holds. A control loads a Release through the entity-returning
 * twin in the same kind of transaction, to show that the probe sees a loaded release.
 */
@SpringBootTest(classes = {App.class})
class AffectedReleaseGatherEntityLoadTest extends MetricsVulnerabilityTestBase {

	@PersistenceContext private EntityManager entityManager;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private SourceCodeEntryRepository sourceCodeEntryRepository;
	@Autowired private DeliverableRepository deliverableRepository;
	@Autowired private VariantRepository variantRepository;

	private record Fixture(UUID org, List<UUID> artifacts, Set<UUID> releases) {}

	/** One release per path: direct, through an SCE, through a deliverable, as a generated artifact. */
	private Fixture fixture() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ComponentAndBranch cb = component(org, ComponentType.COMPONENT);
		ComponentAndBranch product = component(org, ComponentType.PRODUCT);
		UUID direct = UUID.randomUUID();
		UUID viaSce = UUID.randomUUID();
		UUID viaDeliverable = UUID.randomUUID();
		UUID generated = UUID.randomUUID();

		UUID r1 = release(org, cb, List.of(), List.of());
		mergeReleaseRecord(r1, "{\"artifacts\": [\"" + direct + "\"]}");
		UUID r2 = release(org, cb, List.of(), List.of());
		mergeReleaseRecord(r2, "{\"sourceCodeEntry\": \"" + sce(org, viaSce, cb.component().getUuid()) + "\"}");
		UUID r3 = release(org, product, List.of(), List.of());
		variant(org, r3, deliverable(org, viaDeliverable));
		UUID r4 = release(org, cb, List.of(), List.of());
		mergeReleaseRecord(r4, "{\"syntheticArtifacts\": [\"" + generated + "\"]}");
		return new Fixture(org, List.of(direct, viaSce, viaDeliverable, generated), Set.of(r1, r2, r3, r4));
	}

	private record Probe<T>(T result, Set<String> loadedEntities) {}

	/** Runs {@code read} in a read-only transaction and lists the entity names its session then holds. */
	private <T> Probe<T> inReadTransaction(Supplier<T> read) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		tx.setReadOnly(true);
		return tx.execute(status -> {
			T result = read.get();
			Set<String> loaded = entityManager.unwrap(Session.class).getStatistics().getEntityKeys().stream()
					.map(k -> ((EntityKey) k).getEntityName()).collect(Collectors.toSet());
			return new Probe<>(result, loaded);
		});
	}

	@Test
	void theBatchedGatherReturnsUuidsAndLoadsNoReleaseEntity() throws Exception {
		Fixture f = fixture();
		Probe<Set<UUID>> probe = inReadTransaction(
				() -> sharedReleaseService.gatherReleaseIdsForArtifacts(f.artifacts(), f.org()));
		assertEquals(f.releases(), probe.result(), "every path still reaches its release");
		assertFalse(probe.loadedEntities().contains(Release.class.getName()),
				"no Release entity in the session: " + probe.loadedEntities());
	}

	@Test
	void thePerArtifactGatherLoadsNoReleaseEntity() throws Exception {
		Fixture f = fixture();
		Probe<Set<UUID>> probe = inReadTransaction(() -> f.artifacts().stream()
				.flatMap(a -> sharedReleaseService.gatherReleasesForArtifact(a, f.org()).stream())
				.map(ReleaseData::getUuid).collect(Collectors.toSet()));
		assertEquals(f.releases(), probe.result());
		assertFalse(probe.loadedEntities().contains(Release.class.getName()),
				"no Release entity in the session: " + probe.loadedEntities());
	}

	@Test
	void controlTheProbeSeesALoadedRelease() throws Exception {
		Fixture f = fixture();
		Probe<List<Release>> probe = inReadTransaction(
				() -> releaseRepository.findReleasesByReleaseArtifact(f.artifacts().get(0).toString(), f.org().toString()));
		assertEquals(1, probe.result().size());
		assertTrue(probe.loadedEntities().contains(Release.class.getName()),
				"the entity-returning twin does load a Release: " + probe.loadedEntities());
	}

	private UUID sce(UUID org, UUID artifact, UUID component) {
		SourceCodeEntry sce = new SourceCodeEntry();
		sce.setUuid(UUID.randomUUID());
		sce.setCreatedDate(ZonedDateTime.now());
		sce.setLastUpdatedDate(ZonedDateTime.now());
		sce.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", org.toString());
		recordData.put("artifacts", List.of(Map.of("artifactUuid", artifact.toString(),
				"componentUuid", component.toString())));
		sce.setRecordData(recordData);
		return sourceCodeEntryRepository.save(sce).getUuid();
	}

	private UUID deliverable(UUID org, UUID artifact) {
		Deliverable d = new Deliverable();
		d.setUuid(UUID.randomUUID());
		d.setCreatedDate(ZonedDateTime.now());
		d.setLastUpdatedDate(ZonedDateTime.now());
		d.setSchemaVersion(0);
		d.setRecordData(new HashMap<>(Map.of("org", org.toString(), "artifacts", List.of(artifact.toString()))));
		return deliverableRepository.save(d).getUuid();
	}

	private void variant(UUID org, UUID release, UUID deliverable) {
		Variant v = new Variant();
		v.setUuid(UUID.randomUUID());
		v.setCreatedDate(ZonedDateTime.now());
		v.setLastUpdatedDate(ZonedDateTime.now());
		v.setSchemaVersion(0);
		v.setRecordData(new HashMap<>(Map.of("org", org.toString(), "release", release.toString(),
				"outboundDeliverables", List.of(deliverable.toString()))));
		variantRepository.save(v);
	}
}
