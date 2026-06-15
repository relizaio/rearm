/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Component identification attached to vuln-shaped notification events.
 * Lifted to a top-level record alongside {@link AffectedRelease} so that
 * future event types can share it.
 *
 * <p>Field set is the public CEL surface for {@code event.affectedComponent}
 * — adding fields is forward-compatible; renames/removals require the
 * dual-emit period from §13.2 of the design doc.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AffectedComponent(
        String purl,
        String name,
        String version) {
}
