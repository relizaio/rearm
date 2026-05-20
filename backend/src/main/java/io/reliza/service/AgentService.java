/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Agent;
import io.reliza.model.AgentData;
import io.reliza.model.AgentData.AgentStatus;
import io.reliza.model.AgentData.AgentType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD, auto-registration, and hierarchy management for {@link Agent}.
 *
 * Hot paths:
 * <ul>
 *   <li>{@link #findOrRegisterRootAgent} — invoked on session
 *       initialize once the calling agent's {@link io.reliza.model.ModelOntology}
 *       has been resolved. Creates the ROOT row if needed, links it
 *       to the model ontology, and binds the calling key. Idempotent
 *       under the V37 unique index.</li>
 *   <li>{@link #spawnSubAgent} — invoked by a parent agent to
 *       register a child. Creates the SUB row with
 *       {@code agentType = SUB}, a {@code rootAgent} pointer (the
 *       parent's root, or the parent itself if the parent is already
 *       ROOT), and inherits the parent's {@code model} ontology
 *       unless overridden. Appends the new sub uuid into the parent's
 *       {@code subAgents}.</li>
 *   <li>{@link #resolveRoot} — walks {@code rootAgent} from any leaf;
 *       used by the commit-trailer attribution path in PR 2.</li>
 * </ul>
 */
@Service
@Slf4j
public class AgentService {

	@Autowired
	private AuditService auditService;

	private final AgentRepository repository;

	AgentService(AgentRepository repository) {
		this.repository = repository;
	}

	public Optional<Agent> getAgent(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid);
	}

	public Optional<AgentData> getAgentData(UUID uuid) {
		return getAgent(uuid).map(AgentData::dataFromRecord);
	}

	public Optional<AgentData> getByOrgIdentityAndName(UUID orgUuid, UUID agentIdentity, String name) {
		if (orgUuid == null || agentIdentity == null || StringUtils.isBlank(name)) return Optional.empty();
		return repository.findByOrgIdentityAndName(orgUuid.toString(), agentIdentity.toString(), name)
				.map(AgentData::dataFromRecord);
	}

	public List<AgentData> listByOrg(UUID orgUuid) {
		if (orgUuid == null) return List.of();
		return repository.findByOrg(orgUuid.toString()).stream()
				.map(AgentData::dataFromRecord)
				.collect(Collectors.toList());
	}

	public List<AgentData> listByRoot(UUID rootUuid) {
		if (rootUuid == null) return List.of();
		return repository.findByRootAgent(rootUuid.toString()).stream()
				.map(AgentData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Walk {@code rootAgent} from any leaf agent. Returns the agent
	 * itself when it is already a ROOT, or the resolved root when it
	 * is a SUB. A SUB whose {@code rootAgent} points at a missing /
	 * orphan row throws — that's a data-integrity defect, not a
	 * client error.
	 */
	public AgentData resolveRoot(AgentData agent) throws RelizaException {
		if (agent == null) throw new RelizaException("Agent is null");
		if (agent.getAgentType() == AgentType.ROOT) return agent;
		if (agent.getRootAgent() == null) {
			throw new RelizaException(
					"SUB agent " + agent.getUuid() + " has no rootAgent pointer");
		}
		return getAgentData(agent.getRootAgent())
				.orElseThrow(() -> new RelizaException(
						"SUB agent " + agent.getUuid() + " references missing root "
								+ agent.getRootAgent()));
	}

	/**
	 * Find-or-create a ROOT agent for {@code (orgUuid, agentIdentityUuid, name)}.
	 * {@code modelOntologyUuid} is set on creation and (if currently
	 * null on the existing row) also on find — agents created before a
	 * model ontology was available will pick one up on the next session
	 * initialize.
	 *
	 * Concurrent first-session calls on a fresh (identity, name) race on
	 * the V40 unique index; the loser catches the violation and re-reads
	 * the winning row.
	 */
	@Transactional
	public AgentData findOrRegisterRootAgent(UUID orgUuid, UUID agentIdentityUuid, String name,
			UUID modelOntologyUuid, String iconKind, String color, WhoUpdated wu) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("Agent requires an org");
		if (agentIdentityUuid == null) throw new RelizaException("Agent requires an agentIdentity");
		if (StringUtils.isBlank(name)) {
			throw new RelizaException("Agent requires a name on auto-registration");
		}
		Optional<Agent> existing = repository.findByOrgIdentityAndName(
				orgUuid.toString(), agentIdentityUuid.toString(), name);
		if (existing.isPresent()) {
			AgentData ad = AgentData.dataFromRecord(existing.get());
			if (ad.getModel() == null && modelOntologyUuid != null) {
				ad.setModel(modelOntologyUuid);
				return saveData(ad, wu);
			}
			return ad;
		}
		AgentData seed = new AgentData();
		seed.setOrg(orgUuid);
		seed.setAgentIdentity(agentIdentityUuid);
		seed.setName(name);
		seed.setModel(modelOntologyUuid);
		seed.setIconKind(iconKind);
		seed.setColor(color);
		seed.setStatus(AgentStatus.ACTIVE);
		seed.setAgentType(AgentType.ROOT);
		Agent a = new Agent();
		Map<String, Object> recordData = Utils.dataToRecord(seed);
		try {
			Agent saved = save(a, recordData, wu);
			log.info("Auto-registered ROOT Agent uuid={} name='{}' identity={} model={} org={}",
					saved.getUuid(), name, agentIdentityUuid, modelOntologyUuid, orgUuid);
			return AgentData.dataFromRecord(saved);
		} catch (DataIntegrityViolationException e) {
			log.info("Concurrent ROOT-agent auto-registration race for (org={}, identity={}, name='{}') — re-reading winner",
					orgUuid, agentIdentityUuid, name);
			AgentData winner = repository.findByOrgIdentityAndName(
					orgUuid.toString(), agentIdentityUuid.toString(), name)
					.map(AgentData::dataFromRecord)
					.orElseThrow(() -> new RelizaException(
							"Agent auto-registration race detected but no winning row found"));
			if (winner.getModel() == null && modelOntologyUuid != null) {
				winner.setModel(modelOntologyUuid);
				return saveData(winner, wu);
			}
			return winner;
		}
	}

	/**
	 * Spawn a SUB agent under {@code parentUuid}. Resolves the parent
	 * to its owning root, creates the new SUB row with
	 * {@code rootAgent = <root.uuid>}, inherits the parent's
	 * {@code model} ontology unless {@code modelOntologyOverride} is
	 * supplied, appends the new sub uuid into the parent's
	 * {@code subAgents}, and returns the persisted child.
	 *
	 * The SUB inherits the root's {@code agentIdentity} so the V40
	 * unique index ({@code org, agentIdentity, lower(name)}) treats it
	 * as part of the same identity boundary. Two different roots can
	 * each spawn a SUB named "code-reviewer" without colliding.
	 *
	 * If {@code name} collides with another agent under the same
	 * identity, throws — within one identity, names must be unique.
	 */
	@Transactional
	public AgentData spawnSubAgent(UUID parentUuid, String name, UUID modelOntologyOverride,
			String iconKind, String color, WhoUpdated wu) throws RelizaException {
		if (parentUuid == null) throw new RelizaException("spawnSubAgent requires parentUuid");
		if (StringUtils.isBlank(name)) throw new RelizaException("Sub-agent requires a name");
		AgentData parent = getAgentData(parentUuid)
				.orElseThrow(() -> new RelizaException("Parent agent not found: " + parentUuid));
		AgentData root = resolveRoot(parent);
		if (root.getAgentIdentity() == null) {
			throw new RelizaException("Root agent " + root.getUuid()
					+ " has no agentIdentity — cannot spawn SUB without an identity scope");
		}

		Optional<AgentData> nameClash = getByOrgIdentityAndName(
				root.getOrg(), root.getAgentIdentity(), name);
		if (nameClash.isPresent()) {
			throw new RelizaException("Agent named '" + name + "' already exists under this identity");
		}

		AgentData child = new AgentData();
		child.setOrg(root.getOrg());
		child.setAgentIdentity(root.getAgentIdentity());
		child.setName(name);
		child.setModel(modelOntologyOverride != null ? modelOntologyOverride : parent.getModel());
		child.setIconKind(iconKind);
		child.setColor(color);
		child.setStatus(AgentStatus.ACTIVE);
		child.setAgentType(AgentType.SUB);
		child.setRootAgent(root.getUuid());

		Agent persisted;
		Map<String, Object> recordData = Utils.dataToRecord(child);
		try {
			persisted = save(new Agent(), recordData, wu);
		} catch (DataIntegrityViolationException e) {
			throw new RelizaException("Agent named '" + name + "' already exists under this identity");
		}
		AgentData persistedData = AgentData.dataFromRecord(persisted);

		Agent parentRow = repository.findByIdWriteLocked(parent.getUuid())
				.orElseThrow(() -> new RelizaException("Parent agent disappeared mid-spawn: " + parent.getUuid()));
		AgentData parentFresh = AgentData.dataFromRecord(parentRow);
		parentFresh.addSubAgent(persistedData.getUuid());
		saveData(parentFresh, wu);

		log.info("Spawned SUB Agent uuid={} name='{}' parent={} root={} model={}",
				persistedData.getUuid(), name, parent.getUuid(), root.getUuid(), persistedData.getModel());
		return persistedData;
	}

	/**
	 * Refine display fields the agent self-reported. Only non-null
	 * arguments are applied — null leaves the field as-is so partial
	 * edits from the dashboard don't blank other fields.
	 *
	 * Hierarchy fields ({@code agentType}, {@code rootAgent},
	 * {@code subAgents}) and the {@code model} ontology pointer are
	 * intentionally not editable here — hierarchy mutations go through
	 * {@link #spawnSubAgent}; model retargeting goes through a
	 * dedicated mutation when needed (v1 has no such mutation).
	 */
	@Transactional
	public AgentData updateAgent(UUID agentUuid, String name, String iconKind, String color,
			String notes, AgentStatus status, WhoUpdated wu) throws RelizaException {
		Agent a = repository.findByIdWriteLocked(agentUuid)
				.orElseThrow(() -> new RelizaException("Agent not found: " + agentUuid));
		AgentData ad = AgentData.dataFromRecord(a);
		if (StringUtils.isNotBlank(name)) ad.setName(name);
		if (iconKind != null) ad.setIconKind(iconKind);
		if (color != null) ad.setColor(color);
		if (notes != null) ad.setNotes(notes);
		if (status != null) ad.setStatus(status);
		return saveData(ad, wu);
	}

	@Transactional
	public AgentData saveData(AgentData ad, WhoUpdated wu) {
		Agent a = repository.findById(ad.getUuid())
				.orElseGet(() -> {
					Agent fresh = new Agent();
					fresh.setUuid(ad.getUuid() != null ? ad.getUuid() : UUID.randomUUID());
					return fresh;
				});
		Map<String, Object> recordData = Utils.dataToRecord(ad);
		Agent saved = save(a, recordData, wu);
		return AgentData.dataFromRecord(saved);
	}

	private Agent save(Agent a, Map<String, Object> recordData, WhoUpdated wu) {
		Optional<Agent> existing = repository.findById(a.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.AGENTS, a);
			a.setRevision(a.getRevision() + 1);
			a.setLastUpdatedDate(ZonedDateTime.now());
		}
		a.setRecordData(recordData);
		a = (Agent) WhoUpdated.injectWhoUpdatedData(a, wu);
		return repository.save(a);
	}
}
