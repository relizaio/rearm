/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ReleaseRebomRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.ArtifactAttachmentGuard.ArtifactHolders;
import io.reliza.service.RebomService.MalformedRebomAnswerException;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Rule B (SCORE-52 ADR-3): uploading a new version of a CycloneDX serialNumber that already exists
 * in an organization needs organization-wide write, or write on every component that holds it, and
 * is refused before any byte of the upload leaves. rebom only orders accepted versions under its
 * lock; who may store a new version is decided here alone.
 *
 * <p>Each accepted BOM upload is classified as {@link UploadKind#AUTHOR} or {@link UploadKind#COPY}
 * ({@code ArtifactData.bomCopy}). A copy is a re-upload that stored no new version, by a principal
 * that could not at that moment have stored one: it attaches and makes its component a holder, but
 * a holder reached only through copies never authorizes an update.
 *
 * <p>Also records a refusal on the target release ({@link #recordRefusal}).
 */
@Slf4j
@Service
public class SbomUpdateGuard {

	/** What an accepted BOM upload is: an authorizing row, or a copy that never authorizes. */
	public enum UploadKind {
		AUTHOR,
		COPY
	}

	private static final String URN_UUID_PREFIX = "urn:uuid:";

	private final ArtifactRepository artifactRepository;
	private final ArtifactAttachmentGuard artifactAttachmentGuard;
	private final ReleaseRebomRepository releaseRebomRepository;
	private final SharedReleaseService sharedReleaseService;
	private final ReleaseRepository releaseRepository;
	private final EntityManager entityManager;
	private final RebomService rebomService;

	public SbomUpdateGuard(ArtifactRepository artifactRepository, ArtifactAttachmentGuard artifactAttachmentGuard,
			ReleaseRebomRepository releaseRebomRepository, @Lazy SharedReleaseService sharedReleaseService,
			ReleaseRepository releaseRepository, EntityManager entityManager, RebomService rebomService) {
		this.artifactRepository = artifactRepository;
		this.artifactAttachmentGuard = artifactAttachmentGuard;
		this.releaseRebomRepository = releaseRebomRepository;
		this.sharedReleaseService = sharedReleaseService;
		this.releaseRepository = releaseRepository;
		this.entityManager = entityManager;
		this.rebomService = rebomService;
	}

	/**
	 * The canonical text of a serialNumber, keyed as rebom keys a stored BOM: trimmed as JavaScript's
	 * {@code String.prototype.trim} trims ({@link #trimAsRebom}), a leading {@code urn:uuid:} stripped
	 * in any letter case, parsed as a uuid; its {@code toString()} is the lowercase form internalBom.id
	 * is stored in. Empty for null, blank, braced or otherwise non-uuid input, and for a value whose
	 * padding rebom does not trim, which rebom keys to a lineage of its own.
	 */
	public static Optional<UUID> canonicalSerialNumber(String value) {
		if (null == value) {
			return Optional.empty();
		}
		String text = trimAsRebom(value);
		if (text.regionMatches(true, 0, URN_UUID_PREFIX, 0, URN_UUID_PREFIX.length())) {
			text = text.substring(URN_UUID_PREFIX.length());
		}
		// UUID.fromString accepts short groups ("1-2-3-4-5"); only the 36-character form is a serialNumber.
		if (text.length() != 36) {
			return Optional.empty();
		}
		try {
			return Optional.of(UUID.fromString(text));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/**
	 * The lineage of the serialNumber rebom answered an upload with, which becomes the artifact's
	 * internalBom.id and so a holder record: present only when the value is exactly
	 * {@code urn:uuid:} followed by the canonical text of a uuid (36 characters, lowercase, as
	 * {@link UUID#toString} writes it), or that text alone. Those are the two forms rebom keys to the
	 * lineage {@code urn:uuid:<uuid>} and reads back by the uuid. Empty for anything else (SCORE-47 T-4):
	 * a doubled prefix, short groups, upper case or braces is a lineage of its own in rebom, which must
	 * never be recorded as a holding of the uuid it resembles; padded text is a row rebom's reads by the
	 * uuid do not match (they compare the stored text exactly), so it is refused as before.
	 */
	public static Optional<UUID> lineageOfRebomSerialNumber(String serialNumber) {
		if (null == serialNumber) {
			return Optional.empty();
		}
		String text = serialNumber.startsWith(URN_UUID_PREFIX)
				? serialNumber.substring(URN_UUID_PREFIX.length()) : serialNumber;
		try {
			// UUID.fromString accepts short groups and upper case; only its own output text is rebom's key.
			UUID id = UUID.fromString(text);
			return id.toString().equals(text) ? Optional.of(id) : Optional.empty();
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/**
	 * {@code value} with the leading and trailing code points JavaScript's {@code String.prototype.trim}
	 * strips removed, and no others (SCORE-47 T-1): the text rebom keys an upload on. {@code String.trim}
	 * differs both ways: it strips the C0 controls JavaScript keeps, and keeps U+00A0, U+FEFF and the
	 * Unicode spaces JavaScript strips, so a serialNumber padded with one of those would be keyed to a
	 * lineage here other than rebom's. Every such code point is in the Basic Multilingual Plane and none
	 * is a surrogate, so a char-wise scan is exact.
	 */
	static String trimAsRebom(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && isTrimmedByRebom(value.charAt(start))) {
			start++;
		}
		while (end > start && isTrimmedByRebom(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(start, end);
	}

	/**
	 * The ECMA-262 WhiteSpace code points (TAB, VT, FF, SPACE, NO-BREAK SPACE, ZERO WIDTH NO-BREAK SPACE
	 * U+FEFF and every other Unicode Space_Separator: U+1680, U+2000 to U+200A, U+202F, U+205F, U+3000)
	 * and LineTerminator code points (LF, CR, U+2028, U+2029): the set {@code String.prototype.trim}
	 * strips. Not U+0085 or U+180E, which JavaScript keeps.
	 */
	private static boolean isTrimmedByRebom(char c) {
		switch (c) {
		case 0x0009: case 0x000A: case 0x000B: case 0x000C: case 0x000D: case 0x0020:
		case 0x00A0: case 0x1680: case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000: case 0xFEFF:
			return true;
		default:
			return c >= 0x2000 && c <= 0x200A;
		}
	}

	/**
	 * Rule B for an upload of {@code serialNumber} at {@code uploadedVersion}: refuses it, or answers
	 * what the accepted upload is. Each step is a cheap exit before the next:
	 * <ol>
	 * <li>organization-wide write: {@code AUTHOR}, no read;</li>
	 * <li>no artifact of the organization records the serialNumber: one rebom read. rebom holds no
	 * row: a new serialNumber, {@code AUTHOR}. rebom's version is unreadable, or below the upload's:
	 * refused (a merge's serialNumber, or an upload that never committed: no component holds it).
	 * Otherwise nothing new is stored, and the upload is a {@code COPY} when a release's stored merge
	 * lists the serialNumber, an {@code AUTHOR} (the first artifact row) when not;</li>
	 * <li>the upload is not above the highest recorded version: nothing new is stored, never refused;
	 * {@code AUTHOR} when the principal stands behind the serialNumber ({@link #authorizesReupload}),
	 * {@code COPY} otherwise;</li>
	 * <li>every holder of every row (containers included) must be writable, and a row whose holders
	 * do not resolve refuses;</li>
	 * <li>at least one holder must be reached through a row that is not a copy: {@code AUTHOR};
	 * otherwise refused.</li>
	 * </ol>
	 *
	 * @param serialKeyText the text rebom keys this upload on: the trimmed document serialNumber for
	 *        CycloneDX, internalBom.id for an SPDX update
	 * @param serialAsSent the serialNumber as the caller sent it, the only one a refusal names
	 * @throws SbomUpdateNotPermittedException on a refusal
	 * @throws RelizaException rebom's own error on the step 2 read, as itself
	 */
	public UploadKind refuseUnauthorizedUpdate(UUID org, UUID serialNumber, String serialKeyText,
			int uploadedVersion, SbomWriteAuthority authority, String serialAsSent) throws RelizaException {
		if (authority.orgWideWrite()) {
			return UploadKind.AUTHOR;
		}
		List<Artifact> rows = artifactRepository.findBomArtifactsByOrgAndInternalBom(org.toString(),
				serialNumber.toString());
		if (rows.isEmpty()) {
			return classifyRebomOnlySerialNumber(org, serialNumber, serialKeyText, uploadedVersion, authority,
					serialAsSent);
		}
		int recordedMax = rows.stream().mapToInt(row -> versionOf(ArtifactData.dataFromRecord(row))).max().orElse(0);
		if (uploadedVersion <= recordedMax) {
			return authorizesReupload(org, rows, authority) ? UploadKind.AUTHOR : UploadKind.COPY;
		}
		HolderWalk walk = walkHolders(org, rows, authority, false);
		if (walk.verdict() != WalkVerdict.AUTHORIZED) {
			throw refusal(org, serialNumber, uploadedVersion, walk.seen(), authority, serialAsSent);
		}
		return UploadKind.AUTHOR;
	}

	/** Step 2 (b): no artifact row records the serialNumber, so rebom's own record decides. */
	private UploadKind classifyRebomOnlySerialNumber(UUID org, UUID serialNumber, String serialKeyText,
			int uploadedVersion, SbomWriteAuthority authority, String serialAsSent) throws RelizaException {
		OptionalInt stored;
		try {
			stored = rebomService.latestStoredBomVersion(org, serialKeyText);
		} catch (MalformedRebomAnswerException unreadable) {
			log.warn("SBOM update guard: unreadable rebom version for org={} serialNumber={}: {}", org, serialNumber,
					unreadable.getMessage());
			throw refusal(org, serialNumber, uploadedVersion, 0, authority, serialAsSent);
		}
		if (stored.isEmpty()) {
			return UploadKind.AUTHOR;
		}
		if (uploadedVersion > stored.getAsInt()) {
			throw refusal(org, serialNumber, uploadedVersion, 0, authority, serialAsSent);
		}
		return releaseRebomRepository.existsListingRebomId(serialNumber.toString()) ? UploadKind.COPY
				: UploadKind.AUTHOR;
	}

	/**
	 * What an SPDX first upload is, after rebom answered it with {@code serialNumber} (rebom answers
	 * an existing row for a namespace and content it already stores). Never refuses: a first upload
	 * stores no new version of an existing serialNumber. {@code AUTHOR} for an organization-wide
	 * principal (no read) or a serialNumber no other artifact records; otherwise as a CycloneDX
	 * re-upload ({@link #authorizesReupload}).
	 */
	public UploadKind classifySpdxFirstUpload(UUID org, UUID serialNumber, UUID artifactUuid,
			SbomWriteAuthority authority) {
		if (authority.orgWideWrite()) {
			return UploadKind.AUTHOR;
		}
		List<Artifact> rows = artifactRepository.findBomArtifactsByOrgAndInternalBom(org.toString(),
				serialNumber.toString()).stream().filter(row -> !row.getUuid().equals(artifactUuid)).toList();
		if (rows.isEmpty()) {
			return UploadKind.AUTHOR;
		}
		return authorizesReupload(org, rows, authority) ? UploadKind.AUTHOR : UploadKind.COPY;
	}

	/**
	 * Whether a re-upload that stores no new version may authorize: at least one row is not a copy,
	 * every such row's holders resolve, at least one holder is reached through them, and the
	 * principal writes every one of those holders. Holders reached only through copies are not asked:
	 * they gate every update (step 4) whatever this answers. Never refuses.
	 */
	private boolean authorizesReupload(UUID org, List<Artifact> rows, SbomWriteAuthority authority) {
		return walkHolders(org, rows, authority, true).verdict() == WalkVerdict.AUTHORIZED;
	}

	private enum WalkVerdict {
		/** Every holder seen is writable, and at least one was reached through an authorizing row. */
		AUTHORIZED,
		/** A holder is not writable, or a row's holders do not resolve. */
		DENIED,
		/** Every holder seen is writable, but none was reached through an authorizing row. */
		NO_AUTHORIZING_HOLDER
	}

	private record HolderWalk(WalkVerdict verdict, int seen) {}

	/**
	 * The holders of {@code rows} (only the rows that are not copies when {@code authorizingOnly}),
	 * each component asked once, stopping at the first row that does not resolve or the first
	 * component the principal cannot write.
	 */
	private HolderWalk walkHolders(UUID org, List<Artifact> rows, SbomWriteAuthority authority,
			boolean authorizingOnly) {
		Set<UUID> seen = new LinkedHashSet<>();
		Set<UUID> authorizing = new HashSet<>();
		for (Artifact row : rows) {
			boolean copy = ArtifactData.dataFromRecord(row).isCopyRow();
			if (authorizingOnly && copy) {
				continue;
			}
			ArtifactHolders h = artifactAttachmentGuard.holdersOfArtifact(org, row.getUuid());
			if (h.unresolved()) {
				return new HolderWalk(WalkVerdict.DENIED, seen.size());
			}
			for (UUID component : h.components()) {
				if (seen.add(component) && !authority.canWrite(component)) {
					return new HolderWalk(WalkVerdict.DENIED, seen.size());
				}
			}
			if (!copy) {
				authorizing.addAll(h.components());
			}
		}
		return new HolderWalk(authorizing.isEmpty() ? WalkVerdict.NO_AUTHORIZING_HOLDER : WalkVerdict.AUTHORIZED,
				seen.size());
	}

	private static int versionOf(ArtifactData ad) {
		try {
			return null == ad.getVersion() ? 0 : Integer.parseInt(ad.getVersion().trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Logs a refusal (holder count and principal in the server log only) and returns its exception. */
	private static SbomUpdateNotPermittedException refusal(UUID org, UUID serialNumber, int uploadedVersion,
			int holders, SbomWriteAuthority authority, String serialAsSent) {
		log.warn("SBOM update refused: org={} serialNumber={} version={} holders={} principal={}", org, serialNumber,
				uploadedVersion, holders, authority.describe());
		return new SbomUpdateNotPermittedException(SbomUpdateNotPermittedException.messageFor(serialAsSent),
				serialAsSent, uploadedVersion);
	}

	/**
	 * Records a refused upload on the target release, in its own transaction so that it survives
	 * the rollback of the request: a GUARD CHANGED event whose objectId is the uploading
	 * component, newValue the serialNumber as sent and message the refusal; no holder is in any of
	 * them. Appended in place (TPF-71 / TPF-106): no revision and no audit row; the UPDATE takes the same
	 * row lock for the same short transaction as the save it replaced. Called only by resolvers whose release row is committed before the request and not
	 * written by their own transaction before the upload, or the write would wait on their lock.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordRefusal(UUID releaseUuid, UUID componentUuid, SbomUpdateNotPermittedException ex,
			WhoUpdated wu) {
		Optional<Release> or = sharedReleaseService.getRelease(releaseUuid);
		String org = or.map(r -> String.valueOf(ReleaseData.dataFromRecord(r).getOrg())).orElse("unknown");
		log.warn("SBOM update refusal recorded: org={} component={} release={} serialNumber={} version={} principal={}/{}/{}",
				org, componentUuid, releaseUuid, ex.getSerialNumber(), ex.getVersion(),
				null == wu ? null : wu.getCreatedType(), null == wu ? null : wu.getLastUpdatedBy(),
				null == wu ? null : wu.getActor());
		if (or.isEmpty()) {
			return;
		}
		Release r = or.get();
		ReleaseUpdateEvent note = new ReleaseUpdateEvent(ReleaseUpdateScope.GUARD, ReleaseUpdateAction.CHANGED, null,
				ex.getSerialNumber(), componentUuid, ex.getMessage(), ZonedDateTime.now(), wu);
		// In place (TPF-71 / TPF-106), and still never starting the trigger engine (the former
		// considerTriggers=false): a refusal note is not a change of the release.
		if (releaseRepository.appendUpdateEvents(r.getUuid(), ReleaseData.updateEventsJson(List.of(note))) == 0) {
			log.warn("Release {} vanished before its SBOM refusal note was appended", r.getUuid());
		}
		// The uniform form of TPF-106 4.4: re-read by uuid, refresh what the read returns.
		Release m = sharedReleaseService.getRelease(r.getUuid()).orElseThrow();
		entityManager.refresh(m);
	}
}
