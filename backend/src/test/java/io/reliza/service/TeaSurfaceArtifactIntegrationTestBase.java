/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.SyncTaskExecutor;

import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.DownloadLogData;
import io.reliza.model.DownloadLogData.DownloadType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.DownloadLogRepository;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublishMode;

/**
 * Task TEA-10, design 4.3: the artifact world over real HTTP. Every object of the world carries an
 * inventory BOM with retained bytes, a LICENSE file {@code lic} with a detached signature, a LICENSE
 * file {@code nosig} without one, and a file {@code s512} that declares SHA-512 only; its aggregate is
 * the engine's. {@code pub} is PUBLIC, {@code priv} PRIVATE, {@code hid} PUBLIC then hidden,
 * {@code casc} a PRIVATE cascade child of the PUBLIC product {@code prod}; another organization
 * publishes one release; {@code cover} holds TEA_READ on priv's component and on prod's.
 * Round 2: {@code pub} and {@code priv} also carry {@code ext}, release notes stored EXTERNALLY with a
 * download link and a declared SHA-256 (published with the link as the format's url), and pub's
 * {@code ext} an EXTERNALLY stored signature {@code extsig}; {@code far} is a key of the foreign
 * organization with an organization-wide TEA_READ grant there.
 * Round 3: {@code pub} also carries {@code extIri} (an external url with non-ASCII characters, its
 * signature stored EXTERNALLY at a non-ASCII link too) and {@code extCtl} (an external url with CR and LF).
 * Round 4: {@code pub} also carries {@code extLong} (an ASCII url of exactly 4096 characters, its external
 * signature's link 4100), {@code extOver} (4097, signature 4101), {@code extIriOver} (an IRI over 4096
 * octets as a URI, with a signature) and {@code longName} (hosted, named with 304 characters).
 * Round 5 (the merge onto TEA-19 and TEA-20, decisions 18 and 19): the engine now refuses those urls and
 * signature links at publish, so the rows are seeded at a conforming placeholder and their entries frozen
 * past the engine with {@link #freezeUrl} and {@link #freezeSignature} (entries frozen before the rules);
 * {@code extPre} is a conforming row whose entry is frozen without its signatureUrl (its signature is the
 * row's live link), {@code extNoSig} a conforming row whose signature link the engine skipped.
 */
abstract class TeaSurfaceArtifactIntegrationTestBase extends TeaSurfaceArtifactRowsTestBase {

	@Autowired DownloadLogRepository downloadLogRepository;
	@Autowired ApplicationContext applicationContext;

	/** One published TEA artifact: its row, its TEA id, its revision, its format's media type, its bytes. */
	record Art(UUID row, UUID tea, int revision, String mediaType, byte[] bytes) {
		String sha256() {
			return Utils.bytesToHexSha256(bytes);
		}
	}

	/**
	 * One object of the world: its component release and its five artifacts, plus the signature of lic;
	 * on pub and priv also {@code ext} (its bytes are the ones whose SHA-256 is declared, never retained),
	 * its url, and on pub its externally stored signature row and that row's url (null elsewhere); on pub
	 * also {@code extIri} with its url and its signature's url, and {@code extCtl} with its url (round 3).
	 */
	record ArtObj(UUID release, UUID component, UUID head, Art agg, Art bom, Art lic, Art nosig, Art s512, UUID sigRow,
			byte[] sigBytes, Art ext, String extUrl, UUID extSigRow, String extSigUrl, Art extIri, String extIriUrl,
			String extIriSigUrl, Art extCtl, String extCtlUrl, Art extLong, String extLongUrl, Art extOver, Art extIriOver,
			String extIriOverUrl, Art longName, String longNameFile, UUID extIriSigRow, UUID extLongSigRow, UUID extOverSigRow,
			UUID extIriOverSigRow, Art extPre, String extPreUrl, UUID extPreSigRow, String extPreSigUrl, Art extNoSig,
			String extNoSigUrl) {}

	record ArtWorld(String teaOrg, String base, String apiBase, ArtObj pub, ArtObj priv, ArtObj hid, ArtObj casc,
			UUID prodRelease, UUID prodComponent, String foreignBase, Art foreignAgg, UUID coverKey, String coverKeyId,
			String cover, String other, UUID foreignOrg, UUID farKey, String farKeyId, String far) {}

	// ---------------------------------------------------------------- the world

	private record Seeded(UUID release, UUID component, UUID bom, UUID lic, UUID nosig, UUID s512, UUID sig, byte[] sigBytes,
			Map<UUID, byte[]> bytes, UUID ext, String extUrl, UUID extSig, String extSigUrl, UUID extIri, String extIriUrl,
			String extIriSigUrl, UUID extCtl, String extCtlUrl, Bounded bounded, UUID extIriSig, PreRule preRule) {}

	/** Round 4 (decision 16): the rows whose stored url or name a header must bound; on pub only. */
	private record Bounded(UUID extLong, String extLongUrl, UUID extOver, String extOverUrl, UUID extIriOver, String extIriOverUrl,
			UUID longName, String longNameFile, UUID extLongSig, UUID extOverSig, UUID extIriOverSig) {}

	/** Round 5 (rows 35 and 36): a pre-rule entry whose live signature link is admitted, and a skipped signature; on pub only. */
	private record PreRule(UUID extPre, String extPreUrl, UUID extPreSig, String extPreSigUrl, UUID extNoSig, String extNoSigUrl) {}

	/** An EXTERNALLY stored release notes row and its EXTERNALLY stored signature child. */
	record Signed(UUID row, UUID sig) {}

	/** An EXTERNALLY stored release notes row at {@code url} with an EXTERNALLY stored signature child at {@code sigUrl}. */
	private Signed externalWithSignature(Organization o, String name, String url, String sigUrl, String sha) throws Exception {
		UUID row = seedExternal(o, name, url, ArtifactType.RELEASE_NOTES, EXT_MEDIA_TYPE, sha);
		UUID sig = seedExternal(o, name + ".sig", sigUrl, ArtifactType.SIGNATURE, SIG_MEDIA_TYPE, sha.replace('a', 'b'));
		attachChild(row, sig);
		return new Signed(row, sig);
	}

	/**
	 * Round 5 decision 18: a url the TEA-19 rule admits, standing in for {@code url} at publish: every code point
	 * outside U+0021 to U+007E written as {@code x}, cut to 4096 characters; the entry is frozen at {@code url} after.
	 */
	static String placeholder(String url) {
		StringBuilder b = new StringBuilder();
		url.codePoints().forEach(cp -> b.append(cp > 0x20 && cp < 0x7f ? (char) cp : 'x'));
		return b.length() > 4096 ? b.substring(0, 4096) : b.toString();
	}

	private Bounded bounded(Organization o, String slug, Map<UUID, byte[]> bytes, List<UUID> inventory) throws Exception {
		String prefix = "https://files.example/" + slug + "/";
		String longUrl = prefix + "a".repeat(4096 - prefix.length());
		Signed extLong = externalWithSignature(o, slug + "-long.md", longUrl, longUrl + ".sig", "1a".repeat(32));
		String overUrl = prefix + "a".repeat(4097 - prefix.length());
		Signed extOver = externalWithSignature(o, slug + "-over.md", placeholder(overUrl), overUrl + ".sig", "2a".repeat(32));
		String iriOverUrl = prefix + "\u00e9".repeat(700);
		Signed extIriOver = externalWithSignature(o, slug + "-iri-over.md", placeholder(iriOverUrl), iriOverUrl + ".sig", "3a".repeat(32));
		String longNameFile = "n".repeat(300) + ".txt";
		byte[] longNameBytes = ("long name of " + slug).getBytes(StandardCharsets.UTF_8);
		UUID longName = seedFile(o, longNameFile, longNameBytes, ArtifactType.LICENSE, LIC_MEDIA_TYPE, Set.of(DigestAlgorithm.SHA_256));
		bytes.put(longName, longNameBytes);
		inventory.addAll(List.of(extLong.row(), extOver.row(), extIriOver.row(), longName));
		return new Bounded(extLong.row(), longUrl, extOver.row(), overUrl, extIriOver.row(), iriOverUrl, longName, longNameFile,
				extLong.sig(), extOver.sig(), extIriOver.sig());
	}

	private PreRule preRule(Organization o, String slug, List<UUID> inventory) throws Exception {
		String preUrl = "https://files.example/" + slug + "/pre.md";
		Signed pre = externalWithSignature(o, slug + "-pre.md", preUrl, preUrl + ".sig", "4a".repeat(32));
		String noSigUrl = "https://files.example/" + slug + "/nosig.md";
		String longSig = noSigUrl + "/" + "s".repeat(4100 - noSigUrl.length() - 1);
		Signed noSig = externalWithSignature(o, slug + "-nosig.md", noSigUrl, longSig, "5a".repeat(32));
		inventory.addAll(List.of(pre.row(), noSig.row()));
		return new PreRule(pre.row(), preUrl, pre.sig(), preUrl + ".sig", noSig.row(), noSigUrl);
	}

	/**
	 * An ASSEMBLED component release of a new component of {@code o} carrying the four inventory
	 * artifacts, plus {@code ext} when {@code external} (and its external signature when {@code extSigned}).
	 */
	private Seeded seededRelease(Organization o, String label, boolean external, boolean extSigned) throws Exception {
		String slug = "tea-10-" + label + "-" + tag();
		UUID component = componentService.createComponent(CreateComponentDto.builder()
				.organization(o.getUuid()).name(slug).type(ComponentType.COMPONENT)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(), WU).getUuid();
		Branch branch = branchService.findBranchByName(component, "main", true, WU).get();
		String version = "1.0." + Math.abs(UUID.randomUUID().hashCode() % 100000);
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component)
				.branch(branch.getUuid()).org(o.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).version(version).build(), WU).getUuid();
		releasesByVersion.put(version, release);
		Map<UUID, byte[]> bytes = new java.util.HashMap<>();
		byte[] bomBytes = ("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"serialNumber\":\"urn:uuid:" + UUID.randomUUID()
				+ "\",\"version\":1,\"components\":[]}").getBytes(StandardCharsets.UTF_8);
		UUID bom = seedBomWithBytes(o, slug + ".cdx.json", bomBytes);
		bytes.put(bom, bomBytes);
		byte[] licBytes = ("Apache License 2.0 for " + slug + "\n").getBytes(StandardCharsets.UTF_8);
		UUID lic = seedFile(o, slug + "-LICENSE.txt", licBytes, ArtifactType.LICENSE, LIC_MEDIA_TYPE,
				Set.of(DigestAlgorithm.SHA_256, DigestAlgorithm.SHA_512));
		bytes.put(lic, licBytes);
		byte[] sigBytes = ("-----BEGIN SIGNATURE----- " + slug + " -----END SIGNATURE-----").getBytes(StandardCharsets.UTF_8);
		UUID sig = seedSignature(o, lic, sigBytes);
		byte[] nosigBytes = ("# Notice for " + slug + "\n").getBytes(StandardCharsets.UTF_8);
		UUID nosig = seedFile(o, slug + "-NOTICE.md", nosigBytes, ArtifactType.LICENSE, NOSIG_MEDIA_TYPE, Set.of(DigestAlgorithm.SHA_256));
		bytes.put(nosig, nosigBytes);
		byte[] s512Bytes = ("%PDF-1.7 notes of " + slug).getBytes(StandardCharsets.UTF_8);
		UUID s512 = seedFile(o, slug + "-notes.pdf", s512Bytes, ArtifactType.RELEASE_NOTES, S512_MEDIA_TYPE, Set.of(DigestAlgorithm.SHA_512));
		bytes.put(s512, s512Bytes);
		List<UUID> inventory = new ArrayList<>(List.of(bom, lic, nosig, s512));
		UUID ext = null;
		UUID extSig = null;
		String extUrl = null;
		String extSigUrl = null;
		UUID extIri = null;
		UUID extCtl = null;
		String extIriUrl = null;
		String extIriSigUrl = null;
		String extCtlUrl = null;
		Bounded bounded = null;
		UUID extIriSig = null;
		PreRule pre = null;
		if (external) {
			extUrl = "https://files.example/" + slug + "-notes.md";
			byte[] extBytes = ("external notes of " + slug).getBytes(StandardCharsets.UTF_8);
			ext = seedExternal(o, slug + "-notes.md", extUrl, ArtifactType.RELEASE_NOTES, EXT_MEDIA_TYPE, Utils.bytesToHexSha256(extBytes));
			bytes.put(ext, extBytes);
			if (extSigned) {
				extSigUrl = extUrl + ".sig";
				extSig = seedExternal(o, slug + "-notes.md.sig", extSigUrl, ArtifactType.SIGNATURE, SIG_MEDIA_TYPE, "cd".repeat(32));
				attachChild(ext, extSig);
				// round 3: frozen urls that are not URIs, frozen before the TEA-19 rule (seeded at a placeholder)
				extIriUrl = "https://files.example/" + slug + "/na\u00efve-\u2713.md";
				extIri = seedExternal(o, slug + "-iri.md", placeholder(extIriUrl), ArtifactType.RELEASE_NOTES, EXT_MEDIA_TYPE, "ef".repeat(32));
				extIriSigUrl = extIriUrl + ".sig";
				extIriSig = seedExternal(o, slug + "-iri.md.sig", extIriSigUrl, ArtifactType.SIGNATURE, SIG_MEDIA_TYPE, "fe".repeat(32));
				attachChild(extIri, extIriSig);
				extCtlUrl = "https://files.example/" + slug + "/x.md\r\nSet-Cookie: tea10=1";
				extCtl = seedExternal(o, slug + "-ctl.md", placeholder(extCtlUrl), ArtifactType.RELEASE_NOTES, EXT_MEDIA_TYPE, "ce".repeat(32));
				inventory.add(extIri);
				inventory.add(extCtl);
				bounded = bounded(o, slug, bytes, inventory);
				pre = preRule(o, slug, inventory);
			}
			inventory.add(ext);
		}
		for (UUID a : inventory) releaseService.addArtifact(a, release, WU);
		mergedPurls.put(release, new ArrayList<>(List.of("pkg:generic/" + slug + "@" + version)));
		ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.ASSEMBLED, WU);
		return new Seeded(release, component, bom, lic, nosig, s512, sig, sigBytes, bytes, ext, extUrl, extSig, extSigUrl, extIri,
				extIriUrl, extIriSigUrl, extCtl, extCtlUrl, bounded, extIriSig, pre);
	}

	/** The published object: the TEA ids and revisions read from its head's latest collection entries. */
	ArtObj published(Seeded s) {
		UUID head = teaPublicationService.getPublicationOfRelease(s.release()).orElseThrow().getUuid();
		List<TeaPublishedEntry> entries = teaPublicationService.getLatestCollection(head).orElseThrow().getEntries();
		Map<UUID, TeaPublishedEntry> byRow = entries.stream().collect(Collectors.toMap(TeaPublishedEntry::artifact, e -> e));
		TeaPublishedEntry agg = entries.stream().filter(e -> TeaArtifactOrigin.SYNTHETIC == e.origin()).findFirst().orElseThrow();
		Bounded b = s.bounded();
		PreRule p = s.preRule();
		return new ArtObj(s.release(), s.component(), head, art(agg, retained.get(agg.checksums().get(0).algValue())),
				art(byRow.get(s.bom()), s.bytes().get(s.bom())), art(byRow.get(s.lic()), s.bytes().get(s.lic())),
				art(byRow.get(s.nosig()), s.bytes().get(s.nosig())), art(byRow.get(s.s512()), s.bytes().get(s.s512())),
				s.sig(), s.sigBytes(), null == s.ext() ? null : art(byRow.get(s.ext()), s.bytes().get(s.ext())), s.extUrl(),
				s.extSig(), s.extSigUrl(), null == s.extIri() ? null : art(byRow.get(s.extIri()), new byte[0]), s.extIriUrl(),
				s.extIriSigUrl(), null == s.extCtl() ? null : art(byRow.get(s.extCtl()), new byte[0]), s.extCtlUrl(),
				null == b ? null : art(byRow.get(b.extLong()), new byte[0]), null == b ? null : b.extLongUrl(),
				null == b ? null : art(byRow.get(b.extOver()), new byte[0]), null == b ? null : art(byRow.get(b.extIriOver()), new byte[0]),
				null == b ? null : b.extIriOverUrl(), null == b ? null : art(byRow.get(b.longName()), s.bytes().get(b.longName())),
				null == b ? null : b.longNameFile(), s.extIriSig(), null == b ? null : b.extLongSig(), null == b ? null : b.extOverSig(),
				null == b ? null : b.extIriOverSig(), null == p ? null : art(byRow.get(p.extPre()), new byte[0]),
				null == p ? null : p.extPreUrl(), null == p ? null : p.extPreSig(), null == p ? null : p.extPreSigUrl(),
				null == p ? null : art(byRow.get(p.extNoSig()), new byte[0]), null == p ? null : p.extNoSigUrl());
	}

	/** Round 5 decision 18 (test-only): that entry's format url rewritten past the engine, recomputing document_sha256. */
	void freezeUrl(UUID publication, int version, UUID teaUuid, String url) {
		TeaFrozenEntries.freezeUrl(collectionRepository, publication, version, teaUuid, url);
	}

	/** Round 5 decision 19 (test-only): that entry's signature row and revision, and its signatureUrl set or removed. */
	void freezeSignature(UUID publication, int version, UUID teaUuid, UUID signatureArtifact, Integer signatureRevision,
			String signatureUrl) {
		TeaFrozenEntries.freezeSignature(collectionRepository, publication, version, teaUuid, signatureArtifact, signatureRevision,
				signatureUrl);
	}

	/** The entries of pub's version 1 that the TEA-19 and TEA-20 rules no longer produce, frozen as before the rules. */
	private void freezePreRule(Seeded s) {
		UUID head = teaPublicationService.getPublicationOfRelease(s.release()).orElseThrow().getUuid();
		Map<UUID, UUID> tea = teaPublicationService.getLatestCollection(head).orElseThrow().getEntries().stream()
				.collect(Collectors.toMap(TeaPublishedEntry::artifact, TeaPublishedEntry::teaUuid));
		Bounded b = s.bounded();
		freezeUrl(head, 1, tea.get(s.extIri()), s.extIriUrl());
		freezeUrl(head, 1, tea.get(s.extCtl()), s.extCtlUrl());
		freezeUrl(head, 1, tea.get(b.extOver()), b.extOverUrl());
		freezeUrl(head, 1, tea.get(b.extIriOver()), b.extIriOverUrl());
		freezeSignature(head, 1, tea.get(s.extIri()), s.extIriSig(), 1, null);
		freezeSignature(head, 1, tea.get(b.extLong()), b.extLongSig(), 1, null);
		freezeSignature(head, 1, tea.get(b.extOver()), b.extOverSig(), 1, null);
		freezeSignature(head, 1, tea.get(b.extIriOver()), b.extIriOverSig(), 1, null);
		freezeSignature(head, 1, tea.get(s.preRule().extPre()), s.preRule().extPreSig(), 1, null);
	}

	private static Art art(TeaPublishedEntry e, byte[] bytes) {
		assertTrue(null != e, "the entry was published");
		return new Art(e.artifact(), e.teaUuid(), e.revision(), e.mediaType(), bytes);
	}

	/** The world of the design, 4.3. */
	ArtWorld artifactWorld() throws Exception {
		saveOrgProfile(TeaVisibility.PRIVATE, TeaRawArtifacts.ALL, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		Seeded pub = seededRelease(org, "pub", true, true);
		saveComponentProfile(pub.component(), TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.ALL);
		publish(pub.release(), TeaPublishMode.PUBLISH);
		freezePreRule(pub);
		Seeded priv = seededRelease(org, "priv", true, false);
		publish(priv.release(), TeaPublishMode.PUBLISH);
		Seeded hid = seededRelease(org, "hid", false, false);
		saveComponentProfile(hid.component(), TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.ALL);
		publish(hid.release(), TeaPublishMode.PUBLISH);
		ArtObj hidden = published(hid);
		teaPublicationService.hide(hid.release(), null, WU);
		Seeded casc = seededRelease(org, "casc", false, false);
		saveComponentProfile(casc.component(), TeaPublishing.ENABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.ALL);
		UUID prodRelease = product(List.of(casc.release()));
		UUID prodComponent = stored(prodRelease).getComponent();
		saveComponentProfile(prodComponent, TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.ALL);
		publish(prodRelease, TeaPublishMode.PUBLISH);

		Organization foreign = testInitializer.obtainOrganization();
		teaProfileService.saveProfile(foreign.getUuid(), TeaProfileScope.ORGANIZATION, null,
				profileInput(TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.NONE,
						TeaProductComponents.PUBLISH_WITH_PRODUCT).build(), TeaCaller.ORG_ADMIN, WU);
		World far = componentReleaseWithPurl(foreign, "pkg:generic/tea10-foreign-" + tag() + "@1.0.0", List.of());
		publish(far.release(), TeaPublishMode.PUBLISH);
		UUID foreignHead = teaPublicationService.getPublicationOfRelease(far.release()).orElseThrow().getUuid();
		TeaPublishedEntry foreignAgg = teaPublicationService.getLatestCollection(foreignHead).orElseThrow().getEntries().stream()
				.filter(e -> TeaArtifactOrigin.SYNTHETIC == e.origin()).findFirst().orElseThrow();
		String foreignTea = getOrganizationService.getOrganizationData(foreign.getUuid()).orElseThrow().getTeaUuid().toString();

		String t = tag();
		ApiKey coverKey = externalApiKeyService.create(org.getUuid(), "cover-" + t, null, WU);
		externalApiKeyService.setGrants(coverKey.getUuid(), List.of(
				new PermissionDto(org.getUuid(), PermissionScope.COMPONENT, priv.component(), PermissionType.READ_ONLY,
						Set.of(PermissionFunction.TEA_READ), List.of()),
				new PermissionDto(org.getUuid(), PermissionScope.COMPONENT, prodComponent, PermissionType.READ_ONLY,
						Set.of(PermissionFunction.TEA_READ), List.of())), WU);
		ApiKey otherKey = externalApiKeyService.create(org.getUuid(), "other-" + t, null, WU);
		String teaOrg = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getTeaUuid().toString();
		String coverKeyId = ApiKeyService.keyIdOf(apiKeyService.getApiKey(coverKey.getUuid()).orElseThrow());
		// the TEA-3 organization-wide shape, on the foreign organization
		ApiKey farKey = externalApiKeyService.create(foreign.getUuid(), "far-" + t, null, WU);
		externalApiKeyService.setGrants(farKey.getUuid(), List.of(new PermissionDto(foreign.getUuid(), PermissionScope.ORGANIZATION,
				foreign.getUuid(), PermissionType.READ_ONLY, Set.of(PermissionFunction.TEA_READ), List.of())), WU);
		String farKeyId = ApiKeyService.keyIdOf(apiKeyService.getApiKey(farKey.getUuid()).orElseThrow());
		return new ArtWorld(teaOrg, root() + "/tea/" + teaOrg + "/v1.0.0", teaPublicationService.apiBaseOf(org.getUuid()),
				published(pub), published(priv), hidden, published(casc), prodRelease, prodComponent,
				root() + "/tea/" + foreignTea + "/v1.0.0", art(foreignAgg, retained.get(foreignAgg.checksums().get(0).algValue())),
				coverKey.getUuid(), coverKeyId, token(coverKey), token(otherKey), foreign.getUuid(), farKey.getUuid(), farKeyId,
				token(farKey, foreign.getUuid()));
	}

	// ---------------------------------------------------------------- urls and requests

	static String enc(String mediaType) {
		return URLEncoder.encode(mediaType, StandardCharsets.UTF_8);
	}

	/** {@code <base>/artifact/<tea>/<rev or latest><suffix>?mediaType=<a's media type>}; suffix "", "/download" or "/signature/download". */
	static String url(ArtWorld w, Art a, String revision, String suffix) {
		return w.base() + "/artifact/" + a.tea() + "/" + revision + suffix + ("".equals(suffix) ? "" : "?mediaType=" + enc(a.mediaType()));
	}

	/** The absolute versioned URL the Content-Location must carry. */
	static String contentLocation(ArtWorld w, Art a, boolean signature) {
		return w.apiBase() + "/v1.0.0/artifact/" + a.tea() + "/" + a.revision() + (signature ? "/signature/download" : "/download")
				+ "?mediaType=" + enc(a.mediaType());
	}

	HttpResponse<byte[]> head(String url, String bearer, Map<String, String> headers) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody())
				.header("X-Forwarded-For", randomIp());
		if (null != bearer) b.header("Authorization", "Bearer " + bearer);
		if (null != headers) headers.forEach(b::header);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	static String header(HttpResponse<?> r, String name) {
		return r.headers().firstValue(name).orElse(null);
	}

	static String reprDigest(byte[] bytes) {
		return "sha-256=:" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(Utils.bytesToHexSha256(bytes))) + ":";
	}

	/** Every header of a served download the design names (11), against the bytes expected. */
	static void assertDownload(HttpResponse<byte[]> r, byte[] expected, String contentType, String location, String cacheControl,
			String what) {
		assertEquals(200, r.statusCode(), what + ": " + new String(r.body(), StandardCharsets.UTF_8));
		assertArrayEquals(expected, r.body(), what + ": the stored bytes");
		assertHeaders(r, expected, contentType, location, cacheControl, what);
	}

	static void assertHeaders(HttpResponse<?> r, byte[] expected, String contentType, String location, String cacheControl,
			String what) {
		assertEquals(contentType, header(r, "Content-Type"), what);
		assertEquals(String.valueOf(expected.length), header(r, "Content-Length"), what);
		assertTrue(header(r, "Content-Disposition").startsWith("attachment; "), what + ": " + header(r, "Content-Disposition"));
		assertEquals("\"" + Utils.bytesToHexSha256(expected) + "\"", header(r, "ETag"), what + ": a strong tag of the bytes");
		assertEquals(reprDigest(expected), header(r, "Repr-Digest"), what);
		assertEquals(location, header(r, "Content-Location"), what);
		assertEquals(cacheControl, header(r, "Cache-Control"), what);
		assertEquals("Authorization", header(r, "Vary"), what);
		assertEquals(null, header(r, "Content-Encoding"), what);
	}

	static void assertTeaError(HttpResponse<String> r, int status, String error, String what) {
		assertEquals(status, r.statusCode(), what + ": " + r.body());
		assertEquals(error, json(r).get("error"), what);
		assertEquals("no-store", header(r, "Cache-Control"), what);
		io.reliza.service.tea.TeaOpenApiSchemas.assertConforms(r.body(), "error-response");
	}

	// ---------------------------------------------------------------- the download log

	List<DownloadLogData> teaDownloads(ZonedDateTime since) {
		return teaDownloads(org.getUuid(), since);
	}

	List<DownloadLogData> teaDownloads(UUID orgUuid, ZonedDateTime since) {
		return downloadLogRepository.listDownloadLogsByOrg(orgUuid.toString(), since.minusSeconds(1), ZonedDateTime.now().plusMinutes(1))
				.stream().map(DownloadLogData::dataFromRecord).filter(d -> DownloadType.TEA_DOWNLOAD == d.getDownloadType())
				.filter(d -> !d.getCreatedDate().isBefore(since)).toList();
	}

	/**
	 * The TEA_DOWNLOAD rows since {@code since} once every request made so far has finished on the server
	 * (see {@link #awaitRequestsHandled}), so both "n rows" and "no row" are final when this returns.
	 * {@code n}, the rows the caller expects at most, no longer decides how long to wait: callers checking
	 * for no row pass 1, and polling until n rows appeared is exactly the five seconds this removed.
	 */
	List<DownloadLogData> awaitTeaDownloads(ZonedDateTime since, int n) throws Exception {
		return awaitTeaDownloads(org.getUuid(), since, n);
	}

	List<DownloadLogData> awaitTeaDownloads(UUID orgUuid, ZonedDateTime since, int n) throws Exception {
		awaitRequestsHandled();
		return teaDownloads(orgUuid, since);
	}

	/**
	 * Waits until the embedded Tomcat runs no request. The download row is written on the request
	 * thread AFTER the bytes are flushed (ArtifactApiController writes the response, then logs), so a
	 * client holds the whole response a moment before its row exists. DownloadLogService.createDownloadLog
	 * is @Async, but TestAsyncConfig's SyncTaskExecutor (the bean named taskExecutor, asserted here) runs
	 * it inline on that thread. So once no request thread is busy, every row the requests made so far
	 * will ever write is committed, and so is the absence of one. This replaced a fixed five-second wait
	 * on every "no row" check plus a 300 ms settle on every call.
	 */
	void awaitRequestsHandled() throws InterruptedException {
		assertInstanceOf(SyncTaskExecutor.class, applicationContext.getBean("taskExecutor"),
				"the download-log writer must run on the request thread for a finished request to mean a written row");
		ThreadPoolExecutor requestThreads = (ThreadPoolExecutor) ((TomcatWebServer) ((WebServerApplicationContext) applicationContext)
				.getWebServer()).getTomcat().getConnector().getProtocolHandler().getExecutor();
		long deadline = System.currentTimeMillis() + 10_000;
		while (requestThreads.getActiveCount() > 0) {
			assertTrue(System.currentTimeMillis() < deadline, "a request was still running on the server after 10 s");
			Thread.sleep(10);
		}
	}
}
