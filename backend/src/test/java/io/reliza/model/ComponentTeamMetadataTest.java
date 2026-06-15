/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.ComponentData.FreeformContact;

/**
 * Context-free unit tests for the Phase-3a component/product team metadata:
 * the persisted {@code leads} + {@code contacts} fields and the
 * {@link ComponentData#sanitizeContacts} jsoup chokepoint. No Spring context
 * / DB needed — exercises the JSONB serialization round trip via
 * {@code Utils.OM} directly.
 */
class ComponentTeamMetadataTest {

	@Test
	void leadsAndContactsRoundTripThroughJsonb() {
		UUID lead1 = UUID.randomUUID();
		UUID lead2 = UUID.randomUUID();
		ComponentData cd = new ComponentData();
		cd.setLeads(new LinkedHashSet<>(Set.of(lead1, lead2)));
		cd.setContacts(List.of(
				new FreeformContact("Jane Owner", "jane@example.com"),
				new FreeformContact("Ops Channel", "#ops")));

		Map<String, Object> record = Utils.dataToRecord(cd);
		ComponentData back = Utils.OM.convertValue(record, ComponentData.class);

		assertEquals(Set.of(lead1, lead2), back.getLeads());
		assertEquals(2, back.getContacts().size());
		assertEquals("Jane Owner", back.getContacts().get(0).name());
		assertEquals("jane@example.com", back.getContacts().get(0).contact());
		assertEquals("#ops", back.getContacts().get(1).contact());
	}

	@Test
	void newComponentDataHasEmptyNonNullTeamCollections() {
		ComponentData cd = new ComponentData();
		assertTrue(cd.getLeads().isEmpty());
		assertTrue(cd.getContacts().isEmpty());
	}

	@Test
	void sanitizeContactsStripsHtmlAndScript() {
		List<FreeformContact> dirty = List.of(
				new FreeformContact("<script>alert(1)</script>Mallory", "<img src=x onerror=alert(1)>m@e.com"));
		List<FreeformContact> clean = ComponentData.sanitizeContacts(dirty);

		String name = clean.get(0).name();
		String contact = clean.get(0).contact();
		assertFalse(name.contains("<script"), "script tag must be stripped: " + name);
		assertTrue(name.contains("Mallory"));
		// Safelist.basic() drops the img tag entirely (not on the basic safelist).
		assertFalse(contact.contains("onerror"), "event handler must be stripped: " + contact);
		assertTrue(contact.contains("m@e.com"));
	}

	@Test
	void sanitizeContactsPreservesNullInputAndNullFields() {
		assertNull(ComponentData.sanitizeContacts(null));
		List<FreeformContact> withNulls = ComponentData.sanitizeContacts(
				List.of(new FreeformContact(null, "")));
		assertNull(withNulls.get(0).name());
		assertEquals("", withNulls.get(0).contact());
	}
}
