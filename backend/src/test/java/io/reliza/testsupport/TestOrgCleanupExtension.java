/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Removes the organizations a test run created, once, after the whole run.
 *
 * <p>The suites mint roughly six hundred organizations per full run and never remove them. Over
 * three months that reached 19,911 orgs and 1.16M dependent rows, and the cost was not disk: the
 * analytics refresh iterates every organization, so a background tick came to hold the
 * REFRESH_TODAY_ANALYTICS advisory lock for longer than {@code SchedulerIsolationTest} waits for
 * it. That test went from passing in 13 seconds to failing after 557, on changes that had nothing
 * to do with schedulers, and the failure looked like flakiness rather than accumulation.
 *
 * <p>Registered through {@code META-INF/services} with extension auto-detection on, so it runs for
 * every surefire fork without any test having to extend or remember anything. The work hangs off a
 * resource in the ROOT store, which the platform closes once the whole run is over -- not per class,
 * which would delete an organization another class is still using.
 *
 * <h2>What it deletes, and what it will not</h2>
 *
 * Organizations this run created: named like something the suites generate ({@code testOrg…},
 * {@code kevSeed…}) AND created after the run began. The date bound is what makes this an
 * ownership rule rather than a name rule -- the extension auto-registers for every run, so a
 * developer pointing a profile at a shared instance would otherwise have pre-existing
 * organizations with those names removed, and a real organization that happens to be called
 * {@code testOrgChartService} would be at risk in any database.
 *
 * <p>The bound is read from the database ({@code select now()}) rather than the JVM clock, because
 * the two are not the same machine here and a few seconds of skew either way would decide whether
 * a row counts as ours. If it cannot be read, nothing is deleted: without the bound this is the
 * name rule again.
 *
 * <p>The two system organizations seeded by {@code V1__init_schema.sql} are excluded outright.
 *
 * <p>Dependent rows go first, found by scanning {@code information_schema} for the columns that
 * carry an organization ({@code org}, {@code org_id}, {@code org_uuid}, and
 * {@code record_data->>'org'}). Scanning rather than listing tables is deliberate: a list goes
 * stale the first time someone adds a table, silently, and the orphans it leaves are exactly what
 * this exists to prevent. Two tables already name the column differently, which is how the manual
 * cleanup missed 1.16M rows on its first pass.
 *
 * <p>Nothing here can fail a build. A cleanup that turns a green run red would be worse than the
 * accumulation it prevents, so every failure is reported and swallowed. Set
 * {@code -Drearm.test.keepOrgs=true} to skip it when you want to inspect what a run left behind.
 */
public class TestOrgCleanupExtension implements BeforeAllCallback {

	private static final Namespace NAMESPACE = Namespace.create(TestOrgCleanupExtension.class);

	@Override
	public void beforeAll(ExtensionContext context) {
		// getOrComputeIfAbsent on the ROOT store: registered once however many classes run, and
		// closed when the run ends.
		Cleanup cleanup = (Cleanup) context.getRoot().getStore(NAMESPACE)
				.getOrComputeIfAbsent("cleanup", key -> new Cleanup());
		cleanup.rememberDataSource(dataSourceOf(context));
	}

	/**
	 * The DataSource of a Spring test context, when this class runs inside one.
	 *
	 * <p>Captured here and borrowed from later, rather than opening a connection of our own at the
	 * end. The first version did the latter and never ran: by the time the run finishes, every
	 * cached test context still holds its Hikari pool, so Postgres answers a new client with
	 * "sorry, too many clients already" and the cleanup skipped itself silently for the whole
	 * suite. Borrowing from a pool that already exists asks the database for nothing new.
	 */
	private static DataSource dataSourceOf(ExtensionContext context) {
		try {
			return SpringExtension.getApplicationContext(context).getBean(DataSource.class);
		} catch (RuntimeException notASpringTest) {
			return null;
		}
	}


	/**
	 * Names the suites generate. Anything else is somebody's data, not ours.
	 *
	 * <p>{@code testOrg%} rather than {@code testOrg\_%}: one test creates an organization named
	 * exactly {@code testOrg}, and the first version of this pattern left it behind -- which is the
	 * failure mode a name-based rule has, so the patterns are checked against what the suites
	 * actually create rather than assumed.
	 */
	private static final String TEST_ORG_NAME_PATTERNS =
			"(record_data->>'name' LIKE 'testOrg%'"
			+ " OR record_data->>'name' LIKE 'kevSeed%')";

	/** Seeded by V1 and referenced by fixed uuid; never test data. */
	private static final String SYSTEM_ORGS =
			"'00000000-0000-0000-0000-000000000000','00000000-0000-0000-0000-000000000001'";

	private static final String SKIP_PROPERTY = "rearm.test.keepOrgs";

	/** The run is over; remove what it created. */
	static final class Cleanup implements CloseableResource {

		/** Most recently seen test-context DataSource; null when no Spring test ran. */
		private volatile DataSource dataSource;

		/** Database time when this run began; the lower bound on what counts as ours. */
		private volatile Timestamp runStartedAt;

		synchronized void rememberDataSource(DataSource ds) {
			if (null == ds) return;
			this.dataSource = ds;
			if (null == runStartedAt) runStartedAt = readDatabaseTime(ds);
		}

		private static Timestamp readDatabaseTime(DataSource ds) {
			try (Connection c = ds.getConnection();
					Statement st = c.createStatement();
					ResultSet rs = st.executeQuery("SELECT now()")) {
				return rs.next() ? rs.getTimestamp(1) : null;
			} catch (SQLException e) {
				System.out.println("[test-org-cleanup] could not read the database clock: " + e.getMessage());
				return null;
			}
		}

	@Override
	public void close() {
		if (Boolean.getBoolean(SKIP_PROPERTY)) {
			System.out.println("[test-org-cleanup] skipped (-D" + SKIP_PROPERTY + "=true)");
			return;
		}
		if (null == runStartedAt) {
			System.out.println("[test-org-cleanup] skipped: no database clock was read, so there is"
					+ " no way to tell what this run created");
			return;
		}
		long started = System.currentTimeMillis();
		try (Connection c = connect(dataSource)) {
			c.setAutoCommit(false);
			List<String> orgs = testOrgUuids(c, runStartedAt);
			if (orgs.isEmpty()) {
				c.rollback();
				return;
			}
			long dependents = deleteDependents(c, orgs);
			long removed = deleteOrgs(c, orgs);
			c.commit();
			System.out.printf("[test-org-cleanup] removed %d organizations and %d dependent rows in %d ms%n",
					removed, dependents, System.currentTimeMillis() - started);
		} catch (Exception e) {
			// Deliberately swallowed: see the class docs. A cleanup must not fail a run.
			System.out.println("[test-org-cleanup] skipped: " + e.getMessage());
		}
	}

	}

	/**
	 * A pooled connection when a Spring context gave us one, else a direct one.
	 *
	 * <p>The direct path is for a run with no Spring test in it, where nothing holds a pool and a
	 * new client is free.
	 */
	private static Connection connect(DataSource pooled) throws SQLException {
		if (null != pooled) {
			try {
				return pooled.getConnection();
			} catch (SQLException poolUnavailable) {
				// The context may already be closing. Fall through to a direct connection, which
				// is then likely to be available for the same reason.
				System.out.println("[test-org-cleanup] pool unavailable (" + poolUnavailable.getMessage()
						+ "); trying a direct connection");
			}
		}
		String host = env("PG_HOST", "localhost");
		String port = env("PG_PORT", "5440");
		String db = env("PG_DATABASE", "postgres");
		return DriverManager.getConnection(
				"jdbc:postgresql://" + host + ":" + port + "/" + db,
				env("PG_USER", "postgres"), env("PG_PASS", "relizaPass"));
	}

	private static String env(String name, String fallback) {
		String v = System.getenv(name);
		return null == v || v.isBlank() ? fallback : v;
	}

	private static List<String> testOrgUuids(Connection c, Timestamp since) throws SQLException {
		List<String> out = new ArrayList<>();
		String sql = "SELECT uuid::text FROM rearm.organizations WHERE uuid::text NOT IN (" + SYSTEM_ORGS
				+ ") AND created_date >= ? AND " + TEST_ORG_NAME_PATTERNS;
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setTimestamp(1, since);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(rs.getString(1));
			}
		}
		return out;
	}

	/**
	 * Every table that carries an organization, discovered rather than listed.
	 *
	 * @return rows removed
	 */
	private static long deleteDependents(Connection c, List<String> orgs) throws SQLException {
		long total = 0;
		try (Statement st = c.createStatement()) {
			st.execute("CREATE TEMP TABLE test_org_ids (uuid text PRIMARY KEY) ON COMMIT DROP");
		}
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO test_org_ids VALUES (?)")) {
			for (String org : orgs) {
				ps.setString(1, org);
				ps.addBatch();
			}
			ps.executeBatch();
		}
		List<String> statements = new ArrayList<>();
		for (String[] table : orgColumns(c)) {
			statements.add("DELETE FROM rearm." + quote(table[0]) + " WHERE " + quote(table[1])
					+ "::text IN (SELECT uuid FROM test_org_ids)");
		}
		for (String table : recordDataTables(c)) {
			statements.add("DELETE FROM rearm." + quote(table)
					+ " WHERE record_data->>'org' IN (SELECT uuid FROM test_org_ids)");
		}
		// Alphabetical order, which is not dependency order. A foreign key between two dependent
		// tables would make one delete fail, and in Postgres a failed statement poisons the whole
		// transaction -- the cleanup would abort having done nothing, silently, because it cannot
		// fail a build. So each runs inside a savepoint, and whatever fails is retried once after
		// the rest have run, by which time its children are gone. Anything still failing is named.
		List<String> retry = new ArrayList<>();
		total += runAll(c, statements, retry);
		if (!retry.isEmpty()) {
			List<String> stillFailing = new ArrayList<>();
			total += runAll(c, retry, stillFailing);
			for (String sql : stillFailing) {
				System.out.println("[test-org-cleanup] left behind: " + sql);
			}
		}
		return total;
	}

	/**
	 * Run each statement in its own savepoint, collecting the ones that failed.
	 *
	 * @param failed receives the statements that did not run, for a later pass
	 * @return rows removed
	 */
	private static long runAll(Connection c, List<String> statements, List<String> failed)
			throws SQLException {
		long total = 0;
		for (String sql : statements) {
			Savepoint savepoint = c.setSavepoint();
			try (Statement st = c.createStatement()) {
				total += st.executeUpdate(sql);
				c.releaseSavepoint(savepoint);
			} catch (SQLException e) {
				c.rollback(savepoint);
				failed.add(sql);
			}
		}
		return total;
	}

	private static long deleteOrgs(Connection c, List<String> orgs) throws SQLException {
		return delete(c, "DELETE FROM rearm.organizations WHERE uuid::text IN (SELECT uuid FROM test_org_ids)");
	}

	private static long delete(Connection c, String sql) throws SQLException {
		try (Statement st = c.createStatement()) {
			return st.executeUpdate(sql);
		}
	}

	/** Columns that hold an organization uuid directly, whatever they are called. */
	private static List<String[]> orgColumns(Connection c) throws SQLException {
		List<String[]> out = new ArrayList<>();
		String sql = "SELECT table_name, column_name FROM information_schema.columns"
				+ " WHERE table_schema = 'rearm' AND column_name IN ('org','org_id','org_uuid')"
				+ " AND table_name <> 'organizations' ORDER BY table_name";
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) out.add(new String[] { rs.getString(1), rs.getString(2) });
		}
		return out;
	}

	private static List<String> recordDataTables(Connection c) throws SQLException {
		List<String> out = new ArrayList<>();
		String sql = "SELECT table_name FROM information_schema.columns"
				+ " WHERE table_schema = 'rearm' AND column_name = 'record_data'"
				+ " AND table_name <> 'organizations' ORDER BY table_name";
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) out.add(rs.getString(1));
		}
		return out;
	}

	/** Identifiers come from information_schema, but quoting them costs nothing and says so. */
	private static String quote(String identifier) {
		return '"' + identifier.replace("\"", "\"\"") + '"';
	}
}
