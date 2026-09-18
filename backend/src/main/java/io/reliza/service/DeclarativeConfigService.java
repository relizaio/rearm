/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.BranchSuffixMode;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.BranchData.DependencyPattern;
import io.reliza.model.BranchData.FindingAnalyticsParticipation;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentNature;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.DefaultBranchName;
import io.reliza.model.ComponentData.DeviceClass;
import io.reliza.model.DeclarativeProvenance;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.DeclarativePruneMode;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.ReleaseData;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.CreateVcsRepositoryDto;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Declarative (GitOps-style) configuration: idempotent, name-keyed apply and export of
 * the Catalog slice (components / products) and the Branches slice (branches / feature
 * sets of one component). Rows stay the source of truth; every touched row is stamped
 * with the provenance of the spec that last applied it. Pruning of rows absent from a
 * spec is governed by the org-wide {@link OrganizationData.Settings#getDeclarativePrune()}.
 */
@Service
@Slf4j
public class DeclarativeConfigService {

	/** Ownership slice a spec describes; one kind per file. */
	public enum DeclarativeKind { CATALOG, BRANCHES }


	@Autowired private ComponentService componentService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private BranchService branchService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private GetOrganizationService getOrganizationService;

	// ------------------------------------------------------------------ spec DTOs

	@Data public static class SourceDto { private String repo; private String path; private String commit; }

	@Data public static class CatalogSpecDto {
		private DeclarativeKind kind = DeclarativeKind.CATALOG;
		private Integer version = 1;
		private Boolean authoritative = false;
		private List<CatalogComponentDto> components = new LinkedList<>();
	}

	@Data public static class CatalogComponentDto {
		private String name;
		private ComponentType type;
		private ComponentKind kind;
		private String versionSchema;
		private String marketingVersionSchema;
		private VersionTypeEnum versionType;
		private String featureBranchVersioning;
		private String defaultBranch;
		private String vcsUri;
		private String vcsType;
		private String repoPath;
		private ComponentNature nature;
		private DeviceClass deviceClass;
		private BranchSuffixMode branchSuffixMode;
		private List<RearmIdentifier> identifiers;
		private DeclarativeProvenance provenance;
	}

	@Data public static class BranchesSpecDto {
		private DeclarativeKind kind = DeclarativeKind.BRANCHES;
		private Integer version = 1;
		private String component;
		/** Files claim the whole component by default; single-branch callers (provider) pass false. */
		private Boolean authoritative = true;
		private List<BranchSpecDto> branches = new LinkedList<>();
	}

	@Data public static class BranchSpecDto {
		private String name;
		private BranchType type;
		private String versionSchema;
		private String marketingVersionSchema;
		private String vcsBranch;
		private AutoIntegrateState autoIntegrate;
		private FindingAnalyticsParticipation findingAnalyticsParticipation;
		private List<DependencySpecDto> dependencies;
		private List<DependencyPattern> dependencyPatterns;
		private DeclarativeProvenance provenance;
	}

	@Data public static class DependencySpecDto {
		private String component;
		private String branch;
		private String release;
		private StatusEnum status;
		private Boolean isFollowVersion;
	}

	public enum Action { CREATE, UPDATE, UNCHANGED, ARCHIVE, ERROR }

	@Data public static class Change {
		/** Slice the entry belongs to; CATALOG entries are components, BRANCHES entries are branches. */
		private DeclarativeKind kind;
		private String name;
		private Action action;
		private List<String> fields = new LinkedList<>();
		private String message;
		static Change of(DeclarativeKind kind, String name, Action action, Collection<String> fields, String message) {
			Change c = new Change(); c.kind = kind; c.name = name; c.action = action;
			if (fields != null) c.fields = new LinkedList<>(fields); c.message = message; return c;
		}
	}

	@Data public static class ApplyResult {
		private DeclarativeKind kind;
		private boolean dryRun;
		private String specHash;
		private List<Change> changes = new LinkedList<>();
		private int created; private int updated; private int unchanged; private int archived; private int errors;
		void add(Change c) {
			changes.add(c);
			switch (c.action) {
				case CREATE -> created++;
				case UPDATE -> updated++;
				case UNCHANGED -> unchanged++;
				case ARCHIVE -> archived++;
				case ERROR -> errors++;
			}
		}
	}

	// ------------------------------------------------------------------ catalog

	@Transactional
	public ApplyResult applyCatalog(UUID orgUuid, CatalogSpecDto spec, boolean dryRun, SourceDto source, WhoUpdated wu) throws RelizaException {
		if (spec.getKind() != null && spec.getKind() != DeclarativeKind.CATALOG) {
			throw new RelizaException("spec kind " + spec.getKind() + " cannot be applied as CATALOG");
		}
		ApplyResult result = new ApplyResult();
		result.setKind(DeclarativeKind.CATALOG);
		result.setDryRun(dryRun);
		result.setSpecHash(specHash(spec));
		DeclarativeProvenance prov = provenance(result.getSpecHash(), source);
		List<ComponentData> existing = activeComponents(orgUuid);
		Set<String> declared = new HashSet<>();
		for (CatalogComponentDto c : spec.getComponents()) {
			if (StringUtils.isBlank(c.getName())) { result.add(Change.of(DeclarativeKind.CATALOG, "", Action.ERROR, null, "component name is required")); continue; }
			if (!declared.add(c.getName())) { result.add(Change.of(DeclarativeKind.CATALOG, c.getName(), Action.ERROR, null, "declared more than once")); continue; }
			try {
				Optional<ComponentData> cur = existing.stream().filter(x -> c.getName().equals(x.getName())).findFirst();
				if (cur.isEmpty()) {
					result.add(createComponent(orgUuid, c, dryRun, prov, wu));
				} else {
					result.add(updateComponent(orgUuid, cur.get(), c, dryRun, prov, wu));
				}
			} catch (RelizaException | RuntimeException e) {
				log.error("Declarative catalog apply failed for component {}", c.getName(), e);
				result.add(Change.of(DeclarativeKind.CATALOG, c.getName(), Action.ERROR, null, e.getMessage()));
			}
		}
		if (Boolean.TRUE.equals(spec.getAuthoritative())) {
			DeclarativePruneMode prune = pruneMode(orgUuid);
			for (ComponentData cd : existing) {
				if (declared.contains(cd.getName())) continue;
				if (prune == DeclarativePruneMode.ARCHIVE) {
					if (!dryRun) componentService.archiveComponent(cd.getUuid(), wu);
					result.add(Change.of(DeclarativeKind.CATALOG, cd.getName(), Action.ARCHIVE, null, "absent from authoritative spec"));
				} else {
					result.add(Change.of(DeclarativeKind.CATALOG, cd.getName(), Action.UNCHANGED, null, "absent from authoritative spec; declarativePrune=LEAVE"));
				}
			}
		}
		return result;
	}

	private Change createComponent(UUID orgUuid, CatalogComponentDto c, boolean dryRun, DeclarativeProvenance prov, WhoUpdated wu) throws RelizaException {
		if (c.getType() != ComponentType.COMPONENT && c.getType() != ComponentType.PRODUCT) {
			return Change.of(DeclarativeKind.CATALOG, c.getName(), Action.ERROR, null, "type must be COMPONENT or PRODUCT");
		}
		List<String> fields = new LinkedList<>();
		CreateComponentDto.CreateComponentDtoBuilder b = CreateComponentDto.builder()
				.name(c.getName()).organization(orgUuid).type(c.getType());
		fields.add("name"); fields.add("type");
		if (c.getKind() != null) { b.kind(c.getKind()); fields.add("kind"); }
		if (c.getVersionSchema() != null) { b.versionSchema(c.getVersionSchema()); fields.add("versionSchema"); }
		if (c.getMarketingVersionSchema() != null) { b.marketingVersionSchema(c.getMarketingVersionSchema()); fields.add("marketingVersionSchema"); }
		if (c.getVersionType() != null) { b.versionType(c.getVersionType()); fields.add("versionType"); }
		if (c.getFeatureBranchVersioning() != null) { b.featureBranchVersioning(c.getFeatureBranchVersioning()); fields.add("featureBranchVersioning"); }
		if (c.getDefaultBranch() != null) { b.defaultBranch(DefaultBranchName.valueOf(c.getDefaultBranch().toUpperCase())); fields.add("defaultBranch"); }
		if (c.getRepoPath() != null) { b.repoPath(c.getRepoPath()); fields.add("repoPath"); }
		if (c.getNature() != null) { b.nature(c.getNature()); fields.add("nature"); }
		if (c.getDeviceClass() != null) { b.deviceClass(c.getDeviceClass()); fields.add("deviceClass"); }
		if (c.getBranchSuffixMode() != null) { b.branchSuffixMode(c.getBranchSuffixMode()); fields.add("branchSuffixMode"); }
		if (c.getIdentifiers() != null) { b.identifiers(c.getIdentifiers()); fields.add("identifiers"); }
		if (StringUtils.isNotEmpty(c.getVcsUri())) {
			fields.add("vcsUri");
			Optional<VcsRepositoryData> vcs = vcsRepositoryService.getVcsRepositoryDataByUri(orgUuid, c.getVcsUri());
			if (vcs.isPresent()) {
				b.vcs(vcs.get().getUuid());
			} else {
				b.vcsRepository(CreateVcsRepositoryDto.builder()
						.name(Utils.deriveVcsNameFromUri(c.getVcsUri())).organization(orgUuid)
						.uri(c.getVcsUri()).type(vcsType(c.getVcsType())).build());
			}
		}
		if (dryRun) return Change.of(DeclarativeKind.CATALOG, c.getName(), Action.CREATE, fields, null);
		Component created = componentService.createComponent(b.build(), wu);
		componentService.stampDeclarativeProvenance(created.getUuid(), prov, wu);
		return Change.of(DeclarativeKind.CATALOG, c.getName(), Action.CREATE, fields, null);
	}

	private Change updateComponent(UUID orgUuid, ComponentData cur, CatalogComponentDto c, boolean dryRun, DeclarativeProvenance prov, WhoUpdated wu) throws RelizaException {
		if (c.getType() != null && c.getType() != cur.getType()) {
			return Change.of(DeclarativeKind.CATALOG, c.getName(), Action.ERROR, null,
					"exists as " + cur.getType() + ", spec says " + c.getType() + "; type cannot change");
		}
		List<String> fields = new LinkedList<>();
		ComponentDto.ComponentDtoBuilder b = ComponentDto.builder().uuid(cur.getUuid()).name(cur.getName());
		if (differs(c.getKind(), cur.getKind())) { b.kind(c.getKind()); fields.add("kind"); }
		if (differs(c.getVersionSchema(), cur.getVersionSchema())) { b.versionSchema(c.getVersionSchema()); fields.add("versionSchema"); }
		if (differs(c.getMarketingVersionSchema(), cur.getMarketingVersionSchema())) { b.marketingVersionSchema(c.getMarketingVersionSchema()); fields.add("marketingVersionSchema"); }
		if (differs(c.getVersionType(), cur.getVersionType())) { b.versionType(c.getVersionType()); fields.add("versionType"); }
		if (differs(c.getFeatureBranchVersioning(), cur.getFeatureBranchVersioning())) { b.featureBranchVersioning(c.getFeatureBranchVersioning()); fields.add("featureBranchVersioning"); }
		if (differs(c.getRepoPath(), cur.getRepoPath())) { b.repoPath(c.getRepoPath()); fields.add("repoPath"); }
		if (differs(c.getNature(), cur.getNature())) { b.nature(c.getNature()); fields.add("nature"); }
		if (differs(c.getDeviceClass(), cur.getDeviceClass())) { b.deviceClass(c.getDeviceClass()); fields.add("deviceClass"); }
		if (differs(c.getBranchSuffixMode(), cur.getBranchSuffixMode())) { b.branchSuffixMode(c.getBranchSuffixMode()); fields.add("branchSuffixMode"); }
		if (c.getIdentifiers() != null && !sameIdentifiers(c.getIdentifiers(), cur.getIdentifiers())) { b.identifiers(c.getIdentifiers()); fields.add("identifiers"); }
		if (StringUtils.isNotEmpty(c.getVcsUri())) {
			Optional<VcsRepositoryData> vcs = vcsRepositoryService.getVcsRepositoryDataByUri(orgUuid, c.getVcsUri());
			UUID vcsUuid = vcs.map(VcsRepositoryData::getUuid).orElse(null);
			if (vcs.isEmpty()) {
				if (!dryRun) vcsUuid = vcsRepositoryService.provisionVcsRepository(orgUuid, c.getVcsUri(), vcsType(c.getVcsType()), wu);
				fields.add("vcsUri");
				if (vcsUuid != null) b.vcs(vcsUuid);
			} else if (!vcsUuid.equals(cur.getVcs())) {
				b.vcs(vcsUuid); fields.add("vcsUri");
			}
		}
		boolean provenanceStale = cur.getDeclarative() == null || !Objects.equals(cur.getDeclarative().getSpecHash(), prov.getSpecHash());
		if (fields.isEmpty()) {
			if (!dryRun && provenanceStale) componentService.stampDeclarativeProvenance(cur.getUuid(), prov, wu);
			return Change.of(DeclarativeKind.CATALOG, c.getName(), Action.UNCHANGED, null, null);
		}
		if (!dryRun) {
			componentService.updateComponent(b.build(), wu);
			componentService.stampDeclarativeProvenance(cur.getUuid(), prov, wu);
		}
		return Change.of(DeclarativeKind.CATALOG, c.getName(), Action.UPDATE, fields, null);
	}

	public CatalogSpecDto exportCatalog(UUID orgUuid, List<String> names) {
		CatalogSpecDto spec = new CatalogSpecDto();
		spec.setAuthoritative(names == null || names.isEmpty());
		for (ComponentData cd : activeComponents(orgUuid)) {
			if (names != null && !names.isEmpty() && !names.contains(cd.getName())) continue;
			CatalogComponentDto c = new CatalogComponentDto();
			c.setName(cd.getName()); c.setType(cd.getType()); c.setKind(cd.getKind());
			c.setVersionSchema(cd.getVersionSchema()); c.setMarketingVersionSchema(cd.getMarketingVersionSchema());
			c.setVersionType(cd.getVersionType()); c.setFeatureBranchVersioning(cd.getFeatureBranchVersioning());
			c.setRepoPath(cd.getRepoPath()); c.setNature(cd.getNature()); c.setDeviceClass(cd.getDeviceClass());
			c.setBranchSuffixMode(cd.getBranchSuffixMode());
			RearmIdentifier.validateVocabulary(cd.getIdentifiers());
			c.setIdentifiers(cd.getIdentifiers());
			c.setProvenance(cd.getDeclarative());
			if (cd.getVcs() != null) {
				vcsRepositoryService.getVcsRepository(cd.getVcs()).map(VcsRepositoryData::dataFromRecord).ifPresent(v -> {
					c.setVcsUri(v.getUri()); c.setVcsType(v.getType() == null ? null : v.getType().toString());
				});
			}
			spec.getComponents().add(c);
		}
		spec.getComponents().sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		return spec;
	}

	// ------------------------------------------------------------------ branches

	@Transactional
	public ApplyResult applyBranches(UUID orgUuid, BranchesSpecDto spec, boolean dryRun, SourceDto source, WhoUpdated wu) throws RelizaException {
		if (spec.getKind() != null && spec.getKind() != DeclarativeKind.BRANCHES) {
			throw new RelizaException("spec kind " + spec.getKind() + " cannot be applied as BRANCHES");
		}
		ApplyResult result = new ApplyResult();
		result.setKind(DeclarativeKind.BRANCHES);
		result.setDryRun(dryRun);
		result.setSpecHash(specHash(spec));
		DeclarativeProvenance prov = provenance(result.getSpecHash(), source);
		Optional<ComponentData> ocd = componentByName(orgUuid, spec.getComponent());
		if (ocd.isEmpty()) {
			result.add(Change.of(DeclarativeKind.BRANCHES, String.valueOf(spec.getComponent()), Action.ERROR, null, "component not found in this organization"));
			return result;
		}
		ComponentData cd = ocd.get();
		List<BranchData> existing = branchService.listBranchDataOfComponent(cd.getUuid(), StatusEnum.ACTIVE);
		Set<String> declared = new HashSet<>();
		for (BranchSpecDto bs : spec.getBranches()) {
			if (StringUtils.isBlank(bs.getName())) { result.add(Change.of(DeclarativeKind.BRANCHES, "", Action.ERROR, null, "branch name is required")); continue; }
			if (!declared.add(bs.getName())) { result.add(Change.of(DeclarativeKind.BRANCHES, bs.getName(), Action.ERROR, null, "declared more than once")); continue; }
			try {
				Optional<Branch> ob = branchService.findBranchByName(cd.getUuid(), bs.getName());
				if (ob.isEmpty()) {
					result.add(createBranch(orgUuid, cd, bs, dryRun, prov, wu));
				} else {
					BranchData bd = BranchData.branchDataFromDbRecord(ob.get());
					if (bd.getStatus() == StatusEnum.ARCHIVED) {
						result.add(Change.of(DeclarativeKind.BRANCHES, bs.getName(), Action.ERROR, null, "an archived branch with this name exists; unarchive it first"));
					} else {
						result.add(updateBranch(orgUuid, cd, bd, bs, dryRun, prov, wu));
					}
				}
			} catch (RelizaException | RuntimeException e) {
				log.error("Declarative branches apply failed for {} / {}", cd.getName(), bs.getName(), e);
				result.add(Change.of(DeclarativeKind.BRANCHES, bs.getName(), Action.ERROR, null, e.getMessage()));
			}
		}
		DeclarativePruneMode prune = Boolean.FALSE.equals(spec.getAuthoritative()) ? null : pruneMode(orgUuid);
		for (BranchData bd : existing) {
			if (prune == null) break;
			if (bd.getType() == BranchType.BASE || declared.contains(bd.getName())) continue;
			if (prune == DeclarativePruneMode.ARCHIVE) {
				if (!dryRun) branchService.archiveBranch(bd.getUuid(), wu);
				result.add(Change.of(DeclarativeKind.BRANCHES, bd.getName(), Action.ARCHIVE, null, "absent from spec"));
			} else {
				result.add(Change.of(DeclarativeKind.BRANCHES, bd.getName(), Action.UNCHANGED, null, "absent from spec; declarativePrune=LEAVE"));
			}
		}
		return result;
	}

	private Change createBranch(UUID orgUuid, ComponentData cd, BranchSpecDto bs, boolean dryRun, DeclarativeProvenance prov, WhoUpdated wu) throws RelizaException {
		List<String> fields = new LinkedList<>(List.of("name"));
		if (bs.getType() != null) fields.add("type");
		if (bs.getVersionSchema() != null) fields.add("versionSchema");
		if (bs.getMarketingVersionSchema() != null) fields.add("marketingVersionSchema");
		if (bs.getVcsBranch() != null) fields.add("vcsBranch");
		if (bs.getAutoIntegrate() != null) fields.add("autoIntegrate");
		if (bs.getFindingAnalyticsParticipation() != null) fields.add("findingAnalyticsParticipation");
		List<ChildComponent> deps = bs.getDependencies() == null ? null : resolveDependencies(orgUuid, bs.getDependencies());
		if (deps != null) fields.add("dependencies");
		if (bs.getDependencyPatterns() != null) fields.add("dependencyPatterns");
		if (dryRun) return Change.of(DeclarativeKind.BRANCHES, bs.getName(), Action.CREATE, fields, null);
		Branch b = branchService.createBranch(bs.getName(), cd, bs.getType(), null, bs.getVcsBranch(), bs.getVersionSchema(), bs.getMarketingVersionSchema(), wu);
		BranchDto.BranchDtoBuilder upd = BranchDto.builder().uuid(b.getUuid());
		boolean needsUpdate = false;
		if (bs.getAutoIntegrate() != null) { upd.autoIntegrate(bs.getAutoIntegrate()); needsUpdate = true; }
		if (bs.getFindingAnalyticsParticipation() != null) { upd.findingAnalyticsParticipation(bs.getFindingAnalyticsParticipation()); needsUpdate = true; }
		if (deps != null) { upd.dependencies(deps); needsUpdate = true; }
		if (bs.getDependencyPatterns() != null) { upd.dependencyPatterns(withPatternUuids(bs.getDependencyPatterns(), null)); needsUpdate = true; }
		if (needsUpdate) branchService.updateBranch(upd.build(), wu);
		branchService.stampDeclarativeProvenance(b.getUuid(), prov, wu);
		return Change.of(DeclarativeKind.BRANCHES, bs.getName(), Action.CREATE, fields, null);
	}

	private Change updateBranch(UUID orgUuid, ComponentData cd, BranchData cur, BranchSpecDto bs, boolean dryRun, DeclarativeProvenance prov, WhoUpdated wu) throws RelizaException {
		if (bs.getType() != null && bs.getType() != cur.getType()) {
			if (cur.getType() == BranchType.BASE || bs.getType() == BranchType.BASE) {
				return Change.of(DeclarativeKind.BRANCHES, bs.getName(), Action.ERROR, null, "the base branch type cannot change");
			}
		}
		List<String> fields = new LinkedList<>();
		BranchDto.BranchDtoBuilder b = BranchDto.builder().uuid(cur.getUuid());
		if (bs.getType() != null && bs.getType() != cur.getType()) { b.type(bs.getType()); fields.add("type"); }
		if (differs(bs.getVersionSchema(), cur.getVersionSchema())) { b.versionSchema(bs.getVersionSchema()); fields.add("versionSchema"); }
		if (differs(bs.getMarketingVersionSchema(), cur.getMarketingVersionSchema())) { b.marketingVersionSchema(bs.getMarketingVersionSchema()); fields.add("marketingVersionSchema"); }
		if (differs(bs.getVcsBranch(), cur.getVcsBranch())) { b.vcsBranch(bs.getVcsBranch()); fields.add("vcsBranch"); }
		if (differs(bs.getAutoIntegrate(), cur.getAutoIntegrate())) { b.autoIntegrate(bs.getAutoIntegrate()); fields.add("autoIntegrate"); }
		if (differs(bs.getFindingAnalyticsParticipation(), cur.getFindingAnalyticsParticipation())) { b.findingAnalyticsParticipation(bs.getFindingAnalyticsParticipation()); fields.add("findingAnalyticsParticipation"); }
		if (bs.getDependencies() != null) {
			List<ChildComponent> deps = resolveDependencies(orgUuid, bs.getDependencies());
			if (!sameDependencies(deps, cur.getDependencies())) { b.dependencies(deps); fields.add("dependencies"); }
		}
		if (bs.getDependencyPatterns() != null && (!samePatterns(bs.getDependencyPatterns(), cur.getDependencyPatterns()) || hasUnsetDefaults(cur.getDependencyPatterns()))) {
			b.dependencyPatterns(withPatternUuids(bs.getDependencyPatterns(), cur.getDependencyPatterns())); fields.add("dependencyPatterns");
		}
		boolean provenanceStale = cur.getDeclarative() == null || !Objects.equals(cur.getDeclarative().getSpecHash(), prov.getSpecHash());
		if (fields.isEmpty()) {
			if (!dryRun && provenanceStale) branchService.stampDeclarativeProvenance(cur.getUuid(), prov, wu);
			return Change.of(DeclarativeKind.BRANCHES, bs.getName(), Action.UNCHANGED, null, null);
		}
		if (!dryRun) {
			branchService.updateBranch(b.build(), wu);
			branchService.stampDeclarativeProvenance(cur.getUuid(), prov, wu);
		}
		return Change.of(DeclarativeKind.BRANCHES, bs.getName(), Action.UPDATE, fields, null);
	}

	public BranchesSpecDto exportBranches(UUID orgUuid, String componentName) throws RelizaException {
		ComponentData cd = componentByName(orgUuid, componentName)
				.orElseThrow(() -> new RelizaException("Component not found in this organization: " + componentName));
		BranchesSpecDto spec = new BranchesSpecDto();
		spec.setComponent(cd.getName());
		spec.setAuthoritative(true);
		Map<UUID, String> compNames = new LinkedHashMap<>();
		for (BranchData bd : branchService.listBranchDataOfComponent(cd.getUuid(), StatusEnum.ACTIVE)) {
			BranchSpecDto bs = new BranchSpecDto();
			bs.setName(bd.getName()); bs.setType(bd.getType()); bs.setVersionSchema(bd.getVersionSchema());
			bs.setMarketingVersionSchema(bd.getMarketingVersionSchema()); bs.setVcsBranch(bd.getVcsBranch());
			bs.setAutoIntegrate(bd.getAutoIntegrate()); bs.setFindingAnalyticsParticipation(bd.getFindingAnalyticsParticipation());
			bs.setDependencyPatterns(bd.getDependencyPatterns() == null ? null
					: bd.getDependencyPatterns().stream().map(DeclarativeConfigService::normalized).collect(Collectors.toList()));
			bs.setProvenance(bd.getDeclarative());
			List<DependencySpecDto> deps = new LinkedList<>();
			for (ChildComponent cc : bd.getDependencies()) {
				DependencySpecDto d = new DependencySpecDto();
				d.setComponent(compNames.computeIfAbsent(cc.getUuid(), u -> getComponentService.getComponentData(u).map(ComponentData::getName).orElse(u.toString())));
				if (cc.getBranch() != null) d.setBranch(branchService.getBranchData(cc.getBranch()).map(BranchData::getName).orElse(cc.getBranch().toString()));
				if (cc.getRelease() != null) d.setRelease(sharedReleaseService.getReleaseData(cc.getRelease()).map(ReleaseData::getVersion).orElse(cc.getRelease().toString()));
				d.setStatus(cc.getStatus()); d.setIsFollowVersion(cc.getIsFollowVersion());
				deps.add(d);
			}
			bs.setDependencies(deps);
			spec.getBranches().add(bs);
		}
		spec.getBranches().sort((a, b) -> {
			if (a.getType() == BranchType.BASE) return -1;
			if (b.getType() == BranchType.BASE) return 1;
			return a.getName().compareToIgnoreCase(b.getName());
		});
		return spec;
	}

	// ------------------------------------------------------------------ helpers

	private List<ComponentData> activeComponents(UUID orgUuid) {
		return componentService.listComponentDataByOrganization(orgUuid, ComponentType.COMPONENT, ComponentType.PRODUCT).stream()
				.filter(cd -> cd.getStatus() == null || cd.getStatus() == StatusEnum.ACTIVE)
				.collect(Collectors.toList());
	}

	private Optional<ComponentData> componentByName(UUID orgUuid, String name) {
		if (StringUtils.isBlank(name)) return Optional.empty();
		return activeComponents(orgUuid).stream().filter(cd -> name.equals(cd.getName())).findFirst();
	}

	private List<ChildComponent> resolveDependencies(UUID orgUuid, List<DependencySpecDto> specs) throws RelizaException {
		List<ChildComponent> out = new LinkedList<>();
		for (DependencySpecDto d : specs) {
			ComponentData dep = componentByName(orgUuid, d.getComponent())
					.orElseThrow(() -> new RelizaException("dependency component not found: " + d.getComponent()));
			UUID branchUuid = null;
			if (StringUtils.isNotEmpty(d.getBranch())) {
				branchUuid = branchService.findBranchByName(dep.getUuid(), d.getBranch())
						.orElseThrow(() -> new RelizaException("dependency branch not found: " + d.getComponent() + " / " + d.getBranch())).getUuid();
			}
			UUID releaseUuid = null;
			if (StringUtils.isNotEmpty(d.getRelease())) {
				releaseUuid = sharedReleaseService.findReleaseByComponentAndVersion(dep.getUuid(), d.getRelease())
						.orElseThrow(() -> new RelizaException("dependency release not found: " + d.getComponent() + " " + d.getRelease())).getUuid();
			}
			ChildComponent cc = new ChildComponent();
			cc.setUuid(dep.getUuid()); cc.setBranch(branchUuid); cc.setRelease(releaseUuid);
			cc.setStatus(d.getStatus() == null ? StatusEnum.ACTIVE : d.getStatus());
			cc.setIsFollowVersion(d.getIsFollowVersion());
			out.add(cc);
		}
		return out;
	}

	private static String depKey(ChildComponent c) {
		return c.getUuid() + "|" + c.getBranch() + "|" + c.getRelease() + "|" + (c.getStatus() == null ? StatusEnum.ACTIVE : c.getStatus()) + "|" + Boolean.TRUE.equals(c.getIsFollowVersion());
	}

	private static boolean sameDependencies(List<ChildComponent> a, List<ChildComponent> b) {
		Set<String> sa = (a == null ? List.<ChildComponent>of() : a).stream().map(DeclarativeConfigService::depKey).collect(Collectors.toSet());
		Set<String> sb = (b == null ? List.<ChildComponent>of() : b).stream().map(DeclarativeConfigService::depKey).collect(Collectors.toSet());
		return sa.equals(sb);
	}

	private static String patternKey(DependencyPattern raw) {
		DependencyPattern p = normalized(raw);
		return p.getPattern() + "|" + p.getTargetBranchName() + "|" + p.getDefaultStatus() + "|" + p.getFallbackToBase();
	}

	private static boolean samePatterns(List<DependencyPattern> a, List<DependencyPattern> b) {
		Set<String> sa = (a == null ? List.<DependencyPattern>of() : a).stream().map(DeclarativeConfigService::patternKey).collect(Collectors.toSet());
		Set<String> sb = (b == null ? List.<DependencyPattern>of() : b).stream().map(DeclarativeConfigService::patternKey).collect(Collectors.toSet());
		return sa.equals(sb);
	}

	/** Keep existing pattern uuids for patterns that are unchanged; mint uuids for new ones. */
	private static List<DependencyPattern> withPatternUuids(List<DependencyPattern> desired, List<DependencyPattern> current) {
		Map<String, UUID> known = new LinkedHashMap<>();
		if (current != null) current.forEach(p -> { if (p.getUuid() != null) known.putIfAbsent(patternKey(p), p.getUuid()); });
		List<DependencyPattern> out = new ArrayList<>();
		for (DependencyPattern p : desired) {
			DependencyPattern np = new DependencyPattern(known.getOrDefault(patternKey(p), UUID.randomUUID()),
					p.getPattern(), p.getTargetBranchName(),
					p.getDefaultStatus() == null ? StatusEnum.ACTIVE : p.getDefaultStatus(),
					p.getFallbackToBase() == null ? BranchData.FallbackToBase.ENABLED : p.getFallbackToBase());
			out.add(np);
		}
		return out;
	}

	/** Rows written before defaults were enforced may carry null status / fallback; rewrite them on the next apply. */
	private static boolean hasUnsetDefaults(List<DependencyPattern> stored) {
		return stored != null && stored.stream().anyMatch(p -> p.getDefaultStatus() == null || p.getFallbackToBase() == null);
	}

	/** Spec patterns may omit defaults; compare them the way they will be stored. */
	private static DependencyPattern normalized(DependencyPattern p) {
		return new DependencyPattern(p.getUuid(), p.getPattern(), p.getTargetBranchName(),
				p.getDefaultStatus() == null ? StatusEnum.ACTIVE : p.getDefaultStatus(),
				p.getFallbackToBase() == null ? BranchData.FallbackToBase.ENABLED : p.getFallbackToBase());
	}

	private static boolean sameIdentifiers(List<RearmIdentifier> a, List<RearmIdentifier> b) {
		Set<String> sa = (a == null ? List.<RearmIdentifier>of() : a).stream().map(i -> i.getIdType() + "|" + i.getIdValue()).collect(Collectors.toSet());
		Set<String> sb = (b == null ? List.<RearmIdentifier>of() : b).stream().map(i -> i.getIdType() + "|" + i.getIdValue()).collect(Collectors.toSet());
		return sa.equals(sb);
	}

	/** A spec field only counts when set; null means "not managed by this file". */
	private static boolean differs(Object desired, Object current) {
		return desired != null && !desired.equals(current);
	}

	private static VcsType vcsType(String typeStr) {
		if (StringUtils.isEmpty(typeStr)) return null;
		return VcsType.forValue(typeStr); // case-insensitive: "git" and "GIT" both resolve
	}

	private DeclarativePruneMode pruneMode(UUID orgUuid) {
		return getOrganizationService.getOrganizationData(orgUuid)
				.map(OrganizationData::getSettings)
				.map(OrganizationData.Settings::getDeclarativePrune)
				.orElse(DeclarativePruneMode.LEAVE);
	}

	private static DeclarativeProvenance provenance(String specHash, SourceDto source) {
		DeclarativeProvenance.Source src = source == null ? null : new DeclarativeProvenance.Source(source.getRepo(), source.getPath(), source.getCommit());
		return new DeclarativeProvenance(specHash, ZonedDateTime.now(), src);
	}

	/** SHA-256 over the canonical (key-sorted, provenance-stripped) JSON of a spec. */
	static String specHash(Object spec) {
		try {
			Object tree = canonical(Utils.OM.convertValue(spec, Object.class));
			String json = Utils.OM.writeValueAsString(tree);
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] h = md.digest(json.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte x : h) sb.append(String.format("%02x", x));
			return sb.toString();
		} catch (Exception e) {
			throw new IllegalStateException("cannot hash spec", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Object canonical(Object o) {
		if (o instanceof Map<?, ?> m) {
			TreeMap<String, Object> t = new TreeMap<>();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				String k = String.valueOf(e.getKey());
				if ("provenance".equals(k) || e.getValue() == null) continue;
				t.put(k, canonical(e.getValue()));
			}
			return t;
		}
		if (o instanceof List<?> l) {
			List<Object> out = new ArrayList<>();
			for (Object x : l) out.add(canonical(x));
			return out;
		}
		return o;
	}
}
