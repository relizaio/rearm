/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Shared instance-identity block embedded in
 * {@link InstanceDeploymentChangedPayload}. Every field is known at
 * produce-time from the {@code InstanceData} record, so -- like
 * {@link ReleaseRef} -- the producer builds this in full and no fan-out
 * enrichment is needed.
 *
 * <p>{@code environment} is the instance's environment label (the routing
 * dimension for instance events, e.g. {@code PRODUCTION}); {@code uri} is the
 * customer-facing endpoint (e.g. {@code test.relizahub.com}) formatters use as
 * the message header.
 *
 * <p>Unlike {@link ReleaseRef}, which carries typed enums, {@code environment}
 * and {@code instanceType} are flattened to strings at produce-time. This is
 * deliberate: {@code EnvironmentType} is a value class (not an enum), and a
 * stable string label keeps the notification wire/JSONB shape self-contained and
 * independent of those domain types -- the same reason {@code statuses} on the
 * payload is a {@code List<String>}. Formatters and the CEL surface consume these
 * as opaque labels.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstanceRef(
        UUID uuid,
        String uri,
        String name,
        String environment,
        String instanceType) {
}
