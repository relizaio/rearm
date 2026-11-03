/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.SchedulerGuard;
import io.reliza.model.OrgTotalsTable;
import io.reliza.model.OrganizationData;
import io.reliza.repositories.OrgTotalsCountRepository;
import io.reliza.repositories.OrganizationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * TPF-139: the nightly counts of the Statistics totals that grow with release volume (releases,
 * artifacts, source code entries as commits, deliverables) into the organization's record data
 * (OrganizationRepository.stampTotal, TPF-141). Called only from
 * {@code SchedulingService.computeAnalyticsMetrics} under the COMPUTE_ANALYTICS_METRICS advisory
 * lock, after the analytics pass; nothing on the request path counts these tables.
 *
 * <p>Each table of each organization is its own unit: the count runs in its own read-only
 * transaction with the TPF-70 planner settings and the long nightly timeout, and its result is
 * written at once in its own short transaction. A count that fails (a timeout, any Exception or a
 * non-fatal Error) writes nothing, so that table keeps its previous value and counted_at; the
 * other tables count and stamp before and after it.
 */
@Service
public class OrgTotalsCacheService {

	private static final Logger log = LoggerFactory.getLogger(OrgTotalsCacheService.class);

	/** One count that did not run to its stamp. */
	public record CountFailure(UUID org, OrgTotalsTable table) {}

	/** What a nightly pass did: the organizations it walked and the counts that failed. */
	public record NightlyCountSummary(int orgsCounted, List<CountFailure> failures) {}

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private OrganizationService organizationService;

	@Autowired
	private OrgTotalsCountRepository countRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	/** The nightly pass: every listed organization that is not archived, four counts each. */
	public NightlyCountSummary countAllOrgs() {
		long started = System.currentTimeMillis();
		List<OrganizationData> orgs = organizationService.listAllOrganizationData();
		List<CountFailure> failures = new ArrayList<>();
		int orgsCounted = 0;
		for (OrganizationData org : orgs) {
			if (org.getStatus() == StatusEnum.ARCHIVED
					|| CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(org.getUuid())) {
				continue;
			}
			orgsCounted++;
			for (OrgTotalsTable table : countAndStampOrg(org.getUuid())) {
				failures.add(new CountFailure(org.getUuid(), table));
			}
		}
		long elapsed = System.currentTimeMillis() - started;
		if (failures.isEmpty()) {
			log.info("org totals: {} org(s) counted, {} ms", orgsCounted, elapsed);
		} else {
			log.warn("org totals: {} org(s) counted, {} count(s) failed ({}), {} ms", orgsCounted,
					failures.size(), failures, elapsed);
		}
		return new NightlyCountSummary(orgsCounted, List.copyOf(failures));
	}

	/**
	 * One organization's four counts, each written as soon as it is known; returns the tables that
	 * failed. Each table runs in its own SchedulerGuard unit, whose ERROR line names the
	 * organization and the table with the throwable.
	 */
	public List<OrgTotalsTable> countAndStampOrg(UUID org) {
		List<OrgTotalsTable> failed = new ArrayList<>();
		for (OrgTotalsTable table : OrgTotalsTable.values()) {
			boolean ok = SchedulerGuard.runIsolated(
					"org totals: count of " + table + " for org " + org + " (the previous value and its counted_at are kept)",
					() -> {
						// taken before the statement: a lower bound of "as of"; microseconds, the precision section 10 promises
						String countedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MICROS));
						long count = inNightlyCountTransaction(() -> countRepository.count(table, org));
						if (1 != organizationRepository.stampTotal(org, table.getMapKey(), count, countedAt)) {
							throw new IllegalStateException("organization " + org + " has no row to stamp");
						}
					});
			if (!ok) {
				failed.add(table);
			}
		}
		return failed;
	}

	/**
	 * The TPF-70 wrapper with the nightly timeout: one read-only transaction with enable_seqscan
	 * off, jit off and statement_timeout {@link OrgTotalsCountRepository#NIGHTLY_STATEMENT_TIMEOUT},
	 * all SET LOCAL, so the pooled connection returns clean at commit or rollback. Package-private
	 * so a test reads the settings through it.
	 */
	<T> T inNightlyCountTransaction(Supplier<T> read) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.setReadOnly(true);
		return tx.execute(status -> {
			entityManager.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();
			entityManager.createNativeQuery("SET LOCAL jit = off").executeUpdate();
			entityManager.createNativeQuery("SET LOCAL statement_timeout = '"
					+ OrgTotalsCountRepository.NIGHTLY_STATEMENT_TIMEOUT + "'").executeUpdate();
			return read.get();
		});
	}
}
