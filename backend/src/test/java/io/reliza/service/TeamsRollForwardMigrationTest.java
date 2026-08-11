/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationDeliveryStatus;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.ws.App;

/**
 * Roll-forward (OLD -> NEW) verification for the V45 fold of
 * {@code notification_channels} into {@code integrations}
 * ({@code V45__fold_notification_channels_into_integrations.sql}). The
 * prior analysis of this fold only ever observed dev/sandbox data; this
 * test pins the carry-over faithfulness and the dispatch continuity in CI.
 *
 * <p>Method: the test Postgres has already run V45 at boot (so the
 * {@code notification_channels} table is gone). Each test re-creates a
 * pre-V45 {@code notification_channels} row, replays the <b>literal</b>
 * V45 fold SQL read from the migration resource, then asserts the
 * resulting {@code integrations} row — exercising the real migration
 * statement rather than a hand-copied transcription. Everything runs in
 * the test's rolled-back transaction (Postgres DDL is transactional), so
 * the recreated table and the inserted rows never leak between tests or
 * pollute the shared schema.
 *
 * <p>Two dispatch arms prove the only behaviour that the fold can change
 * for an existing Teams customer (the dispatch-time host-suffix gate in
 * {@link TeamsWebhookUrlValidator}, re-checked at
 * {@link TeamsChannelDispatcher}):
 * <ul>
 *   <li><b>Workflows URL</b> ({@code *.logic.azure.com}): a migrated
 *       channel still dispatches — delivery reaches {@code SENT}.</li>
 *   <li><b>Legacy O365 URL</b> ({@code *.outlook.office.com}): a migrated
 *       channel is refused at dispatch — delivery reaches {@code FAILED}
 *       with the host-suffix message, non-retriable.</li>
 * </ul>
 *
 * <p>Carry-over asserted: UUID preserved (so subscriptions/deliveries
 * still resolve the channel by uuid), encrypted secret + name + parameters
 * verbatim, {@code MS_TEAMS -> MSTEAMS} type rename, and
 * {@code status -> isEnabled}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@Transactional
public class TeamsRollForwardMigrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private EncryptionService encryptionService;
    @Autowired private IntegrationRepository integrationRepo;
    @Autowired private NotificationOutboxEventRepository outboxRepo;
    @Autowired private NotificationDeliveryRepository deliveryRepo;
    @Autowired private TeamsChannelDispatcher teamsChannelDispatcher;
    @Autowired private NotificationChannelService channelService;

    /** Power Automate Workflows webhook — accepted by the validator. */
    private static final String WORKFLOWS_URL =
            "https://prod-42.eastus.logic.azure.com/workflows/abc123/triggers/manual/paths/invoke"
                    + "?api-version=2016-06-01&sp=%2Ftriggers%2Fmanual%2Frun&sig=signature-bytes";
    /** Legacy O365 connector webhook — refused by the validator. */
    private static final String O365_URL =
            "https://mytenant.outlook.office.com/webhook/guid@guid/IncomingWebhook/connectorid/guid";

    // ---- Arm 1: carry-over faithfulness + subscription resolution ----------

    @Test
    public void v45FoldPreservesAllCarryOverFields() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();
        UUID resourceGroupUuid = UUID.randomUUID();
        String cipher = encryptionService.encrypt(WORKFLOWS_URL);
        String configData = "{\"customKey\":\"customVal\"}";

        Connection conn = DataSourceUtils.getConnection(dataSource);
        recreatePreV45Table(conn);
        seedOldChannel(conn, channelUuid, orgUuid, "MS_TEAMS", "ENABLED",
                cipher, "ops-teams", configData, resourceGroupUuid.toString());
        // A subscription that routes to this channel by uuid. V45 doesn't
        // touch subscriptions; UUID preservation is what keeps the pointer
        // resolving. Seed it, then assert the referenced uuid still loads.
        UUID subscriptionUuid = UUID.randomUUID();
        seedSubscriptionRoutingTo(conn, subscriptionUuid, orgUuid, channelUuid);
        replayV45Fold(conn);

        Optional<Integration> migrated = integrationRepo.findById(channelUuid);
        assertTrue(migrated.isPresent(), "channel UUID must be preserved as the integration uuid");
        IntegrationData data = IntegrationData.dataFromRecord(migrated.get());

        assertEquals(channelUuid, data.getUuid(), "uuid preserved verbatim");
        assertEquals(IntegrationType.MSTEAMS, data.getType(), "MS_TEAMS renamed to MSTEAMS");
        assertEquals(Boolean.TRUE, data.getIsEnabled(), "status ENABLED -> isEnabled true");
        assertEquals(cipher, data.getSecret(),
                "encrypted webhook URL carried byte-for-byte (no re-encrypt)");
        assertEquals(WORKFLOWS_URL, encryptionService.decrypt(data.getSecret()),
                "carried ciphertext still decrypts to the original URL");
        assertEquals("ops-teams", data.getName(), "name carried verbatim");
        assertEquals(orgUuid, data.getOrg(), "org carried verbatim");
        assertEquals(resourceGroupUuid, data.getResourceGroup(), "resourceGroup scoping carried verbatim");
        assertEquals("customVal", data.getParameters().get("customKey"),
                "configData carried verbatim into parameters");

        // The subscription's channel pointer still resolves post-fold.
        UUID referenced = readSubscriptionChannelUuid(conn, subscriptionUuid);
        assertEquals(channelUuid, referenced, "subscription still references the same channel uuid");
        assertTrue(integrationRepo.findById(referenced).isPresent(),
                "subscription's referenced channel resolves in integrations after the fold");
    }

    @Test
    public void v45FoldMapsDisabledStatusToIsEnabledFalse() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        Connection conn = DataSourceUtils.getConnection(dataSource);
        recreatePreV45Table(conn);
        seedOldChannel(conn, channelUuid, UUID.randomUUID(), "MS_TEAMS", "DISABLED",
                encryptionService.encrypt(WORKFLOWS_URL), "muted-teams", "{}", null);
        replayV45Fold(conn);

        IntegrationData data = IntegrationData.dataFromRecord(integrationRepo.findById(channelUuid).orElseThrow());
        assertEquals(Boolean.FALSE, data.getIsEnabled(), "status DISABLED -> isEnabled false");
    }

    // ---- Arm 2: dispatch continuity after the fold -------------------------

    @Test
    public void migratedWorkflowsChannelStillDispatchesSent() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();
        Connection conn = DataSourceUtils.getConnection(dataSource);
        recreatePreV45Table(conn);
        seedOldChannel(conn, channelUuid, orgUuid, "MS_TEAMS", "ENABLED",
                encryptionService.encrypt(WORKFLOWS_URL), "ops-teams", "{}", null);
        replayV45Fold(conn);

        NotificationDelivery delivery = dispatchAgainstMigratedChannel(channelUuid, orgUuid);

        assertEquals(NotificationDeliveryStatus.SENT, delivery.getStatus(),
                "a migrated Workflows-URL channel passes the host-suffix gate and dispatches");
    }

    @Test
    public void migratedO365ChannelIsRefusedNonRetriable() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();
        Connection conn = DataSourceUtils.getConnection(dataSource);
        recreatePreV45Table(conn);
        seedOldChannel(conn, channelUuid, orgUuid, "MS_TEAMS", "ENABLED",
                encryptionService.encrypt(O365_URL), "legacy-o365-teams", "{}", null);
        replayV45Fold(conn);

        NotificationDelivery delivery = dispatchAgainstMigratedChannel(channelUuid, orgUuid);

        assertEquals(NotificationDeliveryStatus.FAILED, delivery.getStatus(),
                "a migrated legacy O365-URL channel is refused at dispatch (non-retriable)");
        assertNotNull(delivery.getLastError());
        assertTrue(delivery.getLastError().contains("Workflows host"),
                "failure names the Teams Workflows-host gate, got: " + delivery.getLastError());

        // Customer-migration smoothness: the misconfigured migrated channel
        // doesn't just fail this delivery, it auto-disables so subsequent events
        // skip it (fan-out suppresses disabled channels) instead of piling up
        // FAILED rows. A legacy O365 Teams channel self-quiesces on first send.
        IntegrationData disabled = IntegrationData.dataFromRecord(
                integrationRepo.findById(channelUuid).orElseThrow());
        assertEquals(Boolean.FALSE, disabled.getIsEnabled(),
                "misconfigured migrated channel is auto-disabled after the refused dispatch");
        assertNotNull(disabled.getDisabledReason(), "auto-disable records a reason");
        assertTrue(disabled.getDisabledReason().contains("Workflows host"),
                "auto-disable reason names the Teams Workflows-host gate, got: " + disabled.getDisabledReason());
    }

    // ---- Migration replay + seeding helpers --------------------------------

    /**
     * Re-create the pre-V45 {@code notification_channels} table (dropped at
     * boot by V45) so the literal fold SQL has a source to read. Mirrors the
     * V42 DDL shape. Lives in the rolled-back test transaction.
     */
    private static void recreatePreV45Table(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE rearm.notification_channels ("
                    + " uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),"
                    + " revision integer NOT NULL default 0,"
                    + " schema_version integer NOT NULL default 0,"
                    + " created_date timestamptz NOT NULL default now(),"
                    + " last_updated_date timestamptz NOT NULL default now(),"
                    + " record_data jsonb NOT NULL)");
        }
    }

    private void seedOldChannel(Connection conn, UUID uuid, UUID org, String type, String status,
            String encryptedSecret, String name, String configDataJson, String resourceGroup)
            throws Exception {
        // Build the OLD NotificationChannelData JSONB shape (status,
        // encryptedSecret, configData — the pre-fold field names).
        StringBuilder rd = new StringBuilder("{");
        rd.append("\"uuid\":").append(quote(uuid.toString())).append(",");
        rd.append("\"org\":").append(quote(org.toString())).append(",");
        rd.append("\"type\":").append(quote(type)).append(",");
        rd.append("\"status\":").append(quote(status)).append(",");
        rd.append("\"encryptedSecret\":").append(quote(encryptedSecret)).append(",");
        rd.append("\"name\":").append(quote(name)).append(",");
        if (resourceGroup != null) {
            rd.append("\"resourceGroup\":").append(quote(resourceGroup)).append(",");
        }
        rd.append("\"configData\":").append(configDataJson);
        rd.append("}");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rearm.notification_channels (uuid, record_data) VALUES (?, CAST(? AS jsonb))")) {
            ps.setObject(1, uuid);
            ps.setString(2, rd.toString());
            ps.executeUpdate();
        }
    }

    private static void seedSubscriptionRoutingTo(Connection conn, UUID subscriptionUuid,
            UUID org, UUID channelUuid) throws Exception {
        String rd = "{\"org\":" + quote(org.toString())
                + ",\"routes\":[{\"channels\":[" + quote(channelUuid.toString()) + "]}]}";
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rearm.notification_subscriptions (uuid, record_data) VALUES (?, CAST(? AS jsonb))")) {
            ps.setObject(1, subscriptionUuid);
            ps.setString(2, rd);
            ps.executeUpdate();
        }
    }

    private static UUID readSubscriptionChannelUuid(Connection conn, UUID subscriptionUuid) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT record_data->'routes'->0->'channels'->>0 FROM rearm.notification_subscriptions WHERE uuid = ?")) {
            ps.setObject(1, subscriptionUuid);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "seeded subscription must exist");
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    /**
     * Replay the <b>literal</b> V45 fold by executing the INSERT...SELECT
     * statement read from the migration resource (the part before the
     * trailing {@code DROP TABLE}). Runs on the tx-bound connection so the
     * inserted {@code integrations} rows are visible to the repositories and
     * rolled back at test end.
     */
    private static void replayV45Fold(Connection conn) throws Exception {
        String sql;
        try (var in = TeamsRollForwardMigrationTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V45__fold_notification_channels_into_integrations.sql")) {
            assertNotNull(in, "V45 migration resource must be on the test classpath");
            sql = new String(in.readAllBytes());
        }
        // Take only the INSERT...SELECT (skip the table DROP; the test tx
        // rolls back the recreated table anyway).
        int dropAt = sql.indexOf("DROP TABLE");
        String insert = (dropAt >= 0 ? sql.substring(0, dropAt) : sql).trim();
        assertTrue(insert.toUpperCase().contains("INSERT INTO REARM.INTEGRATIONS"),
                "expected the V45 INSERT into integrations to be present");
        try (Statement st = conn.createStatement()) {
            st.execute(insert);
        }
    }

    private static String quote(String s) {
        // JSON-quote a value that we control (uuids, enum names, our own
        // ciphertext from EncryptionService — base64/hex, no embedded quotes).
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- Dispatch driver ---------------------------------------------------

    /**
     * Drive the real {@link NotificationDeliveryWorker#processOne} against
     * the migrated channel, with a {@link TeamsChannelDispatcher} spy whose
     * only stub is {@code doPost} (so a passing Workflows URL is scored
     * SUCCESS without a live network call). The real decrypt + URL
     * validation + formatter all run — so the O365 arm is refused by the
     * genuine validator before {@code doPost} is ever reached.
     */
    private NotificationDelivery dispatchAgainstMigratedChannel(UUID channelUuid, UUID org) throws Exception {
        TeamsChannelDispatcher spyDispatcher = spy(teamsChannelDispatcher);
        doReturn(ChannelDispatchResult.success())
                .when(spyDispatcher).doPost(any(), any(), any(), any(), any());

        NotificationDeliveryWorker worker = new NotificationDeliveryWorker();
        inject(worker, "deliveryRepo", deliveryRepo);
        inject(worker, "integrationRepo", integrationRepo);
        inject(worker, "outboxRepo", outboxRepo);
        // The worker auto-disables a channel on CHANNEL_MISCONFIGURED (the O365
        // arm), so the real service must be wired or that path NPEs. Real bean
        // (not a mock): this is a full-context test and the migrated channel row
        // is present in the tx, so the auto-disable actually runs and rolls back.
        inject(worker, "channelService", channelService);
        inject(worker, "channelDispatchers", List.<ChannelDispatcher>of(spyDispatcher));
        worker.buildDispatcherRegistry();

        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setOrg(org);
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.FANNED_OUT);
        event.setOccurredAt(ZonedDateTime.now());
        event.setOrigin(NotificationDeliveryOrigin.REAL);
        event.setRecordData(Map.of());
        event = outboxRepo.save(event);

        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setUuid(UUID.randomUUID());
        delivery.setOrg(org);
        delivery.setOutboxEventUuid(event.getUuid());
        delivery.setChannelUuid(channelUuid);
        delivery.setStatus(NotificationDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(ZonedDateTime.now().minusSeconds(1));
        delivery.setOrigin(NotificationDeliveryOrigin.REAL);

        worker.processOne(delivery);
        return delivery;
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = NotificationDeliveryWorker.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
