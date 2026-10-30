/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.web.multipart.MultipartFile;

import io.reliza.exceptions.InvalidInputException;
import io.reliza.model.AgentTaskInput;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.UpdateComponentDto.ReleaseOutputEventInput;
import tools.jackson.databind.exc.InvalidDefinitionException;

/**
 * The client-input conversion helper (task TPF-63, architecture-1 section 4 row 1): the message names
 * the field and what it must be, never the value sent, and carries no cause.
 */
class InputConversionTest {

	private static InvalidInputException refused(Executable call) {
		return assertThrows(InvalidInputException.class, call);
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	// row 1 case 1
	@Test
	void anEnumHeldAsAStringNamesTheFieldAndItsValues() {
		InvalidInputException e = refused(() -> InputConversion.convert(map("storedIn", "EXTERNAL"),
				ArtifactDto.class, "artifactInput.artifact"));
		assertEquals("artifactInput.artifact.storedIn: must be one of REARM, EXTERNALLY", e.getMessage());
		assertNull(e.getCause());
	}

	// row 1 case 2
	@Test
	void anEnumInAListNamesItsIndex() {
		InvalidInputException e = refused(() -> InputConversion.convert(
				map("inventoryTypes", List.of("SOFTWARE", "FIRMWARE")), ArtifactDto.class, "a"));
		assertEquals("a.inventoryTypes[1]: must be one of SOFTWARE, HARDWARE, CRYPTOGRAPHY, SERVICE, VULNERABILITY",
				e.getMessage());
	}

	// row 1 case 3
	@Test
	void anIdThatIsNotAUuid() {
		InvalidInputException e = refused(() -> InputConversion.convert(map("uuid", "not-a-uuid"),
				ArtifactDto.class, "a"));
		assertEquals("a.uuid: not a uuid", e.getMessage());
	}

	// row 1 case 4
	@Test
	void anObjectWhereAUuidGoes() {
		InvalidInputException e = refused(() -> InputConversion.convert(
				map("artifacts", List.of(map("type", "BOM"))), ArtifactDto.class, "artifactInput.artifact"));
		assertEquals("artifactInput.artifact.artifacts[0]: not a uuid", e.getMessage());
	}

	// row 1 case 5
	@Test
	void eachElementIsConvertedUnderItsIndex() {
		InvalidInputException e = refused(() -> InputConversion.convertEach(
				List.of(map("name", "ok", "type", "EXTERNAL_VALIDATION"), map("integration", "x")),
				ReleaseOutputEventInput.class, "triggers"));
		assertEquals("triggers[1].integration: not a uuid", e.getMessage());

		InvalidInputException inSet = refused(() -> InputConversion.convertEach(
				List.of(map("users", List.of("x"))), ReleaseOutputEventInput.class, "triggers"));
		assertEquals("triggers[0].users[0]: not a uuid", inSet.getMessage());
	}

	// row 1 case 6
	@Test
	void aBooleanThatIsNot() {
		InvalidInputException e = refused(() -> InputConversion.convertEach(
				List.of(map("includeSuppressed", "yes")), ReleaseOutputEventInput.class, "triggers"));
		assertEquals("triggers[0].includeSuppressed: must be true or false", e.getMessage());
	}

	// row 1 case 7
	@Test
	void aNumberThatIsNot() {
		InvalidInputException e = refused(() -> InputConversion.convert(map("title", "t", "workLevel", "high"),
				AgentTaskInput.SplitChild.class, "children[0]"));
		assertEquals("children[0].workLevel: must be a number", e.getMessage());
	}

	// row 1 case 8
	@Test
	void anUploadIsRefusedWithoutTouchingTheFile() {
		MultipartFile file = mock(MultipartFile.class);
		InvalidInputException e = refused(() -> InputConversion.convert(map("displayIdentifier", "x", "fileSce", file),
				ArtifactDto.class, "artifactInput.artifact"));
		assertEquals("artifactInput.artifact.fileSce: a file is not accepted here", e.getMessage());
		verifyNoInteractions(file);
	}

	record Holder(Map<String, UUID> m) {}

	// row 1 case 9
	@Test
	void thePathIsRenderedLogSafe() {
		InvalidInputException lineBreak = refused(() -> InputConversion.convert(map("m", map("a\nb", "x")),
				Holder.class, "p"));
		assertEquals("p.m.a?b: not a uuid", lineBreak.getMessage());

		InvalidInputException longKey = refused(() -> InputConversion.convert(map("m", map("k".repeat(300), "x")),
				Holder.class, "p"));
		assertEquals("p.m." + "k".repeat(252) + "...: not a uuid", longKey.getMessage());
	}

	/** externalArtifact() of ReleaseDatafetcherAddArtifactManualTargetBindingTestBase, copied. */
	private static Map<String, Object> externalArtifact() {
		Map<String, Object> link = new LinkedHashMap<>();
		link.put("uri", "https://example.com/tpf44/new-" + UUID.randomUUID());
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("type", ArtifactType.OTHER.name());
		artifact.put("displayIdentifier", "new-" + UUID.randomUUID());
		artifact.put("storedIn", StoredIn.EXTERNALLY.name());
		artifact.put("downloadLinks", List.of(link));
		return artifact;
	}

	// row 1 case 10
	@Test
	void validInputConvertsAsBefore() {
		Map<String, Object> artifact = externalArtifact();
		assertEquals(Utils.OM.convertValue(artifact, ArtifactDto.class),
				InputConversion.convert(artifact, ArtifactDto.class, "x"));
		assertNull(InputConversion.convert(null, ArtifactDto.class, "x"));
		assertNull(InputConversion.convertEach(null, ArtifactDto.class, "x"));
	}

	// row 1 case 11
	@Test
	void aTypeJacksonCannotReadIsNotTheClientsFault() {
		assertThrows(InvalidDefinitionException.class,
				() -> InputConversion.convert(map("x", 1), Runnable.class, "p"));
	}
}
