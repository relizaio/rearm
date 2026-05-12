/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class VexStatementProposalDataTest {

    @Test
    void roundTripsThroughJsonb() {
        UUID org = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        UUID release = UUID.randomUUID();

        VexStatementProposalData data = VexStatementProposalData.createVexStatementProposalData(
            org, artifact, SourceFormat.OPENVEX, "abc123hash", "{\"status\":\"not_affected\"}",
            AnalysisScope.RELEASE, release,
            "pkg:maven/x/y", "pkg:maven/x/y@1.2.3", LocationType.PURL,
            "CVE-2024-1", List.of("GHSA-x"), FindingType.VULNERABILITY,
            AnalysisState.NOT_AFFECTED, AnalysisJustification.CODE_NOT_REACHABLE,
            "details text", null, List.of(), null, null,
            List.of("expanded inline_mitigations_already_exist → PROTECTED_BY_MITIGATING_CONTROL"),
            null, null, null);

        Map<String, Object> serialized = data.toRecordData();
        VexStatementProposalData restored = VexStatementProposalData.dataFromMap(serialized, data.getUuid());

        assertEquals(data.getUuid(), restored.getUuid());
        assertEquals(org, restored.getOrg());
        assertEquals(artifact, restored.getSourceArtifact());
        assertEquals("abc123hash", restored.getSourceStatementHash());
        assertEquals(AnalysisState.NOT_AFFECTED, restored.getAnalysisState());
        assertEquals(AnalysisJustification.CODE_NOT_REACHABLE, restored.getAnalysisJustification());
        assertEquals(ProposalStatus.PENDING, restored.getStatus());
        assertEquals(SignatureStatus.UNSIGNED, restored.getSignatureStatus());
        assertNull(restored.getIssuerIdentity());
        assertEquals(1, restored.getTranslationNotes().size());
    }

    @Test
    void newProposalDefaultsToPendingAndUnsigned() {
        VexStatementProposalData d = VexStatementProposalData.createVexStatementProposalData(
            UUID.randomUUID(), UUID.randomUUID(), SourceFormat.OPENVEX, "h", "{}",
            AnalysisScope.RELEASE, UUID.randomUUID(),
            "p", "p", LocationType.PURL,
            "CVE-1", List.of(), FindingType.VULNERABILITY,
            AnalysisState.EXPLOITABLE, null, null, null, List.of(), null, null,
            List.of(),
            null, null, null);
        assertEquals(ProposalStatus.PENDING, d.getStatus());
        assertEquals(SignatureStatus.UNSIGNED, d.getSignatureStatus());
    }
}
