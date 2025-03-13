/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/


package io.reliza.model;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;

import lombok.Data;

/**
 * This class contains data model to stream back to client when instance requests for releases to be deployed
 * @author pavel
 *
 */
@Data
public class EventPayload {
	private List<TextPayload> title;
	private ZonedDateTime date;
	private List<EventEntry> events;
	private URI mainUri;
	
	public static record EventEntry(String key, List<TextPayload> event) {}
}

