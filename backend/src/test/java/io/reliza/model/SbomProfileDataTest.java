/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SbomProfileData.SbomOptionalDependencies;
import io.reliza.model.SbomProfileData.SbomSourceKind;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
import io.reliza.model.SbomProfileData.SbomVulnerabilityDocuments;
import io.reliza.service.RebomService.BomStructureType;

/** Task TEA-27, design 4.4: the SBOM profile data class, its key, defaults, round trip and adapters. */
class SbomProfileDataTest {

	private static SbomProfileData everyFieldSet() {
		SbomProfileData d = new SbomProfileData();
		d.setUuid(UUID.randomUUID());
		d.setOrg(UUID.randomUUID());
		d.setScope(SettingsScope.COMPONENT);
		d.setObject(UUID.randomUUID());
		d.setName("fda-premarket.v2");
		d.setMode(ScopedSettingsMode.OVERRIDE);
		d.setFollowedPerspective(UUID.randomUUID());
		d.setDescription("The premarket shape");
		d.setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		d.setOptionalDependencies(SbomOptionalDependencies.EXCLUDE);
		d.setStructure(BomStructureType.HIERARCHICAL);
		d.setSources(Set.of(SbomSourceKind.SOURCE_CODE, SbomSourceKind.DELIVERABLE));
		d.setExcludedCoverage(Set.of(ArtifactCoverageType.BUILD_TIME));
		d.setSupportMetadata(SbomSupportMetadata.INCLUDE);
		d.setInternalMetadata(SbomInternalMetadata.INCLUDE);
		d.setVulnerabilityDocuments(SbomVulnerabilityDocuments.NONE);
		d.setLastUpdatedBy(UUID.randomUUID());
		return d;
	}

	/** The entity of {@code d} with its key columns, as a save writes them. */
	private static SbomProfile entityOf(SbomProfileData d) {
		SbomProfile e = new SbomProfile();
		e.setUuid(d.getUuid());
		e.setOrg(d.getOrg());
		e.setScope(d.getScope());
		e.setObjectUuid(d.getObject());
		e.setName(d.getName());
		e.setRevision(4);
		e.setCreatedDate(ZonedDateTime.now().minusDays(1));
		e.setRecordData(d.toRecordData());
		return e;
	}

	@Test
	void everyFieldSetRoundTrips() {
		SbomProfileData d = everyFieldSet();
		SbomProfile e = entityOf(d);
		SbomProfileData back = SbomProfileData.dataFromRecord(e);
		assertEquals(4, back.getRevision());
		assertEquals(e.getCreatedDate(), back.getCreatedDate());
		assertEquals(d.getLastUpdatedBy(), back.getLastUpdatedBy(), "RelizaDataParent fields survive");
		d.setRevision(4);
		d.setCreatedDate(e.getCreatedDate());
		assertEquals(d, back);
		assertEquals(List.of(SbomSourceKind.DELIVERABLE, SbomSourceKind.SOURCE_CODE), List.copyOf(back.getSources()),
				"sets read back in enum order");
		assertFalse(e.getRecordData().containsKey("revision"), "the revision is the entity's, not the record's");
	}

	@Test
	void theColumnsAreTheKeyWhenTheRecordLacksThem() {
		// F-2: a record without uuid, org, scope, object and name still reads back keyed.
		SbomProfileData d = everyFieldSet();
		SbomProfile e = entityOf(d);
		Map<String, Object> record = d.toRecordData();
		for (String k : List.of("uuid", "org", "scope", "object", "name")) record.remove(k);
		e.setRecordData(record);
		SbomProfileData back = SbomProfileData.dataFromRecord(e);
		assertEquals(e.getUuid(), back.getUuid());
		assertEquals(e.getOrg(), back.getOrg());
		assertEquals(SettingsScope.COMPONENT, back.getScope());
		assertEquals(e.getObjectUuid(), back.getObject());
		assertEquals("fda-premarket.v2", back.getName());
	}

	@Test
	void theColumnsWinWhenTheRecordDisagrees() {
		SbomProfileData d = everyFieldSet();
		SbomProfile e = entityOf(d);
		Map<String, Object> record = d.toRecordData();
		record.put("uuid", UUID.randomUUID().toString());
		record.put("org", UUID.randomUUID().toString());
		record.put("scope", "PERSPECTIVE");
		record.put("object", UUID.randomUUID().toString());
		record.put("name", "other");
		e.setRecordData(record);
		SbomProfileData back = SbomProfileData.dataFromRecord(e);
		assertEquals(e.getUuid(), back.getUuid());
		assertEquals(e.getOrg(), back.getOrg());
		assertEquals(SettingsScope.COMPONENT, back.getScope());
		assertEquals(e.getObjectUuid(), back.getObject());
		assertEquals("fda-premarket.v2", back.getName());

		// At ORGANIZATION the object column is NULL and so is the object read back.
		e.setScope(SettingsScope.ORGANIZATION);
		e.setObjectUuid(null);
		SbomProfileData org = SbomProfileData.dataFromRecord(e);
		assertEquals(SettingsScope.ORGANIZATION, org.getScope());
		assertNull(org.getObject());
	}

	@Test
	void adaptersMapOntoTheMergePath() {
		// The TEA-2 cases of TeaProfileDataTest, moved with the adapters.
		SbomProfileData d = SbomProfileData.builtInDefault(UUID.randomUUID());
		assertTrue(d.belongsToFilter().isEmpty(), "all three sources: no filter");
		d.setSources(Set.of(SbomSourceKind.SOURCE_CODE));
		assertEquals(Set.of(ArtifactBelongsTo.SCE), d.belongsToFilter());
		d.setSources(Set.of(SbomSourceKind.DELIVERABLE, SbomSourceKind.RELEASE));
		assertEquals(Set.of(ArtifactBelongsTo.DELIVERABLE, ArtifactBelongsTo.RELEASE), d.belongsToFilter());

		assertFalse(d.tldOnly());
		d.setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		assertTrue(d.tldOnly());

		assertFalse(d.ignoreDev());
		d.setOptionalDependencies(SbomOptionalDependencies.EXCLUDE);
		assertTrue(d.ignoreDev());

		assertEquals(List.of(ArtifactCoverageType.DEV, ArtifactCoverageType.TEST), d.excludeCoverageTypes());
		d.setExcludedCoverage(Set.of(ArtifactCoverageType.BUILD_TIME, ArtifactCoverageType.DEV));
		assertEquals(List.of(ArtifactCoverageType.DEV, ArtifactCoverageType.BUILD_TIME), d.excludeCoverageTypes(),
				"enum order");
		d.setExcludedCoverage(Set.of());
		assertTrue(d.excludeCoverageTypes().isEmpty());
	}

	@Test
	void copyIsDetached() {
		SbomProfileData d = everyFieldSet();
		d.setRevision(2);
		SbomProfileData c = d.copy();
		assertEquals(d, c);
		c.setSupportMetadata(SbomSupportMetadata.EXCLUDE);
		assertEquals(SbomSupportMetadata.INCLUDE, d.getSupportMetadata());
	}

	@Test
	void builtInDefaultCarriesEveryDecidedDefault() {
		UUID org = UUID.randomUUID();
		SbomProfileData d = SbomProfileData.builtInDefault(org);
		assertEquals(org, d.getOrg());
		assertEquals(SettingsScope.ORGANIZATION, d.getScope());
		assertEquals("default", d.getName());
		assertEquals(SbomProfileData.DEFAULT_NAME, d.getName());
		assertNull(d.getUuid());
		assertNull(d.getObject());
		assertNull(d.getMode());
		assertEquals("Built-in defaults", d.getDescription());
		assertEquals(SbomDependencyDepth.FULL, d.getDependencyDepth());
		assertEquals(SbomOptionalDependencies.INCLUDE, d.getOptionalDependencies());
		assertEquals(BomStructureType.FLAT, d.getStructure());
		assertEquals(EnumSet.allOf(SbomSourceKind.class), d.getSources());
		assertEquals(EnumSet.of(ArtifactCoverageType.DEV, ArtifactCoverageType.TEST), d.getExcludedCoverage());
		assertEquals(SbomSupportMetadata.EXCLUDE, d.getSupportMetadata());
		assertEquals(SbomInternalMetadata.EXCLUDE, d.getInternalMetadata());
		assertEquals(SbomVulnerabilityDocuments.NONE, d.getVulnerabilityDocuments());
	}

	@ParameterizedTest
	@ValueSource(strings = { "default", "fda-premarket.v2", "A", "0_x", "Default" })
	void theNamePatternAccepts(String name) {
		assertTrue(SbomProfileData.isValidName(name), name);
	}

	@Test
	void theNamePatternRefuses() {
		for (String name : List.of("", ".default", "-x", "_x", "two words", " default", "default ", "tab\tname",
				"a".repeat(65), "d\u00e9faut", "\u0430bc", "a/b", "a:b")) {
			assertFalse(SbomProfileData.isValidName(name), name);
		}
		assertFalse(SbomProfileData.isValidName(null));
		assertTrue(SbomProfileData.isValidName("a".repeat(64)), "64 characters is the most");
	}
}
