/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.ReleaseRepository;

/**
 * TEA-4: the user and programmatic release update diffs the inventory list only. An update that
 * would add one of the release's generated artifacts to its inventory is refused before anything
 * is saved, and an ordinary update leaves the generated list as it was. Built like
 * OssReleaseServiceUpdateEventOldValueTest.
 */
public class OssReleaseServiceSyntheticGuardTest {

	private final UUID inventory = UUID.randomUUID();
	private final UUID synthetic = UUID.randomUUID();

	private ReleaseData releaseData() {
		ReleaseData rd = new ReleaseData();
		rd.setArtifacts(new LinkedList<>(List.of(inventory)));
		rd.addSyntheticArtifact(synthetic);
		return rd;
	}

	@Test
	void addingAGeneratedArtifactToTheInventoryIsRefusedBeforeTheSave() throws Exception {
		ReleaseData rData = releaseData();
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		OssReleaseService svc = spiedService(r);

		InvocationTargetException e = assertThrows(InvocationTargetException.class, () -> invoke(svc, r, rData,
				ReleaseDto.builder().artifacts(new LinkedList<>(List.of(inventory, synthetic))).build()));

		RelizaException cause = assertInstanceOf(RelizaException.class, e.getCause());
		assertEquals("artifact " + synthetic + " is a generated artifact of release " + r.getUuid()
				+ "; it cannot be attached as inventory", cause.getMessage());
		verify(svc, never()).saveRelease(any(Release.class), any(ReleaseData.class), any());
		assertEquals(List.of(inventory), rData.getArtifacts(), "nothing changed");
	}

	@Test
	void anUpdateThatLeavesArtifactsAloneKeepsTheGeneratedList() throws Exception {
		ReleaseData rData = releaseData();
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		OssReleaseService svc = spiedService(r);

		invoke(svc, r, rData, ReleaseDto.builder().artifacts(new LinkedList<>(List.of(inventory)))
				.notes("new notes").build());

		ArgumentCaptor<ReleaseData> saved = ArgumentCaptor.forClass(ReleaseData.class);
		verify(svc).saveRelease(any(Release.class), saved.capture(), any());
		assertEquals(List.of(synthetic), saved.getValue().getSyntheticArtifacts());
		assertEquals(List.of(inventory), saved.getValue().getArtifacts());
	}

	private static OssReleaseService spiedService(Release r) {
		OssReleaseService svc = spy(new OssReleaseService(mock(ReleaseRepository.class)));
		doReturn(r).when(svc).saveRelease(any(Release.class), any(ReleaseData.class), any());
		return svc;
	}

	private static void invoke(OssReleaseService svc, Release r, ReleaseData rData, ReleaseDto dto) throws Exception {
		Method m = OssReleaseService.class.getDeclaredMethod("doUpdateRelease", Release.class,
				ReleaseData.class, ReleaseDto.class, WhoUpdated.class);
		m.setAccessible(true);
		m.invoke(svc, r, rData, dto, WhoUpdated.getAutoWhoUpdated());
	}
}
