/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.netflix.graphql.dgs.exceptions.DgsEntityNotFoundException;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.JustifiedUnknown;
import io.reliza.model.JustifiedUnknownField;
import io.reliza.model.JustifiedUnknownReason;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbom;
import io.reliza.model.ReleaseSbomJustification;
import io.reliza.model.ReleaseSbomJustificationAudit;
import io.reliza.model.ReleaseSbomJustificationData;
import io.reliza.model.ReleaseSbomVersion;
import io.reliza.model.SupportBulkActKind;
import io.reliza.model.SupportBulkSelector;
import io.reliza.model.SupportTextBounds;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.repositories.ReleaseSbomJustificationAuditRepository;
import io.reliza.repositories.ReleaseSbomJustificationRepository;
import io.reliza.repositories.ReleaseSbomRepository;
import io.reliza.repositories.ReleaseSbomVersionRepository;
import io.reliza.service.SupportBomInjector.DocumentNode;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * Hash justifications per release SBOM identity head (FDAW-1 D5, F-14 item 2, F-22 item 4; FDAW-14 3.3).
 *
 * <p>A justification names a component of the head's pinned document by its
 * {@link SupportBomInjector#componentKey}, and only a node of the head's LATEST version whose
 * {@code hashes} is absent or empty can be justified: a key with a hash, or outside the document, is
 * refused at write, and the injector emits only on hashless nodes, so a stored key that later gained a
 * hash is never served as live.
 *
 * <p>Every write takes the head uuid alone: the head is loaded, release and org are taken from the row,
 * and COMPONENT WRITE is required on that release's component; reads require RELEASE READ. An absent
 * head, another organisation's head and a caller without the right all get the one text
 * {@value #NOT_FOUND}.
 */
@Slf4j
@Service
public class ReleaseSbomJustificationService {

	public static final String NOT_FOUND = "release SBOM not found";

	@Autowired private ReleaseSbomRepository releaseSbomRepository;
	@Autowired private ReleaseSbomVersionRepository releaseSbomVersionRepository;
	@Autowired private ReleaseSbomJustificationRepository releaseSbomJustificationRepository;
	@Autowired private ReleaseSbomJustificationAuditRepository releaseSbomJustificationAuditRepository;
	@Autowired private SupportBulkActService supportBulkActService;
	@Autowired private RebomService rebomService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private AuthorizationService authorizationService;

	/** Through the proxy, so a selector sweep's write runs in its own transaction before the receipt. */
	@Autowired @Lazy private ReleaseSbomJustificationService self;

	/** How an explicit write and a selector sweep treat a key that is already justified (3.2a, hash half). */
	public enum Overwrite { REPLACE, SKIP_JUSTIFIED }

	/** A head the caller was authorised on, with its release. */
	public record Head(ReleaseSbom head, ReleaseData release) {
		public UUID uuid() { return head.getUuid(); }
		public UUID org() { return head.getOrg(); }
	}

	/** One hashless node of the head's latest version, for the UI (3.8 {@code ReleaseSbomHashlessComponent}). */
	public record HashlessNode(String key, String name, String version) {}

	/** The head's latest version read once: its number, its hashless keys in document order, every key. */
	public record HashlessKeys(int version, Map<String, HashlessNode> hashless, Set<String> allKeys) {}

	/** What a write did: the head's row after it, the keys written and the keys a sweep left as they were. */
	public record WriteResult(ReleaseSbomJustification row, List<String> setKeys, List<String> skippedKeys,
			UUID bulkAct) {}

	// ------------------------------------------------------------------ the head and its gate

	/** The head for a write: COMPONENT WRITE on its release's component, else {@value #NOT_FOUND}. */
	public Head writableHead(UUID releaseSbom, UserData caller) throws RelizaException {
		return gatedHead(releaseSbom, caller, true);
	}

	/** The head for a read: RELEASE READ on its release, else {@value #NOT_FOUND}. */
	public Head readableHead(UUID releaseSbom, UserData caller) throws RelizaException {
		return gatedHead(releaseSbom, caller, false);
	}

	private Head gatedHead(UUID releaseSbom, UserData caller, boolean write) throws RelizaException {
		if (null == releaseSbom || null == caller) throw new RelizaException(NOT_FOUND);
		ReleaseSbom head = releaseSbomRepository.findById(releaseSbom).orElseThrow(() -> new RelizaException(NOT_FOUND));
		ReleaseData rd = sharedReleaseService.getReleaseData(head.getRelease())
				.filter(r -> head.getOrg().equals(r.getOrg()))
				.orElseThrow(() -> new RelizaException(NOT_FOUND));
		try {
			if (write) {
				ComponentData cd = getComponentService.getComponentData(rd.getComponent())
						.orElseThrow(() -> new RelizaException(NOT_FOUND));
				authorizationService.isUserAuthorizedForObjectGraphQL(caller, PermissionFunction.RESOURCE,
						PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.WRITE);
			} else {
				authorizationService.isUserAuthorizedForObjectGraphQL(caller, PermissionFunction.RESOURCE,
						PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.READ);
			}
		} catch (AccessDeniedException | DgsEntityNotFoundException denied) {
			throw new RelizaException(NOT_FOUND);
		}
		return new Head(head, rd);
	}

	/** The heads of a release, by profile name; the caller gates the release first. */
	public List<ReleaseSbom> listByRelease(UUID release) {
		return releaseSbomRepository.listByRelease(release);
	}

	// ------------------------------------------------------------------ the document

	/**
	 * The head's latest version document read once (the read FDAW-7 uses for the BOM-Link index), its
	 * component nodes walked at every depth as the injector walks them; a key is hashless when one of its
	 * nodes has no hash. No version yet: version 0 and nothing to justify.
	 */
	public HashlessKeys hashlessKeys(ReleaseSbom head) {
		Optional<ReleaseSbomVersion> latest = releaseSbomVersionRepository.latest(head.getUuid());
		if (latest.isEmpty()) return new HashlessKeys(0, Map.of(), Set.of());
		int version = latest.get().getVersion();
		JsonNode bom = rebomService.findBomByVersion(head.getUuid(), head.getOrg(), version);
		Map<String, HashlessNode> hashless = new LinkedHashMap<>();
		Set<String> all = new LinkedHashSet<>();
		for (DocumentNode n : SupportBomInjector.documentNodes(bom)) {
			all.add(n.key());
			if (!n.hashed()) hashless.putIfAbsent(n.key(), new HashlessNode(n.key(), n.name(), n.version()));
		}
		return new HashlessKeys(version, hashless, all);
	}

	/** The hashless keys of {@code keys} matched against the selector by decoded purl fields (3.4, hash half). */
	public static List<String> matching(HashlessKeys keys, SupportBulkSelector selector) {
		List<String> out = new ArrayList<>();
		for (String key : keys.hashless().keySet()) {
			PackageURL purl;
			try {
				purl = new PackageURL(key.replace("+", "%2B"));
			} catch (MalformedPackageURLException | RuntimeException notAPurl) {
				continue;
			}
			if (selector.matches(purl)) out.add(key);
		}
		return out;
	}

	// ------------------------------------------------------------------ writes

	/**
	 * Justifies {@code keys} of the head (3.3): the code accepted by {@link JustifiedUnknownField#HASH},
	 * every key hashless in the latest version. {@link Overwrite#REPLACE} replaces a justified key's code
	 * and text (the explicit set: the user picked those keys); {@link Overwrite#SKIP_JUSTIFIED} leaves it
	 * as it was (a selector sweep). Moves {@code revision} and files one audit row when at least one key
	 * was set. The document is read before the head's row is locked.
	 */
	@Transactional
	public WriteResult set(Head head, List<String> keys, JustifiedUnknownReason code, String text, String reason,
			UUID caller, UUID bulkAct, Overwrite overwrite) {
		List<String> boundedKeys = SupportTextBounds.boundedKeys(keys, SbomComponentService.BULK_SUPPORT_MAX_IDS,
				explicitOverCap(null == keys ? 0 : keys.size()));
		String boundedText = SupportTextBounds.boundedUnknownText(text);
		String boundedReason = SupportTextBounds.bounded(reason, "reason", SupportTextBounds.MAX_REASON_LENGTH);
		JustifiedUnknownField.HASH.validate(code, boundedText);
		HashlessKeys doc = hashlessKeys(head.head());
		requireHashless(doc, boundedKeys);

		ReleaseSbomJustification row = lockedRow(head);
		Map<String, JustifiedUnknown> hashes = new LinkedHashMap<>(row.getRecordData().hashes());
		String now = ZonedDateTime.now().toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
		List<String> set = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (String key : new LinkedHashSet<>(boundedKeys)) {
			if (Overwrite.SKIP_JUSTIFIED == overwrite && hashes.containsKey(key)) {
				skipped.add(key);
				continue;
			}
			hashes.put(key, new JustifiedUnknown(code, boundedText, bulkAct, caller, now));
			set.add(key);
		}
		if (!set.isEmpty()) row = save(row, hashes, caller, boundedReason, bulkAct);
		return new WriteResult(row, set, skipped, bulkAct);
	}

	/** Removes {@code keys} from the head's justifications; a reason is required (3.11). */
	@Transactional
	public WriteResult clear(Head head, List<String> keys, String reason, UUID caller) {
		List<String> boundedKeys = SupportTextBounds.boundedKeys(keys, SbomComponentService.BULK_SUPPORT_MAX_IDS,
				explicitOverCap(null == keys ? 0 : keys.size()));
		String boundedReason = SupportTextBounds.bounded(reason, "reason", SupportTextBounds.MAX_REASON_LENGTH);
		if (null == boundedReason) throw new IllegalArgumentException("justified unknown: clearing it needs a reason");
		ReleaseSbomJustification row = lockedRow(head);
		Map<String, JustifiedUnknown> hashes = new LinkedHashMap<>(row.getRecordData().hashes());
		List<String> cleared = new ArrayList<>();
		for (String key : new LinkedHashSet<>(boundedKeys)) {
			if (null != hashes.remove(key)) cleared.add(key);
		}
		if (!cleared.isEmpty()) row = save(row, hashes, caller, boundedReason, null);
		return new WriteResult(row, cleared, List.of(), null);
	}

	/**
	 * A selector sweep over the head's hashless keys (3.4, hash half): matched by decoded type and
	 * namespace, capped at {@link SbomComponentService#BULK_SUPPORT_MAX_IDS} before any row is written,
	 * justified keys skipped, and recorded once under a server-minted act.
	 */
	public WriteResult setBySelector(Head head, SupportBulkSelector selector, JustifiedUnknownReason code,
			String text, String reason, UUID caller) {
		String boundedText = SupportTextBounds.boundedUnknownText(text);
		String boundedReason = SupportTextBounds.bounded(reason, "reason", SupportTextBounds.MAX_REASON_LENGTH);
		JustifiedUnknownField.HASH.validate(code, boundedText);
		List<String> matched = matching(hashlessKeys(head.head()), selector);
		if (matched.size() > SbomComponentService.BULK_SUPPORT_MAX_IDS) {
			throw new IllegalArgumentException(SbomComponentService.selectorOverCap(selector, true));
		}
		UUID act = UUID.randomUUID();
		WriteResult written = matched.isEmpty() ? new WriteResult(self.current(head), List.of(), List.of(), act)
				: self.set(head, matched, code, boundedText, boundedReason, caller, act, Overwrite.SKIP_JUSTIFIED);
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("code", code.name());
		values.put("text", boundedText);
		values.put("reason", boundedReason);
		supportBulkActService.record(new SupportBulkActService.Receipt(act, head.org(),
				SupportBulkActKind.HASH_JUSTIFICATION, head.release().getUuid(), head.uuid(),
				selector.receipt(null), matched, written.setKeys().size(), caller, values));
		return new WriteResult(written.row(), written.setKeys(), written.skippedKeys(), act);
	}

	/** The head's row as stored, or an unsaved empty one at revision 0. */
	@Transactional(readOnly = true)
	public ReleaseSbomJustification current(Head head) {
		return releaseSbomJustificationRepository.findById(head.uuid()).orElseGet(() -> emptyRow(head));
	}

	private ReleaseSbomJustification lockedRow(Head head) {
		releaseSbomJustificationRepository.insertIfAbsent(head.uuid(), head.org(), head.release().getUuid());
		return releaseSbomJustificationRepository.lockById(head.uuid())
				.orElseThrow(() -> new IllegalStateException("release SBOM justification row of " + head.uuid()
						+ " is missing after its insert"));
	}

	private ReleaseSbomJustification save(ReleaseSbomJustification row, Map<String, JustifiedUnknown> hashes,
			UUID caller, String reason, UUID bulkAct) {
		ReleaseSbomJustificationData data = new ReleaseSbomJustificationData(hashes);
		row.setRecordData(data);
		row.setRevision(row.getRevision() + 1);
		row.setLastUpdatedDate(ZonedDateTime.now());
		ReleaseSbomJustification saved = releaseSbomJustificationRepository.save(row);
		ReleaseSbomJustificationAudit audit = new ReleaseSbomJustificationAudit();
		audit.setReleaseSbom(saved.getReleaseSbom());
		audit.setOrg(saved.getOrg());
		audit.setRevision(saved.getRevision());
		audit.setRecordData(data);
		audit.setAssertedBy(caller);
		audit.setReason(reason);
		audit.setBulkAct(bulkAct);
		releaseSbomJustificationAuditRepository.save(audit);
		return saved;
	}

	private static ReleaseSbomJustification emptyRow(Head head) {
		ReleaseSbomJustification r = new ReleaseSbomJustification();
		r.setReleaseSbom(head.uuid());
		r.setOrg(head.org());
		r.setRelease(head.release().getUuid());
		return r;
	}

	/** The first key that has a hash or is not in the latest version is refused, named (3.11). */
	private static void requireHashless(HashlessKeys doc, List<String> keys) {
		for (String key : keys) {
			if (!doc.allKeys().contains(key)) {
				throw new IllegalArgumentException("component " + key + " is not in release SBOM version " + doc.version());
			}
			if (!doc.hashless().containsKey(key)) {
				throw new IllegalArgumentException("component " + key + " has a hash in release SBOM version "
						+ doc.version() + " and cannot be justified");
			}
		}
	}

	/** The explicit-form cap text for a hash write, the support explicit form's wording. */
	public static String explicitOverCap(int supplied) {
		return "a bulk support attestation is limited to " + SbomComponentService.BULK_SUPPORT_MAX_IDS
				+ " components per call; " + supplied + " were supplied";
	}

	// ------------------------------------------------------------------ readers

	/**
	 * The codes the injector writes for a publication of {@code (release, profileName)} (3.5): the
	 * head's justified keys, or empty when the head or its row does not exist. Throws on a read failure,
	 * which the injection seam turns into its strip-and-mark fallback.
	 */
	@Transactional(readOnly = true)
	public Map<String, JustifiedUnknownReason> hashCodesFor(UUID release, String profileName) {
		Optional<ReleaseSbom> head = releaseSbomRepository.findByReleaseAndProfileName(release, profileName);
		if (head.isEmpty()) return Map.of();
		Optional<ReleaseSbomJustification> row = releaseSbomJustificationRepository.findById(head.get().getUuid());
		if (row.isEmpty()) return Map.of();
		Map<String, JustifiedUnknownReason> codes = new LinkedHashMap<>();
		row.get().getRecordData().hashes().forEach((k, v) -> {
			if (null != v && null != v.code()) codes.put(k, v.code());
		});
		return codes;
	}

	/** {@code justifications:<release_sboms.uuid>=<revision>}, 0 without a row: A3's fingerprint line (3.6). */
	@Transactional(readOnly = true)
	public String fingerprintLine(UUID releaseSbom) {
		int revision = releaseSbomJustificationRepository.findById(releaseSbom)
				.map(ReleaseSbomJustification::getRevision).orElse(0);
		return "justifications:" + releaseSbom + "=" + revision;
	}

	/** The head's justification writes recorded after {@code builtAt} (audit time): A3's count term (3.6). */
	@Transactional(readOnly = true)
	public long countAuditSince(UUID releaseSbom, ZonedDateTime builtAt) {
		return releaseSbomJustificationAuditRepository.countByReleaseSbomAndAssertedDateAfter(releaseSbom, builtAt);
	}
}
