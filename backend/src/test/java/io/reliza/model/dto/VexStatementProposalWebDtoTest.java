/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisScope;
import io.reliza.model.AnalysisState;
import io.reliza.model.FindingType;
import io.reliza.model.LocationType;
import io.reliza.model.ProposalStatus;
import io.reliza.model.SourceFormat;
import io.reliza.model.VexStatementProposalData;

class VexStatementProposalWebDtoTest {

	@Test
	void mapsCoreFields() {
		VexStatementProposalData d = VexStatementProposalData.createVexStatementProposalData(
			UUID.randomUUID(), UUID.randomUUID(), SourceFormat.OPENVEX, "h", "{}",
			AnalysisScope.RELEASE, UUID.randomUUID(),
			"pkg:maven/x/y", "pkg:maven/x/y@1", LocationType.PURL,
			"CVE-1", List.of(), FindingType.VULNERABILITY,
			AnalysisState.NOT_AFFECTED, AnalysisJustification.CODE_NOT_REACHABLE,
			"details", null, List.of(), null, null, List.of(),
			null, null, null);

		VexStatementProposalWebDto w = VexStatementProposalWebDto.fromData(d);
		assertEquals(d.getUuid(), w.getUuid());
		assertEquals(ProposalStatus.PENDING, w.getStatus());
		assertEquals("CVE-1", w.getFindingId());
		assertEquals(AnalysisState.NOT_AFFECTED, w.getAnalysisState());
		assertNotNull(w.getSourceStatementJson());
	}
}
