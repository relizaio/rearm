/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.NotificationOutboxEvent;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationState;

/**
 * Typed accessor for the JSONB payload on {@link NotificationOutboxEvent} when
 * {@code eventType} is {@code RELEASE_TEA_PUBLISHED} or {@code RELEASE_TEA_HIDDEN} (task TEA-5).
 * One event per act, on the release the act was invoked on: a product cascade's component
 * releases get none of their own, and {@code childrenPublished} counts them instead.
 *
 * @param collectionVersion the collection version now served; null on a hide
 * @param exposure what the publication is right now; null on a hide
 * @param publicUrl the TEA URL of the release object; null while the org has no TEA id
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseTeaPublicationPayload(
        ReleaseRef release,
        UUID publication,
        TeaPublicationState state,
        Integer collectionVersion,
        TeaExposureState exposure,
        String publicUrl,
        int childrenPublished) {

    /**
     * The one plain rendering every channel uses: "Release c 1.0 published on TEA (collection
     * version N, PRIVATE)" or "Release c 1.0 hidden from TEA".
     */
    @JsonIgnore
    public String headline() {
        String name = null == release ? "release" : (release.componentName() + " " + release.version());
        if (TeaPublicationState.HIDDEN == state) return "Release " + name + " hidden from TEA";
        StringBuilder b = new StringBuilder("Release ").append(name).append(" published on TEA (collection version ")
                .append(null == collectionVersion ? "?" : collectionVersion);
        if (null != exposure) b.append(", ").append(exposure.name());
        b.append(")");
        if (childrenPublished > 0) b.append(" with ").append(childrenPublished)
                .append(childrenPublished == 1 ? " component release" : " component releases");
        return b.toString();
    }
}
