package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The attach-time release touches must run after the enclosing commit, or at once without one. */
public class TxUtilsTest {

	@Test
	void runsInlineWithoutAmbientTransaction() {
		List<String> log = new ArrayList<>();
		TxUtils.afterCommitOrNow(() -> log.add("ran"));
		assertEquals(List.of("ran"), log);
	}

	@Test
	void defersUntilAfterCommitInsideATransaction() {
		List<String> log = new ArrayList<>();
		TransactionSynchronizationManager.initSynchronization();
		try {
			TxUtils.afterCommitOrNow(() -> log.add("touch"));
			assertTrue(log.isEmpty(), "nothing runs while the transaction is open");
			log.add("commit");
			for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) s.afterCommit();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
		assertEquals(List.of("commit", "touch"), log, "the touch takes its timestamp after the commit");
	}
}
