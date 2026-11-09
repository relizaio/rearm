/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * The part of a CycloneDX 2.0 document ReARM reads: header, the component definitions and
 * inventories that carry identities, and blueprints with their assets and assumptions. Everything
 * else stays in the stored document. Read with {@link Cdx20DocumentReader}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#")
public class Cdx20Document {
	private String specFormat;
	private String specVersion;
	private String serialNumber;
	private Integer version;
	private Cdx20Definitions definitions;
	private List<Cdx20Inventory> inventories;
	private List<Cdx20Blueprint> blueprints;

	/**
	 * Every component the document declares: definitions, then each inventory's subject and
	 * components, nested components included. References ({@code ref} entries) are returned as
	 * they are, unresolved.
	 */
	public List<Cdx20Component> allComponents() {
		List<Cdx20Component> out = new ArrayList<>();
		if (definitions != null) addAll(out, definitions.getComponents());
		if (inventories != null) {
			for (Cdx20Inventory inv : inventories) {
				if (inv == null) continue;
				if (inv.getSubject() != null) addAll(out, List.of(inv.getSubject()));
				addAll(out, inv.getComponents());
			}
		}
		return out;
	}

	private static void addAll(List<Cdx20Component> out, List<Cdx20Component> components) {
		if (components == null) return;
		for (Cdx20Component c : components) {
			if (c == null) continue;
			out.add(c);
			addAll(out, c.getComponents());
		}
	}
}
