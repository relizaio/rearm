/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.Set;
import java.util.UUID;

import io.reliza.model.ComponentData.EventScope;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReleaseInputEventDto {
	private UUID uuid;
	private String name;
	private Set<UUID> outputEvents;
	private EventScope scope;
	private String celExpression;

	public static ReleaseInputEventDto fromData(ReleaseInputEvent event, EventScope scope) {
		return ReleaseInputEventDto.builder()
				.uuid(event.getUuid())
				.name(event.getName())
				.outputEvents(event.getOutputEvents())
				.scope(scope)
				.celExpression(event.getCelExpression())
				.build();
	}
}
