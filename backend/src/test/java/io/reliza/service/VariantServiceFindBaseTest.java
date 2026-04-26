/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.model.Variant;
import io.reliza.repositories.VariantRepository;

/**
 * Pin the contract for {@link VariantService#findBaseVariantForRelease(UUID)}: returns
 * {@link Optional#empty()} when no base variant exists, instead of throwing.
 *
 * <p>Background: the existing throwing variant {@link VariantService#getBaseVariantForRelease(UUID)}
 * called {@code Optional.get()} unguarded, which propagated as a {@link NoSuchElementException}
 * from the SBOM reconcile scheduler tick on every run for releases without a base variant.
 * The new {@code find*} overload lets background callers skip silently.
 */
@ExtendWith(MockitoExtension.class)
public class VariantServiceFindBaseTest {

	@Mock private VariantRepository variantRepository;

	@InjectMocks private VariantService service;

	@Test
	void findBaseVariantForRelease_returnsEmpty_whenRepoHasNoBaseVariant() {
		UUID releaseId = UUID.randomUUID();
		org.mockito.Mockito.when(variantRepository.findBaseVariantOfRelease(releaseId.toString()))
				.thenReturn(Optional.empty());

		Optional<io.reliza.model.VariantData> result = service.findBaseVariantForRelease(releaseId);

		assertTrue(result.isEmpty(), "missing base variant must surface as empty Optional, not throw");
	}

	@Test
	void getBaseVariantForRelease_stillThrows_whenRepoHasNoBaseVariant() {
		// Existing throwing API kept for callers that treat missing variant as a programming
		// error (CdxImportService, DeliverableDataFetcher, etc). Pin the contrast so a future
		// "fix" doesn't accidentally swap the throwing semantics.
		UUID releaseId = UUID.randomUUID();
		org.mockito.Mockito.when(variantRepository.findBaseVariantOfRelease(releaseId.toString()))
				.thenReturn(Optional.empty());

		assertThrows(NoSuchElementException.class, () -> service.getBaseVariantForRelease(releaseId));
	}

	@Test
	void findBaseVariantForRelease_returnsData_whenRepoHasBaseVariant() {
		UUID releaseId = UUID.randomUUID();
		Variant v = new Variant();
		v.setUuid(UUID.randomUUID());
		v.setRecordData(java.util.Map.of("type", "BASE"));
		org.mockito.Mockito.when(variantRepository.findBaseVariantOfRelease(releaseId.toString()))
				.thenReturn(Optional.of(v));

		Optional<io.reliza.model.VariantData> result = service.findBaseVariantForRelease(releaseId);

		assertTrue(result.isPresent());
		assertEquals(v.getUuid(), result.get().getUuid());
	}
}
