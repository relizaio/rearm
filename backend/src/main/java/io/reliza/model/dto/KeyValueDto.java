/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

/**
 * Generic Key Value Dto
 *
 */
public record KeyValueDto (
	String key,
	String value,
	Long lastUpdated
) {}
