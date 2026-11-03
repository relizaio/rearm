/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.OrgTotalsTable;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.TotalsCell;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.OrganizationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * TPF-70 (architecture round 2, test 4.5 row 1): {@code getNumericAnalytics} runs the live totals
 * statement inside one read-only transaction that first sets, in this order, {@code enable_seqscan}
 * off, {@code jit} off and a 30 s {@code statement_timeout}, all {@code SET LOCAL}, and nothing
 * else. TPF-139 / TPF-141 (architecture round 2, 6.1, 6.2, 6.4.1-6.4.4): the four nightly counts
 * come from the organization record the caller loaded (no second query), null while pending, with
 * counted_at the oldest instant of the non-null counts; both whole-record funnels carry the stored
 * totals forward under the row lock before the audit row. Plain Mockito, no Spring context; the
 * injected fields are set by reflection (the {@code OrganizationServiceSettingsValidationTest} idiom).
 */
class OrganizationServiceTotalsTransactionTest {

	private static final List<String> LIVE_KEYS = List.of("components", "products", "vcs", "branches", "feature_sets");
	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String STORED_NINE =
			"{\"releases\": {\"count\": 9, \"countedAt\": \"2026-11-02T00:00:03.512417Z\"}}";
	private static final ZonedDateTime T1 = ZonedDateTime.of(2026, 11, 2, 0, 0, 3, 512_417_000, ZoneOffset.UTC);

	private final UUID org = UUID.randomUUID();
	private OrganizationRepository repository;
	private EntityManager entityManager;
	private Query query;
	private PlatformTransactionManager transactionManager;
	private TransactionStatus status;
	private GetOrganizationService getOrganizationService;
	private AuditService auditService;
	private OrganizationService service;

	@BeforeEach
	void setUp() {
		repository = mock(OrganizationRepository.class);
		Map<String, Object> counts = new HashMap<>();
		for (String key : LIVE_KEYS) {
			counts.put(key, 1L);
		}
		when(repository.getLiveNumericAnalytics(org.toString())).thenReturn(counts);
		service = new OrganizationService(repository);

		entityManager = mock(EntityManager.class);
		query = mock(Query.class);
		when(entityManager.createNativeQuery(anyString())).thenReturn(query);
		ReflectionTestUtils.setField(service, "entityManager", entityManager);

		transactionManager = mock(PlatformTransactionManager.class);
		status = mock(TransactionStatus.class);
		when(transactionManager.getTransaction(any())).thenReturn(status);
		ReflectionTestUtils.setField(service, "transactionManager", transactionManager);

		UserService userService = mock(UserService.class);
		when(userService.listUserDataByOrg(org)).thenReturn(List.of());
		ReflectionTestUtils.setField(service, "userService", userService);
		UserGroupService userGroupService = mock(UserGroupService.class);
		when(userGroupService.getUserGroupsByOrganization(org)).thenReturn(List.of());
		ReflectionTestUtils.setField(service, "userGroupService", userGroupService);

		getOrganizationService = mock(GetOrganizationService.class);
		auditService = mock(AuditService.class);
		ReflectionTestUtils.setField(service, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(service, "auditService", auditService);
	}

	private OrganizationData od(Map<String, TotalsCell> totals) {
		OrganizationData od = new OrganizationData();
		od.setUuid(org);
		od.setTotals(totals);
		return od;
	}

	private static Organization entityWithRecord(String json) {
		Organization o = new Organization();
		@SuppressWarnings("unchecked")
		Map<String, Object> record = Utils.OM.readValue(json, LinkedHashMap.class);
		o.setRecordData(record);
		return o;
	}

	private void assertLiveAndUserKeys(Map<String, Object> result) {
		for (String key : LIVE_KEYS) {
			assertEquals(1L, result.get(key), key);
		}
		assertEquals(BigInteger.ZERO, result.get("admin_and_write_users"));
		assertEquals(BigInteger.ZERO, result.get("read_only_users"));
		assertEquals(12, result.size(), result.toString());
	}

	private void assertPending(Map<String, Object> result) {
		assertLiveAndUserKeys(result);
		for (OrgTotalsTable table : OrgTotalsTable.values()) {
			assertTrue(result.containsKey(table.getMapKey()), table + " in " + result);
			assertNull(result.get(table.getMapKey()), table + " in " + result);
		}
		assertTrue(result.containsKey("counted_at"), result.toString());
		assertNull(result.get("counted_at"));
	}

	// ---------------------------------------------------------------- 6.1 the record shape

	/** 6.1.1: the serialised key set and the three stored forms read back. */
	@Test
	void theRecordCarriesTotalsAndReadsEveryStoredForm() {
		OrganizationData fresh = new OrganizationData();
		fresh.setName("x");
		Map<String, Object> record = Utils.dataToRecord(fresh);
		assertTrue(record.containsKey("totals"), record.keySet().toString());
		assertEquals(Map.of(), record.get("totals"));
		for (String helper : List.of("totalsCount", "oldestTotalsCountedAt", "withStoredTotals")) {
			assertFalse(record.containsKey(helper), helper + " in " + record.keySet());
		}

		OrganizationData absent = OrganizationData.orgDataFromDbRecord(entityWithRecord("{\"name\":\"x\"}"));
		assertNotNull(absent.getTotals());
		assertTrue(absent.getTotals().isEmpty());
		for (OrgTotalsTable table : OrgTotalsTable.values()) {
			assertNull(absent.totalsCount(table), table.toString());
		}

		OrganizationData jsonNull = OrganizationData.orgDataFromDbRecord(entityWithRecord("{\"name\":\"x\",\"totals\":null}"));
		assertNull(jsonNull.getTotals());
		for (OrgTotalsTable table : OrgTotalsTable.values()) {
			assertNull(jsonNull.totalsCount(table), table.toString());
		}

		OrganizationData one = OrganizationData.orgDataFromDbRecord(entityWithRecord(
				"{\"name\":\"x\",\"totals\":{\"releases\":{\"count\":7,\"countedAt\":\"2026-11-02T00:00:03.512417Z\"}}}"));
		assertEquals(7L, one.totalsCount(OrgTotalsTable.RELEASES));
		assertNull(one.totalsCount(OrgTotalsTable.ARTIFACTS));
		assertNull(one.totalsCount(OrgTotalsTable.COMMITS));
		assertNull(one.totalsCount(OrgTotalsTable.DELIVERABLES));
		assertTrue(one.oldestTotalsCountedAt().isPresent());
		assertEquals(Instant.parse("2026-11-02T00:00:03.512417Z"), one.oldestTotalsCountedAt().get().toInstant());
	}

	/** 6.1.2: the oldest instant among the cells whose count is non-null. */
	@Test
	void theOldestCountedAtIgnoresCellsWithoutACountOrAnInstant() {
		Map<String, TotalsCell> mixed = new LinkedHashMap<>();
		mixed.put("releases", new TotalsCell(7L, T1));
		mixed.put("artifacts", new TotalsCell(null, null));
		mixed.put("commits", new TotalsCell(3L, T1.minusHours(1)));
		mixed.put("deliverables", new TotalsCell(5L, T1.plusHours(1)));
		assertEquals(T1.minusHours(1).toInstant(), od(mixed).oldestTotalsCountedAt().get().toInstant());

		Map<String, TotalsCell> noCounts = new LinkedHashMap<>();
		for (OrgTotalsTable table : OrgTotalsTable.values()) {
			noCounts.put(table.getMapKey(), new TotalsCell(null, T1));
		}
		assertTrue(od(noCounts).oldestTotalsCountedAt().isEmpty());

		Map<String, TotalsCell> noInstant = new LinkedHashMap<>();
		noInstant.put("releases", new TotalsCell(4L, null));
		assertTrue(od(noInstant).oldestTotalsCountedAt().isEmpty());
	}

	/** 6.1.3: the stored side always wins; an unbacked key is removed; other keys untouched. */
	@Test
	void withStoredTotalsTakesTheStoredSide() {
		Map<String, Object> forgedCell = new LinkedHashMap<>(Map.of("count", 1, "countedAt", "a"));
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("name", "x");
		input.put("totals", new LinkedHashMap<>(Map.of("releases", forgedCell)));
		Map<String, Object> inputCopy = new LinkedHashMap<>(input);

		Map<String, Object> replaced = OrganizationData.withStoredTotals(input, Optional.of(STORED_NINE));
		assertEquals(Utils.OM.readValue(STORED_NINE, LinkedHashMap.class), replaced.get("totals"));
		assertEquals("x", replaced.get("name"));
		assertEquals(inputCopy, input, "the input map must not be modified");

		Map<String, Object> removed = OrganizationData.withStoredTotals(input, Optional.empty());
		assertFalse(removed.containsKey("totals"), removed.toString());
		assertEquals("x", removed.get("name"));

		Map<String, Object> without = new LinkedHashMap<>(Map.of("name", "y"));
		Map<String, Object> added = OrganizationData.withStoredTotals(without, Optional.of(STORED_NINE));
		assertEquals(Utils.OM.readValue(STORED_NINE, LinkedHashMap.class), added.get("totals"));
		assertEquals("y", added.get("name"));
		assertEquals(1, without.size());
	}

	// ---------------------------------------------------------------- 6.2 the reader

	/** 6.2.1: the wrapper unchanged, no second query, the mixed state with the oldest counted_at. */
	@Test
	void theTotalsReadRunsInsideTheReadOnlyPlannerTransaction() {
		ZonedDateTime t2 = T1.minusHours(1);
		ZonedDateTime t3 = T1.plusHours(1);
		Map<String, TotalsCell> totals = new LinkedHashMap<>();
		totals.put("releases", new TotalsCell(7L, T1));
		totals.put("artifacts", new TotalsCell(null, null));
		totals.put("commits", new TotalsCell(3L, t2));
		totals.put("deliverables", new TotalsCell(5L, t3));

		Map<String, Object> result = service.getNumericAnalytics(od(totals));

		InOrder order = inOrder(transactionManager, entityManager, query, repository);
		order.verify(transactionManager).getTransaction(any());
		order.verify(entityManager).createNativeQuery("SET LOCAL enable_seqscan = off");
		order.verify(query).executeUpdate();
		order.verify(entityManager).createNativeQuery("SET LOCAL jit = off");
		order.verify(query).executeUpdate();
		order.verify(entityManager).createNativeQuery("SET LOCAL statement_timeout = '30s'");
		order.verify(query).executeUpdate();
		order.verify(repository).getLiveNumericAnalytics(org.toString());
		order.verify(transactionManager).commit(status);

		verify(entityManager, times(3)).createNativeQuery(anyString());
		verify(query, times(3)).executeUpdate();
		verify(repository).getLiveNumericAnalytics(org.toString());
		verifyNoMoreInteractions(repository);

		ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(transactionManager).getTransaction(definition.capture());
		assertTrue(definition.getValue().isReadOnly(), "the totals transaction must be read-only");

		assertLiveAndUserKeys(result);
		assertEquals(7L, result.get("releases"));
		assertTrue(result.containsKey("artifacts"), result.toString());
		assertNull(result.get("artifacts"));
		assertEquals(3L, result.get("commits"));
		assertEquals(5L, result.get("deliverables"));
		assertEquals(DateTimeFormatter.ISO_INSTANT.format(t2.toInstant()), result.get("counted_at"));
	}

	/** 6.2.2: no totals yet (the default empty map, or a JSON null): pending. */
	@Test
	void noStoredTotalsReadAsPending() {
		assertPending(service.getNumericAnalytics(od(new LinkedHashMap<>())));
		assertPending(service.getNumericAnalytics(od(null)));
	}

	/** 6.2.3: the full state equals the section 10 map. */
	@Test
	void theFullStateIsTheSectionTenMap() {
		Map<String, TotalsCell> totals = new LinkedHashMap<>();
		totals.put("releases", new TotalsCell(104233L, T1));
		totals.put("artifacts", new TotalsCell(512904L, T1.plusNanos(92_000_000)));
		totals.put("commits", new TotalsCell(201118L, T1.plusNanos(179_000_000)));
		totals.put("deliverables", new TotalsCell(49877L, T1.plusNanos(210_000_000)));

		Map<String, Object> result = service.getNumericAnalytics(od(totals));

		Map<String, Object> expected = new HashMap<>();
		for (String key : LIVE_KEYS) {
			expected.put(key, 1L);
		}
		expected.put("releases", 104233L);
		expected.put("artifacts", 512904L);
		expected.put("commits", 201118L);
		expected.put("deliverables", 49877L);
		expected.put("counted_at", "2026-11-02T00:00:03.512417Z");
		expected.put("admin_and_write_users", BigInteger.ZERO);
		expected.put("read_only_users", BigInteger.ZERO);
		assertEquals(expected, result);
	}

	/** 6.2.4: a missing cell (example 3) and a failed night (example 4). */
	@Test
	void theMixedStatesFollowSectionTen() {
		Map<String, TotalsCell> missing = new LinkedHashMap<>();
		missing.put("artifacts", new TotalsCell(11L, T1));
		missing.put("commits", new TotalsCell(12L, T1.plusSeconds(1)));
		missing.put("deliverables", new TotalsCell(13L, T1.plusSeconds(2)));
		Map<String, Object> result = service.getNumericAnalytics(od(missing));
		assertLiveAndUserKeys(result);
		assertTrue(result.containsKey("releases"), result.toString());
		assertNull(result.get("releases"));
		assertEquals(11L, result.get("artifacts"));
		assertEquals(12L, result.get("commits"));
		assertEquals(13L, result.get("deliverables"));
		assertEquals(DateTimeFormatter.ISO_INSTANT.format(T1.toInstant()), result.get("counted_at"));

		Map<String, TotalsCell> failed = new LinkedHashMap<>();
		failed.put("releases", new TotalsCell(1L, T1));
		failed.put("artifacts", new TotalsCell(2L, T1.minusDays(1)));
		failed.put("commits", new TotalsCell(3L, T1));
		failed.put("deliverables", new TotalsCell(4L, T1));
		Map<String, Object> failedResult = service.getNumericAnalytics(od(failed));
		assertLiveAndUserKeys(failedResult);
		assertEquals(2L, failedResult.get("artifacts"));
		assertEquals(DateTimeFormatter.ISO_INSTANT.format(T1.minusDays(1).toInstant()), failedResult.get("counted_at"));
	}

	// ---------------------------------------------------------------- 6.4.1-6.4.4 the guard

	/** An existing organization the funnel rows save; its record carries {@code totals} as given. */
	private Organization existing(Map<String, TotalsCell> totals) {
		Organization entity = new Organization();
		OrganizationData data = new OrganizationData();
		data.setName("totals-org");
		data.setTotals(totals);
		entity.setRecordData(Utils.dataToRecord(data));
		when(repository.findById(entity.getUuid())).thenReturn(Optional.of(entity));
		when(getOrganizationService.getOrganization(entity.getUuid())).thenReturn(Optional.of(entity));
		when(getOrganizationService.getOrganizationData(entity.getUuid()))
				.thenAnswer(inv -> Optional.of(OrganizationData.orgDataFromDbRecord(entity)));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(repository.currentTeaUuidForUpdate(any())).thenReturn(Optional.empty());
		when(repository.currentStatusForUpdate(any())).thenReturn(Optional.empty());
		return entity;
	}

	private Organization saved() {
		ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
		verify(repository).save(saved.capture());
		return saved.getValue();
	}

	/** 6.4.1: the stored totals are saved, read under the lock after the TEA id and status, before the audit. */
	@Test
	void updateSettingsWritesTheStoredTotalsBackUnderTheLockBeforeTheAudit() throws Exception {
		Organization entity = existing(new LinkedHashMap<>());
		when(repository.currentTotalsForUpdate(entity.getUuid())).thenReturn(Optional.of(STORED_NINE));

		service.updateSettings(entity.getUuid(), new OrganizationData.Settings(), WU);

		OrganizationData savedData = OrganizationData.orgDataFromDbRecord(saved());
		assertEquals(9L, savedData.totalsCount(OrgTotalsTable.RELEASES));
		assertNull(savedData.totalsCount(OrgTotalsTable.ARTIFACTS));
		assertNull(savedData.totalsCount(OrgTotalsTable.COMMITS));
		assertNull(savedData.totalsCount(OrgTotalsTable.DELIVERABLES));
		InOrder order = inOrder(repository, auditService);
		order.verify(repository).currentTeaUuidForUpdate(entity.getUuid());
		order.verify(repository).currentStatusForUpdate(entity.getUuid());
		order.verify(repository).currentTotalsForUpdate(entity.getUuid());
		order.verify(auditService).createAndSaveAuditRecord(TableName.ORGANIZATIONS, entity);
		order.verify(repository).save(any());
	}

	/** 6.4.2: totals the row does not have are never saved. */
	@Test
	void aRecordCarryingTotalsTheRowDoesNotHaveIsSavedWithoutThem() throws Exception {
		Map<String, TotalsCell> forged = new LinkedHashMap<>();
		forged.put("releases", new TotalsCell(1L, T1));
		Organization entity = existing(forged);
		when(repository.currentTotalsForUpdate(entity.getUuid())).thenReturn(Optional.empty());

		service.updateSettings(entity.getUuid(), new OrganizationData.Settings(), WU);

		assertFalse(saved().getRecordData().containsKey("totals"), entity.getRecordData().toString());
	}

}
