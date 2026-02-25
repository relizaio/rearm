/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/


package io.reliza.model;

import java.util.UUID;

public interface RelizaObject {
	
	public UUID getUuid();

	public UUID getOrg();
	
	public UUID getResourceGroup();
}
