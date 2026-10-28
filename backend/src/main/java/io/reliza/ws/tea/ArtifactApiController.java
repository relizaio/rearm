/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;

import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.LogSafe;
import io.reliza.model.ArtifactData;
import io.reliza.model.DownloadLogData.DownloadConfig;
import io.reliza.model.DownloadLogData.DownloadSubjectType;
import io.reliza.model.DownloadLogData.DownloadType;
import io.reliza.model.TeaDownloadObject;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.TeaArtifact;
import io.reliza.service.DownloadLogService;
import io.reliza.service.TeaAuthorizationService.TeaPrincipal;
import io.reliza.service.tea.TeaArtifactSurfaceService;
import io.reliza.service.tea.TeaArtifactSurfaceService.ArtifactRevision;
import io.reliza.service.tea.TeaArtifactSurfaceService.RawBytes;
import io.reliza.service.tea.TeaSurfaceService.Decision;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import lombok.extern.slf4j.Slf4j;

/**
 * The six TEA 1.0.0 artifact operations (task TEA-10): the metadata of a revision as the collection
 * froze it, and the stored bytes of the revision and of its detached signature with the strong ETag,
 * conditional GET and caching headers of the spec. Each step reveals more than the one before, so the
 * order is fixed: the base, the access decision, the format, the signature, then a 302 for bytes that
 * live outside ReARM (the signature's redirect included, so a caller that names no acceptable format
 * is told 406, never the link), or the validator and the bytes. A signature is a 302 to the format's
 * frozen signatureUrl first, then the published row: its live link only when the TEA-19 url rule admits
 * it, else SIGNATURE_NOT_FOUND for a row stored externally, else the hosted bytes (round 5 decisions 19, 20). A refusal the client earns with its
 * request alone (400, 401, 404, 406) writes nothing above DEBUG here. A location too long for a header
 * is 404 NOT_IMPLEMENTED, thrown by TeaArtifactResponses.redirectHeaders before any status is set
 * (round 4 decision 16).
 */
@Slf4j
@Controller
@RequestMapping(TeaPaths.ORG_BASE)
public class ArtifactApiController implements ArtifactApi {

	@Autowired private TeaRequests teaRequests;
	@Autowired private TeaArtifactSurfaceService teaArtifactSurfaceService;
	@Autowired private DownloadLogService downloadLogService;

	private final NativeWebRequest request;

	@Autowired
	public ArtifactApiController(NativeWebRequest request) {
		this.request = request;
	}

	@Override
	public Optional<NativeWebRequest> getRequest() {
		return Optional.ofNullable(request);
	}

	@Override
	public ResponseEntity<TeaArtifact> getLatestArtifact(UUID uuid) {
		return metadata(uuid, null);
	}

	@Override
	public ResponseEntity<TeaArtifact> getArtifactByVersion(UUID uuid, Integer artifactVersion) {
		return metadata(uuid, artifactVersion);
	}

	@Override
	public ResponseEntity<Resource> downloadLatestArtifact(UUID uuid, String requestedMediaType, String ifNoneMatch) {
		return download(uuid, null, requestedMediaType, ifNoneMatch, TeaDownloadObject.CONTENT);
	}

	@Override
	public ResponseEntity<Resource> downloadArtifactByVersion(UUID uuid, Integer artifactVersion, String requestedMediaType,
			String ifNoneMatch) {
		return download(uuid, artifactVersion, requestedMediaType, ifNoneMatch, TeaDownloadObject.CONTENT);
	}

	@Override
	public ResponseEntity<Resource> downloadLatestArtifactSignature(UUID uuid, String requestedMediaType, String ifNoneMatch) {
		return download(uuid, null, requestedMediaType, ifNoneMatch, TeaDownloadObject.SIGNATURE);
	}

	@Override
	public ResponseEntity<Resource> downloadArtifactSignatureByVersion(UUID uuid, Integer artifactVersion,
			String requestedMediaType, String ifNoneMatch) {
		return download(uuid, artifactVersion, requestedMediaType, ifNoneMatch, TeaDownloadObject.SIGNATURE);
	}

	// ------------------------------------------------------------------ the steps

	private ArtifactRevision resolve(TeaRequestContext ctx, UUID uuid, Integer version) {
		Decision<ArtifactRevision> d = null == version
				? teaArtifactSurfaceService.latest(ctx.caller(), ctx.org(), uuid)
				: teaArtifactSurfaceService.revision(ctx.caller(), ctx.org(), uuid, version);
		return TeaRendering.served(d);
	}

	/** The frozen artifact object of the collection document, written as stored; the TEA-6 JSON headers, no ETag. */
	private ResponseEntity<TeaArtifact> metadata(UUID uuid, Integer version) {
		TeaRequestContext ctx = teaRequests.context(request);
		ArtifactRevision r = resolve(ctx, uuid, version);
		log.debug("TEA artifact metadata of organization {}: artifact {} revision {}, caller {}, publication {}",
				ctx.org().org(), r.teaUuid(), r.revision(), ctx.caller().state(), r.publication().getUuid());
		TeaRawJson.write(request, HttpStatus.OK.value(), TeaResponses.headersFor(ctx),
				TeaJson.MAPPER.writeValueAsString(r.artifactObject()));
		return null;
	}

	private ResponseEntity<Resource> download(UUID uuid, Integer version, String requestedMediaType, String ifNoneMatch,
			TeaDownloadObject kind) {
		TeaRequestContext ctx = teaRequests.context(request);
		boolean latest = null == version;
		ArtifactRevision r = resolve(ctx, uuid, version);
		TeaArtifactResponses.FormatChoice choice = TeaArtifactResponses.chooseFormat(r, requestedMediaType,
				request.getHeader(HttpHeaders.ACCEPT), kind);
		String mediaType = choice.mediaType();
		boolean byAccept = choice.byAccept();
		ArtifactData signature = null;
		Optional<String> stored;
		if (TeaDownloadObject.SIGNATURE == kind) {
			// a signature published by url: the frozen signatureUrl (TEA-20), never the row (round 5 decision 19)
			Optional<String> sigUrl = teaArtifactSurfaceService.externalSignatureUrl(r, mediaType);
			if (sigUrl.isPresent()) return redirect(ctx, r, kind, latest, byAccept, sigUrl.get());
			signature = teaArtifactSurfaceService.signatureRow(r).orElseThrow(TeaRefusal::signatureNotFound);
			// an entry frozen before TEA-20: the row's live link, only when the url rule admits it (decision 20)
			Optional<String> location = TeaArtifactSurfaceService.admittedExternalLocation(signature);
			if (location.isPresent()) return redirect(ctx, r, kind, latest, byAccept, location.get());
			if (teaArtifactSurfaceService.isLinklessExternalSignature(r, signature)) throw TeaRefusal.signatureNotFound();
			stored = TeaArtifactSurfaceService.storedSignatureSha256(r, signature);
		} else {
			Optional<String> url = teaArtifactSurfaceService.externalUrl(r, mediaType);
			if (url.isPresent()) return redirect(ctx, r, kind, latest, byAccept, url.get());
			stored = teaArtifactSurfaceService.storedContentSha256(r, mediaType);
		}
		// with a stored digest the validator is known before the bytes are read
		if (stored.isPresent() && notModified(ctx, r, mediaType, stored.get(), kind, latest, byAccept, ifNoneMatch)) return null;
		RawBytes bytes = TeaDownloadObject.SIGNATURE == kind ? teaArtifactSurfaceService.signature(r, signature)
				: teaArtifactSurfaceService.content(r, mediaType);
		String sha256 = stored.orElse(bytes.sha256());
		if (stored.isEmpty() && notModified(ctx, r, mediaType, sha256, kind, latest, byAccept, ifNoneMatch)) return null;
		HttpHeaders headers = TeaArtifactResponses.downloadHeaders(ctx, r, mediaType, sha256, kind, latest, byAccept,
				bytes.contentType(), bytes.fileName(), bytes.bytes().length);
		TeaRawBytes.write(request, headers, bytes.bytes());
		boolean head = null != ctx.raw() && "HEAD".equals(ctx.raw().getMethod());
		log.debug("TEA artifact {} of organization {}: artifact {} revision {}, caller {}, publication {}, {}",
				kind, ctx.org().org(), r.teaUuid(), r.revision(), ctx.caller().state(), r.publication().getUuid(),
				head ? "HEAD" : "served");
		if (!head) logDownload(ctx, r, mediaType, kind, TeaDownloadObject.SIGNATURE == kind ? signature : null);
		return null;
	}

	/**
	 * Bytes that live outside ReARM (decision 11): a 302 to where they are, after the access decision
	 * and the format; no validator, If-None-Match not evaluated, no log row (decision 12), no ERROR.
	 */
	private ResponseEntity<Resource> redirect(TeaRequestContext ctx, ArtifactRevision r, TeaDownloadObject kind, boolean latest,
			boolean byAccept, String location) {
		TeaRawBytes.redirect(request, TeaArtifactResponses.redirectHeaders(ctx, r, latest, location, byAccept));
		log.debug("TEA artifact {} of organization {}: artifact {} revision {}, caller {}, publication {}, redirected",
				kind, ctx.org().org(), r.teaUuid(), r.revision(), ctx.caller().state(), r.publication().getUuid());
		return null;
	}

	private boolean notModified(TeaRequestContext ctx, ArtifactRevision r, String mediaType, String sha256, TeaDownloadObject kind,
			boolean latest, boolean byAccept, String ifNoneMatch) {
		HttpHeaders validators = TeaArtifactResponses.validatorHeaders(ctx, r, mediaType, sha256, kind, latest, byAccept);
		if (!TeaResponses.ifNoneMatchMatches(ifNoneMatch, validators.getETag())) return false;
		TeaRawJson.notModified(request, validators);
		return true;
	}

	/**
	 * One TEA_DOWNLOAD row per served download 200: subject the published artifact row, keyed by an
	 * EXTERNAL key of this organization, else anonymous (decision 13: a key of another organization too).
	 */
	private void logDownload(TeaRequestContext ctx, ArtifactRevision r, String mediaType, TeaDownloadObject kind, ArtifactData signature) {
		TeaPrincipal principal = ctx.ownPrincipal().orElse(null);
		String address = TeaRequests.clientAddress(ctx.raw());
		WhoUpdated wu = null != principal ? WhoUpdated.getApiWhoUpdated(principal.keyUuid(), address)
				: WhoUpdated.getWhoUpdated(ProgrammaticType.API, null, address);
		DownloadConfig config = DownloadConfig.builder()
				.teaKeyId(null == principal ? null : principal.keyId())
				.teaPublication(r.publication().getUuid())
				.teaCollectionVersion(r.collectionVersion())
				.teaArtifactUuid(r.teaUuid())
				.teaArtifactVersion(r.revision())
				.teaObject(kind)
				.mediaType(mediaType)
				.artifactUuid(null == signature ? r.entry().artifact() : signature.getUuid())
				.artifactVersion(null == signature ? Integer.valueOf(r.revision()) : parseVersion(signature.getVersion()))
				.build();
		downloadLogService.createDownloadLog(ctx.org().org(), DownloadType.TEA_DOWNLOAD, DownloadSubjectType.ARTIFACT,
				r.entry().artifact(), wu, config);
	}

	private static Integer parseVersion(String version) {
		if (null == version) return null;
		try {
			return Integer.valueOf(version.strip());
		} catch (NumberFormatException notAnInteger) {
			// round 5 decision 22: the stored version through LogSafe, no exception object
			log.error("TEA download log: signature version {} is not an integer", LogSafe.text(version));
			return null;
		}
	}
}
