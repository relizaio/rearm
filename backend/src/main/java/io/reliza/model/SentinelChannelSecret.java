/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Serialized form of an Azure Sentinel channel's encrypted secret blob.
 *
 * <p>All fields are sensitive — Azure AD service-principal credentials
 * (tenant/client/secret) and the DCR routing addresses
 * (endpoint/immutable id/stream name) together let any holder write
 * to the customer's Sentinel workspace. The whole record is
 * JSON-serialized then encrypted via {@code EncryptionService} and
 * stored in {@code IntegrationData.secret}.
 *
 * <p>Field reference:
 * <ul>
 *   <li>{@code tenantId} — Azure AD tenant (GUID), used in the OAuth
 *       token endpoint URL.</li>
 *   <li>{@code clientId} — service-principal app registration ID.</li>
 *   <li>{@code clientSecret} — the SP secret used in the client-
 *       credentials grant.</li>
 *   <li>{@code dcrEndpoint} — Data Collection Endpoint URL, e.g.
 *       {@code https://my-dce.westus2-1.ingest.monitor.azure.com}.</li>
 *   <li>{@code dcrImmutableId} — Data Collection Rule immutable id
 *       (starts {@code dcr-...}). DCRs are named lookups; the
 *       immutable id is the stable identifier the API expects.</li>
 *   <li>{@code streamName} — stream within the DCR (e.g.
 *       {@code Custom-ReARMNotifications_CL}). Must match the DCR's
 *       streamDeclarations entry.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SentinelChannelSecret(
        String tenantId,
        String clientId,
        String clientSecret,
        String dcrEndpoint,
        String dcrImmutableId,
        String streamName) {
}
