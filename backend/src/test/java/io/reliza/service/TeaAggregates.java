/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.UUID;

import io.reliza.model.SbomProfileData;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublicationData.TeaAggregateArtifact;

/**
 * Task TEA-28, design 4.7: what the TEA-5 to TEA-27 suites read as the head's single aggregate is the
 * {@code default} entry of its {@code aggregateArtifacts}; a single-name publication has the same shape.
 */
final class TeaAggregates {

	private TeaAggregates() {}

	/** The artifact row of the head's {@code default} named aggregate, null when it lists none. */
	static UUID defaultAggregate(TeaPublicationData head) {
		return aggregateOf(head, SbomProfileData.DEFAULT_NAME);
	}

	/** The artifact row of the head's named aggregate {@code name}, null when it lists none. */
	static UUID aggregateOf(TeaPublicationData head, String name) {
		if (null == head) return null;
		return head.getAggregateArtifacts().stream().filter(a -> name.equals(a.profileName()))
				.map(TeaAggregateArtifact::artifact).findFirst().orElse(null);
	}
}
