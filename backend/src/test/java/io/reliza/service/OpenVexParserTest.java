/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.model.dto.OpenVexParseResult;

class OpenVexParserTest {

    private final OpenVexParser p = new OpenVexParser(new OpenVexStatementValidator());

    @Test
    void parsesSingleValidStatement() {
        String doc = """
        {
          "@context": "https://openvex.dev/ns/v0.2.0",
          "@id": "urn:uuid:1",
          "author": "test",
          "timestamp": "2026-05-05T00:00:00Z",
          "version": 1,
          "statements": [{
            "vulnerability": {"name": "CVE-2024-1"},
            "products": [{"@id": "pkg:maven/x/y@1"}],
            "status": "not_affected",
            "justification": "vulnerable_code_not_present"
          }]
        }
        """;
        OpenVexParseResult r = p.parseRaw(doc);
        assertNull(r.docError());
        assertEquals(1, r.valid().size());
        assertEquals("CVE-2024-1", r.valid().get(0).vulnerability());
        assertEquals("not_affected", r.valid().get(0).status());
        assertTrue(r.invalid().isEmpty());
    }

    @Test
    void splitsValidAndInvalidStatements() {
        String doc = """
        {
          "@context": "https://openvex.dev/ns/v0.2.0",
          "statements": [
            {"vulnerability": {"name": "CVE-1"}, "products": [{"@id": "p1"}], "status": "not_affected", "justification": "vulnerable_code_not_present"},
            {"vulnerability": {"name": "CVE-2"}, "products": [{"@id": "p2"}], "status": "affected"}
          ]
        }
        """;
        OpenVexParseResult r = p.parseRaw(doc);
        assertEquals(1, r.valid().size());
        assertEquals(1, r.invalid().size());
        assertEquals("CVE-2", r.invalid().get(0).statement().vulnerability());
        assertTrue(!r.invalid().get(0).errors().isEmpty(), "validator errors should propagate to InvalidStatement");
    }

    @Test
    void malformedJsonReturnsDocError() {
        OpenVexParseResult r = p.parseRaw("not json");
        assertNotNull(r.docError());
        assertTrue(r.valid().isEmpty());
        assertTrue(r.invalid().isEmpty());
    }

    @Test
    void emptyDocReturnsDocError() {
        OpenVexParseResult r = p.parseRaw("{}");
        assertNotNull(r.docError());
        assertTrue(r.valid().isEmpty());
        assertTrue(r.invalid().isEmpty());
    }
}
