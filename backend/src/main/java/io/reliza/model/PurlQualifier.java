/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-(org, full_purl) interning row referenced by
 * release_sbom_component_artifacts and release_sbom_edges. Lets the same
 * qualifier-bearing PURL be stored once per org instead of repeated inside
 * JSONB across every release / artifact / edge.
 *
 * <p>NULL references in the child tables mean "exact PURL equals canonical
 * PURL" on the related sbom_components row — we avoid intern rows for the
 * ~80% of components that carry no qualifiers.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "purl_qualifiers")
public class PurlQualifier implements Serializable {
	private static final long serialVersionUID = 234738L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Column(name = "full_purl", nullable = false)
	private String fullPurl;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public String getFullPurl() { return fullPurl; }
	public void setFullPurl(String fullPurl) { this.fullPurl = fullPurl; }

	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }
}
