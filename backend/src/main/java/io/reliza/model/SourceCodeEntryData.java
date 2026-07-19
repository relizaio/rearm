/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.dto.SceDto;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * This class references specific entry inside a release, i.e. details on specific project
 * within a larger release.
 * It is also a parent for Artifacts, so that zero or more artifacts belong to a release entry.
 * The idea here is that release entry references specific commit or vcs tag, however we assume that 
 * multiple artifacts could be built of this task. For example, we could build both a jar file and a 
 * docker image with the same jar file inside, thus having 2 artifacts in this case per release entry.
 * @author pavel
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourceCodeEntryData extends RelizaDataParent implements RelizaObject {
	
	private static final String DEMO_COMMIT_EMAIL = "info@reliza.io";

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;
	
	@Setter(AccessLevel.PRIVATE)
	private UUID branch; // source code entry may reference a branch of a project
	
	@Setter(AccessLevel.PRIVATE)
	private UUID vcs; // source code entry must reference a vcs repository
	
	@JsonProperty(CommonVariables.VCS_BRANCH_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String vcsBranch; // source code entry may reference vcs branch
	
	@JsonProperty(CommonVariables.COMMIT_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String commit; // i.e. svn revision or git commit hash
	
	@JsonProperty(CommonVariables.COMMIT_MESSAGE_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String commitMessage; // actual commit message
	
	@JsonProperty(CommonVariables.COMMIT_AUTHOR_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String commitAuthor; // commit author name
	
	@JsonProperty(CommonVariables.COMMIT_EMAIL_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String commitEmail; // commit author email
	
	@JsonProperty(CommonVariables.VCS_TAG_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String vcsTag; // i.e. svn tag or git tag
	
	@JsonProperty(CommonVariables.NOTES_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String notes; // any additional meta may go here
	
	@Setter(AccessLevel.PRIVATE)
	private UUID org;
	
	@JsonProperty(CommonVariables.DATE_ACTUAL_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private ZonedDateTime dateActual;

	@JsonProperty(CommonVariables.TICKET_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private UUID ticket;

	@Setter()
	@JsonProperty(CommonVariables.ARTIFACTS_FIELD)
	private List<SCEArtifact> artifacts = new ArrayList<>();

	/**
	 * Resolved {@link AgentSession} this commit was authored under. Set
	 * by {@link io.reliza.service.SourceCodeEntryService} from the
	 * {@code ReARM-Agentic-Session:} commit trailer (PR 2, see
	 * {@code ai-plans/agentic/README.md} §6). Null on non-agent
	 * commits and when the trailer references a session ReARM doesn't
	 * recognise (logged but not fatal).
	 */
	@Setter
	@JsonProperty
	private UUID agentSession;

	/**
	 * Leaf agent uuid from the {@code ReARM-Agent:} commit trailer.
	 * May be a SUB agent — distinct from {@link AgentSessionData#getAgent()}
	 * which is always the ROOT. Preserved here so the monitoring
	 * read-side can show which specific sub-agent authored each commit
	 * even though the session is owned by the root.
	 */
	@Setter
	@JsonProperty
	private UUID agent;

	/**
	 * Outcome of the agent-attribution trailer resolution. Set by
	 * {@link io.reliza.service.SourceCodeEntryService#resolveAndAttributeTrailers}
	 * on every code path so policies can gate on attribution failure
	 * symmetrically with how {@code commit.signature.state} is used for
	 * unsigned commits. Null on legacy rows created before this field
	 * existed; the CEL surface normalises null to {@code UNATTRIBUTED}.
	 */
	@Setter
	@JsonProperty
	private AttributionState attributionState;

	/**
	 * Human-readable reason set when {@link #attributionState} is
	 * {@link AttributionState#REJECTED}. Surfaces in audit / UI tooltips
	 * so the operator knows whether the agent uuid was malformed, the
	 * session id failed lookup, the orgs disagreed, etc. Null for the
	 * resolved and unattributed paths.
	 */
	@Setter
	@JsonProperty
	private String attributionReason;

	public enum AttributionState {
		/** No agent-attribution trailers present on the commit. */
		UNATTRIBUTED,
		/** Both trailers parsed and agent + session resolved cleanly. */
		RESOLVED,
		/** Trailers were present but the resolver rejected the claim
		 *  (parser error, agent / session not found, cross-org, malformed
		 *  clientSessionId, etc.) — {@link #attributionReason} explains
		 *  which. Suitable target for a release-level CEL gate. */
		REJECTED
	}

	private SourceCodeEntryData () {}
	
	/**
	 * componentUuid semantics: a concrete uuid means the artifact was attached in
	 * the context of that component (monorepo case -- each component tags its own
	 * SCE artifacts); null means the artifact is commit-scoped and applies to
	 * every release referencing this SCE (signatures / signed payloads -- the
	 * content is a property of the commit, not of any one component).
	 */
	public record SCEArtifact (UUID artifactUuid, UUID componentUuid) {}

	/**
	 * Artifact types whose content is a property of the commit itself, so one
	 * copy on the canonical (vcs, commit) SCE serves every component sharing
	 * that commit. Kept in sync with the semantic dedupe in
	 * SourceCodeEntryService, which collapses byte-identical copies of these
	 * across components.
	 */
	public static final java.util.Set<ArtifactData.ArtifactType> COMMIT_SCOPED_ARTIFACT_TYPES =
			java.util.Set.of(ArtifactData.ArtifactType.SIGNATURE, ArtifactData.ArtifactType.SIGNED_PAYLOAD);

	/** Resolve the component tag for a new SCE artifact: null (commit-scoped) for
	 * signature-class types, the attaching component otherwise. */
	public static UUID sceArtifactComponentTag(ArtifactData.ArtifactType type, UUID componentUuid) {
		return (type != null && COMMIT_SCOPED_ARTIFACT_TYPES.contains(type)) ? null : componentUuid;
	}

	public static SourceCodeEntryData scEntryDataFactory(SceDto sceDto) {
		SourceCodeEntryData sced = new SourceCodeEntryData();
		sced.setBranch(sceDto.getBranch());
		sced.setVcs(sceDto.getVcs());
		sced.setOrg(sceDto.getOrganizationUuid());
		sced.setVcsBranch(sceDto.getVcsBranch());
		sced.setCommit(sceDto.getCommit());
		sced.setCommitMessage(sceDto.getCommitMessage());
		sced.setVcsTag(sceDto.getVcsTag());
		sced.setNotes(sceDto.getNotes());
		sced.setDateActual(sceDto.getDate());
		sced.setTicket(sceDto.getTicket());
		sced.setCommitAuthor(sceDto.getCommitAuthor());
		sced.setCommitEmail(sceDto.getCommitEmail());
		if (null != sceDto.getArtifacts()) sced.setArtifacts(sceDto.getArtifacts());
		return sced;
	}
	
	public static SourceCodeEntryData obtainNullSceData() {
		SourceCodeEntryData sced = new SourceCodeEntryData();
		sced.uuid = new UUID(0,0);
		sced.setCommitMessage(CommonVariables.DETAILS_UNAVAILABLE_MESSAGE);
		return sced;
	}

	public static SourceCodeEntryData dataFromRecord (SourceCodeEntry sce) {
		return dataFromRecord(sce, false);
	}
	
	public static SourceCodeEntryData dataFromRecord (SourceCodeEntry sce, boolean isDemo) {
		if (sce.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Release entry schema version is " + sce.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = sce.getRecordData();
		SourceCodeEntryData red = Utils.OM.convertValue(recordData, SourceCodeEntryData.class);
		red.setUuid(sce.getUuid());
		red.setCreatedDate(sce.getCreatedDate());
		red.setUpdatedDate(sce.getLastUpdatedDate());
		if (isDemo) {
			red.setCommitEmail(DEMO_COMMIT_EMAIL);
		}
		return red;
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * On the merge path in {@link io.reliza.service.SourceCodeEntryService},
	 * when an existing SCE row already has a commit-metadata field
	 * populated and the incoming sceDto leaves the same field blank/null,
	 * copy the existing value forward so a partial-metadata caller doesn't
	 * wipe earlier complete values. Lives here because the scalar setters
	 * are AccessLevel.PRIVATE — only methods on this class can mutate them.
	 *
	 * <p>Trailer-attribution fields (agent, agentSession) are NOT touched —
	 * they're resolved fresh by the parser on each create.
	 */
	public void preserveScalarsFrom(SourceCodeEntryData existing) {
		if (isBlank(this.commitMessage) && !isBlank(existing.commitMessage)) {
			this.commitMessage = existing.commitMessage;
		}
		if (isBlank(this.commitAuthor) && !isBlank(existing.commitAuthor)) {
			this.commitAuthor = existing.commitAuthor;
		}
		if (isBlank(this.commitEmail) && !isBlank(existing.commitEmail)) {
			this.commitEmail = existing.commitEmail;
		}
		if (this.dateActual == null && existing.dateActual != null) {
			this.dateActual = existing.dateActual;
		}
		if (isBlank(this.vcsBranch) && !isBlank(existing.vcsBranch)) {
			this.vcsBranch = existing.vcsBranch;
		}
		if (isBlank(this.vcsTag) && !isBlank(existing.vcsTag)) {
			this.vcsTag = existing.vcsTag;
		}
		if (isBlank(this.notes) && !isBlank(existing.notes)) {
			this.notes = existing.notes;
		}
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
