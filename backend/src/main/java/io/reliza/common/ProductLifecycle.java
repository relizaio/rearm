/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

public enum ProductLifecycle {
    PRIVATE_DEVELOPMENT("Pre-Release Development"),
    PUBLIC_BETA("Public Beta"),
    GENERAL_AVAILABILITY("General Availability"),
    END_OF_DEVELOPMENT("End of Development"),
    END_OF_SALES("End of Sales"),
    END_OF_SUPPORT("End of Support"),
    END_OF_LIFE("End of Life");
	
	private String prettyName;
	
	private ProductLifecycle(String prettyName) {
		this.prettyName = prettyName;
	}
	
	public String getPrettyName() {
		return this.prettyName;
	}
}
