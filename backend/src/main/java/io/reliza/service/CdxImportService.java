/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.Pedigree;
import org.cyclonedx.model.Property;
import org.cyclonedx.parsers.JsonParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CdxType;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData.PackageType;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.VcsRepository;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.VariantData;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.service.oss.OssReleaseService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CdxImportService {

	private static final String REARM_IMPORT_PREFIX = "reliza:rearmImport:";
	private static final String PROP_IMPORTABLE = REARM_IMPORT_PREFIX + "rearmImportable";
	private static final String PROP_COMPONENT_NAME = REARM_IMPORT_PREFIX + "componentName";
	private static final String PROP_VCS_URI = REARM_IMPORT_PREFIX + "vcsUri";
	private static final String PROP_VCS_BRANCH = REARM_IMPORT_PREFIX + "vcsBranch";
	private static final String PROP_BASE_BRANCH = REARM_IMPORT_PREFIX + "baseBranch";
	private static final String PROP_VCS_TAG = REARM_IMPORT_PREFIX + "vcsTag";
	private static final String PROP_COMPONENT_VERSION_SCHEMA = REARM_IMPORT_PREFIX + "componentVersionSchema";
	private static final String PROP_BRANCH_VERSION_SCHEMA = REARM_IMPORT_PREFIX + "branchVersionSchema";

	@Autowired
	private ComponentService componentService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private VcsRepositoryService vcsRepositoryService;

	@Autowired
	private SourceCodeEntryService sourceCodeEntryService;

	@Autowired
	private OssReleaseService ossReleaseService;

	@Autowired
	private DeliverableService deliverableService;

	@Autowired
	private VariantService variantService;

	public record ImportComponentResult(String componentName, String version, boolean success, String error) {}

	@Transactional
	public List<ImportComponentResult> importFromCycloneDx(UUID orgUuid, String cdxJson, WhoUpdated wu) {
		List<ImportComponentResult> results = new ArrayList<>();

		Bom bom;
		try {
			bom = new JsonParser().parse(cdxJson.getBytes());
		} catch (ParseException e) {
			log.error("Failed to parse CycloneDX JSON", e);
			results.add(new ImportComponentResult("N/A", "N/A", false, "Failed to parse CycloneDX JSON: " + e.getMessage()));
			return results;
		}

		if (bom.getComponents() == null || bom.getComponents().isEmpty()) {
			results.add(new ImportComponentResult("N/A", "N/A", false, "No components found in CycloneDX BOM"));
			return results;
		}

		for (Component cdxComponent : bom.getComponents()) {
			try {
				ImportComponentResult result = importComponent(orgUuid, cdxComponent, wu);
				results.add(result);
			} catch (Exception e) {
				String name = resolveProperty(cdxComponent, PROP_COMPONENT_NAME);
				if (StringUtils.isEmpty(name)) name = cdxComponent.getName();
				log.error("Failed to import component '{}'", name, e);
				results.add(new ImportComponentResult(name, cdxComponent.getVersion(), false, e.getMessage()));
			}
		}

		return results;
	}

	private ImportComponentResult importComponent(UUID orgUuid, Component cdxComponent, WhoUpdated wu) throws RelizaException {
		// Only import if marked as rearmImportable = true
		String importable = resolveProperty(cdxComponent, PROP_IMPORTABLE);
		if (!"true".equalsIgnoreCase(importable)) {
			return null;
		}

		String componentName = resolveProperty(cdxComponent, PROP_COMPONENT_NAME);
		if (StringUtils.isEmpty(componentName)) componentName = cdxComponent.getName();
		if (StringUtils.isEmpty(componentName)) {
			throw new RelizaException("Component name cannot be determined");
		}

		String version = cdxComponent.getVersion();
		if (StringUtils.isEmpty(version)) {
			throw new RelizaException("Component version is required for import");
		}

		String versionSchema = resolveProperty(cdxComponent, PROP_COMPONENT_VERSION_SCHEMA);
		if (StringUtils.isEmpty(versionSchema)) versionSchema = "semver";

		String branchVersionSchema = resolveProperty(cdxComponent, PROP_BRANCH_VERSION_SCHEMA);
		if (StringUtils.isEmpty(branchVersionSchema)) branchVersionSchema = version;

		String baseBranchName = resolveProperty(cdxComponent, PROP_BASE_BRANCH);
		if (StringUtils.isEmpty(baseBranchName)) baseBranchName = "main";

		String vcsBranchName = resolveProperty(cdxComponent, PROP_VCS_BRANCH);
		if (StringUtils.isEmpty(vcsBranchName)) vcsBranchName = baseBranchName;

		// Resolve VCS repo URI
		String vcsUri = resolveProperty(cdxComponent, PROP_VCS_URI);
		if (StringUtils.isEmpty(vcsUri)) {
			vcsUri = resolveVcsUriFromPedigree(cdxComponent);
		}

		// Find or create component
		ComponentData componentData = findOrCreateComponent(orgUuid, componentName, versionSchema, branchVersionSchema, vcsUri, wu);

		// Find or create branch
		BranchData branchData = findOrCreateBranch(componentData, vcsBranchName, vcsUri, wu);

		// Create SCEs from pedigree commits if present
		String vcsTag = resolveProperty(cdxComponent, PROP_VCS_TAG);
		List<UUID> sceUuids = createSceFromPedigree(orgUuid, cdxComponent, branchData, vcsBranchName, vcsUri, vcsTag, wu);

		UUID primarySceUuid = sceUuids.isEmpty() ? null : sceUuids.get(0);

		// Create deliverables for container/file type components
		List<UUID> deliverableUuids = new ArrayList<>();
		CdxType cdxType = CdxType.resolveStringToType(cdxComponent.getType().toString());
		if (cdxType == CdxType.CONTAINER || cdxType == CdxType.FILE) {
			UUID deliverableUuid = createDeliverable(orgUuid, cdxComponent, cdxType, branchData, version, wu);
			if (deliverableUuid != null) deliverableUuids.add(deliverableUuid);
		}

		// Create release
		List<UUID> allCommits = new LinkedList<>(sceUuids);
		if (primarySceUuid != null) allCommits.remove(primarySceUuid);

		ReleaseDto.ReleaseDtoBuilder releaseDtoBuilder = ReleaseDto.builder()
				.branch(branchData.getUuid())
				.org(orgUuid)
				.version(version)
				.lifecycle(ReleaseLifecycle.ASSEMBLED);

		if (primarySceUuid != null) {
			releaseDtoBuilder.sourceCodeEntry(primarySceUuid);
		}
		if (!allCommits.isEmpty()) {
			releaseDtoBuilder.commits(allCommits);
		}

		Release release = ossReleaseService.createRelease(releaseDtoBuilder.build(), wu);

		if (!deliverableUuids.isEmpty() && release != null) {
			ReleaseData releaseData = ReleaseData.dataFromRecord(release);
			VariantData baseVariant = variantService.getBaseVariantForRelease(releaseData);
			variantService.addOutboundDeliverables(deliverableUuids, baseVariant.getUuid(), wu);
		}

		return new ImportComponentResult(componentName, version, true, null);
	}

	private ComponentData findOrCreateComponent(UUID orgUuid, String name, String versionSchema,
			String branchVersionSchema, String vcsUri, WhoUpdated wu) throws RelizaException {
		Optional<ComponentData> existing = componentService.findComponentDataByOrgNameType(orgUuid, name, ComponentType.COMPONENT);
		if (existing.isPresent()) {
			return existing.get();
		}

		UUID vcsRepoUuid = null;
		if (StringUtils.isNotEmpty(vcsUri)) {
			Optional<VcsRepository> vcsRepo = vcsRepositoryService.getVcsRepositoryByUri(orgUuid, vcsUri, null, VcsType.GIT, true, wu);
			if (vcsRepo.isPresent()) vcsRepoUuid = vcsRepo.get().getUuid();
		}

		var cpdBuilder = CreateComponentDto.builder()
				.name(name)
				.organization(orgUuid)
				.type(ComponentType.COMPONENT)
				.versionSchema(versionSchema)
				.featureBranchVersioning(branchVersionSchema);

		if (vcsRepoUuid != null) cpdBuilder.vcs(vcsRepoUuid);

		var comp = componentService.createComponent(cpdBuilder.build(), wu);
		return ComponentData.dataFromRecord(comp);
	}

	private BranchData findOrCreateBranch(ComponentData componentData, String branchName, String vcsUri, WhoUpdated wu) throws RelizaException {
		Optional<io.reliza.model.Branch> branchOpt = branchService.findBranchByName(componentData.getUuid(), branchName, true, wu);
		if (branchOpt.isPresent()) {
			BranchData bd = BranchData.branchDataFromDbRecord(branchOpt.get());
			// Attach VCS repo to branch if not yet set and URI is available
			if (bd.getVcs() == null && StringUtils.isNotEmpty(vcsUri)) {
				Optional<VcsRepository> vcsRepo = vcsRepositoryService.getVcsRepositoryByUri(componentData.getOrg(), vcsUri, null, VcsType.GIT, true, wu);
				if (vcsRepo.isPresent()) {
					var branchDto = io.reliza.model.dto.BranchDto.builder()
							.uuid(bd.getUuid())
							.vcs(vcsRepo.get().getUuid())
							.vcsBranch(branchName)
							.build();
					try {
						branchService.updateBranch(branchDto, wu);
					} catch (RelizaException e) {
						log.warn("Could not attach VCS to branch {}: {}", branchName, e.getMessage());
					}
					bd = BranchData.branchDataFromDbRecord(branchService.getBranch(bd.getUuid()).get());
				}
			}
			return bd;
		}
		throw new RelizaException("Could not find or create branch: " + branchName);
	}

	private List<UUID> createSceFromPedigree(UUID orgUuid, Component cdxComponent, BranchData branchData,
			String vcsBranchName, String vcsUri, String vcsTag, WhoUpdated wu) {
		List<UUID> sceUuids = new ArrayList<>();
		Pedigree pedigree = cdxComponent.getPedigree();
		if (pedigree == null || pedigree.getCommits() == null || pedigree.getCommits().isEmpty()) {
			return sceUuids;
		}

		for (org.cyclonedx.model.Commit commit : pedigree.getCommits()) {
			String commitHash = commit.getUid();
			if (StringUtils.isEmpty(commitHash)) continue;

			// Prefer pedigree commit URL for VCS if top-level not provided
			String effectiveVcsUri = vcsUri;
			if (StringUtils.isEmpty(effectiveVcsUri) && StringUtils.isNotEmpty(commit.getUrl())) {
				effectiveVcsUri = commit.getUrl();
			}

			try {
				SceDto.SceDtoBuilder sceDtoBuilder = SceDto.builder()
						.branch(branchData.getUuid())
						.organizationUuid(orgUuid)
						.commit(commitHash)
						.vcsBranch(vcsBranchName);

				if (sceUuids.isEmpty() && StringUtils.isNotEmpty(vcsTag)) {
					sceDtoBuilder.vcsTag(vcsTag);
				}
				if (StringUtils.isNotEmpty(commit.getMessage())) {
					sceDtoBuilder.commitMessage(commit.getMessage());
				}
				var author = commit.getAuthor();
				if (author != null) {
					if (StringUtils.isNotEmpty(author.getName())) {
						sceDtoBuilder.commitAuthor(author.getName());
					}
					if (StringUtils.isNotEmpty(author.getEmail())) {
						sceDtoBuilder.commitEmail(author.getEmail());
					}
					if (author.getTimestamp() != null) {
						sceDtoBuilder.date(author.getTimestamp().toInstant().atZone(java.time.ZoneOffset.UTC));
					}
				}
				if (StringUtils.isNotEmpty(effectiveVcsUri)) {
					sceDtoBuilder.uri(effectiveVcsUri)
							.type(VcsType.GIT);
				}

				SceDto sceDto = sceDtoBuilder.build();
				Optional<io.reliza.model.SourceCodeEntryData> osced = sourceCodeEntryService
						.populateSourceCodeEntryByVcsAndCommit(sceDto, true, wu);
				if (osced != null && osced.isPresent()) {
					sceUuids.add(osced.get().getUuid());
				}
			} catch (Exception e) {
				log.warn("Failed to create SCE for commit {}: {}", commitHash, e.getMessage());
			}
		}

		return sceUuids;
	}

	private UUID createDeliverable(UUID orgUuid, Component cdxComponent, CdxType cdxType,
			BranchData branchData, String version, WhoUpdated wu) {
		try {
			String displayIdentifier = cdxComponent.getName();
			if (StringUtils.isNotEmpty(cdxComponent.getVersion())) {
				displayIdentifier += ":" + cdxComponent.getVersion();
			}

			Set<DigestRecord> digestRecords = new LinkedHashSet<>();
			if (cdxComponent.getHashes() != null) {
				for (Hash hash : cdxComponent.getHashes()) {
					TeaChecksumType algo = mapHashAlgorithm(hash.getAlgorithm());
					if (algo != null) {
						digestRecords.add(new DigestRecord(algo, hash.getValue(), DigestScope.ORIGINAL_FILE));
					}
				}
			}

			SoftwareDeliverableMetadata sdm = SoftwareDeliverableMetadata.builder()
					.digestRecords(digestRecords)
					.packageType(cdxType == CdxType.CONTAINER ? PackageType.CONTAINER : null)
					.build();

			DeliverableDto deliverableDto = DeliverableDto.builder()
					.org(orgUuid)
					.branch(branchData.getUuid())
					.type(cdxType)
					.displayIdentifier(displayIdentifier)
					.version(version)
					.softwareMetadata(sdm)
					.build();

			var deliverable = deliverableService.createDeliverable(deliverableDto, wu);
			return deliverable != null ? deliverable.getUuid() : null;
		} catch (Exception e) {
			log.warn("Failed to create deliverable for component '{}': {}", cdxComponent.getName(), e.getMessage());
			return null;
		}
	}

	private String resolveProperty(Component component, String name) {
		if (component.getProperties() == null) return null;
		return component.getProperties().stream()
				.filter(p -> name.equals(p.getName()))
				.map(Property::getValue)
				.findFirst()
				.orElse(null);
	}

	private String resolveVcsUriFromPedigree(Component component) {
		if (component.getPedigree() == null) return null;
		var commits = component.getPedigree().getCommits();
		if (commits == null || commits.isEmpty()) return null;
		return commits.get(0).getUrl();
	}

	private TeaChecksumType mapHashAlgorithm(String algorithmStr) {
		if (algorithmStr == null) return null;
		try {
			return mapHashAlgorithm(Hash.Algorithm.fromSpec(algorithmStr));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private TeaChecksumType mapHashAlgorithm(Hash.Algorithm algorithm) {
		if (algorithm == null) return null;
		return switch (algorithm) {
			case MD5 -> TeaChecksumType.MD5;
			case SHA1 -> TeaChecksumType.SHA_1;
			case SHA_256 -> TeaChecksumType.SHA_256;
			case SHA_384 -> TeaChecksumType.SHA_384;
			case SHA_512 -> TeaChecksumType.SHA_512;
			case SHA3_256 -> TeaChecksumType.SHA3_256;
			case SHA3_384 -> TeaChecksumType.SHA3_384;
			case SHA3_512 -> TeaChecksumType.SHA3_512;
			case BLAKE2b_256 -> TeaChecksumType.BLAKE2B_256;
			case BLAKE2b_384 -> TeaChecksumType.BLAKE2B_384;
			case BLAKE2b_512 -> TeaChecksumType.BLAKE2B_512;
			case BLAKE3 -> TeaChecksumType.BLAKE3;
			default -> null;
		};
	}
}
