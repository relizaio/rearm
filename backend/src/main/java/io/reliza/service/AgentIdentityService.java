/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentIdentity;
import io.reliza.model.AgentIdentityCredential;
import io.reliza.model.AgentIdentityData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentIdentityCredentialRepository;
import io.reliza.repositories.AgentIdentityRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Find-or-create AgentIdentity rows keyed by a credential pair. Called
 * from {@code sessionInitializeProgrammatic} *before* the Agent
 * find-or-create so the Agent can be scoped to
 * {@code (org, agentIdentity, lower(name))} — see
 * {@link io.reliza.model.AgentData}.
 *
 * Credential uniqueness is enforced by the DB
 * {@code UNIQUE(identity_type, identity_value)} on
 * {@code rearm.agent_identity_credentials}; the service handles the
 * insert race by catching {@link DataIntegrityViolationException} and
 * re-reading the winner.
 */
@Service
@Slf4j
public class AgentIdentityService {

	@Autowired
	private AuditService auditService;

	private final AgentIdentityRepository identityRepo;
	private final AgentIdentityCredentialRepository credRepo;

	AgentIdentityService(AgentIdentityRepository identityRepo,
			AgentIdentityCredentialRepository credRepo) {
		this.identityRepo = identityRepo;
		this.credRepo = credRepo;
	}

	public Optional<AgentIdentityData> getAgentIdentityData(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return identityRepo.findById(uuid).map(AgentIdentityData::dataFromRecord);
	}

	/**
	 * Read-only reverse lookup: which AgentIdentity owns a given
	 * credential pair, without the find-or-create side effect of
	 * {@link #findOrRegisterByCredential}. Returns empty when the
	 * credential has never been bound (e.g. a freshly minted FREEFORM
	 * key that no agent has used yet).
	 */
	public Optional<AgentIdentityData> findByCredential(
			AgentIdentityCredential.IdentityType identityType, String identityValue) {
		if (identityType == null || identityValue == null || identityValue.isBlank()) {
			return Optional.empty();
		}
		return credRepo.findByTypeAndValue(identityType.name(), identityValue)
				.flatMap(c -> identityRepo.findById(c.getAgentIdentityUuid()))
				.map(AgentIdentityData::dataFromRecord);
	}

	/**
	 * Credentials bound to an AgentIdentity, oldest first. Used to
	 * surface the FREEFORM key(s) backing an agent in the UI.
	 */
	public List<AgentIdentityCredential> listCredentials(UUID agentIdentityUuid) {
		if (agentIdentityUuid == null) return List.of();
		return credRepo.findByAgentIdentityUuid(agentIdentityUuid);
	}

	/**
	 * Look up the AgentIdentity that owns a given credential pair, or
	 * create a fresh identity carrying just this credential if none
	 * exists. The credential's
	 * {@code UNIQUE(identity_type, identity_value)} index handles
	 * concurrent registers: the loser catches the DB violation and
	 * re-reads the winning credential.
	 *
	 * @param orgUuid       org the new identity belongs to (only used
	 *                      when creating; the row's org never moves)
	 * @param identityType  e.g. {@code REARM_API_KEY}
	 * @param identityValue the credential value (key uuid, OIDC subject)
	 */
	@Transactional
	public AgentIdentityData findOrRegisterByCredential(UUID orgUuid,
			AgentIdentityCredential.IdentityType identityType,
			String identityValue, WhoUpdated wu) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		if (identityType == null) throw new RelizaException("identityType is required");
		if (identityValue == null || identityValue.isBlank()) {
			throw new RelizaException("identityValue is required");
		}

		Optional<AgentIdentityCredential> existing =
				credRepo.findByTypeAndValue(identityType.name(), identityValue);
		if (existing.isPresent()) {
			return identityRepo.findById(existing.get().getAgentIdentityUuid())
					.map(AgentIdentityData::dataFromRecord)
					.orElseThrow(() -> new RelizaException(
							"Credential row points at missing AgentIdentity "
							+ existing.get().getAgentIdentityUuid()));
		}

		AgentIdentityData seed = new AgentIdentityData();
		seed.setOrg(orgUuid);
		seed.setName("Identity for " + identityType.name() + " " + identityValue);
		seed.setCreatedType(io.reliza.common.CommonVariables.ProgrammaticType.AUTO);
		AgentIdentity row = new AgentIdentity();
		Map<String, Object> recordData = Utils.dataToRecord(seed);
		AgentIdentity savedIdentity = saveIdentity(row, recordData, wu);

		AgentIdentityCredential cred = new AgentIdentityCredential();
		cred.setAgentIdentityUuid(savedIdentity.getUuid());
		cred.setIdentityType(identityType.name());
		cred.setIdentityValue(identityValue);
		try {
			credRepo.save(cred);
			log.info("Registered AgentIdentity uuid={} for credential ({},{})",
					savedIdentity.getUuid(), identityType, identityValue);
			return AgentIdentityData.dataFromRecord(savedIdentity);
		} catch (DataIntegrityViolationException e) {
			log.info("Concurrent identity-credential race for ({},{}) — re-reading winner",
					identityType, identityValue);
			AgentIdentityCredential winner = credRepo.findByTypeAndValue(
					identityType.name(), identityValue)
					.orElseThrow(() -> new RelizaException(
							"Credential race detected but no winning row found"));
			// Orphan the speculative identity row we wrote — it has no
			// credentials pointing at it and would otherwise leak.
			identityRepo.deleteById(savedIdentity.getUuid());
			return identityRepo.findById(winner.getAgentIdentityUuid())
					.map(AgentIdentityData::dataFromRecord)
					.orElseThrow(() -> new RelizaException(
							"Race-winner credential points at missing AgentIdentity"));
		}
	}

	private AgentIdentity saveIdentity(AgentIdentity ai, Map<String, Object> recordData, WhoUpdated wu) {
		Optional<AgentIdentity> existing = identityRepo.findById(ai.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.AGENT_IDENTITIES, ai);
			ai.setRevision(ai.getRevision() + 1);
			ai.setLastUpdatedDate(ZonedDateTime.now());
		}
		ai.setRecordData(recordData);
		ai = (AgentIdentity) WhoUpdated.injectWhoUpdatedData(ai, wu);
		return identityRepo.save(ai);
	}
}
