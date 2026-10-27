/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentRef;
import io.reliza.model.ComponentData.ComponentType;

/**
 * TPF-100 (architecture round 1 section 9.2, test 7): a FIND_COMPONENT_REFS_* row and a document map
 * to the same ref, with the document's defaults for missing values.
 */
public class ComponentRefTest {

	private static final UUID UUID_ = UUID.randomUUID();
	private static final UUID ORG = UUID.randomUUID();
	private static final UUID VCS = UUID.randomUUID();

	private static Object[] row(String type, String status, String vcs, String kind) {
		return new String[] {UUID_.toString(), "name-1", type, status, ORG.toString(), vcs, kind};
	}

	@Test
	public void aRowMapsFieldByField() {
		ComponentRef r = ComponentRef.fromRow(row("PRODUCT", "ARCHIVED", VCS.toString(), "HELM"));
		assertEquals(new ComponentRef(UUID_, "name-1", ComponentType.PRODUCT, StatusEnum.ARCHIVED, ORG, VCS,
				ComponentKind.HELM), r);
		assertEquals(UUID_, r.uuid(), "the record accessor GraphQL resolves the uuid field through");
	}

	@Test
	public void missingValuesReadAsTheDocumentDefaults() {
		ComponentRef r = ComponentRef.fromRow(row("COMPONENT", null, null, null));
		assertEquals(StatusEnum.ACTIVE, r.status(), "no status reads ACTIVE");
		assertEquals(ComponentKind.GENERIC, r.kind(), "no kind reads GENERIC");
		assertNull(r.vcs(), "no vcs stays null");
		assertEquals(ComponentKind.GENERIC, ComponentRef.fromRow(row("COMPONENT", "ACTIVE", null, "NOPE")).kind(),
				"an unknown kind reads GENERIC");
		assertNull(ComponentRef.fromRow(row(null, "ACTIVE", null, "GENERIC")).type(), "no type reads null, as the document");
	}

	@Test
	public void aDocumentCarriesTheSameSevenValues() {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID_);
		cd.setName("name-1");
		cd.setType(ComponentType.PRODUCT);
		cd.setStatus(StatusEnum.ARCHIVED);
		cd.setOrg(ORG);
		cd.setVcs(VCS);
		cd.setKind(ComponentKind.HELM);
		assertEquals(ComponentRef.fromRow(row("PRODUCT", "ARCHIVED", VCS.toString(), "HELM")), ComponentRef.of(cd));
	}
}
