/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.LogSafe;
import io.reliza.common.Utils;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.Link;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.service.ArtifactService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.TeaAuthorizationService.TeaAccess;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaPublicationService;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.tea.TeaSurfaceService.Access;
import io.reliza.service.tea.TeaSurfaceService.Decision;
import io.reliza.service.tea.TeaSurfaceService.TeaOrg;
import io.reliza.ws.tea.TeaArtifactResponses;
import io.reliza.ws.tea.TeaJson;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The TEA artifact endpoints' read model (task TEA-10): a TEA artifact id and revision are what the
 * stored collection rows name, decided per publication by TEA-6's rule; the metadata is the frozen
 * object of the document, never the live row; the bytes are the raw stored bytes of that revision.
 */
@Slf4j
@Service
public class TeaArtifactSurfaceService {

	/** One collection row naming the id, with the entry that names it. */
	public record Candidate(TeaPublishedCollectionData collection, TeaPublishedEntry entry) {}

	/**
	 * The revision a caller is served: the first served row naming it fixes the publication, the
	 * collection version, the entry and the frozen object; {@code publiclyAccessible} when any served
	 * publication naming that revision is PUBLIC.
	 */
	public record ArtifactRevision(UUID teaUuid, int revision, TeaPublicationData publication, int collectionVersion,
			TeaPublishedEntry entry, ObjectNode artifactObject, boolean publiclyAccessible) {}

	/** Bytes as stored, their sha256, the content type and the file name to serve them under. */
	public record RawBytes(byte[] bytes, String sha256, String contentType, String fileName) {}

	@Autowired private TeaPublicationService teaPublicationService;
	@Autowired private TeaSurfaceService teaSurfaceService;
	@Autowired private ArtifactService artifactService;
	@Autowired private SharedArtifactService sharedArtifactService;

	// ------------------------------------------------------------------ resolution

	/** The greatest revision of the id among the publications that serve this caller. */
	public Decision<ArtifactRevision> latest(TeaCaller caller, TeaOrg org, UUID teaUuid) {
		return resolve(caller, org, teaUuid, null);
	}

	/** That revision of the id, when a publication naming it serves this caller. */
	public Decision<ArtifactRevision> revision(TeaCaller caller, TeaOrg org, UUID teaUuid, int revision) {
		return resolve(caller, org, teaUuid, revision);
	}

	private Decision<ArtifactRevision> resolve(TeaCaller caller, TeaOrg org, UUID teaUuid, Integer wanted) {
		if (null == org || null == teaUuid || (null != wanted && wanted < 1)) return Decision.refuse(TeaAccess.CONCEAL);
		List<Candidate> candidates = new ArrayList<>();
		for (TeaPublishedCollectionData row : teaPublicationService.listCollectionsNamingArtifact(org.org(), teaUuid)) {
			if (null == row.getEntries()) continue;
			for (TeaPublishedEntry e : row.getEntries()) {
				if (null != e && teaUuid.equals(e.teaUuid()) && (null == wanted || e.revision() == wanted)) {
					candidates.add(new Candidate(row, e));
				}
			}
		}
		if (candidates.isEmpty()) return Decision.refuse(TeaAccess.CONCEAL);

		Map<UUID, Access> accesses = new LinkedHashMap<>();
		Map<UUID, TeaPublicationData> publications = new LinkedHashMap<>();
		for (Candidate c : candidates) {
			UUID p = c.collection().getUuid();
			if (accesses.containsKey(p)) continue;
			Optional<TeaPublicationData> pd = teaPublicationService.getPublication(p);
			if (pd.isEmpty()) {
				accesses.put(p, new Access(TeaAccess.CONCEAL, null));
				continue;
			}
			publications.put(p, pd.get());
			accesses.put(p, teaSurfaceService.evaluate(caller, org, pd.get()));
		}
		List<Candidate> served = candidates.stream()
				.filter(c -> TeaAccess.SERVE == accesses.get(c.collection().getUuid()).access()).toList();
		if (served.isEmpty()) {
			return Decision.refuse(TeaSurfaceService.strongest(accesses.values().stream().map(Access::access).toList()));
		}
		int revision = null != wanted ? wanted : served.stream().mapToInt(c -> c.entry().revision()).max().getAsInt();
		List<Candidate> atRevision = served.stream().filter(c -> c.entry().revision() == revision).toList();
		Candidate first = atRevision.get(0);
		boolean publiclyAccessible = atRevision.stream().map(c -> accesses.get(c.collection().getUuid()).exposure())
				.anyMatch(e -> null != e && TeaExposureState.PUBLIC == e.state());
		UUID publication = first.collection().getUuid();
		Optional<ObjectNode> object = artifactObjectOf(first.collection(), teaUuid, revision);
		if (object.isEmpty()) {
			log.error("TEA collection {} version {} names artifact {} revision {} in its entries but not in its document; "
					+ "answered as unknown", publication, first.collection().getVersion(), teaUuid, revision);
			return Decision.refuse(TeaAccess.CONCEAL);
		}
		log.debug("TEA artifact {} revision {} of organization {}: served from publication {} version {} to a caller {}",
				teaUuid, revision, org.org(), publication, first.collection().getVersion(),
				null == caller ? null : caller.state());
		return Decision.serve(new ArtifactRevision(teaUuid, revision, publications.get(publication),
				first.collection().getVersion(), first.entry(), object.get(), publiclyAccessible));
	}

	/** The element of the stored document's {@code artifacts} with this uuid and version, parsed (field order kept). */
	static Optional<ObjectNode> artifactObjectOf(TeaPublishedCollectionData row, UUID teaUuid, int revision) {
		if (null == row.getDocument()) return Optional.empty();
		JsonNode artifacts = TeaJson.MAPPER.readTree(row.getDocument()).get("artifacts");
		if (null == artifacts || !artifacts.isArray()) return Optional.empty();
		for (JsonNode a : artifacts) {
			JsonNode uuid = a.get("uuid");
			JsonNode version = a.get("version");
			if (a instanceof ObjectNode o && null != uuid && null != version && version.isIntegralNumber()
					&& teaUuid.toString().equals(uuid.asString()) && version.asInt() == revision) {
				return Optional.of(o);
			}
		}
		return Optional.empty();
	}

	// ------------------------------------------------------------------ content

	/**
	 * The SHA-256 checksum the frozen object publishes for the format with this stored media type, lowercase,
	 * when it is 64 hexadecimal digits (design round 6 decision 23); otherwise empty with one ERROR, and the
	 * download serves the bytes' own digest.
	 */
	public Optional<String> storedContentSha256(ArtifactRevision r, String mediaType) {
		JsonNode formats = r.artifactObject().get("formats");
		if (null == formats || !formats.isArray() || null == mediaType) return Optional.empty();
		for (JsonNode f : formats) {
			JsonNode mt = f.get("mediaType");
			if (null == mt || !mediaType.equals(mt.asString())) continue;
			JsonNode checksums = f.get("checksums");
			if (null == checksums || !checksums.isArray()) return Optional.empty();
			for (JsonNode c : checksums) {
				JsonNode alg = c.get("algType");
				JsonNode value = c.get("algValue");
				if (TeaChecksumType.SHA_256 == checksumTypeOf(alg) && null != value && StringUtils.isNotBlank(value.asString())) {
					return wellFormedSha256(r, "content", value.asString());
				}
			}
			return Optional.empty();
		}
		return Optional.empty();
	}

	/** The TEA checksum type a frozen checksum names; null when it is missing or not a TEA 1.0.0 type. */
	static TeaChecksumType checksumTypeOf(JsonNode alg) {
		if (null == alg || !alg.isString()) return null;
		String s = alg.asString();
		for (TeaChecksumType t : TeaChecksumType.values()) {
			if (t.getValue().equals(s)) return t;
		}
		return null;
	}

	/**
	 * The url the frozen object publishes for the format with this stored media type, trimmed, when
	 * it is not blank: the bytes of that format live outside ReARM (a row stored EXTERNALLY, task
	 * TEA-10 decision 11). Read from the published revision, never from the live row.
	 */
	public Optional<String> externalUrl(ArtifactRevision r, String mediaType) {
		JsonNode formats = r.artifactObject().get("formats");
		if (null == formats || !formats.isArray() || null == mediaType) return Optional.empty();
		for (JsonNode f : formats) {
			JsonNode mt = f.get("mediaType");
			if (null == mt || !mediaType.equals(mt.asString())) continue;
			JsonNode url = f.get("url");
			if (null == url || url.isNull() || StringUtils.isBlank(url.asString())) return Optional.empty();
			return Optional.of(url.asString().strip());
		}
		return Optional.empty();
	}

	/**
	 * The {@code signatureUrl} the frozen object publishes for the format with this stored media type,
	 * stripped, when it is not blank: the signature was published by url (task TEA-20, design round 5
	 * decision 19). Read from the published revision, never from the live row.
	 */
	public Optional<String> externalSignatureUrl(ArtifactRevision r, String mediaType) {
		JsonNode formats = r.artifactObject().get("formats");
		if (null == formats || !formats.isArray() || null == mediaType) return Optional.empty();
		for (JsonNode f : formats) {
			JsonNode mt = f.get("mediaType");
			if (null == mt || !mediaType.equals(mt.asString())) continue;
			JsonNode url = f.get("signatureUrl");
			if (null == url || url.isNull() || StringUtils.isBlank(url.asString())) return Optional.empty();
			return Optional.of(url.asString().strip());
		}
		return Optional.empty();
	}

	/** The stored raw bytes of the revision: the row when its version is the revision, else that version's snapshot. */
	public RawBytes content(ArtifactRevision r, String mediaType) {
		UUID row = r.entry().artifact();
		Optional<ArtifactData> ad = artifactService.getArtifactDataByVersion(row, r.revision());
		if (ad.isEmpty()) {
			log.error("TEA artifact {} revision {} (artifact row {}, publication {}): the row or its version is gone",
					r.teaUuid(), r.revision(), row, publicationOf(r));
			throw new IllegalStateException("the stored bytes of TEA artifact " + r.teaUuid() + " revision " + r.revision()
					+ " are not available");
		}
		byte[] bytes = fetch(r, ad.get(), "content");
		// decision 24: the rendering of the parsed media type, never the stored string
		String contentType = TeaArtifactResponses.mediaTypeOf(mediaType).map(MediaType::toString)
				.orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
		return new RawBytes(bytes, Utils.bytesToHexSha256(bytes), contentType, contentFileName(r));
	}

	/**
	 * The frozen object's name, else {@code <uuid>-<revision>}. A generated revision whose frozen name is
	 * the bare SBOM profile name it was shaped by (task TEA-28, D10) is served as {@code <name>.cdx.json};
	 * every other case, the legacy aggregate's file-style name included, is the name as frozen.
	 */
	public static String contentFileName(ArtifactRevision r) {
		JsonNode name = r.artifactObject().get("name");
		String n = null == name ? null : name.asString();
		if (StringUtils.isBlank(n)) return r.teaUuid() + "-" + r.revision();
		TeaPublishedEntry e = r.entry();
		if (null != e && TeaArtifactOrigin.SYNTHETIC == e.origin() && n.equals(e.sbomProfileName())) {
			return n + ".cdx.json";
		}
		return n;
	}

	// ------------------------------------------------------------------ signatures

	/** The signature published alongside the revision: the row or snapshot at the recorded revision; empty when none. */
	public Optional<ArtifactData> signatureRow(ArtifactRevision r) {
		UUID signature = r.entry().signatureArtifact();
		if (null == signature) return Optional.empty();
		Optional<ArtifactData> ad = artifactService.getArtifactDataByVersion(signature, r.entry().signatureRevision());
		UUID org = null == r.publication() ? null : r.publication().getOrg();
		if (ad.isEmpty() || null == org || !org.equals(ad.get().getOrg())) {
			log.error("TEA artifact {} revision {} (publication {}): the signature row {} revision {} it names is {}",
					r.teaUuid(), r.revision(), publicationOf(r), signature, r.entry().signatureRevision(),
					ad.isEmpty() ? "gone" : "of another organization");
			return Optional.empty();
		}
		return ad;
	}

	/**
	 * Where a signature row stored EXTERNALLY lives: the uri of its first download link with a non-blank
	 * uri, as the engine reads a ride-along's link; empty for a row stored in ReARM and for an EXTERNALLY
	 * stored row without a link.
	 */
	public static Optional<String> externalLocation(ArtifactData row) {
		if (null == row || StoredIn.EXTERNALLY != row.getStoredIn() || null == row.getDownloadLinks()) return Optional.empty();
		return row.getDownloadLinks().stream().filter(l -> null != l && StringUtils.isNotBlank(l.getUri()))
				.map(Link::getUri).findFirst();
	}

	/**
	 * The live link of a signature row stored EXTERNALLY, when it passes the TEA-19 url rule
	 * ({@link TeaExternalUrl#problemOf}): only an entry frozen before TEA-20 reaches it, and its link is
	 * served under the same rule as every other url of the surface (design round 5 decision 20).
	 */
	public static Optional<String> admittedExternalLocation(ArtifactData row) {
		return externalLocation(row).filter(l -> TeaExternalUrl.problemOf(l).isEmpty());
	}

	/**
	 * True, with one WARN line, for a signature row stored EXTERNALLY without an admitted link (none, or one
	 * the url rule refuses): it answers SIGNATURE_NOT_FOUND and no byte read is tried. The line names the
	 * rule's problem, never the link.
	 */
	public boolean isLinklessExternalSignature(ArtifactRevision r, ArtifactData row) {
		if (null == row || StoredIn.EXTERNALLY != row.getStoredIn() || admittedExternalLocation(row).isPresent()) return false;
		TeaExternalUrl.Problem problem = TeaExternalUrl.problemOf(externalLocation(row).orElse(null))
				.orElse(TeaExternalUrl.Problem.EMPTY);
		log.warn("TEA artifact {} revision {} (publication {}): the signature row {} version {} is stored externally "
				+ "without an admitted download link ({}); answered as no signature", r.teaUuid(), r.revision(),
				publicationOf(r), row.getUuid(), LogSafe.text(row.getVersion()), problem);
		return true;
	}

	/**
	 * The signature's stored SHA-256 (design round 6 decision 23): the retained upload's when it is 64
	 * hexadecimal digits, else the declared original file's when it is; a record that is not writes one ERROR
	 * naming its scope, and when neither is usable the answer is empty and the bytes' own digest is served.
	 */
	public static Optional<String> storedSignatureSha256(ArtifactRevision r, ArtifactData signature) {
		Optional<String> asUploaded = sha256Of(signature, DigestScope.AS_UPLOADED)
				.flatMap(v -> wellFormedSha256(r, "signature " + DigestScope.AS_UPLOADED.name(), v));
		if (asUploaded.isPresent()) return asUploaded;
		return sha256Of(signature, DigestScope.ORIGINAL_FILE)
				.flatMap(v -> wellFormedSha256(r, "signature " + DigestScope.ORIGINAL_FILE.name(), v));
	}

	private static Optional<String> sha256Of(ArtifactData ad, DigestScope scope) {
		if (null == ad.getDigestRecords()) return Optional.empty();
		return ad.getDigestRecords().stream()
				.filter(d -> null != d && scope == d.scope() && DigestAlgorithm.SHA_256 == d.algo() && StringUtils.isNotBlank(d.digest()))
				.map(DigestRecord::digest).findFirst();
	}

	private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

	/**
	 * A stored SHA-256 is a validator only when, lower-cased, it is 64 hexadecimal digits (decision 23);
	 * otherwise empty with one ERROR naming the TEA id, the revision, the scope and the value's length,
	 * never the value.
	 */
	private static Optional<String> wellFormedSha256(ArtifactRevision r, String scope, String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if (SHA_256_HEX.matcher(lower).matches()) return Optional.of(lower);
		log.error("TEA artifact {} revision {} (publication {}): the stored {} SHA-256 of {} characters is not 64 "
				+ "hexadecimal digits; the bytes' own digest is served", r.teaUuid(), r.revision(), publicationOf(r),
				scope, value.length());
		return Optional.empty();
	}

	/** The signature's stored bytes, served as its media type tag (else octet-stream) under its file name tag. */
	public RawBytes signature(ArtifactRevision r, ArtifactData signature) {
		byte[] bytes = fetch(r, signature, "signature");
		String contentType = tagOf(signature, CommonVariables.MEDIA_TYPE_FIELD).flatMap(TeaArtifactSurfaceService::concreteMediaType)
				.map(MediaType::toString).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
		String fileName = tagOf(signature, CommonVariables.FILE_NAME_FIELD).orElse(contentFileName(r) + ".sig");
		return new RawBytes(bytes, Utils.bytesToHexSha256(bytes), contentType, fileName);
	}

	private static Optional<String> tagOf(ArtifactData ad, String key) {
		if (null == ad.getTags()) return Optional.empty();
		return ad.getTags().stream().filter(t -> null != t && key.equals(t.key())).map(TagRecord::value)
				.filter(StringUtils::isNotBlank).findFirst();
	}

	/**
	 * The signature's media type tag parsed, when it is a concrete media type whose rendering is a header
	 * value (design round 6 decision 24, through {@link TeaArtifactResponses#mediaTypeOf}); a tag that is not
	 * one writes one ERROR (the tag through {@link LogSafe#text}, no exception object, decision 22), a
	 * wildcard tag writes nothing.
	 */
	private static Optional<MediaType> concreteMediaType(String tag) {
		if (tag.length() > TeaArtifactResponses.MAX_MEDIA_TYPE_CHARS) {
			// round 4 decision 16c: too long to be a media type; the tag itself is not logged
			log.error("TEA signature media type tag of {} characters is not a media type; served as octet-stream", tag.length());
			return Optional.empty();
		}
		Optional<MediaType> parsed = TeaArtifactResponses.mediaTypeOf(tag);
		if (parsed.isEmpty()) {
			log.error("TEA signature media type tag {} is not a media type; served as octet-stream", LogSafe.text(tag));
			return Optional.empty();
		}
		return parsed.filter(MediaType::isConcrete);
	}

	// ------------------------------------------------------------------ the one byte-stable read

	private byte[] fetch(ArtifactRevision r, ArtifactData ad, String what) {
		try {
			ResponseEntity<byte[]> response = sharedArtifactService.downloadRawArtifact(ad).block();
			if (null == response || null == response.getBody()) {
				throw new IllegalStateException("the raw download answered no body");
			}
			return response.getBody();
		} catch (Exception e) {
			log.error("TEA artifact {} revision {} (artifact row {} version {}, publication {}): fetching the stored {} bytes failed",
					r.teaUuid(), r.revision(), ad.getUuid(), LogSafe.text(ad.getVersion()), publicationOf(r), what, e);
			throw new IllegalStateException("the stored " + what + " bytes of TEA artifact " + r.teaUuid() + " revision "
					+ r.revision() + " could not be read", e);
		}
	}

	private static UUID publicationOf(ArtifactRevision r) {
		return null == r.publication() ? null : r.publication().getUuid();
	}
}
