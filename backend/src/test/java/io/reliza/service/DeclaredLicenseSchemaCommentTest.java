/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * SCORE-41 T-23: pins the supplierName field comment sentence (architecture round 5 section 3.1)
 * in the SDL. The comment is the carrier the UI hint and the docs page read, so it must promise
 * only the remedy that holds: a new SBOM uploaded to a release made after the change. The round 2,
 * round 3 and round 4 phrasings, which promised more than the platform does, must not come back.
 */
class DeclaredLicenseSchemaCommentTest {

    private static final Path SCHEMA = Paths.get("src/main/resources/schema/schema.graphqls");
    private static final Path USER = Paths.get("src/main/resources/schema/user.graphqls");

    static final String SENTENCE = "Applies to the nodes ReARM makes at the next export and to SBOMs"
            + " uploaded after it is set. A root uploaded earlier keeps the supplier recorded at its"
            + " upload; a newly generated SBOM (a new serialNumber) uploaded to a release made after"
            + " the change carries the new value in that release's export. An identical file is"
            + " answered with the stored row, unchanged.";

    /** The round 3 remedy, the round 2 remedy and the round 4 sentence (T-6, F-3). */
    static final List<String> FORBIDDEN = List.of(
            "higher version", "uploaded again", "uploaded after the change carries");

    @Test
    void schemaCarriesTheSentenceOnComponentAndCreateComponentInput() throws Exception {
        assertEquals(2, count(collapsed(SCHEMA), SENTENCE),
                "schema.graphqls must carry the supplierName sentence on Component and CreateComponentInput");
    }

    @Test
    void userSchemaCarriesTheSentenceOnUpdateComponentInput() throws Exception {
        assertEquals(1, count(collapsed(USER), SENTENCE),
                "user.graphqls must carry the supplierName sentence on UpdateComponentInput");
    }

    @Test
    void earlierRemediesAppearInNeitherFile() throws Exception {
        for (Path p : List.of(SCHEMA, USER)) {
            String text = collapsed(p);
            for (String s : FORBIDDEN) {
                assertEquals(0, count(text, s), p + " must not contain: " + s);
            }
        }
    }

    static String collapsed(Path p) throws Exception {
        return Files.readString(p).replaceAll("\\s+", " ");
    }

    static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
