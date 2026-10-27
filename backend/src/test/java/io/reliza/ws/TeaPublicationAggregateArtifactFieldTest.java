/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublicationData.TeaAggregateArtifact;

/**
 * Task TEA-28, design 4.10: the deprecated {@code TeaPublication.aggregateArtifact} answers the
 * {@code default} named aggregate's row, else the first listed, else null; the UI reads it until TEA-30.
 */
class TeaPublicationAggregateArtifactFieldTest {

	private static TeaPublicationData head(TeaAggregateArtifact... aggregates) {
		TeaPublicationData p = new TeaPublicationData();
		p.setAggregateArtifacts(List.of(aggregates));
		return p;
	}

	private static UUID resolve(TeaPublicationData p) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getSource()).thenReturn(p);
		return new TeaPublicationDataFetcher().aggregateArtifact(dfe);
	}

	@Test
	void theDefaultEntryWinsWhereverItStands() {
		UUID top = UUID.randomUUID();
		UUID def = UUID.randomUUID();
		assertEquals(def, resolve(head(new TeaAggregateArtifact("top", top), new TeaAggregateArtifact("default", def))));
	}

	@Test
	void withoutADefaultTheFirstEntryAnswers() {
		UUID fda = UUID.randomUUID();
		assertEquals(fda, resolve(head(new TeaAggregateArtifact("fda", fda),
				new TeaAggregateArtifact("top", UUID.randomUUID()))));
	}

	@Test
	void anEmptyListOrNoHeadAnswersNull() {
		assertNull(resolve(head()));
		assertNull(resolve(null));
	}
}
