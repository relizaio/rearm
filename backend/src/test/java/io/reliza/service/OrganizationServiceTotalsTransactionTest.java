/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import io.reliza.repositories.OrganizationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * TPF-70 (architecture round 2, test 4.5 row 1): {@code getNumericAnalytics} runs the totals
 * statement inside one read-only transaction that first sets, in this order, {@code enable_seqscan}
 * off, {@code jit} off and a 30 s {@code statement_timeout}, all {@code SET LOCAL}, and nothing
 * else. Plain Mockito, no Spring context; the injected fields are set by reflection (the
 * {@code OrganizationServiceSettingsValidationTest} idiom).
 */
class OrganizationServiceTotalsTransactionTest {

	private static final List<String> COUNT_KEYS = List.of("components", "products", "releases", "vcs",
			"artifacts", "commits", "branches", "deliverables", "feature_sets");

	private final UUID org = UUID.randomUUID();
	private OrganizationRepository repository;
	private EntityManager entityManager;
	private Query query;
	private PlatformTransactionManager transactionManager;
	private TransactionStatus status;
	private OrganizationService service;

	private static void inject(Object target, String field, Object value) throws Exception {
		Field f = OrganizationService.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	@BeforeEach
	void setUp() throws Exception {
		repository = mock(OrganizationRepository.class);
		Map<String, BigInteger> counts = new HashMap<>();
		for (String key : COUNT_KEYS) {
			counts.put(key, BigInteger.ONE);
		}
		when(repository.getNumericAnalytics(org.toString())).thenReturn(counts);
		service = new OrganizationService(repository);

		entityManager = mock(EntityManager.class);
		query = mock(Query.class);
		when(entityManager.createNativeQuery(anyString())).thenReturn(query);
		inject(service, "entityManager", entityManager);

		transactionManager = mock(PlatformTransactionManager.class);
		status = mock(TransactionStatus.class);
		when(transactionManager.getTransaction(any())).thenReturn(status);
		inject(service, "transactionManager", transactionManager);

		UserService userService = mock(UserService.class);
		when(userService.listUserDataByOrg(org)).thenReturn(List.of());
		inject(service, "userService", userService);
		UserGroupService userGroupService = mock(UserGroupService.class);
		when(userGroupService.getUserGroupsByOrganization(org)).thenReturn(List.of());
		inject(service, "userGroupService", userGroupService);
	}

	@Test
	void theTotalsReadRunsInsideTheReadOnlyPlannerTransaction() {
		Map<String, BigInteger> result = service.getNumericAnalytics(org);

		InOrder order = inOrder(transactionManager, entityManager, query, repository);
		order.verify(transactionManager).getTransaction(any());
		order.verify(entityManager).createNativeQuery("SET LOCAL enable_seqscan = off");
		order.verify(query).executeUpdate();
		order.verify(entityManager).createNativeQuery("SET LOCAL jit = off");
		order.verify(query).executeUpdate();
		order.verify(entityManager).createNativeQuery("SET LOCAL statement_timeout = '30s'");
		order.verify(query).executeUpdate();
		order.verify(repository).getNumericAnalytics(org.toString());
		order.verify(transactionManager).commit(status);

		verify(entityManager, times(3)).createNativeQuery(anyString());
		verify(query, times(3)).executeUpdate();

		ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(transactionManager).getTransaction(definition.capture());
		assertTrue(definition.getValue().isReadOnly(), "the totals transaction must be read-only");

		assertEquals(COUNT_KEYS.size() + 2, result.size(), result.toString());
		for (String key : COUNT_KEYS) {
			assertEquals(BigInteger.ONE, result.get(key), key);
		}
		assertEquals(BigInteger.ZERO, result.get("admin_and_write_users"));
		assertEquals(BigInteger.ZERO, result.get("read_only_users"));
	}
}
