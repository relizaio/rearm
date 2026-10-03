/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import graphql.schema.DataFetchingFieldSelectionSet;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserData;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;

/**
 * TEA-4: Release.syntheticArtifactDetails reads ReleaseData.syntheticArtifacts the way
 * artifactDetails reads the inventory list (batched, heavy only when the selection asks for metric
 * rows, list order, missing ids skipped), and artifactDetails never reads the synthetic list.
 */
class ReleaseDatafetcherSyntheticArtifactsTest {

	private ArtifactService artifactService;
	private ReleaseDatafetcher fetcher;
	private final Map<UUID, ArtifactData> rows = new HashMap<>();

	@BeforeEach
	void wire() {
		artifactService = mock(ArtifactService.class);
		fetcher = new ReleaseDatafetcher();
		ReflectionTestUtils.setField(fetcher, "artifactService", artifactService);
		when(artifactService.getArtifactDataListLight(any())).thenAnswer(inv -> load(inv.getArgument(0)));
		when(artifactService.getArtifactDataList(any())).thenAnswer(inv -> load(inv.getArgument(0)));
	}

	private List<ArtifactData> load(Iterable<?> ids) {
		List<ArtifactData> out = new ArrayList<>();
		for (Object id : ids) if (rows.containsKey(id)) out.add(rows.get(id));
		// answered out of order on purpose: the fetcher re-orders to the list
		java.util.Collections.reverse(out);
		return out;
	}

	private UUID artifact() {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", UUID.randomUUID());
		rows.put(ad.getUuid(), ad);
		return ad.getUuid();
	}

	private static DgsDataFetchingEnvironment dfe(ReleaseData rd, boolean metricsRows) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		DataFetchingFieldSelectionSet selection = mock(DataFetchingFieldSelectionSet.class);
		when(selection.contains(anyString())).thenReturn(metricsRows);
		when(dfe.getSource()).thenReturn(rd);
		when(dfe.getSelectionSet()).thenReturn(selection);
		return dfe;
	}

	private static List<UUID> uuids(Collection<ArtifactData> ads) {
		return ads.stream().map(ArtifactData::getUuid).toList();
	}

	private static ReleaseData release(List<UUID> inventory, List<UUID> synthetic) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		rd.setArtifacts(new LinkedList<>(inventory));
		synthetic.forEach(rd::addSyntheticArtifact);
		return rd;
	}

	@Test
	void syntheticDetailsAnswerInListOrderAndSkipAMissingId() {
		UUID first = artifact();
		UUID second = artifact();
		ReleaseData rd = release(List.of(artifact()), List.of(first, UUID.randomUUID(), second));

		assertEquals(List.of(first, second), uuids(fetcher.syntheticArtifactsOfRelease(dfe(rd, false))));
		verify(artifactService).getArtifactDataListLight(List.of(first, rd.getSyntheticArtifacts().get(1), second));
		verify(artifactService, never()).getArtifactDataList(any());
	}

	@Test
	void syntheticDetailsLoadHeavyRowsOnlyWhenMetricRowsAreSelected() {
		UUID s = artifact();
		ReleaseData rd = release(List.of(), List.of(s));

		assertEquals(List.of(s), uuids(fetcher.syntheticArtifactsOfRelease(dfe(rd, true))));
		verify(artifactService).getArtifactDataList(List.of(s));
		verify(artifactService, never()).getArtifactDataListLight(any());
	}

	@Test
	void anEmptySyntheticListAnswersEmptyWithoutALoad() {
		ReleaseData rd = release(List.of(artifact()), List.of());

		assertEquals(List.of(), fetcher.syntheticArtifactsOfRelease(dfe(rd, false)));
		verifyNoInteractions(artifactService);
	}

	@Test
	void artifactDetailsAnswerTheInventoryListOnly() {
		UUID a = artifact();
		UUID s = artifact();
		ReleaseData rd = release(List.of(a), List.of(s));

		assertEquals(List.of(a), uuids(fetcher.artifactsOfReleaseWithDep(dfe(rd, false))));
		verify(artifactService).getArtifactDataListLight(List.of(a));
	}

	/** The manual artifact upload never writes a new version of a generated artifact, in place or not. */
	@Test
	void theManualUploadRefusesANewVersionOfAGeneratedArtifact() throws Exception {
		UserService userService = mock(UserService.class);
		SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
		GetComponentService getComponentService = mock(GetComponentService.class);
		ReflectionTestUtils.setField(fetcher, "userService", userService);
		ReflectionTestUtils.setField(fetcher, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(fetcher, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(fetcher, "authorizationService", mock(AuthorizationService.class));
		UUID s = artifact();
		ReleaseData rd = release(List.of(), List.of(s));
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(mock(UserData.class)));
		when(sharedReleaseService.getReleaseData(rd.getUuid())).thenReturn(Optional.of(rd));
		when(artifactService.getArtifactData(s)).thenReturn(Optional.of(rows.get(s)));
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		Map<String, Object> input = new HashMap<>();
		input.put("release", rd.getUuid().toString());
		input.put("artifact", new HashMap<>(Map.of("type", "BOM", "displayIdentifier", "new-version.json")));
		when(dfe.getArgument("artifactInput")).thenReturn(input);

		RelizaException e = assertThrows(RelizaException.class, () -> fetcher.addArtifactManual(dfe, s));

		assertEquals("artifact " + s + " is a generated artifact of release " + rd.getUuid()
				+ "; it cannot be attached as inventory", e.getMessage());
		verifyNoInteractions(getComponentService);
		verify(artifactService, never()).uploadArtifact(any(), any(), any(), any());
		verify(artifactService, never()).createArtifact(any(), any());
	}
}
