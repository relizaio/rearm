/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Data;

/**
 * A scheme that the schema allows either as a predefined string (such as {@code purl} or
 * {@code IEC-62304}) or as a custom object {@code {name, description}}. Used by identities and
 * safety integrity levels. Exactly one of {@code predefined} and {@code customName} is set.
 */
@Data
public class Cdx20Scheme {
	private String predefined;
	private String customName;
	private String customDescription;

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static Cdx20Scheme of(Object raw) {
		Cdx20Scheme s = new Cdx20Scheme();
		if (raw instanceof String str) {
			s.setPredefined(str);
		} else if (raw instanceof Map<?, ?> m) {
			s.setCustomName(m.get("name") instanceof String n ? n : null);
			s.setCustomDescription(m.get("description") instanceof String d ? d : null);
		}
		return s;
	}

	/** True when this is the predefined scheme {@code value}. */
	public boolean isPredefined(String value) {
		return predefined != null && predefined.equals(value);
	}

	@JsonValue
	public Object toJson() {
		if (predefined != null) return predefined;
		Map<String, Object> m = new LinkedHashMap<>();
		if (customName != null) m.put("name", customName);
		if (customDescription != null) m.put("description", customDescription);
		return m;
	}
}
