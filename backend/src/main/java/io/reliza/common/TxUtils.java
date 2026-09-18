/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Small transaction-ordering helpers. */
public final class TxUtils {

	private TxUtils() {}

	/**
	 * Run {@code action} after the ambient transaction commits, or right away when there is none
	 * (direct unit-test invocation, no transaction manager).
	 *
	 * <p>Use it for side effects whose ordering matters against concurrent readers: a "touch" that
	 * pushes a release back into a finder pool must take its {@code now()} <em>after</em> the write
	 * it announces is visible. Inside the still-open transaction the touch's timestamp predates any
	 * compute that starts before the commit, and that compute then fences the release out with a
	 * later stamp although it never saw the write (metrics_rollup_paths, outbound-deliverable arm,
	 * 2026-09-12).
	 */
	public static void afterCommitOrNow(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		} else {
			action.run();
		}
	}
}
