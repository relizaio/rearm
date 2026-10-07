/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

/** Test access to AgentDocumentService's package-private check rules (task RD2-24). */
public final class AgentDocumentServiceAccess {

	private AgentDocumentServiceAccess() {}

	public static String verdict(int pass, int fail, int skip, int blockingFailed) {
		return AgentDocumentService.verdictOf(new AgentDocumentService.ElementCheckCounts(pass, fail, skip, blockingFailed));
	}
}
