/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import io.reliza.common.CommonVariables.AuthPrincipalType;

public interface AuthPrincipal {
	public AuthPrincipalType getAuthPrincipalType();
	
	public String getRemoteIp();
}
