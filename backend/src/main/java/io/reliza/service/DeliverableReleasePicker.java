/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import io.reliza.model.BranchData.BranchType;
import io.reliza.model.ReleaseData;

/**
 * Chooses the release a deliverable should resolve to when several releases
 * carry it in their base variant. Same digest on different branches is normal
 * for some build systems (rebuilds, re-tags, promotion builds), and legacy
 * Reliza Hub data carries the same artifact on a release and its proxy.
 *
 * <p>Priority, first match wins, newest release first inside each tier:
 * <ol>
 *   <li>a release on one of the {@code preferredBranches} -- for instance
 *       matching these are the branches the instance plan actually depends
 *       on (feature sets and their dependency branches), so the deployed
 *       release lines up with the product releases the plan expects;</li>
 *   <li>a release on a BASE branch of its component;</li>
 *   <li>the newest release.</li>
 * </ol>
 * Pure function so it can be unit tested without a Spring context.
 */
public final class DeliverableReleasePicker {

	private DeliverableReleasePicker() {}

	public static Optional<ReleaseData> pick(List<ReleaseData> candidates, Set<UUID> preferredBranches,
			Function<UUID, Optional<BranchType>> branchType) {
		if (candidates == null || candidates.isEmpty()) {
			return Optional.empty();
		}
		if (candidates.size() == 1) {
			return Optional.of(candidates.get(0));
		}
		List<ReleaseData> newestFirst = candidates.stream()
				.sorted(Comparator.comparing(ReleaseData::getCreatedDate,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
		if (preferredBranches != null && !preferredBranches.isEmpty()) {
			Optional<ReleaseData> onPreferred = newestFirst.stream()
					.filter(r -> r.getBranch() != null && preferredBranches.contains(r.getBranch()))
					.findFirst();
			if (onPreferred.isPresent()) {
				return onPreferred;
			}
		}
		Optional<ReleaseData> onBase = newestFirst.stream()
				.filter(r -> r.getBranch() != null
						&& branchType.apply(r.getBranch()).map(t -> t == BranchType.BASE).orElse(false))
				.findFirst();
		if (onBase.isPresent()) {
			return onBase;
		}
		return Optional.of(newestFirst.get(0));
	}
}
