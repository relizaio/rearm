/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchArchiveOutcome;
import io.reliza.model.BranchArchiveResult;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Archive many branches (or feature sets) in one call, reporting PER BRANCH.
 *
 * <p>A class of its own because {@link AuthorizationService} already depends on
 * {@link BranchService}, and the per-item authorization must use the real bean. Each write goes
 * through {@link BranchService#archiveBranchIsolated} on the other bean, so REQUIRES_NEW applies
 * without a self reference: one failed write undoes nothing else.
 */
@Slf4j
@Service
public class BranchBulkArchiveService {

	/**
	 * The most uuids one call may carry, counted before de-duplication and refused outright,
	 * never truncated. What it bounds is amplification: the rate limiter charges per request,
	 * so this is what limits the writes one request buys. The UI pages its selection at this
	 * size, so no UI call ever meets the bound.
	 */
	public static final int BULK_ARCHIVE_MAX_IDS = 200;

	static final String FAILED_GENERIC_MESSAGE = "could not be archived; see server logs for this branch";

	@Autowired
	private BranchService branchService;

	@Autowired
	private AuthorizationService authorizationService;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * One result per distinct uuid, in input order. Never throws for a single branch: a refused,
	 * skipped or failed branch is an outcome. The whole call is refused only by the bound and,
	 * as archiveBranch is, by a sealed or unlicensed installation.
	 *
	 * @throws IllegalArgumentException more than {@link #BULK_ARCHIVE_MAX_IDS} uuids
	 * @throws RelizaException the system is sealed or the licence is invalid
	 */
	public List<BranchArchiveResult> archiveBranches(List<UUID> uuids, UserData ud, WhoUpdated wu)
			throws RelizaException {
		if (null == uuids || uuids.isEmpty()) return List.of();
		// Bound here as well as at the resolver, so the bound is a property of the WRITE, not
		// of one entry point into it.
		if (uuids.size() > BULK_ARCHIVE_MAX_IDS) {
			throw new IllegalArgumentException("archiveBranches is limited to " + BULK_ARCHIVE_MAX_IDS
					+ " branches per call; " + uuids.size() + " were supplied");
		}
		// The single mutation passes this gate inside isUserAuthorizedForObjectGraphQL; the
		// per-item check below is the non-throwing entry point, which does not, so the gate is
		// called by name, once, before any read: a sealed or unlicensed installation refuses
		// the whole batch with the single mutation's message.
		authorizationService.validateSystemOperational(CallType.WRITE);

		Set<UUID> requested = new LinkedHashSet<>(uuids);
		Map<UUID, Branch> branches = branchService.getBranches(requested).stream()
				.collect(Collectors.toMap(Branch::getUuid, Function.identity()));

		List<BranchArchiveResult> results = new ArrayList<>(requested.size());
		for (UUID uuid : requested) {
			results.add(archiveOne(uuid, branches.get(uuid), ud, wu));
		}
		return results;
	}

	private BranchArchiveResult archiveOne(UUID uuid, Branch branch, UserData ud, WhoUpdated wu) {
		if (null == branch) {
			return BranchArchiveResult.skipped(uuid, BranchArchiveOutcome.NOT_FOUND);
		}
		BranchData bd = BranchData.branchDataFromDbRecord(branch);
		// Another organization's branch answers exactly what an unknown uuid answers, so the
		// outcome list cannot tell a caller whether a uuid exists elsewhere.
		if (!ud.isGlobalAdmin() && !ud.isInOrganization(bd.getOrg())) {
			return BranchArchiveResult.skipped(uuid, BranchArchiveOutcome.NOT_FOUND);
		}
		// archiveBranch's own question, on this row's own organization.
		if (!authorizationService.isUserAuthorizedForObject(ud, PermissionFunction.RESOURCE,
				PermissionScope.BRANCH, uuid, List.of(bd), CallType.WRITE)) {
			return BranchArchiveResult.skipped(uuid, BranchArchiveOutcome.FORBIDDEN);
		}
		if (BranchType.BASE == bd.getType()) {
			return BranchArchiveResult.skipped(uuid, BranchArchiveOutcome.SKIPPED_BASE);
		}
		if (StatusEnum.ARCHIVED == bd.getStatus()) {
			return BranchArchiveResult.skipped(uuid, BranchArchiveOutcome.SKIPPED_ARCHIVED);
		}
		try {
			// false means the row is gone; no branch delete path exists, so it is not expected
			// after the batch read found it, and it would mean what NOT_FOUND says.
			return Boolean.TRUE.equals(branchService.archiveBranchIsolated(uuid, wu))
					? BranchArchiveResult.archived(uuid)
					: BranchArchiveResult.skipped(uuid, BranchArchiveOutcome.NOT_FOUND);
		} catch (RelizaException e) {
			// The name/project validation, or a branch that became base after the read: both
			// thrown before any write and written for a person.
			return BranchArchiveResult.failed(uuid, e.getMessage());
		} catch (RuntimeException e) {
			log.error("Bulk archive failed for branch {}", uuid, e);
			// The resolver is a web request, so open-in-view binds one persistence context
			// across the loop; discard it after a failed write so the items after this one
			// do not fail against a poisoned context.
			entityManager.clear();
			return BranchArchiveResult.failed(uuid, FAILED_GENERIC_MESSAGE);
		}
	}
}
