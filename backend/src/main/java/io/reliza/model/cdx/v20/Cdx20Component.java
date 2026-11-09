/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * A component definition, or a reference to one: an inventory entry may carry only {@code ref}
 * (the refId or BOM-Link of a component defined elsewhere). Identities sit in
 * {@code identifiers}, grouped by the asserting party; 2.0 has no top-level purl.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-component-2.0/$defs/component")
public class Cdx20Component {
	// 1.x documents and early 2.0 drafts call it bom-ref.
	@JsonAlias("bom-ref")
	private String refId;
	@Cdx20SchemaDef("#/$defs/cyclonedx-component-2.0/$defs/componentReference")
	private String ref;
	// Kept as written: nothing branches on the component type yet.
	private String type;
	private String name;
	private String version;
	private List<Cdx20Identifier> identifiers;
	private List<Cdx20Hash> hashes;
	private List<Cdx20Component> components;

	/** The purl values any party asserts for this component, in document order, duplicates removed. */
	public List<String> purls() {
		if (identifiers == null) return List.of();
		return identifiers.stream()
			.filter(Objects::nonNull)
			.flatMap(i -> i.getIdentities() == null ? Stream.<Cdx20Identity>empty() : i.getIdentities().stream())
			.filter(id -> id != null && id.getScheme() != null && id.getScheme().isPredefined(Cdx20Identity.PURL_SCHEME))
			.map(Cdx20Identity::getValue)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
	}
}
