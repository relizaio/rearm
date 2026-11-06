/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseSbomWriter;
import io.reliza.model.SbomComponent;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseSbomRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.service.ReleaseSbomJustificationService.Head;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * FDAW-14 fixtures over the FDAW-7 release SBOM base: a head whose latest version holds hashed and
 * hashless nodes, users scoped to a component or an organisation, and support components mapped into a
 * release inventory (mapped, so the orphan GC tick never deletes them mid-test).
 */
abstract class ReleaseSbomJustificationTestBase extends ReleaseSbomIntegrationTestBase {

	@Autowired ReleaseSbomJustificationService justificationService;
	@Autowired SupportBulkActService supportBulkActService;
	@Autowired ReleaseSbomRepository releaseSbomRepository;
	@Autowired SbomComponentRepository sbomComponentRepository;
	@Autowired ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Autowired PlatformTransactionManager transactionManager;

	/** A node of a fixture document: its purl and whether it carries a hash. */
	record Node(String purl, boolean hashed) {}

	static Node hashless(String purl) {
		return new Node(purl, false);
	}

	static Node hashed(String purl) {
		return new Node(purl, true);
	}

	static JsonNode docOf(List<Node> nodes) {
		ObjectNode d = Utils.OM.createObjectNode();
		d.put("bomFormat", "CycloneDX");
		d.put("specVersion", "1.6");
		d.putObject("metadata").putObject("component").put("name", "fdaw14").put("type", "application");
		ArrayNode components = d.putArray("components");
		for (Node n : nodes) {
			ObjectNode c = components.addObject().put("type", "library").put("name", n.purl()).put("purl", n.purl());
			if (n.hashed()) c.putArray("hashes").addObject().put("alg", "SHA-256").put("content", "00".repeat(32));
		}
		return d;
	}

	/** The component key the injector and the justification service use for {@code purl}. */
	static String keyOf(String purl) {
		ObjectNode n = Utils.OM.createObjectNode().put("type", "library").put("name", purl).put("purl", purl);
		return SupportBomInjector.componentKey(n);
	}

	/** A head of {@code profile} on {@code release} whose version 1 is {@code nodes}. */
	UUID headOf(UUID release, String profile, List<Node> nodes) throws Exception {
		try (TeaPublishLock.Lease lease = teaPublishLock.acquire(release, Duration.ofSeconds(10))) {
			return releaseSbomService.ensureVersion(stored(release), profile, docOf(nodes), lease,
					ReleaseSbomWriter.pkg(null), WU).pin().serial();
		}
	}

	/** The service-level head, ungated (the gate has its own tests). */
	Head head(UUID serial) {
		var h = releaseSbomRepository.findById(serial).orElseThrow();
		return new Head(h, sharedReleaseService.getReleaseData(h.getRelease()).orElseThrow());
	}

	/** A user of {@code o} with one permission row. */
	UserData userOf(Organization o, PermissionScope scope, UUID object, PermissionType type) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Fdaw14 " + tag, "fdaw14-" + tag + "@justify.io", true, List.of(o.getUuid()),
				"fdaw14-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), o.getUuid(), scope, object, type, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/**
	 * Support components of {@code o}, in one transaction with their inventory mapping into a release of
	 * their own (or {@code release} when given), so the orphan GC cannot take them.
	 */
	List<SbomComponent> components(Organization o, UUID release, List<String> purls, boolean root) {
		UUID target = null != release ? release : uncheckedRelease(o);
		return new TransactionTemplate(transactionManager).execute(status -> {
			UUID canonicalArtifact = UUID.randomUUID();
			ReleaseArtifactIndex idx = new ReleaseArtifactIndex();
			idx.setOrg(o.getUuid());
			idx.setReleaseUuid(target);
			idx.setCanonicalArtifactUuid(canonicalArtifact);
			releaseArtifactIndexRepository.save(idx);
			List<SbomComponent> out = new ArrayList<>();
			for (String purl : purls) {
				SbomComponent sc = new SbomComponent();
				sc.setOrg(o.getUuid());
				sc.setCanonicalPurl(purl);
				Map<String, Object> rd = new HashMap<>();
				rd.put("name", purl);
				if (root) rd.put("isRoot", Boolean.TRUE);
				sc.setRecordData(rd);
				sc = sbomComponentRepository.save(sc);
				ArtifactSbomComponent asc = new ArtifactSbomComponent();
				asc.setOrg(o.getUuid());
				asc.setCanonicalArtifactUuid(canonicalArtifact);
				asc.setSbomComponentUuid(sc.getUuid());
				asc.setExactPurl(purl);
				asc.setParents(new ArrayList<>());
				artifactSbomComponentRepository.save(asc);
				out.add(sc);
			}
			return out;
		});
	}

	private UUID uncheckedRelease(Organization o) {
		try {
			return componentReleaseIn(o, null).release();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	long count(String table, String where, Object... args) {
		return jdbc.queryForObject("SELECT count(*) FROM rearm." + table + (null == where ? "" : " WHERE " + where),
				Long.class, args);
	}
}
