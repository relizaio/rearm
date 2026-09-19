/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * The external identity behind a FEDERATED key row: one repository of one provider, trusted by
 * the org's rules. Ids are pinned on first use so a renamed or recreated repository cannot
 * inherit the trust; an admin clears the pin to accept the new one.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FederatedIdentity implements Serializable {
	private static final long serialVersionUID = 1L;

	private FederatedTrustRule.Provider provider;
	private String issuer;
	private String owner;
	/** owner/name as the provider reports it */
	private String repository;
	/** host/owner/name in ReARM's cleaned VCS form, what the calling repository's components are matched on */
	private String repositoryUri;
	private String repositoryId;
	private String ownerId;
	private ZonedDateTime pinnedDate;
	private String lastRef;
	private String lastRunId;
	private String lastActor;
}
