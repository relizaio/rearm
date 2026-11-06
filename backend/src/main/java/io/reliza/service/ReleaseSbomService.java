/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.SidPurlUtils;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbom;
import io.reliza.model.ReleaseSbomRef;
import io.reliza.model.ReleaseSbomVersion;
import io.reliza.model.ReleaseSbomVersionData;
import io.reliza.model.ReleaseSbomWriter;
import io.reliza.model.ReleaseSbomWriter.Kind;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.repositories.ReleaseSbomRepository;
import io.reliza.repositories.ReleaseSbomVersionRepository;
import io.reliza.service.TeaPublicationService.NamedBytes;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The release SBOM identity (FDAW-1 D1, F-9; FDAW-7 3.3): one head per (release, SBOM profile name)
 * whose uuid is the CycloneDX serialNumber, and one immutable version row per content change. Every
 * writer (a TEA publish, a package act) calls {@link #ensureVersion} under the release's lease with a
 * merged, stripped document; equal content reuses the latest version, changed content uploads a new
 * revision of the same artifact row under the serial with the version plus one. The caller binds the
 * artifact in its own release save and never archives a pin's artifact. The service builds the tags
 * of every upload it makes (3.3a); no caller passes a tag.
 */
@Slf4j
@Service
public class ReleaseSbomService {

	@Autowired private ReleaseSbomRepository releaseSbomRepository;
	@Autowired private ReleaseSbomVersionRepository releaseSbomVersionRepository;
	@Autowired private SyntheticArtifactService syntheticArtifactService;
	@Autowired private ArtifactService artifactService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private RebomService rebomService;

	/** Through the proxy, so {@link #recordVersion} runs in its own transaction. */
	@Autowired @Lazy private ReleaseSbomService self;

	/** What one call did: the pin, whether a new version was written, whether the caller must bind the artifact. */
	public record EnsureOutcome(ReleaseSbomRef pin, boolean created, boolean needsBind) {}

	/** The rows of one new version, written in their own transaction. */
	record VersionWrite(ReleaseSbomVersionData version, boolean newHead) {}

	/**
	 * The identity's version for {@code document} (3.3): reuse of the latest version when its content
	 * digest is equal, else a new revision uploaded, read back and recorded. {@code document} is the
	 * merged document with rebom's producer link stripped; the caller holds {@code lease}, the lease of
	 * {@code rd}. Uploads only: the caller binds the artifact when {@code needsBind}.
	 *
	 * @throws IllegalArgumentException when {@code lease} is another release's
	 * @throws RelizaException when the upload, the read-back or the rows fail; nothing is pinned then
	 */
	public EnsureOutcome ensureVersion(ReleaseData rd, String profileName, JsonNode document,
			TeaPublishLock.Lease lease, ReleaseSbomWriter writer, WhoUpdated wu) throws RelizaException {
		if (null == lease || !rd.getUuid().equals(lease.release())) {
			throw new IllegalArgumentException("ensureVersion of release " + rd.getUuid()
					+ " was called with the lease of release " + (null == lease ? null : lease.release()));
		}
		ComponentData cd = getComponentService.getComponentData(rd.getComponent()).orElse(null);
		String label = labelOf(rd, cd);
		// 1. the head, else an adopted pre-identity TEA row, else a fresh identity
		Optional<ReleaseSbom> head = releaseSbomRepository.findByReleaseAndProfileName(rd.getUuid(), profileName);
		if (head.isEmpty()) head = adopt(rd, profileName, label, wu);
		String digest = TeaCollectionAssembler.contentDigest(document);
		Optional<ReleaseSbomVersionData> latest = head
				.flatMap(h -> releaseSbomVersionRepository.latest(h.getUuid()))
				.map(ReleaseSbomVersion::toData);

		// 2. equal content: reuse the latest version, upload nothing
		if (latest.isPresent() && digest.equals(latest.get().getContentDigest())) {
			ReleaseSbomRef pin = latest.get().toRef();
			if (Kind.TEA == writer.kind()) {
				ArtifactData stored = artifactService.getArtifactData(pin.artifact()).orElseThrow(
						() -> new RelizaException("release SBOM " + pin.serial() + " of release " + label
								+ ": its artifact " + pin.artifact() + " is missing"));
				stampTeaTags(stored, writer, profileName, wu);
			}
			return new EnsureOutcome(pin, false, needsBind(rd, pin.artifact()));
		}

		// 3. a new revision: version 1 under a fresh serial, or the head's latest plus one
		UUID serial = head.map(ReleaseSbom::getUuid).orElseGet(UUID::randomUUID);
		int version = head.map(h -> h.getLatestVersion() + 1).orElse(1);
		ArtifactData existing = head.flatMap(h -> artifactService.getArtifactData(h.getArtifact())).orElse(null);
		if (head.isPresent() && null == existing) {
			throw new RelizaException("release SBOM " + serial + " of release " + label + ": its artifact "
					+ head.get().getArtifact() + " is missing");
		}
		ObjectNode doc = (ObjectNode) document.deepCopy();
		doc.put("serialNumber", "urn:uuid:" + serial);
		doc.put("version", version);
		JsonNode metadata = doc.get("metadata");
		ObjectNode md = metadata instanceof ObjectNode m ? m : doc.putObject("metadata");
		md.put("timestamp", Instant.now().toString());
		byte[] bytes = Utils.OM.writeValueAsBytes(doc);
		String componentName = null == cd ? String.valueOf(rd.getComponent()) : cd.getName();
		String fileName = null != existing && StringUtils.isNotBlank(existing.getDisplayIdentifier())
				? existing.getDisplayIdentifier()
				: componentName + "-" + rd.getVersion() + "-" + profileName + "-tea-aggregate.cdx.json";
		ArtifactDto dto = ArtifactDto.builder().uuid(null == existing ? null : existing.getUuid())
				.type(ArtifactType.BOM).bomFormat(BomFormat.CYCLONEDX).displayIdentifier(fileName)
				.storedIn(StoredIn.REARM).tags(tagsForUpload(writer, existing, profileName, digest)).build();
		OrganizationData od = getOrganizationService.getOrganizationData(rd.getOrg()).orElse(null);
		String preferredPurl = SidPurlUtils.pickPreferredPurl(rd.getIdentifiers())
				.map(RearmIdentifier::getIdValue).orElse(null);
		RebomOptions options = new RebomOptions(componentName, null == od ? null : od.getName(), rd.getVersion(),
				ArtifactBelongsTo.SYNTHETIC, null, StripBom.FALSE, preferredPurl);
		// uploaded only: the caller binds it. A rebom version conflict surfaces here and is never
		// retried under a fresh serial (security F-5): that would re-mint an identity already pinned.
		UUID written = syntheticArtifactService.upload(rd.getUuid(), dto, new NamedBytes(bytes, fileName), options, wu);

		// 4. read-back: what rebom stored must carry the serial, the version and a retained digest
		ArtifactData stored = artifactService.getArtifactData(written).orElse(null);
		Optional<String> sha = null == stored ? Optional.empty()
				: TeaPublicationService.digestOf(stored, DigestScope.AS_UPLOADED);
		if (null == stored || null == stored.getInternalBom() || !serial.equals(stored.getInternalBom().id())
				|| TeaPublicationService.parseRevision(stored.getVersion()).orElse(-1) != version || sha.isEmpty()) {
			if (null == existing) archiveUnpinned(written, label, wu);
			throw new RelizaException("release SBOM " + serial + " of release " + label
					+ ": rebom stored it under another serial or version (sent " + serial + " version " + version + ")");
		}

		// 5. the rows, in their own transaction, before the caller's act commits
		if (latest.isPresent() && null != latest.get().getWriter()
				&& !Objects.equals(latest.get().getRecipe(), writer.recipe())) {
			ReleaseSbomVersionData l = latest.get();
			log.warn("release SBOM {} of release {} profile {}: version {} ({}) is shaped by recipe {}, the latest, "
					+ "version {} ({}), was {}", serial, label, profileName, version, writer.kind(),
					recipeLabel(writer.recipe(), writer.recipeRevision()), l.getVersion(), l.getWriter(),
					recipeLabel(l.getRecipe(), l.getRecipeRevision()));
		}
		ReleaseSbomVersionData v = new ReleaseSbomVersionData();
		v.setUuid(serial);
		v.setVersion(version);
		v.setOrg(rd.getOrg());
		v.setRelease(rd.getUuid());
		v.setProfileName(profileName);
		v.setArtifact(written);
		v.setSha256(sha.get().toLowerCase());
		v.setContentDigest(digest);
		v.setCreatedBy(null == wu ? null : wu.getLastUpdatedBy());
		v.setWriter(writer.kind());
		v.setRecipe(writer.recipe());
		v.setRecipeRevision(writer.recipeRevision());
		try {
			record(new VersionWrite(v, head.isEmpty()), rd);
		} catch (RelizaException | RuntimeException e) {
			// no row names a first upload whose rows failed: it is nobody's pin, so it goes like a failed read-back
			if (null == existing) archiveUnpinned(written, label, wu);
			throw e;
		}

		// 6. the pin
		ReleaseSbomRef pin = v.toRef();
		return new EnsureOutcome(pin, true, needsBind(rd, pin.artifact()));
	}

	/** The newest pin of (release, profile name), or empty. No caller gate: B3 gates the release first. */
	public Optional<ReleaseSbomRef> latestPin(UUID release, String profileName) {
		return releaseSbomRepository.findByReleaseAndProfileName(release, profileName)
				.flatMap(h -> releaseSbomVersionRepository.latest(h.getUuid())).map(v -> v.toData().toRef());
	}

	/** The pin of one stored version, or empty. No caller gate: B3 gates the release first. */
	public Optional<ReleaseSbomRef> pinOf(UUID serial, int version) {
		return releaseSbomVersionRepository.findVersion(serial, version).map(v -> v.toData().toRef());
	}

	/** One stored version with its writer record, or empty. */
	public Optional<ReleaseSbomVersionData> versionOf(UUID serial, int version) {
		return releaseSbomVersionRepository.findVersion(serial, version).map(ReleaseSbomVersion::toData);
	}

	private static boolean needsBind(ReleaseData rd, UUID artifact) {
		return null == rd.getSyntheticArtifacts() || !rd.getSyntheticArtifacts().contains(artifact);
	}

	/** {@link #recordVersion} through the proxy, a key conflict mapped to the defect it is (F-9 item 1). */
	private void record(VersionWrite w, ReleaseData rd) throws RelizaException {
		ReleaseSbomVersionData v = w.version();
		try {
			self.recordVersion(w);
		} catch (DataIntegrityViolationException e) {
			String text = "release SBOM " + v.getUuid() + " version " + v.getVersion()
					+ " already exists: a second writer ran without the lease of release " + rd.getUuid();
			log.error(text, e);
			throw new RelizaException(text);
		}
	}

	/**
	 * Step 5: the version row (an INSERT) and the head (an INSERT for a new identity, an update of the
	 * stored head of this release and profile name for a bump, security F-6), in their own transaction,
	 * so they commit before the caller's act and survive its failure.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RelizaException.class)
	public void recordVersion(VersionWrite w) throws RelizaException {
		ReleaseSbomVersionData v = w.version();
		releaseSbomVersionRepository.save(new ReleaseSbomVersion(v));
		if (w.newHead()) {
			releaseSbomRepository.save(new ReleaseSbom(v.getUuid(), v.getOrg(), v.getRelease(), v.getProfileName(),
					v.getArtifact(), v.getVersion()));
			return;
		}
		ReleaseSbom h = releaseSbomRepository.findByReleaseAndProfileName(v.getRelease(), v.getProfileName())
				.filter(x -> x.getUuid().equals(v.getUuid()))
				.orElseThrow(() -> new RelizaException("release SBOM " + v.getUuid() + " of release " + v.getRelease()
						+ ": the head of profile " + v.getProfileName() + " is not this serial"));
		h.advance(v.getVersion(), v.getArtifact());
		releaseSbomRepository.save(h);
	}

	// ------------------------------------------------------------------ adoption (3.3)

	/**
	 * A head for a release whose TEA aggregate was minted before the identity existed (D1 "adoption, not
	 * re-minting"): the first generated row of the release carrying the system tags
	 * {@code teaAggregate=true} and {@code teaProfileName=<name>} is adopted with its stored serial and
	 * version when the serial is readable and free. Otherwise empty, and the caller mints a fresh identity.
	 */
	Optional<ReleaseSbom> adopt(ReleaseData rd, String profileName, String label, WhoUpdated wu)
			throws RelizaException {
		Optional<ArtifactData> hit = syntheticArtifactService.findByTags(rd,
				Map.of(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", CommonVariables.TEA_PROFILE_NAME_TAG_KEY,
						profileName));
		if (hit.isEmpty()) return Optional.empty();
		ArtifactData row = artifactService.getArtifactData(hit.get().getUuid()).orElse(null);
		Optional<Integer> storedVersion = null == row ? Optional.empty()
				: TeaPublicationService.parseRevision(row.getVersion());
		Optional<String> sha = null == row ? Optional.empty()
				: TeaPublicationService.digestOf(row, DigestScope.AS_UPLOADED);
		if (null == row || null == row.getInternalBom() || null == row.getInternalBom().id()
				|| storedVersion.isEmpty() || sha.isEmpty()) {
			log.warn("release SBOM of release {} profile {}: artifact {} has no readable serial or version; a fresh "
					+ "identity is minted", label, profileName, hit.get().getUuid());
			return Optional.empty();
		}
		UUID serial = row.getInternalBom().id();
		Optional<ReleaseSbom> holder = releaseSbomRepository.findById(serial);
		if (holder.isPresent()) {
			log.error("release SBOM of release {} profile {}: artifact {} names serial {}, which release {} holds; a "
					+ "fresh identity is minted", label, profileName, row.getUuid(), serial, holder.get().getRelease());
			return Optional.empty();
		}
		String digest = TeaPublicationService.systemTagValue(row, CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY)
				.orElse(null);
		if (StringUtils.isBlank(digest)) {
			JsonNode bytes;
			try {
				bytes = rebomService.findBomByVersion(serial, rd.getOrg(), storedVersion.get());
			} catch (RuntimeException e) {
				throw new RelizaException("release SBOM of release " + label + " profile " + profileName
						+ ": the stored bytes of artifact " + row.getUuid() + " could not be read: " + e.getMessage());
			}
			if (null == bytes || !bytes.isObject()) {
				throw new RelizaException("release SBOM of release " + label + " profile " + profileName
						+ ": the stored bytes of artifact " + row.getUuid() + " could not be read");
			}
			digest = TeaCollectionAssembler.contentDigest(bytes);
		}
		// an adopted version's writer and recipe are not known: none is recorded, and no recipe WARN compares with it
		ReleaseSbomVersionData v = new ReleaseSbomVersionData();
		v.setUuid(serial);
		v.setVersion(storedVersion.get());
		v.setOrg(rd.getOrg());
		v.setRelease(rd.getUuid());
		v.setProfileName(profileName);
		v.setArtifact(row.getUuid());
		v.setSha256(sha.get().toLowerCase());
		v.setContentDigest(digest);
		v.setCreatedBy(null == wu ? null : wu.getLastUpdatedBy());
		record(new VersionWrite(v, true), rd);
		log.info("release SBOM of release {} profile {}: adopted serial {} version {} from artifact {}", label,
				profileName, serial, storedVersion.get(), row.getUuid());
		return releaseSbomRepository.findById(serial);
	}

	// ------------------------------------------------------------------ tags (3.3a)

	private static final List<String> TEA_IDENTITY_KEYS = List.of(CommonVariables.TEA_AGGREGATE_TAG_KEY,
			CommonVariables.TEA_PROFILE_NAME_TAG_KEY, CommonVariables.TEA_PUBLICATION_TAG_KEY);

	/**
	 * The tags of one upload (3.3a): a TEA writer writes TEA's four tags with its publication uuid; a
	 * package writer carries the three TEA identity tags of {@code stored} verbatim when it holds them
	 * as system tags, and writes the content digest. Every tag is {@code Removable.NO}; the upload path
	 * adds its own.
	 */
	static List<TagRecord> tagsForUpload(ReleaseSbomWriter writer, ArtifactData stored, String profileName,
			String digest) {
		List<TagRecord> tags = new ArrayList<>();
		if (Kind.TEA == writer.kind()) {
			tags.addAll(teaIdentityTags(writer, profileName));
		} else if (null != stored) {
			for (String key : TEA_IDENTITY_KEYS) {
				TeaPublicationService.systemTagValue(stored, key)
						.ifPresent(value -> tags.add(new TagRecord(key, value, Removable.NO)));
			}
		}
		tags.add(new TagRecord(CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY, digest, Removable.NO));
		return tags;
	}

	private static List<TagRecord> teaIdentityTags(ReleaseSbomWriter writer, String profileName) {
		return List.of(new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", Removable.NO),
				new TagRecord(CommonVariables.TEA_PROFILE_NAME_TAG_KEY, profileName, Removable.NO),
				new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, writer.publication().toString(), Removable.NO));
	}

	/**
	 * A TEA reuse of a row (3.3a): brings the row's three TEA identity tags to this publication's values
	 * when any is absent or differs, through {@code mergeSystemTags}; nothing when all three are equal.
	 * Never called for a package writer.
	 */
	void stampTeaTags(ArtifactData stored, ReleaseSbomWriter writer, String profileName, WhoUpdated wu)
			throws RelizaException {
		if (Kind.TEA != writer.kind()) return;
		List<TagRecord> wanted = teaIdentityTags(writer, profileName);
		boolean equal = wanted.stream().allMatch(t -> TeaPublicationService.systemTagValue(stored, t.key())
				.filter(t.value()::equals).isPresent());
		if (equal) return;
		artifactService.mergeSystemTags(stored.getUuid(), wanted, wu);
	}

	// ------------------------------------------------------------------ helpers

	private void archiveUnpinned(UUID artifact, String label, WhoUpdated wu) {
		log.warn("release SBOM of release {}: archiving the unpinned upload {} that was not recorded", label,
				artifact);
		try {
			artifactService.archiveArtifact(artifact, wu);
		} catch (RuntimeException e) {
			log.error("release SBOM of release {}: archiving the unpinned upload {} failed", label, artifact, e);
		}
	}

	private static String labelOf(ReleaseData rd, ComponentData cd) {
		return (null == cd ? String.valueOf(rd.getComponent()) : cd.getName()) + " " + rd.getVersion() + " ("
				+ rd.getUuid() + ")";
	}

	private static String recipeLabel(UUID recipe, Integer revision) {
		return null == recipe ? "built-in" : recipe + " r" + revision;
	}
}
