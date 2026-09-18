/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * Which identity tokens a trust rule accepts. {@code owner} is required; every list is a
 * constraint only when non-empty. Repository entries are globs matched against the bare name
 * and against {@code owner/name}; ref entries are globs against the full ref
 * ({@code refs/heads/main}), a bare pattern matching both heads and tags; workflow entries match
 * the workflow file name or a glob over the full {@code workflow_ref}; subjects are globs over
 * the raw {@code sub} claim for shapes the other fields cannot express. In every glob {@code *}
 * spans any characters including {@code /} and {@code :}, so an exclusion like {@code team-*}
 * also excludes {@code team-a/x}; write exclusions as precisely as the inclusions.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FederatedMatcher implements Serializable {
	private static final long serialVersionUID = 1L;

	private String owner;
	private List<String> repositories = new LinkedList<>();
	private List<String> excludeRepositories = new LinkedList<>();
	private List<String> refs = new LinkedList<>();
	private List<String> environments = new LinkedList<>();
	private List<String> workflows = new LinkedList<>();
	private List<String> events = new LinkedList<>();
	private List<String> subjects = new LinkedList<>();

	public List<String> getRepositories() { return repositories == null ? List.of() : repositories; }
	public List<String> getExcludeRepositories() { return excludeRepositories == null ? List.of() : excludeRepositories; }
	public List<String> getRefs() { return refs == null ? List.of() : refs; }
	public List<String> getEnvironments() { return environments == null ? List.of() : environments; }
	public List<String> getWorkflows() { return workflows == null ? List.of() : workflows; }
	public List<String> getEvents() { return events == null ? List.of() : events; }
	public List<String> getSubjects() { return subjects == null ? List.of() : subjects; }
}
