/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Who started a draft or requested its build, as the act record snapshots a person: their uuid, name
 * and e-mail in the organization at that moment.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftActor(UUID actor, String actorName, String actorEmail) implements Serializable {}
