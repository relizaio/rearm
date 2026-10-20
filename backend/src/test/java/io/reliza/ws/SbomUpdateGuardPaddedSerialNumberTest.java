/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.Utils;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.Component;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.ArtifactServiceSpies;
import io.reliza.service.SbomUpdateGuard;
import tools.jackson.databind.node.ObjectNode;

/**
 * SCORE-52 T-24 and T-29 (h) (carried from SCORE-47 T-1): rule B keys a serialNumber exactly as rebom
 * keys a stored BOM. rebom trims with JavaScript's {@code String.prototype.trim}, so a serialNumber
 * padded with any code point that trim strips is the bare uuid: a writer on B only is refused for it
 * as for the bare uuid, on a CycloneDX 2.0 and a 1.6 document, at either end and at both, and a
 * writer on every holder passes. A padding rebom keeps (the C0 controls {@code String.trim} strips,
 * U+0085, U+180E, U+200B, U+2060) or a code point inside the value is a serialNumber of its own in
 * rebom and is not checked against the bare uuid's. The text the step 2 read sends rebom is the
 * document's serialNumber after the rebom trim.
 */
class SbomUpdateGuardPaddedSerialNumberTest extends SbomUpdateGuardTestBase {

	/**
	 * What {@code String.prototype.trim} strips, the ECMA-262 WhiteSpace and LineTerminator sets: the
	 * list Node prints for {@code [...Array(0x110000).keys()].filter(c => ('a' + String.fromCodePoint(c)).trim() === 'a')}.
	 */
	private static final int[] TRIMMED_BY_REBOM = { 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
			0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029,
			0x202F, 0x205F, 0x3000, 0xFEFF };

	/** Stripped by {@code String.trim} (every code point up to U+0020), kept by JavaScript. */
	private static final int[] TRIMMED_BY_JAVA_ONLY = IntStream.rangeClosed(0x0000, 0x0020)
			.filter(cp -> Arrays.stream(TRIMMED_BY_REBOM).noneMatch(t -> t == cp)).toArray();

	/** Whitespace-like, kept by both. */
	private static final int[] KEPT_BY_BOTH = { 0x0085, 0x180E, 0x200B, 0x2060 };

	private static boolean trimmedByRebom(int cp) {
		return Arrays.stream(TRIMMED_BY_REBOM).anyMatch(t -> t == cp);
	}

	private static String pad(int cp) {
		return Character.toString(cp);
	}

	private static String hex(int cp) {
		return String.format("U+%04X", cp);
	}

	/** A CycloneDX document of {@code specVersion}; the serialNumber is escaped as JSON, never as Java source. */
	private static String doc(String specVersion, String serialNumber, int version) {
		ObjectNode n = Utils.OM.createObjectNode();
		n.put("bomFormat", "CycloneDX");
		n.put("specVersion", specVersion);
		n.put("serialNumber", serialNumber);
		n.put("version", version);
		n.putArray("components");
		return Utils.OM.writeValueAsString(n);
	}

	/** A and C each hold S at version 1 on a row of their own; rebom stops every upload that reaches it. */
	private World twoHolders() throws Exception {
		World w = world();
		c = component(w.org());
		release(w.org(), c, List.of(bomArtifact(w.org().getUuid(), w.serial(), "1")));
		rebomStopsUploads();
		return w;
	}

	private Component c;

	private void reset() {
		clearInvocations(uploads, rebomService);
	}

	@Test
	void canonicalSerialNumberTrimsExactlyWhatRebomTrims() {
		UUID s = UUID.randomUUID();
		String urn = "urn:uuid:" + s;
		List<String> wrong = new ArrayList<>();
		for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
			if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) continue;
			String p = pad(cp);
			Optional<UUID> expected = trimmedByRebom(cp) ? Optional.of(s) : Optional.empty();
			String[] shapes = { p + urn, urn + p, p + urn + p, p + s + p };
			String[] names = { "leading", "trailing", "both ends", "both ends, no prefix" };
			for (int i = 0; i < shapes.length; i++) {
				if (!expected.equals(SbomUpdateGuard.canonicalSerialNumber(shapes[i]))) {
					wrong.add(hex(cp) + " " + names[i]);
				}
			}
			// a code point inside the value is never trimmed
			if (SbomUpdateGuard.canonicalSerialNumber("urn:uuid:" + p + s).isPresent()
					|| SbomUpdateGuard.canonicalSerialNumber(urn.substring(0, 20) + p + urn.substring(20)).isPresent()) {
				wrong.add(hex(cp) + " inside");
			}
		}
		assertEquals(List.of(), wrong);
		StringBuilder all = new StringBuilder();
		for (int cp : TRIMMED_BY_REBOM) all.append(pad(cp));
		assertEquals(Optional.of(s), SbomUpdateGuard.canonicalSerialNumber(all + urn + all));
		assertEquals(Optional.empty(), SbomUpdateGuard.canonicalSerialNumber(all.toString()));
	}

	@Test
	void aWriterOnBOnlyIsRefusedForEveryPaddingRebomTrims() throws Exception {
		World w = twoHolders();
		UserData writerB = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		String urn = "urn:uuid:" + w.serial();
		long rows = artifactRowsWithSerial(w.org().getUuid(), w.serial());

		for (int cp : TRIMMED_BY_REBOM) {
			String p = pad(cp);
			String[][] shapes = { { "2.0", p + urn }, { "1.6", urn + p }, { "2.0", p + urn + p }, { "1.6", p + p + urn } };
			for (String[] shape : shapes) {
				String at = hex(cp) + " on " + shape[0] + " at " + shape[1].indexOf('u');
				reset();
				ExecutionResult r = manualUpload(writerB, w.releaseB(), doc(shape[0], shape[1], 2));
				GraphQLError error = onlyError(r);
				assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, error.getExtensions().get("code"), at + " " + error);
				verify(rebomService, never()).latestStoredBomVersion(any(), any());
				verify(rebomService, never()).uploadSbom(any(), any(), any(), any(), any());
				ArtifactServiceSpies.nothingRetainedOrWritten(uploads);
			}
		}
		assertEquals(rows, artifactRowsWithSerial(w.org().getUuid(), w.serial()));
		assertEquals(List.of(w.y()), artifactsOf(w.releaseB()));
	}

	@Test
	void aWriterOnEveryHolderPassesThroughThePaddingAndAWriterOnOneIsRefused() throws Exception {
		World w = twoHolders();
		UserData writerA = writer(w.org(), PermissionScope.COMPONENT, w.a().getUuid());
		UserData bothWriter = writerOn(w.org(), w.a().getUuid(), c.getUuid());
		String urn = "urn:uuid:" + w.serial();

		for (int cp : TRIMMED_BY_REBOM) {
			reset();
			ExecutionResult r = manualUpload(bothWriter, w.releaseA(), doc("2.0", pad(cp) + urn + pad(cp), 2));
			assertTrue(onlyError(r).getMessage().contains(REBOM_REACHED), hex(cp) + " " + r.getErrors());
			verify(rebomService, times(1)).uploadSbom(any(), any(), any(), any(), any());
			reset();
			GraphQLError refused = onlyError(manualUpload(writerA, w.releaseA(), doc("2.0", pad(cp) + urn, 2)));
			assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, refused.getExtensions().get("code"), hex(cp));
			verify(rebomService, never()).uploadSbom(any(), any(), any(), any(), any());
		}
	}

	@Test
	void theStepTwoReadSendsRebomTheTrimmedText() throws Exception {
		World w = world();
		UserData writerB = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		for (int cp : new int[] { 0x0020, 0x00A0, 0x2028, 0xFEFF }) {
			UUID fresh = UUID.randomUUID();
			String urn = "urn:uuid:" + fresh;
			reset();
			ExecutionResult r = manualUpload(writerB, w.releaseB(), doc("1.6", pad(cp) + urn + pad(cp), 1));
			assertTrue(r.getErrors().isEmpty(), hex(cp) + " " + r.getErrors());
			verify(rebomService, times(1)).latestStoredBomVersion(eq(w.org().getUuid()), eq(urn));
		}
	}

	@Test
	void aPaddingRebomKeepsIsASerialNumberOfItsOwnAndIsNotChecked() throws Exception {
		World w = twoHolders();
		UserData writerB = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		String urn = "urn:uuid:" + w.serial();
		List<String> serials = new ArrayList<>();
		for (int cp : TRIMMED_BY_JAVA_ONLY) serials.add(pad(cp) + urn);
		for (int cp : KEPT_BY_BOTH) serials.add(urn + pad(cp));
		serials.add("urn:uuid:" + pad(0x00A0) + w.serial());
		assertEquals(TRIMMED_BY_JAVA_ONLY.length + KEPT_BY_BOTH.length + 1, serials.size());
		assertEquals(27, TRIMMED_BY_JAVA_ONLY.length);

		for (String serial : serials) {
			String at = hex(serial.codePoints().filter(cp -> cp < 0x21 || cp > 0x7E).findFirst().orElse(-1));
			reset();
			ExecutionResult r = manualUpload(writerB, w.releaseB(), doc("2.0", serial, 2));
			assertTrue(onlyError(r).getMessage().contains(REBOM_REACHED), at + " " + r.getErrors());
			verify(rebomService, times(1)).uploadSbom(any(), any(), any(), any(), any());
			verify(rebomService, never()).latestStoredBomVersion(eq(w.org().getUuid()), eq(urn));
		}
	}
}
