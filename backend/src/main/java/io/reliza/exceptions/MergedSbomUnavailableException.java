/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.tea.Rebom.RebomOptions;

/**
 * A release's merged SBOM cannot be served, and why.
 *
 * <p>Two different answers used to collapse into one "No SBOMs found!": nothing in the release's
 * closure carries an SBOM, or a component release's merge failed and was dropped on the floor.
 * The first is a fact about the data, the second is a fault, and a caller (the VDR enrichment,
 * the TEA aggregate) has to tell them apart -- so it switches on {@link #reason()}, never on the
 * message. The message is for the person reading the GraphQL error: it names the release that
 * was asked for and every component release that was looked at.
 *
 * <p>A RelizaException, so GraphQLExceptionHandlers carries the message to the caller as
 * BAD_REQUEST exactly as before.
 */
public class MergedSbomUnavailableException extends RelizaException {

	private static final long serialVersionUID = -3528311924170931735L;

	public enum Reason {
		/** Nothing in the release's closure carries an SBOM under the requested filters. */
		NO_SBOM_ARTIFACTS,
		/** At least one component release's merge failed; nothing was merged for the product. */
		CHILD_MERGE_FAILED
	}

	public enum ChildBomStatus {
		MERGED,
		NO_SBOM,
		FAILED
	}

	/**
	 * What happened to one component release of a product during the merge.
	 *
	 * @param rebomId the child's merged bom when {@code status} is MERGED, else null
	 * @param detail the failure message when {@code status} is FAILED, else null
	 */
	public record ChildBomOutcome(UUID release, String componentName, String version,
			ChildBomStatus status, UUID rebomId, String detail) implements Serializable {
		private static final long serialVersionUID = 1L;

		String label() {
			return componentName + " " + version + " (" + release + ")";
		}
	}

	private final Reason reason;
	private final UUID release;
	private final List<ChildBomOutcome> children;

	private MergedSbomUnavailableException(String message, Reason reason, UUID release,
			List<ChildBomOutcome> children) {
		super(message);
		this.reason = reason;
		this.release = release;
		this.children = null == children ? List.of() : List.copyOf(children);
	}

	public Reason reason() {
		return reason;
	}

	/** The release the merged SBOM was asked for. */
	public UUID release() {
		return release;
	}

	/** One outcome per component release looked at, in unwind order; empty for a component release. */
	public List<ChildBomOutcome> children() {
		return children;
	}

	public static MergedSbomUnavailableException noSbomArtifacts(ReleaseData rd, ComponentData cd,
			RebomOptions options, List<ArtifactCoverageType> exclude, List<ChildBomOutcome> children) {
		String filters = "under belongsTo=" + belongsToLabel(options) + ", excluding coverage "
				+ coverageLabel(exclude);
		String message;
		if (null != cd && cd.getType() == ComponentType.PRODUCT) {
			String head = "No SBOMs found: product release " + releaseLabel(rd, cd)
					+ " carries no SBOM artifact itself and ";
			if (null == children || children.isEmpty()) {
				message = head + "it has no component releases " + filters;
			} else {
				message = head + "none of its " + children.size() + " component releases carries one "
						+ filters + ": " + children.stream().map(ChildBomOutcome::label)
								.collect(Collectors.joining(", "));
			}
		} else {
			message = "No SBOMs found: release " + releaseLabel(rd, cd) + " carries no SBOM artifact "
					+ filters;
		}
		return new MergedSbomUnavailableException(message, Reason.NO_SBOM_ARTIFACTS, rd.getUuid(), children);
	}

	public static MergedSbomUnavailableException childMergeFailed(ReleaseData rd, ComponentData cd,
			List<ChildBomOutcome> children) {
		List<ChildBomOutcome> failed = children.stream()
				.filter(c -> c.status() == ChildBomStatus.FAILED).toList();
		String message = "Merged SBOM for product release " + releaseLabel(rd, cd) + " could not be built: "
				+ failed.size() + " of " + children.size() + " component releases failed: "
				+ failed.stream().map(c -> c.label() + ": " + c.detail()).collect(Collectors.joining("; "));
		return new MergedSbomUnavailableException(message, Reason.CHILD_MERGE_FAILED, rd.getUuid(), children);
	}

	private static String releaseLabel(ReleaseData rd, ComponentData cd) {
		String name = null != cd && null != cd.getName() ? cd.getName() : String.valueOf(rd.getComponent());
		return name + " " + rd.getVersion() + " (" + rd.getUuid() + ")";
	}

	private static String belongsToLabel(RebomOptions options) {
		return null == options || null == options.belongsTo() ? "ANY" : options.belongsTo().name();
	}

	private static String coverageLabel(List<ArtifactCoverageType> exclude) {
		if (null == exclude || exclude.isEmpty()) return "none";
		return exclude.stream().map(Enum::name).collect(Collectors.joining(","));
	}
}
