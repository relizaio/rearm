/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.CdxImportService.ImportComponentResult;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * End-to-end integration test for {@link CdxImportService#importFromCycloneDx}.
 *
 * <p>Pins the transaction contract between the import and pedigree SCE creation:
 * {@code importFromCycloneDx} runs in an outer transaction, while
 * {@link SourceCodeEntryService#createSourceCodeEntry} runs {@code REQUIRES_NEW}
 * and validates the SCE's VCS row from that inner transaction. The VCS repository
 * must therefore be provisioned through
 * {@link VcsRepositoryService#provisionVcsRepository} (committed before the inner
 * tx runs). When the import created the row inline in its outer tx instead, every
 * pedigree commit failed SCE creation with "VCS &lt;uuid&gt; not found" and imported
 * releases silently lost their source code entries -- which is exactly what this
 * test would catch: it asserts on the release's SCE, not just the import result.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class CdxImportServiceIntegrationTest {

	@Autowired private CdxImportService cdxImportService;
	@Autowired private ComponentService componentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private TestInitializer testInitializer;

	private static final String COMPONENT_NAME = "docker.io/example/cdx-import-sce-test";
	private static final String VERSION = "3.2.1";
	private static final String COMMIT = "080b0220574cc853ae1e2946ce7a5610ba855757";
	private static final String COMMIT_MESSAGE = "update base image (#23485)";
	private static final String VCS_URI = "github.com/example/cdx-import-sce-test";
	private static final String VCS_TAG = "v3.2.1";

	private static final String CDX_JSON = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "version": 1,
			  "components": [
			    {
			      "type": "container",
			      "name": "%s",
			      "version": "%s",
			      "hashes": [
			        {"alg": "SHA-256", "content": "0ccc1eca228ecea120d7ad9e4386b8e86ced200212f00060a9abc753dbe7f17f"}
			      ],
			      "pedigree": {
			        "commits": [
			          {"uid": "%s", "url": "%s", "message": "%s"}
			        ]
			      },
			      "properties": [
			        {"name": "reliza:rearmImport:rearmImportable", "value": "true"},
			        {"name": "reliza:rearmImport:vcsUri", "value": "%s"},
			        {"name": "reliza:rearmImport:vcsTag", "value": "%s"}
			      ]
			    }
			  ]
			}
			""".formatted(COMPONENT_NAME, VERSION, COMMIT, VCS_URI, COMMIT_MESSAGE, VCS_URI, VCS_TAG);

	@Test
	public void importedReleaseCarriesPedigreeSourceCodeEntry() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		WhoUpdated wu = WhoUpdated.getTestWhoUpdated();

		List<ImportComponentResult> results = cdxImportService.importFromCycloneDx(org.getUuid(), CDX_JSON, wu);

		assertEquals(1, results.size());
		ImportComponentResult result = results.get(0);
		assertTrue(result.success(), "import failed: " + result.error());
		assertEquals(COMPONENT_NAME, result.componentName());
		assertEquals(VERSION, result.version());

		ComponentData componentData = componentService
				.findComponentDataByOrgNameType(org.getUuid(), COMPONENT_NAME, ComponentType.COMPONENT)
				.orElseThrow();

		Release release = sharedReleaseService
				.findReleaseByComponentAndVersion(componentData.getUuid(), VERSION)
				.orElseThrow();
		ReleaseData releaseData = ReleaseData.dataFromRecord(release);

		// The regression this test pins: the pedigree commit must survive as the
		// release's source code entry. With the VCS row created in the import's
		// uncommitted outer tx, the REQUIRES_NEW SCE creation could not see it,
		// the SCE was skipped with a warn, and this field came back null.
		UUID sceUuid = releaseData.getSourceCodeEntry();
		assertNotNull(sceUuid, "imported release lost its pedigree source code entry");

		SourceCodeEntryData sced = getSourceCodeEntryService.getSourceCodeEntryData(sceUuid).orElseThrow();
		assertEquals(COMMIT, sced.getCommit());
		assertEquals(VCS_TAG, sced.getVcsTag());
		assertEquals(org.getUuid(), sced.getOrg());
	}

	/**
	 * Pins VCS URI canonicalization on the provisioning lookup. The stored key is
	 * always the cleaned form (git@ / trailing .git / scp colon stripped), so once
	 * an import committed the row under the clean URI, a later import referencing
	 * the same repository in scp-style '.git'-suffixed form must resolve to that
	 * row. Before the lookup key was canonicalized the same way, it missed the
	 * committed row, re-inserted the identical cleaned URI and failed the whole
	 * component on the org+uri unique index.
	 */
	@Test
	public void dirtyVcsUriResolvesToExistingRepositoryAndKeepsSce() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		WhoUpdated wu = WhoUpdated.getTestWhoUpdated();

		// First import commits the VCS repository row under the clean URI form.
		List<ImportComponentResult> cleanResults = cdxImportService.importFromCycloneDx(org.getUuid(), CDX_JSON, wu);
		assertEquals(1, cleanResults.size());
		assertTrue(cleanResults.get(0).success(), "clean-uri import failed: " + cleanResults.get(0).error());

		// Second import references the same repository in dirty scp form.
		String dirtyUriJson = CDX_JSON
				.replace(COMPONENT_NAME, COMPONENT_NAME + "-dirty-uri")
				.replace(VCS_URI, "git@" + VCS_URI.replaceFirst("/", ":") + ".git");

		List<ImportComponentResult> results = cdxImportService.importFromCycloneDx(org.getUuid(), dirtyUriJson, wu);

		assertEquals(1, results.size());
		ImportComponentResult result = results.get(0);
		assertTrue(result.success(), "dirty-uri import failed: " + result.error());

		ComponentData componentData = componentService
				.findComponentDataByOrgNameType(org.getUuid(), COMPONENT_NAME + "-dirty-uri", ComponentType.COMPONENT)
				.orElseThrow();
		Release release = sharedReleaseService
				.findReleaseByComponentAndVersion(componentData.getUuid(), VERSION)
				.orElseThrow();
		ReleaseData releaseData = ReleaseData.dataFromRecord(release);
		assertNotNull(releaseData.getSourceCodeEntry(), "imported release lost its pedigree source code entry");
	}
}
