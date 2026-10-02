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

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaDependencyDepth;
import io.reliza.model.TeaProfileData.TeaInternalMetadata;
import io.reliza.model.TeaProfileData.TeaOptionalDependencies;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaSbomSource;
import io.reliza.model.TeaProfileData.TeaSupportMetadata;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaProfileData.TeaVulnerabilityDocuments;
import io.reliza.service.RebomService.BomStructureType;

/** Task TEA-2, design 4.3: the TEA profile data class, its defaults, record round trip and adapters. */
class TeaProfileDataTest {

	@Test
	void builtInDefaultCarriesEveryDecidedDefault() {
		UUID org = UUID.randomUUID();
		TeaProfileData d = TeaProfileData.builtInDefault(org);
		assertEquals(org, d.getOrg());
		assertEquals(TeaProfileScope.ORGANIZATION, d.getScope());
		assertNull(d.getUuid());
		assertNull(d.getObject());
		assertNull(d.getMode());
		assertNull(d.getFollowedPerspective());
		assertEquals(TeaPublishing.DISABLED, d.getPublishing());
		assertEquals(TeaVisibility.PRIVATE, d.getVisibility());
		assertEquals(TeaDependencyDepth.FULL, d.getDependencyDepth());
		assertEquals(TeaOptionalDependencies.INCLUDE, d.getOptionalDependencies());
		assertEquals(BomStructureType.FLAT, d.getStructure());
		assertEquals(EnumSet.allOf(TeaSbomSource.class), d.getSources());
		assertEquals(EnumSet.of(ArtifactCoverageType.DEV, ArtifactCoverageType.TEST), d.getExcludedCoverage());
		assertEquals(TeaSupportMetadata.EXCLUDE, d.getSupportMetadata());
		assertEquals(TeaInternalMetadata.EXCLUDE, d.getInternalMetadata());
		assertEquals(TeaRawArtifacts.NONE, d.getRawArtifacts());
		assertEquals(TeaProductComponents.PUBLISH_WITH_PRODUCT, d.getProductComponents());
		assertEquals(ReleaseLifecycle.ASSEMBLED, d.getMinimumLifecycle());
		assertEquals(TeaTei.DISABLED, d.getTei());
		assertNull(d.getTeiDomain());
		assertEquals(TeaVulnerabilityDocuments.NONE, d.getVulnerabilityDocuments());
	}

	private static TeaProfileData everyFieldSet() {
		TeaProfileData d = new TeaProfileData();
		d.setOrg(UUID.randomUUID());
		d.setScope(TeaProfileScope.COMPONENT);
		d.setObject(UUID.randomUUID());
		d.setMode(TeaComponentProfileMode.OVERRIDE);
		d.setFollowedPerspective(UUID.randomUUID());
		d.setPublishing(TeaPublishing.ENABLED);
		d.setVisibility(TeaVisibility.PUBLIC);
		d.setDependencyDepth(TeaDependencyDepth.TOP_LEVEL_ONLY);
		d.setOptionalDependencies(TeaOptionalDependencies.EXCLUDE);
		d.setStructure(BomStructureType.HIERARCHICAL);
		d.setSources(Set.of(TeaSbomSource.SOURCE_CODE, TeaSbomSource.DELIVERABLE));
		d.setExcludedCoverage(Set.of(ArtifactCoverageType.BUILD_TIME));
		d.setSupportMetadata(TeaSupportMetadata.INCLUDE);
		d.setInternalMetadata(TeaInternalMetadata.INCLUDE);
		d.setRawArtifacts(TeaRawArtifacts.NON_BOM);
		d.setProductComponents(TeaProductComponents.PRODUCT_ONLY);
		d.setMinimumLifecycle(ReleaseLifecycle.GENERAL_AVAILABILITY);
		d.setTei(TeaTei.UUID);
		d.setTeiDomain("products.example.com");
		d.setVulnerabilityDocuments(TeaVulnerabilityDocuments.NONE);
		d.setLastUpdatedBy(UUID.randomUUID());
		return d;
	}

	@Test
	void recordRoundTripKeepsEveryField() {
		TeaProfileData d = everyFieldSet();
		TeaProfile e = new TeaProfile();
		e.setRevision(4);
		e.setCreatedDate(ZonedDateTime.now().minusDays(1));
		e.setRecordData(d.toRecordData());

		TeaProfileData back = TeaProfileData.dataFromRecord(e);
		assertEquals(e.getUuid(), back.getUuid(), "the entity uuid wins");
		assertEquals(4, back.getRevision());
		assertEquals(e.getCreatedDate(), back.getCreatedDate());
		assertEquals(d.getLastUpdatedBy(), back.getLastUpdatedBy(), "RelizaDataParent fields survive");
		d.setUuid(e.getUuid());
		d.setRevision(4);
		d.setCreatedDate(e.getCreatedDate());
		assertEquals(d, back);
		assertEquals(List.of(TeaSbomSource.DELIVERABLE, TeaSbomSource.SOURCE_CODE), List.copyOf(back.getSources()),
				"sets read back in enum order");
		assertFalse(e.getRecordData().containsKey("revision"), "the revision is the entity's, not the record's");
	}

	@Test
	void aRecordWithAnUnknownKeyStillReads() {
		TeaProfileData d = everyFieldSet();
		Map<String, Object> record = d.toRecordData();
		record.put("somethingFromALaterVersion", Map.of("x", 1));
		TeaProfile e = new TeaProfile();
		e.setRecordData(record);
		assertEquals(TeaPublishing.ENABLED, TeaProfileData.dataFromRecord(e).getPublishing());
	}

	@Test
	void adaptersMapOntoTheMergePath() {
		TeaProfileData d = TeaProfileData.builtInDefault(UUID.randomUUID());
		assertTrue(d.belongsToFilter().isEmpty(), "all three sources: no filter");
		d.setSources(Set.of(TeaSbomSource.SOURCE_CODE));
		assertEquals(Set.of(ArtifactBelongsTo.SCE), d.belongsToFilter());
		d.setSources(Set.of(TeaSbomSource.DELIVERABLE, TeaSbomSource.RELEASE));
		assertEquals(Set.of(ArtifactBelongsTo.DELIVERABLE, ArtifactBelongsTo.RELEASE), d.belongsToFilter());

		assertFalse(d.tldOnly());
		d.setDependencyDepth(TeaDependencyDepth.TOP_LEVEL_ONLY);
		assertTrue(d.tldOnly());

		assertFalse(d.ignoreDev());
		d.setOptionalDependencies(TeaOptionalDependencies.EXCLUDE);
		assertTrue(d.ignoreDev());

		assertEquals(List.of(ArtifactCoverageType.DEV, ArtifactCoverageType.TEST), d.excludeCoverageTypes());
		d.setExcludedCoverage(Set.of());
		assertTrue(d.excludeCoverageTypes().isEmpty());
	}

	@Test
	void copyIsDetached() {
		TeaProfileData d = everyFieldSet();
		d.setRevision(2);
		TeaProfileData c = d.copy();
		assertEquals(d, c);
		c.setSupportMetadata(TeaSupportMetadata.EXCLUDE);
		assertEquals(TeaSupportMetadata.INCLUDE, d.getSupportMetadata());
	}
}
