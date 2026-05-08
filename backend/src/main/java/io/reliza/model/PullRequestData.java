/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.common.Utils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * First-class PullRequest entity. Represents a pull-request / merge-request /
 * change-list at the SCM. Identity is ({@link #targetVcsRepository},
 * {@link #identity}) — string identity supports GitHub PR numbers,
 * GitLab MR iids, and Gerrit change-IDs uniformly.
 *
 * Aggregation across components in monorepos is logical: a release is
 * attributed to this PR when its head SCE UUID appears in {@link #commits}.
 * No FK is enforced — the read-time aggregator queries
 * `releases.record_data->>'sourceCodeEntry'` against the commits list.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PullRequestData extends RelizaDataParent implements RelizaObject {

	public enum PullRequestUpdateScope {
		PR_CREATED,
		STATE,
		TITLE,
		COMMITS,
		HEAD,
		SOURCE_BRANCH,
		TARGET_BRANCH,
		ENDPOINT,
		MERGED,
		CLOSED
	}

	public enum PullRequestUpdateAction {
		ADDED,
		REMOVED,
		CHANGED
	}

	public record PullRequestUpdateEvent(PullRequestUpdateScope pus, PullRequestUpdateAction pua,
			String oldValue, String newValue, UUID objectId, ZonedDateTime date, WhoUpdated wu) {}

	/**
	 * Outbound: the aggregator's decision at a point in time, paired with
	 * the head SCE it was computed against and the set of releases it
	 * folded in. Stored append-only — the read path picks the latest
	 * entry whose sourceCodeEntry matches the current head.
	 */
	public record PullRequestValidationEvent(ZonedDateTime date, ValidationState validationState,
			String comment, UUID sourceCodeEntry, List<UUID> attributedReleases, WhoUpdated wu) {}

	/**
	 * Inbound: a release contributed an outcome (success/failure) toward
	 * this PR. The aggregator keys these by release UUID (latest wins per
	 * release) and matches by SCE.
	 */
	public record ReleaseValidationEvent(UUID release, ZonedDateTime date,
			ValidationState validationResult, WhoUpdated wu) {}

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	/**
	 * Target VCS repository — owns identity. For same-repo PRs (the common
	 * case) this equals {@link #sourceVcsRepository}.
	 */
	@JsonProperty
	private UUID targetVcsRepository;

	/**
	 * Source VCS repository — set on cross-repo / fork PRs. V1 stores it
	 * for forensic value but does not aggregate cross-repo releases.
	 */
	@JsonProperty
	private UUID sourceVcsRepository;

	/**
	 * SCM-side identifier as a string. GitHub: "42". GitLab: "42". Gerrit:
	 * "I8473b95934b5732ac55d26311a706c9c2bde9940". Stored opaque to ReARM —
	 * uniqueness is enforced jointly with {@link #targetVcsRepository}.
	 */
	@JsonProperty
	private String identity;

	@JsonProperty(CommonVariables.STATE_FIELD)
	private PullRequestState state;

	@JsonProperty(CommonVariables.TITLE_FIELD)
	private String title;

	@JsonProperty
	private String sourceBranchName;

	@JsonProperty
	private String targetBranchName;

	@JsonProperty(CommonVariables.ENDPOINT_FIELD)
	private URI endpoint;

	/**
	 * Append-only list of SCE UUIDs known to belong to this PR. Order is
	 * insertion order; the head is the last entry (latest commit observed
	 * at the SCM tip). Aggregation matches releases by head SCE.
	 */
	@JsonProperty(CommonVariables.COMMITS_FIELD)
	private List<UUID> commits = new LinkedList<>();

	/**
	 * SCM-side creation timestamp of the PR. Distinct from the parent
	 * {@code createdDate} (row creation in this database). Stored under
	 * its own JSON key to avoid colliding with the inherited timestamp.
	 */
	@JsonProperty
	private ZonedDateTime prCreatedDate;

	@JsonProperty(CommonVariables.CLOSED_DATE_FIELD)
	private ZonedDateTime closedDate;

	@JsonProperty(CommonVariables.MERGED_DATE_FIELD)
	private ZonedDateTime mergedDate;

	@JsonIgnore
	private List<PullRequestValidationEvent> prValidationEvents = new LinkedList<>();

	@JsonIgnore
	private List<ReleaseValidationEvent> releaseValidationEvents = new LinkedList<>();

	@JsonIgnore
	private List<PullRequestUpdateEvent> updateEvents = new LinkedList<>();

	public void addPrValidationEvent(PullRequestValidationEvent ev) {
		this.prValidationEvents.add(ev);
	}

	public void addReleaseValidationEvent(ReleaseValidationEvent ev) {
		this.releaseValidationEvents.add(ev);
	}

	public void addUpdateEvent(PullRequestUpdateEvent ev) {
		this.updateEvents.add(ev);
	}

	@Override
	public UUID getResourceGroup() {
		// Pull requests don't carry their own resource group; access is
		// scoped via the owning org and (downstream) the target VCS
		// repository's resource group.
		return null;
	}

	public static PullRequestData dataFromRecord(PullRequest pr) {
		if (pr.getSchemaVersion() != 0) {
			throw new IllegalStateException("PullRequest schema version is " + pr.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = pr.getRecordData();
		PullRequestData prd = Utils.OM.convertValue(recordData, PullRequestData.class);
		prd.setUuid(pr.getUuid());
		prd.setCreatedDate(pr.getCreatedDate());
		if (pr.getPrValidationEvents() != null) {
			prd.setPrValidationEvents(pr.getPrValidationEvents().stream()
					.map(m -> Utils.OM.convertValue(m, PullRequestValidationEvent.class))
					.collect(Collectors.toCollection(LinkedList::new)));
		}
		if (pr.getReleaseValidationEvents() != null) {
			prd.setReleaseValidationEvents(pr.getReleaseValidationEvents().stream()
					.map(m -> Utils.OM.convertValue(m, ReleaseValidationEvent.class))
					.collect(Collectors.toCollection(LinkedList::new)));
		}
		if (pr.getUpdateEvents() != null) {
			prd.setUpdateEvents(pr.getUpdateEvents().stream()
					.map(m -> Utils.OM.convertValue(m, PullRequestUpdateEvent.class))
					.collect(Collectors.toCollection(LinkedList::new)));
		}
		return prd;
	}
}
