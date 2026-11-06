/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.util.List;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.callback.Statement;
import org.flywaydb.core.api.callback.Warning;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * FDAW-47: writes every server message of a migration statement to the application log, with the
 * migration's number, so that the counts a data migration reports reach the deploy log under a
 * logger and a prefix this tree owns.
 *
 * <p>Spring Boot's Flyway auto-configuration hands every {@link Callback} bean to the
 * auto-configured {@code Flyway}; nothing else wires this class. On
 * {@link Event#AFTER_EACH_MIGRATE_STATEMENT} each JDBC warning of the statement is logged once:
 * SQL state {@code 00000} (a {@code RAISE NOTICE}) as {@code migration V<n> notice: <text>} at
 * INFO, any other state as {@code migration V<n> warning: <text> (SQL State <s> code <c>)} at WARN.
 * A statement without warnings writes nothing. {@link Event#AFTER_EACH_MIGRATE_STATEMENT_ERROR} is
 * handled the same way in case a Flyway version passes a failed statement's warnings; flyway-core
 * 12.4.0 passes none, so nothing is promised beside a failure. Errors are left to Flyway, which
 * raises them as the migration's failure. The callback never throws, opens no connection and reads
 * no table: an exception in a callback would fail the migration.
 *
 * <p>Convention for data migrations (V113 and after):
 * <ol>
 * <li>Counts and identifiers a migration reports are {@code RAISE NOTICE} lines (SQL state
 * {@code 00000}). The deploy log carries each twice at INFO: as {@code migration V<n> notice: <text>}
 * on the logger {@code io.reliza.common.MigrationNoticeLogger}, and as Flyway's own
 * {@code DB: <text>} on the logger {@code org.flywaydb.core.internal.sqlscript.DefaultSqlScriptExecutor}.
 * {@code RAISE WARNING} is for a condition the operator should act on and is logged twice at WARN
 * ({@code SQL State 01000}).</li>
 * <li>The migration's header says where the lines land, in these words or equivalent: "The counts
 * are RAISE NOTICE lines; the deploy log carries each as {@code migration V<n> notice: <text>}
 * (logger {@code io.reliza.common.MigrationNoticeLogger}) and as Flyway's {@code DB: <text>} (logger
 * {@code org.flywaydb.core.internal.sqlscript.DefaultSqlScriptExecutor}), both at INFO." Not "printed
 * as NOTICEs in the Flyway log": the log never contains the word NOTICE.</li>
 * <li>A notice carries counts and row identifiers (uuids), never a column that can hold a
 * credential, a person's name or e-mail, or free text a user wrote: the line goes to the same log
 * as every other application line and nothing redacts it. The same holds for functions and
 * triggers a migration creates that run at request time: they raise no NOTICE, WARNING or INFO
 * carrying person data, user-written text or credentials.</li>
 * </ol>
 *
 * <p>The server sends a {@code RAISE NOTICE} only when the session's {@code client_min_messages}
 * admits it; a role or database default above NOTICE drops it silently. {@code spring.flyway.init-sqls}
 * in {@code backend/application.yaml} ({@code SET client_min_messages TO NOTICE}) is run by Flyway on
 * every connection it opens, so the migration statements run in a session that admits notices.
 *
 * <p>V107 ({@code V107__submissions_state_final_cancelled.sql}, FDAW-8) says in its header that its
 * counts are "printed as NOTICEs in the Flyway log". That sentence means Flyway's INFO line
 * {@code DB: <text>} and, on a database that applies V107 after FDAW-47, also
 * {@code migration V107 notice: <text>}. V107 is not edited to say so: Flyway checksums the whole
 * file, comments included, and every database that has applied it would refuse to start.
 */
@Slf4j
@Component
public class MigrationNoticeLogger implements Callback {

	/** The SQL state of a {@code RAISE NOTICE} without {@code ERRCODE}. */
	private static final String NOTICE_STATE = "00000";

	@Override
	public boolean supports(Event event, Context context) {
		return event == Event.AFTER_EACH_MIGRATE_STATEMENT || event == Event.AFTER_EACH_MIGRATE_STATEMENT_ERROR;
	}

	@Override
	public boolean canHandleInTransaction(Event event, Context context) {
		return true;
	}

	@Override
	public void handle(Event event, Context context) {
		if (null == context) return;
		Statement statement = context.getStatement();
		if (null == statement) return;
		List<Warning> warnings = statement.getWarnings();
		if (null == warnings || warnings.isEmpty()) return;
		String migration = label(context.getMigrationInfo());
		for (Warning w : warnings) {
			if (null == w) continue;
			if (NOTICE_STATE.equals(w.getState())) {
				log.info("migration {} notice: {}", migration, w.getMessage());
			} else {
				log.warn("migration {} warning: {} (SQL State {} code {})", migration, w.getMessage(), w.getState(),
						w.getCode());
			}
		}
	}

	@Override
	public String getCallbackName() {
		return MigrationNoticeLogger.class.getSimpleName();
	}

	private static String label(MigrationInfo info) {
		if (null == info) return "?";
		if (null == info.getVersion()) return "R:" + info.getDescription();
		return "V" + info.getVersion().getVersion();
	}
}
