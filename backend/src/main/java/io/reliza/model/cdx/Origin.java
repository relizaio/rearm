/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * Country-of-origin + multi-source split. {@code basis} is a freeform note on
 * how the split was determined (e.g. "number of parts", "by cost"). HBOM/CDX
 * has no standard field for this yet (open question) — on CDX export this is
 * emitted as a namespaced property/extension, not a core field.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Origin {
	private String basis;
	private List<OriginShare> origins;
}
