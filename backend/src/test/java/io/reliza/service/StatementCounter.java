/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import jakarta.persistence.EntityManagerFactory;

/**
 * How many JDBC statements a block of code prepares (task TPF-69), read from Hibernate's statistics. The
 * statistics are factory-wide, so a scheduled job that runs during the block adds its own statements; noise only
 * ever adds, so the reading is the smallest of a few runs of the same block. Statistics are switched on for the
 * measurement and back to what they were after.
 */
public final class StatementCounter {

	private StatementCounter() {}

	/** A block that may throw. */
	@FunctionalInterface
	public interface Block {
		void run() throws Exception;
	}

	/** The fewest statements {@code block} prepared over {@code runs} runs. */
	public static long fewest(EntityManagerFactory emf, int runs, Block block) throws Exception {
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		boolean was = stats.isStatisticsEnabled();
		stats.setStatisticsEnabled(true);
		try {
			long fewest = Long.MAX_VALUE;
			for (int i = 0; i < runs; i++) {
				stats.clear();
				block.run();
				fewest = Math.min(fewest, stats.getPrepareStatementCount());
			}
			return fewest;
		} finally {
			stats.setStatisticsEnabled(was);
		}
	}
}
