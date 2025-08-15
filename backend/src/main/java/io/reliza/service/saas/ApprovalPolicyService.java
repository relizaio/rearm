/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.saas;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.reliza.model.RelizaObject;

/**
 * This class proper is part of ReARM Pro. Present here for compatibility with ReARM Community Edition.
 */
@Service
public class ApprovalPolicyService {
    public Optional<RelizaObject> getApprovalPolicyData (UUID uuid) {
		return Optional.empty();
	}
}