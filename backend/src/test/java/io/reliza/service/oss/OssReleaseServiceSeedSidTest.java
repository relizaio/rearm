/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ReleaseData;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;

/**
 * Tests for {@code OssReleaseService.seedDtoWithExistingSidIdentity} — the helper that
 * makes rebuild / PENDING-update flow through {@code doUpdateRelease}'s sid-immutability
 * checks idempotent. Without it, any rebuild of a release that pre-dates sid enablement
 * (or whose component was renamed, or whose org authority changed) would crash.
 */
public class OssReleaseServiceSeedSidTest {

	@Test
	void preSidEnablementRelease_seededWithNullSnapshot_dtoIdentifiersUntouched() throws Exception {
		// Existing release pre-dates sid enablement: no snapshot, no sid PURL.
		ReleaseData existing = release(/* snapshot */ null, List.of(
				teaPurl("pkg:maven/io.reliza/legacy-thing@1.0")));
		// Caller provides nothing on the rebuild dto.
		ReleaseDto dto = dtoOf(/* identifiers */ null);

		invokeSeed(dto, existing);

		assertNull(dto.getSidComponentName(),
				"snapshot stays null when existing release has none — immutability check is a no-op");
		assertNull(dto.getIdentifiers(),
				"caller passed no identifiers → leave dto null so doUpdateRelease skips identifier handling " +
				"and the stored list survives");
	}

	@Test
	void postTRelease_seedRoundTripsExistingSidIdentity() throws Exception {
		// Existing release was emitted with the OLD authority "old.example.com".
		ReleaseData existing = release("ReARM Backend", List.of(
				teaPurl("pkg:sid/old.example.com/ReARM%20Backend@1.0"),
				teaPurl("pkg:maven/io.reliza/rearm@1.0")));
		// Caller is rebuilding and supplied a CPE identifier.
		ReleaseDto dto = dtoOf(List.of(teaCpe("cpe:2.3:a:reliza:rearm:1.0")));

		invokeSeed(dto, existing);

		assertEquals("ReARM Backend", dto.getSidComponentName(),
				"snapshot must mirror the existing release for the immutability check to pass");
		assertNotNull(dto.getIdentifiers());
		List<String> sidValues = dto.getIdentifiers().stream()
				.filter(t -> t.getIdType() == RearmIdentifierType.PURL
						&& t.getIdValue() != null && t.getIdValue().startsWith("pkg:sid/"))
				.map(RearmIdentifier::getIdValue)
				.collect(Collectors.toList());
		assertEquals(List.of("pkg:sid/old.example.com/ReARM%20Backend@1.0"), sidValues,
				"existing sid PURL must be re-attached to the dto so doUpdateRelease " +
				"sees identical sid sets on both sides (D17 idempotent)");
		assertTrue(dto.getIdentifiers().stream()
				.anyMatch(t -> t.getIdType() == RearmIdentifierType.CPE),
				"caller's non-sid identifier (CPE) flows through unchanged");
	}

	@Test
	void callerSuppliedSidPurl_silentlyDropped_existingSidReattached() throws Exception {
		// Caller tries to fabricate a sid PURL with a different authority — must be dropped.
		// Existing release's sid is the source of truth.
		ReleaseData existing = release("X", List.of(
				teaPurl("pkg:sid/legit.example.com/X@1.0")));
		ReleaseDto dto = dtoOf(List.of(
				teaCpe("cpe:legit"),
				teaPurl("pkg:sid/spoofed.example.com/X@1.0")));

		invokeSeed(dto, existing);

		List<String> sidValues = dto.getIdentifiers().stream()
				.filter(t -> t.getIdType() == RearmIdentifierType.PURL
						&& t.getIdValue() != null && t.getIdValue().startsWith("pkg:sid/"))
				.map(RearmIdentifier::getIdValue)
				.collect(Collectors.toList());
		assertEquals(List.of("pkg:sid/legit.example.com/X@1.0"), sidValues,
				"caller's spoofed sid must be dropped, existing sid re-attached");
	}

	@Test
	void existingReleaseSnapshotNull_butCallerPassedIdentifiers_stillIdempotent() throws Exception {
		// Mixed case: existing release has a snapshot but no sid PURL (e.g. fields stored
		// inconsistently by some legacy migration path). Seeding must still produce an
		// idempotent dto — snapshot mirrors existing (which is null in this variant), and
		// no sid PURL is added since the existing release has none.
		ReleaseData existing = release(/* snapshot */ null, List.of(
				teaPurl("pkg:maven/foo/bar@1.0")));
		ReleaseDto dto = dtoOf(List.of(
				teaCpe("cpe:test"),
				teaPurl("pkg:sid/spoofed/X@1.0")));

		invokeSeed(dto, existing);

		assertNull(dto.getSidComponentName());
		// caller's spoofed sid stripped; no existing sid to re-attach
		assertTrue(dto.getIdentifiers().stream()
				.noneMatch(t -> t.getIdValue() != null && t.getIdValue().startsWith("pkg:sid/")),
				"no sid PURL on dto when existing release has none — both sides empty for sid");
	}

	// ---- Helpers ----

	private static void invokeSeed(ReleaseDto dto, ReleaseData existing) throws Exception {
		Method m = OssReleaseService.class.getDeclaredMethod(
				"seedDtoWithExistingSidIdentity", ReleaseDto.class, ReleaseData.class);
		m.setAccessible(true);
		m.invoke(null, dto, existing);
	}

	private static ReleaseData release(String snapshot, List<RearmIdentifier> identifiers) {
		ReleaseData rd = new ReleaseData();
		// ReleaseData.setUuid is private (audit-controlled). The seed helper doesn't
		// reach for the uuid, so we leave it null in tests.
		rd.setSidComponentName(snapshot);
		rd.setIdentifiers(new LinkedList<>(identifiers));
		return rd;
	}

	private static ReleaseDto dtoOf(List<RearmIdentifier> identifiers) {
		return ReleaseDto.builder()
				.identifiers(identifiers != null ? new LinkedList<>(identifiers) : null)
				.build();
	}

	private static RearmIdentifier teaPurl(String value) {
		RearmIdentifier ti = new RearmIdentifier();
		ti.setIdType(RearmIdentifierType.PURL);
		ti.setIdValue(value);
		return ti;
	}

	private static RearmIdentifier teaCpe(String value) {
		RearmIdentifier ti = new RearmIdentifier();
		ti.setIdType(RearmIdentifierType.CPE);
		ti.setIdValue(value);
		return ti;
	}
}
