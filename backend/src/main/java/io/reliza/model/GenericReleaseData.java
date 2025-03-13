/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.UUID;

public interface GenericReleaseData {
	
	public UUID getUuid();
	
	public ZonedDateTime getCreatedDate();
	
	public String getVersion();
}
