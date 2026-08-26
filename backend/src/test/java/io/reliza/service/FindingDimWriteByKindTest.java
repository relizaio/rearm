package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.FindingChangeEvent.FindingKind;
import io.reliza.model.Organization;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Guards the ONE property {@code writeEventsToV3ByKind} exists for: the reported change-kind mix must
 * describe the rows that ACTUALLY landed, not the rows that were offered.
 *
 * <p>The repair sweep re-diffs a release's whole recent slice, so almost everything it offers is already
 * present and skipped by {@code ON CONFLICT DO NOTHING}. If the mix counted offered events instead, the
 * sweep's alert would describe hundreds of already-stored events and the operator would be reading noise.
 *
 * <p>This is asserted by writing a batch, then re-writing that SAME batch plus one event of a DIFFERENT
 * kind: the second call must report only the newcomer.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FindingDimWriteByKindTest {

	@Autowired private FindingDimBackfillService findingDimBackfillService;
	@Autowired private TestInitializer testInitializer;

	private static final String PURL = "pkg:npm/bykind@1.0.0";

	private static FindingChangeEvent event(UUID org, UUID release, UUID branch, UUID component,
			String vulnId, FindingChangeKind kind, int revision) {
		FindingChangeEvent ev = new FindingChangeEvent();
		ev.setOrg(org);
		ev.setReleaseUuid(release);
		ev.setBranchUuid(branch);
		ev.setComponentUuid(component);
		ev.setComponentName("bykind-component");
		ev.setVersion("1.0.0");
		ev.setChangeDate(ZonedDateTime.now());
		ev.setToMetricsRevision(revision);
		ev.setChangeKind(kind);
		ev.setFindingKind(FindingKind.VULNERABILITY);
		ev.setFindingKey(vulnId + "|" + PURL);
		ev.setVulnId(vulnId);
		ev.setPurl(PURL);
		ev.setSeverity("HIGH");
		ev.setKnownExploited(false);
		return ev;
	}

	@Test
	void reportsOnlyRowsThatLandedNotRowsThatWereOffered() {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		UUID release = UUID.randomUUID();
		UUID branch = UUID.randomUUID();
		UUID component = UUID.randomUUID();

		FindingChangeEvent appeared = event(orgUuid, release, branch, component,
				"CVE-2026-9300", FindingChangeKind.APPEARED, 1);
		FindingChangeEvent resolved = event(orgUuid, release, branch, component,
				"CVE-2026-9300", FindingChangeKind.RESOLVED, 2);

		var first = findingDimBackfillService.writeEventsToV3ByKind(orgUuid, List.of(appeared));
		assertEquals(Map.of(FindingChangeKind.APPEARED, 1), first.byKind(),
				"first write should report the one row it inserted");
		assertTrue(first.byRevision().get(1).noOfferedRowWasAlreadyPresent(),
				"nothing was stored beforehand for that revision, so every offered row landed -- this is the "
				+ "signature the sweep's alert uses to say the live emit never ran for it");

		// Re-offer the SAME event (skipped by ON CONFLICT) alongside a genuinely new one of another kind.
		var second = findingDimBackfillService.writeEventsToV3ByKind(orgUuid, List.of(appeared, resolved));
		assertEquals(Map.of(FindingChangeKind.RESOLVED, 1), second.byKind(),
				"re-offered events are skipped by ON CONFLICT and must NOT appear in the reported mix -- "
				+ "counting offered events instead of landed ones is what makes the sweep's alert unreadable");
		assertEquals(1, second.landed(), "only the new one landed");
		assertTrue(second.byRevision().get(2).noOfferedRowWasAlreadyPresent(),
				"revision 2 was untouched before, so IT reads as an emit that never ran");
		assertFalse(second.byRevision().get(1).noOfferedRowWasAlreadyPresent(),
				"revision 1 already had its row, so it must NOT read as a lost emit -- classifying per "
				+ "revision is what keeps one healthy revision from masking a lost one beside it");

		// The shape the whole classification turns on, and the one this test previously never built: ONE
		// revision holding both an already-present row and a genuinely-new one. Only the offered-vs-landed
		// comparison can see it -- a `landed > 0` check alone reports it as an emit that never ran.
		FindingChangeEvent alsoOnRev2 = event(orgUuid, release, branch, component,
				"CVE-2026-9301", FindingChangeKind.RESOLVED, 2);
		var mixed = findingDimBackfillService.writeEventsToV3ByKind(orgUuid, List.of(resolved, alsoOnRev2));
		assertEquals(2, mixed.byRevision().get(2).offered(), "both were offered against revision 2");
		assertEquals(1, mixed.byRevision().get(2).landed(), "but only the newcomer landed");
		assertFalse(mixed.byRevision().get(2).noOfferedRowWasAlreadyPresent(),
				"one offered row was already present, so this revision's emit demonstrably RAN -- reporting "
				+ "it as a lost write is the false alarm the split exists to prevent");

		var third = findingDimBackfillService.writeEventsToV3ByKind(orgUuid, List.of(appeared, resolved));
		assertEquals(Map.of(), third.byKind(), "a wholly-redundant re-write must report nothing at all");
		assertTrue(third.isEmpty(), "and must report itself empty");
	}
}
