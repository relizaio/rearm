/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisState;
import io.reliza.model.dto.CdxVexParseResult;
import io.reliza.model.dto.CdxVexStatement;

class CdxVexParserTest {

    private final CdxVexParser p = new CdxVexParser();

    @Test
    void parsesSingleStatementWithBomRefResolution() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "components": [
            {"bom-ref": "comp-a", "type": "library", "name": "log4j-core",
             "purl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.20.0"}
          ],
          "vulnerabilities": [
            {"id": "CVE-2024-12345", "affects": [{"ref": "comp-a"}],
             "analysis": {
               "state": "false_positive",
               "justification": "code_not_reachable",
               "detail": "JndiLookup stripped at build."
             }}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertNull(r.docError());
        assertEquals(1, r.statements().size());
        CdxVexStatement s = r.statements().get(0);
        assertEquals("CVE-2024-12345", s.vulnerabilityId());
        assertEquals(1, s.productPurls().size());
        assertEquals("pkg:maven/org.apache.logging.log4j/log4j-core@2.20.0", s.productPurls().get(0));
        assertEquals(AnalysisState.FALSE_POSITIVE, s.state());
        assertEquals(AnalysisJustification.CODE_NOT_REACHABLE, s.justification());
        assertEquals("JndiLookup stripped at build.", s.details());
    }

    @Test
    void parsesPurlInRefDirectly() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-X", "affects": [{"ref": "pkg:maven/x/y@1.0"}],
             "analysis": {"state": "exploitable"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(1, r.statements().size());
        assertEquals("pkg:maven/x/y@1.0", r.statements().get(0).productPurls().get(0));
        assertEquals(AnalysisState.EXPLOITABLE, r.statements().get(0).state());
    }

    @Test
    void resolvedWithPedigreeCollapsesToResolved() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-Y", "affects": [{"ref": "pkg:maven/x/y@1.0"}],
             "analysis": {"state": "resolved_with_pedigree"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(AnalysisState.RESOLVED, r.statements().get(0).state());
    }

    @Test
    void unresolvedBomRefIsSkippedWithEmptyPurls() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-Z", "affects": [{"ref": "unknown-ref"}],
             "analysis": {"state": "not_affected", "justification": "code_not_present"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(1, r.statements().size());
        assertTrue(r.statements().get(0).productPurls().isEmpty());
    }

    @Test
    void malformedJsonReturnsDocError() {
        CdxVexParseResult r = p.parseRaw("not cdx json");
        assertEquals(0, r.statements().size());
        assertEquals("doc parse failed", r.docError().substring(0, 16));
    }
}
