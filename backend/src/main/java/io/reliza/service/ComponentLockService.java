/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AttestationData;
import io.reliza.model.AttestationData.ActorType;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentLock;
import io.reliza.model.ComponentLock.AttestationRequirement;
import io.reliza.model.ComponentLock.LockCause;
import io.reliza.model.ComponentLock.Origin;
import io.reliza.model.ComponentLock.Scope;
import io.reliza.model.ComponentLock.Status;
import io.reliza.model.ComponentLock.SubjectType;
import io.reliza.model.ComponentLock.UnlockLevel;
import io.reliza.model.WhoUpdated;
import lombok.extern.slf4j.Slf4j;

/**
 * Raising, releasing and above all enforcing locks.
 *
 * <p>Enforcement is one method, {@link #assertUnlocked}, called from the service methods behind
 * the write entry points rather than from the fetchers -- there are more fetchers than there are
 * operations, and a lock that one path forgets is not a lock.
 *
 * <p>The lock applies to everyone, administrators included. CI keys routinely carry admin rights,
 * and a lock that admin keys walk through would stop nothing that matters. An administrator's
 * power is to release it, on the record.
 */
@Slf4j
@Service
public class ComponentLockService {

	@Autowired private GetComponentService getComponentService;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private AttestationService attestationService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;

	/** The operations a lock refuses, named so the refusal can say which one was attempted. */
	public enum LockedOperation {
		VERSION_ASSIGNMENT("assign a version"),
		RELEASE_CREATION("create a release"),
		RELEASE_CONTENT("change release content");

		private final String phrase;

		LockedOperation(String phrase) {
			this.phrase = phrase;
		}

		public String phrase() {
			return phrase;
		}
	}

	/** A lock and where it was found, so a refusal can name the branch or the component. */
	public record ActiveLock(ComponentLock lock, ComponentData component, BranchData branch) {}

	/**
	 * Every active lock that applies: the component's own, plus the branch's when a branch is in
	 * play. Component locks cover all branches; branch locks cover theirs alone.
	 */
	public List<ActiveLock> activeLocks(UUID componentUuid, UUID branchUuid) {
		List<ActiveLock> out = new ArrayList<>();
		Optional<ComponentData> ocd = componentUuid == null
				? Optional.empty() : getComponentService.getComponentData(componentUuid);
		Optional<BranchData> obd = branchUuid == null
				? Optional.empty() : branchService.getBranchData(branchUuid);
		// A branch knows its component, so a caller that has only the branch still gets the
		// component's locks -- the common case on the version-assignment path.
		if (ocd.isEmpty() && obd.isPresent()) {
			ocd = getComponentService.getComponentData(obd.get().getComponent());
		}
		if (ocd.isPresent() && ocd.get().getLocks() != null) {
			for (ComponentLock l : ocd.get().getLocks()) {
				if (l != null && l.active()) out.add(new ActiveLock(l, ocd.get(), null));
			}
		}
		if (obd.isPresent() && obd.get().getLocks() != null) {
			for (ComponentLock l : obd.get().getLocks()) {
				if (l != null && l.active()) out.add(new ActiveLock(l, ocd.orElse(null), obd.get()));
			}
		}
		return out;
	}

	public boolean isLocked(UUID componentUuid, UUID branchUuid) {
		return !activeLocks(componentUuid, branchUuid).isEmpty();
	}

	/**
	 * Refuse the operation while anything is locked.
	 *
	 * <p>Throws {@link ActionRefusedException}, which the GraphQL layer surfaces as a client error
	 * with the message intact -- and the message is written for whoever reads it, which is usually
	 * an agent or a CI log, so it names the cause and the exact command that resolves it.
	 */
	public void assertUnlocked(UUID componentUuid, UUID branchUuid, LockedOperation op) {
		List<ActiveLock> locks = activeLocks(componentUuid, branchUuid);
		if (locks.isEmpty()) return;
		throw new ActionRefusedException(refusal(locks, op));
	}

	private String refusal(List<ActiveLock> locks, LockedOperation op) {
		ActiveLock first = locks.get(0);
		ComponentLock l = first.lock();
		String where = l.scope() == Scope.BRANCH && first.branch() != null
				? "Branch " + first.branch().getName() + " of component "
						+ (first.component() != null ? first.component().getName() : "?")
				: "Component " + (first.component() != null ? first.component().getName() : "?");
		StringBuilder sb = new StringBuilder();
		sb.append(where).append(" is locked, so ReARM will not ").append(op.phrase()).append(": ")
				.append(StringUtils.defaultIfEmpty(l.reason(), "no reason recorded"));
		List<LockCause> causes = l.causes() == null ? List.of() : l.causes();
		if (!causes.isEmpty()) {
			sb.append(". Unresolved: ");
			sb.append(causes.stream().limit(5)
					.map(c -> c.subjectType() + " " + c.subjectUuid()
							+ (StringUtils.isEmpty(c.detail()) ? "" : " (" + c.detail() + ")"))
					.reduce((a, b) -> a + ", " + b).orElse(""));
			int hidden = causes.size() - 5 + l.droppedCauses();
			if (hidden > 0) sb.append(" and ").append(hidden).append(" more");
		}
		// The next step, spelled out and runnable. An agent that reads this pastes it: the sha is
		// the real one and the component is named, because a message telling somebody to fill in
		// a placeholder is a message that needs a human.
		if (l.attestationRequirement() != null && l.attestationRequirement() != AttestationRequirement.NONE) {
			causes.stream().filter(c -> c.subjectType() == SubjectType.SCE).findFirst()
					.flatMap(c -> getSourceCodeEntryService.getSourceCodeEntryData(c.subjectUuid()))
					.ifPresent(sced -> sb.append(". Claim it with: rearm attest --commit ")
							.append(sced.getCommit())
							.append(" --component ")
							.append(first.component() != null ? first.component().getUuid() : "<component>")
							.append(" --verdict MINE --session <your session> --note '...'"));
		}
		// Who can clear it, and -- when that is the caller -- the command that does it. A lock an
		// agent is allowed to release itself is the whole point of the AGENT level, so telling it
		// to go and ask somebody is both wrong and the slowest possible answer.
		if (l.effectiveLevel() == UnlockLevel.AGENT) {
			sb.append(". Then release it with: rearm lock release --lock ").append(l.uuid())
					.append(" --reason '...' --session <your session>");
		} else {
			sb.append(". Or ask ").append(l.effectiveLevel() == UnlockLevel.ADMIN
							? "an administrator" : "a person")
					.append(" to release lock ").append(l.uuid());
		}
		if (locks.size() > 1) sb.append(" (").append(locks.size() - 1).append(" other locks also apply)");
		return sb.toString();
	}

	/**
	 * Refuse an attestation requirement on a hand-raised lock.
	 *
	 * <p>A manual lock has no causes and never gains any -- a policy folds causes only into the
	 * lock its own rule raised -- so a requirement here has nothing to wait for and is met the
	 * moment it is asked about. Accepting one would mean a lock that says it is waiting for a
	 * claim and releases to anyone with the level, which reads as a guarantee and is not one.
	 * Who may release a manual lock is the unlock level's job, and that works.
	 */
	private static void rejectRequirementOnManualLock(AttestationRequirement requirement)
			throws RelizaException {
		if (requirement != null && requirement != AttestationRequirement.NONE) {
			throw new RelizaException("A lock raised by hand has no causes, so an attestation "
					+ "requirement of " + requirement + " would have nothing to wait for and would "
					+ "be met the moment it was checked. Raise it with NONE and use the unlock "
					+ "level to say who may release it; requirements belong on locks a rule raised.");
		}
	}

	/** Raise a lock by hand. Defaults to the strictest release rule; nothing resolves it but a person. */
	@Transactional
	public ComponentLock lockComponent(UUID componentUuid, String reason, UnlockLevel level,
			AttestationRequirement requirement, WhoUpdated wu) throws RelizaException {
		rejectRequirementOnManualLock(requirement);
		ComponentLock lock = ComponentLock.manual(Scope.COMPONENT, null, reason,
				level == null ? UnlockLevel.ADMIN : level,
				requirement == null ? AttestationRequirement.NONE : requirement, wu);
		underRowLock(componentUuid, null, current -> new Written<>(append(current, lock), lock), wu);
		log.info("Component {} locked: {}", componentUuid, reason);
		return lock;
	}

	@Transactional
	public ComponentLock lockBranch(UUID branchUuid, String reason, UnlockLevel level,
			AttestationRequirement requirement, WhoUpdated wu) throws RelizaException {
		rejectRequirementOnManualLock(requirement);
		ComponentLock lock = ComponentLock.manual(Scope.BRANCH, branchUuid, reason,
				level == null ? UnlockLevel.ADMIN : level,
				requirement == null ? AttestationRequirement.NONE : requirement, wu);
		underRowLock(null, branchUuid, current -> new Written<>(append(current, lock), lock), wu);
		log.info("Branch {} locked: {}", branchUuid, reason);
		return lock;
	}

	/**
	 * Store a policy-raised lock, folding into an existing one from the same output event rather
	 * than raising a second: a rule that keeps failing must accumulate causes, not locks.
	 */
	@Transactional
	public ComponentLock raisePolicyLock(UUID componentUuid, UUID branchUuid, Scope scope, UUID outputEvent,
			String reason, List<LockCause> causes, UnlockLevel level, AttestationRequirement requirement,
			WhoUpdated wu) throws RelizaException {
		// Under the row lock for the whole fold: two builds of the same branch failing the same
		// rule at once would otherwise each read the list, each decide to fold, and the second
		// write would drop the first one's causes.
		return underRowLock(componentUuid, scope == Scope.BRANCH ? branchUuid : null, current -> {
			List<ComponentLock> list = new ArrayList<>(current);
			Optional<ComponentLock> fold = list.stream()
					.filter(l -> l != null && l.active() && l.origin() == Origin.POLICY
							&& outputEvent != null && outputEvent.equals(l.outputEvent()))
					.findFirst();
			if (fold.isPresent()) {
				ComponentLock updated = fold.get().withCauses(causes);
				if (updated == fold.get()) return new Written<>(null, fold.get());
				list.set(list.indexOf(fold.get()), updated);
				return new Written<>(list, updated);
			}
			ComponentLock raised = ComponentLock.policy(scope, branchUuid, reason, outputEvent, causes,
					level, requirement, wu);
			list.add(raised);
			return new Written<>(list, raised);
		}, wu);
	}

	/**
	 * Release a lock, writing the LOCK_RELEASE attestation that is the durable record of it.
	 *
	 * @param override an administrator releasing without the requirement being met. Always
	 *        recorded on the attestation; this is the escape hatch and it is never silent.
	 */
	@Transactional
	public ComponentLock releaseLock(UUID org, UUID lockUuid, String reason, boolean override,
			ActorType actorType, UUID actorUuid, UUID agentSession, WhoUpdated wu) throws RelizaException {
		Located located = locate(org, lockUuid)
				.orElseThrow(() -> new RelizaException("Lock not found: " + lockUuid));
		// `locate` reads unlocked, so it says which row to take -- not what the lock says. The
		// active check, the requirement check and the attestation all happen against the copy
		// read back under the row lock, or two concurrent releases would both pass the check and
		// both write a LOCK_RELEASE row before serializing on the overwrite.
		return mutate(located, fresh -> {
			if (!fresh.active()) throw new RelizaException("Lock is already released: " + lockUuid);
			// The requirement is half the lock. Checking only the permission tier let a writer
			// release a lock waiting on claims nobody had filed, which made `override`
			// decorative -- it is the one way past an unmet requirement, and recorded as such.
			if (!override) {
				List<LockCause> unmet = unmetCauses(org, fresh);
				if (!unmet.isEmpty()) {
					throw new ActionRefusedException("Lock " + lockUuid + " requires "
							+ (fresh.attestationRequirement() == AttestationRequirement.HUMAN
									? "a person to claim" : "a claim on")
							+ " every cause first; " + unmet.size() + " outstanding: "
							+ unmet.stream().limit(3).map(c -> StringUtils.defaultIfEmpty(c.detail(),
									String.valueOf(c.subjectUuid()))).reduce((a, b) -> a + ", " + b).orElse("")
							+ ". An admin may release it anyway with an override and a reason.");
				}
			}
			AttestationData att = attestationService.recordLockRelease(org, lockUuid,
					AttestationData.SubjectRef.of(located.componentUuid(), located.componentName(), null, null),
					actorType, actorUuid, agentSession, reason, override, wu);
			return fresh.released(att.getUuid());
		}, wu);
	}

	/**
	 * Causes still waiting for the claim the lock's requirement asks for.
	 *
	 * <p>Shared rather than private to the policy side, because the manual release path has to
	 * ask the same question -- that it did not was the bug that made `override` meaningless.
	 * HUMAN means a person: an agent's claim is recorded and shown, and does not satisfy it.
	 *
	 * <p>Which causes count depends on what the lock is made of. When there are commits to own,
	 * the release cause is context -- it says which build failed the rule, and requiring a claim
	 * on it as well would mean attesting twice to resolve one mistake. When there are no commits,
	 * which is the shape a vulnerability rule raises, the release is the only thing there is to
	 * claim, and a requirement that governed nothing would be vacuously met: the lock would ask
	 * for an attestation and then let any writer release it with none on record. So the
	 * requirement falls back to the release cause, which the user lane can attest to.
	 *
	 * <p>The consequence for rule authors: the programmatic lane only attests commits, so a lock
	 * an agent must be able to release on its own should be raised with requirement NONE unless
	 * it is certain to carry commit causes.
	 */
	public List<LockCause> unmetCauses(UUID org, ComponentLock lock) {
		AttestationRequirement requirement = lock.attestationRequirement() == null
				? AttestationRequirement.NONE : lock.attestationRequirement();
		if (requirement == AttestationRequirement.NONE) return List.of();
		List<LockCause> causes = lock.causes() == null ? List.of() : lock.causes();
		boolean hasCommits = causes.stream().anyMatch(c -> c != null && c.subjectType() == SubjectType.SCE);
		SubjectType governing = hasCommits ? SubjectType.SCE : SubjectType.RELEASE;
		List<LockCause> out = new ArrayList<>();
		for (LockCause c : causes) {
			if (c == null || c.subjectType() != governing) continue;
			AttestationData.SubjectType subject = governing == SubjectType.SCE
					? AttestationData.SubjectType.SCE : AttestationData.SubjectType.RELEASE;
			boolean claimed = attestationService
					.attestationsOfSubject(org, subject, c.subjectUuid())
					.stream().filter(AttestationData::claimsSubject)
					.anyMatch(a -> requirement != AttestationRequirement.HUMAN
							|| a.getActorType() == ActorType.USER);
			if (!claimed) out.add(c);
		}
		return out;
	}

	/** Where a lock lives, so a release can write it back to the right row. */
	public record Located(ComponentLock lock, UUID componentUuid, String componentName, UUID branchUuid) {}

	public Optional<Located> locate(UUID org, UUID lockUuid) {
		for (ComponentData cd : componentsOf(org)) {
			if (cd.getLocks() != null) {
				for (ComponentLock l : cd.getLocks()) {
					if (l != null && lockUuid.equals(l.uuid())) {
						return Optional.of(new Located(l, cd.getUuid(), cd.getName(), null));
					}
				}
			}
			for (BranchData bd : branchService.listBranchDataOfComponent(cd.getUuid(), StatusEnum.ACTIVE)) {
				if (bd.getLocks() == null) continue;
				for (ComponentLock l : bd.getLocks()) {
					if (l != null && lockUuid.equals(l.uuid())) {
						return Optional.of(new Located(l, cd.getUuid(), cd.getName(), bd.getUuid()));
					}
				}
			}
		}
		return Optional.empty();
	}

	/** Every active lock in the org, with where it lives. */
	public List<Located> listActive(UUID org) {
		List<Located> out = new ArrayList<>();
		if (org == null) return out;
		for (ComponentData cd : componentsOf(org)) {
			if (cd.getLocks() != null) {
				for (ComponentLock l : cd.getLocks()) {
					if (l != null && l.active()) out.add(new Located(l, cd.getUuid(), cd.getName(), null));
				}
			}
			for (BranchData bd : branchService.listBranchDataOfComponent(cd.getUuid(), StatusEnum.ACTIVE)) {
				if (bd.getLocks() == null) continue;
				for (ComponentLock l : bd.getLocks()) {
					if (l != null && l.active()) {
						out.add(new Located(l, cd.getUuid(), cd.getName(), bd.getUuid()));
					}
				}
			}
		}
		return out;
	}

	/**
	 * Raise a lock's escalation floor. Monotonic: this only ever makes a lock harder to release,
	 * which is why it is safe to call from anywhere that notices a contested cause.
	 */
	@Transactional
	public ComponentLock escalate(UUID org, UUID lockUuid, UnlockLevel level) throws RelizaException {
		Located located = locate(org, lockUuid)
				.orElseThrow(() -> new RelizaException("Lock not found: " + lockUuid));
		return mutate(located, fresh -> fresh.withEscalation(level), WhoUpdated.getAutoWhoUpdated());
	}

	/**
	 * Every component and product of the org. Both types are named explicitly: the varargs
	 * listing iterates the types it is given, so calling it with none returns nothing at all.
	 */
	private List<ComponentData> componentsOf(UUID org) {
		return componentService.listComponentDataByOrganization(org,
				ComponentData.ComponentType.COMPONENT, ComponentData.ComponentType.PRODUCT);
	}

	/** What a locked body decided: the list to write (null to write nothing) and what to return. */
	private record Written<T>(List<ComponentLock> list, T value) {}

	@FunctionalInterface
	private interface LockedBody<T> {
		Written<T> apply(List<ComponentLock> current) throws RelizaException;
	}

	/**
	 * Read the row's lock list under a write lock, decide, write back, all in one transaction.
	 *
	 * <p>Every mutation goes through here, because the lock list is a read-modify-write on JSONB
	 * and the interesting races are decided on the read, not the write: two releases that each
	 * read an unlocked list both see the lock active and both write a LOCK_RELEASE attestation,
	 * and two policy raises from parallel builds each fold into the copy they read, so the second
	 * write silently drops the first one's causes. Taking the row lock before the read closes
	 * both. The caller's body runs while the row is held, so anything it decides from `current`
	 * -- including writing an attestation -- is decided against what is actually stored.
	 */
	private <T> T underRowLock(UUID componentUuid, UUID branchUuid, LockedBody<T> body, WhoUpdated wu)
			throws RelizaException {
		List<ComponentLock> current = branchUuid != null
				? branchService.getBranchDataWriteLocked(branchUuid).getLocks()
				: componentService.getComponentDataWriteLocked(componentUuid).getLocks();
		Written<T> written = body.apply(current == null ? List.of() : current);
		if (written.list() != null) {
			if (branchUuid != null) {
				branchService.setLocks(branchUuid, prune(written.list()), wu);
			} else {
				componentService.setLocks(componentUuid, prune(written.list()), wu);
			}
		}
		return written.value();
	}

	@FunctionalInterface
	private interface LockMutation {
		ComponentLock apply(ComponentLock fresh) throws RelizaException;
	}

	/**
	 * Apply a change to one lock, re-reading it under the row lock first.
	 *
	 * <p>The {@code Located} handed in comes from an unlocked scan, so it is used for where the
	 * lock lives and nothing else -- the lock the body sees is the stored one.
	 */
	private ComponentLock mutate(Located located, LockMutation body, WhoUpdated wu) throws RelizaException {
		return underRowLock(located.componentUuid(), located.branchUuid(), current -> {
			ComponentLock fresh = current.stream()
					.filter(l -> l != null && located.lock().uuid().equals(l.uuid()))
					.findFirst()
					.orElseThrow(() -> new RelizaException("Lock not found: " + located.lock().uuid()));
			ComponentLock updated = body.apply(fresh);
			if (updated == fresh) return new Written<>(null, fresh);
			return new Written<>(replace(current, updated), updated);
		}, wu);
	}

	private static List<ComponentLock> append(List<ComponentLock> existing, ComponentLock lock) {
		List<ComponentLock> list = existing == null ? new LinkedList<>() : new ArrayList<>(existing);
		list.add(lock);
		return list;
	}

	private static List<ComponentLock> replace(List<ComponentLock> existing, ComponentLock lock) {
		List<ComponentLock> list = existing == null ? new LinkedList<>() : new ArrayList<>(existing);
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) != null && lock.uuid().equals(list.get(i).uuid())) {
				list.set(i, lock);
				return list;
			}
		}
		list.add(lock);
		return list;
	}

	/**
	 * Keep every active lock and only the most recent few released ones.
	 *
	 * <p>These rows are read on every build, so an unbounded history here would be paid for by
	 * every version assignment forever. The history that matters is the attestation log, which is
	 * append-only and indexed for exactly that question.
	 */
	private static List<ComponentLock> prune(List<ComponentLock> locks) {
		if (locks == null) return new LinkedList<>();
		List<ComponentLock> active = locks.stream().filter(l -> l != null && l.active()).toList();
		List<ComponentLock> released = locks.stream()
				.filter(l -> l != null && l.status() == Status.RELEASED)
				.sorted(Comparator.comparing(ComponentLock::releasedAt,
						Comparator.nullsFirst(Comparator.reverseOrder())))
				.limit(ComponentLock.MAX_RELEASED_LOCKS)
				.toList();
		List<ComponentLock> out = new ArrayList<>(active);
		out.addAll(released);
		return out;
	}
}
