/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One identity assertion ({scheme, value}) backing a component's generalized
 * canonical identity — e.g. {@code {"purl","pkg:npm/foo@1"}} or
 * {@code {"cpe","cpe:2.3:a:..."}}. A flat list of these is the backend-side
 * stand-in for CycloneDX's first-class component identity array (a CDX 2.0
 * feature); replace with the spec type once it lands. Persisted to JSONB.
 *
 * <p>Implements {@link Serializable} because the hypersistence JsonBinaryType
 * deep-copies JSONB attribute values for Hibernate dirty-checking, which
 * requires every element object to be Serializable (the old {@code Map} shape
 * was). Without it, any {@code SbomComponent} save throws a JpaSystemException.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComponentIdentity(String scheme, String value) implements Serializable {}
