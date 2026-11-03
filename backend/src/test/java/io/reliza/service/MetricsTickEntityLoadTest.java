/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
import io.reliza.model.Release;
import io.reliza.repositories.MetricsVulnerabilityTestBase;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.ReleaseRepository.MetricsComputeCandidate;
import io.reliza.ws.App;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The metrics tick reads candidates, not releases. Its finders used to return whole Release rows
 * (record_data and the metrics jsonb) for up to ~100 releases a tick, held for the whole tick,
 * when the tick reads only the uuid, the fence's failure count and the drain-mode product flag;
 * the product cascade and markContainingProductsStale loaded whole product rows to read uuids.
 *
 * <p>Each case runs the read in a read-only transaction and then lists the entities the Hibernate
 * session holds, as {@code AffectedReleaseGatherEntityLoadTest} does. A control loads a Release
 * through the entity-returning twin to show that the probe sees a loaded release.
 */
@SpringBootTest(classes = {App.class})
class MetricsTickEntityLoadTest extends MetricsVulnerabilityTestBase {

	@PersistenceContext private EntityManager entityManager;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private ReleaseRepository releaseRepository;

	/** A component release, a product over it with two recorded compute failures, and an empty product. */
	private record Fixture(UUID org, UUID child, UUID product, UUID emptyProduct) {}

	private Fixture fixture() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ComponentAndBranch cb = component(org, ComponentType.COMPONENT);
		ComponentAndBranch pb = component(org, ComponentType.PRODUCT);
		UUID child = release(org, cb, List.of(), List.of());
		UUID product = release(org, pb, List.of(), List.of());
		mergeReleaseRecord(product, "{\"parentReleases\": [{\"release\": \"" + child + "\"}]}");
		UUID emptyProduct = release(org, pb, List.of(), List.of());
		mergeReleaseRecord(emptyProduct, "{\"parentReleases\": []}");
		for (UUID r : List.of(child, product, emptyProduct)) makeEligible(r);
		// Two failures with a zero fence: the count is recorded and the row stays eligible.
		releaseRepository.recordMetricsComputeIncomplete(product, 0);
		releaseRepository.recordMetricsComputeIncomplete(product, 0);
		return new Fixture(org, child, product, emptyProduct);
	}

	/** In the BY_UPDATE pool: no lastScanned, so any last_updated_date is past it. */
	private void makeEligible(UUID release) {
		jdbc.update("UPDATE rearm.releases SET metrics = metrics - 'lastScanned' WHERE uuid = ?", release);
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

	private static Map<UUID, MetricsComputeCandidate> byUuid(List<MetricsComputeCandidate> candidates) {
		return candidates.stream().collect(Collectors.toMap(MetricsComputeCandidate::getUuid, Function.identity()));
	}

	@Test
	void theByUpdateFinderReturnsCandidatesAndLoadsNoReleaseEntity() throws Exception {
		Fixture f = fixture();
		// The finder returns only the oldest {@code limit} eligible rows; take them all so the
		// fixture is in the window whatever else the shared database holds.
		int limit = (int) releaseRepository.countReleasesEligibleForMetricsCompute() + 10;
		Probe<List<MetricsComputeCandidate>> probe = inReadTransaction(
				() -> releaseRepository.findReleasesForMetricsComputeByUpdate(limit));
		assertFalse(probe.loadedEntities().contains(Release.class.getName()),
				"no Release entity in the session: " + probe.loadedEntities());

		Map<UUID, MetricsComputeCandidate> found = byUuid(probe.result());
		MetricsComputeCandidate child = found.get(f.child());
		MetricsComputeCandidate product = found.get(f.product());
		MetricsComputeCandidate emptyProduct = found.get(f.emptyProduct());
		assertNotNull(child, "the eligible component release is picked");
		assertNotNull(product, "the eligible product release is picked");
		assertNotNull(emptyProduct, "the eligible product release without children is picked");
		assertEquals(Boolean.FALSE, child.getProduct(), "no parentReleases: a component for drain mode");
		assertEquals(Boolean.TRUE, product.getProduct(), "a non-empty parentReleases: a product for drain mode");
		assertEquals(Boolean.FALSE, emptyProduct.getProduct(),
				"an empty parentReleases classifies as a component, as the in-memory check did");
		assertNull(child.getMetricsComputeFailureCount(), "no failure recorded");
		assertEquals(2, product.getMetricsComputeFailureCount(), "the fence's failure count is read from flow_control");
	}

	@Test
	void theProductCascadeReadsLoadNoReleaseEntity() throws Exception {
		Fixture f = fixture();
		Probe<List<MetricsComputeCandidate>> candidates = inReadTransaction(() -> releaseRepository
				.findProductMetricsCandidatesByRelease(f.org().toString(), f.child().toString()));
		assertEquals(Set.of(f.product()), byUuid(candidates.result()).keySet());
		MetricsComputeCandidate product = candidates.result().get(0);
		assertEquals(Boolean.TRUE, product.getProduct());
		assertEquals(2, product.getMetricsComputeFailureCount());
		assertFalse(candidates.loadedEntities().contains(Release.class.getName()),
				"no Release entity in the session: " + candidates.loadedEntities());

		Probe<Optional<String>> org = inReadTransaction(() -> releaseRepository.findOrgOfRelease(f.child()));
		assertEquals(Optional.of(f.org().toString()), org.result());
		assertFalse(org.loadedEntities().contains(Release.class.getName()),
				"no Release entity in the session: " + org.loadedEntities());
		assertTrue(inReadTransaction(() -> releaseRepository.findOrgOfRelease(UUID.randomUUID())).result().isEmpty(),
				"a release that does not exist has no organization");
	}

	@Test
	void markContainingProductsStaleReadLoadsNoReleaseEntity() throws Exception {
		Fixture f = fixture();
		Probe<List<UUID>> probe = inReadTransaction(() -> releaseRepository
				.findProductUuidsToRequeueByRelease(f.org().toString(), f.child().toString()));
		assertEquals(List.of(f.product()), probe.result());
		assertFalse(probe.loadedEntities().contains(Release.class.getName()),
				"no Release entity in the session: " + probe.loadedEntities());
	}

	@Test
	void controlTheProbeSeesALoadedRelease() throws Exception {
		Fixture f = fixture();
		Probe<List<Release>> probe = inReadTransaction(
				() -> releaseRepository.findProductsByRelease(f.org().toString(), f.child().toString()));
		assertEquals(1, probe.result().size());
		assertTrue(probe.loadedEntities().contains(Release.class.getName()),
				"the entity-returning twin does load a Release: " + probe.loadedEntities());
	}
}
