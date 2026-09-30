/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.core.type.TypeReference;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository.CanonicalPurlRow;
import io.reliza.repositories.SbomComponentRepository.DueBucketRow;
import io.reliza.service.ComponentLatestVersionService.RefreshResult;
import io.reliza.service.DTrackService.ComponentLatestVersion;
import io.reliza.service.DTrackService.LatestVersionAnswer;
import io.reliza.service.DTrackService.LatestVersionLookup;
import io.reliza.service.DTrackService.RepositoryMetaListing;
import io.reliza.service.DTrackService.RepositoryMetaRejectedException;

/**
 * {@link ComponentLatestVersionService#refreshOrg}: which components are asked about, what
 * a run may spend, and what gets stamped. Bucket members are matched to Dependency-Track's
 * listing as findings are, so DT's re-encoded purls (Debian {@code %2B}, scoped npm
 * {@code %40}) land on the stored canonicals.
 */
class ComponentLatestVersionServiceTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID PROJECT = UUID.randomUUID();
	private static final UUID AGENT = UUID.randomUUID();
	private static final UUID BASE_FILES = UUID.randomUUID();
	private static final UUID GONE = UUID.randomUUID();

	private SbomComponentRepository components;
	private DTrackService dtrack;
	private ComponentLatestVersionService service;
	/** Each stamp call's rows, uuid to latest version (null values kept). */
	private final List<Map<UUID, String>> stamps = new ArrayList<>();

	@BeforeEach
	void setUp() {
		components = mock(SbomComponentRepository.class);
		dtrack = mock(DTrackService.class);
		service = new ComponentLatestVersionService();
		ReflectionTestUtils.setField(service, "sbomComponentRepository", components);
		ReflectionTestUtils.setField(service, "dTrackService", dtrack);
		ReflectionTestUtils.setField(service, "fetchLimit", 20);
		when(components.findLatestVersionDueBuckets(anyString(), any(ZonedDateTime.class), any(ZonedDateTime.class), anyInt()))
				.thenReturn(List.of());
		when(components.findLatestVersionDueUnbucketed(anyString(), any(ZonedDateTime.class), anyInt())).thenReturn(List.of());
		when(components.stampLatestVersions(eq(ORG.toString()), anyString())).thenAnswer(inv -> {
			List<Map<String, String>> rows = Utils.OM.readValue((String) inv.getArgument(1),
					new TypeReference<List<Map<String, String>>>() {});
			Map<UUID, String> stamp = new LinkedHashMap<>();
			rows.forEach(r -> stamp.put(UUID.fromString(r.get("uuid")), r.get("latest")));
			stamps.add(stamp);
			return rows.size();
		});
	}

	private static CanonicalPurlRow row(UUID uuid, String canonicalPurl) {
		return new CanonicalPurlRow() {
			@Override
			public UUID getUuid() {
				return uuid;
			}

			@Override
			public String getCanonicalPurl() {
				return canonicalPurl;
			}

			@Override
			public String getLatestVersion() {
				return null;
			}

			@Override
			public String getLatestVersionChecked() {
				return null;
			}
		};
	}

	private static DueBucketRow due(int index) {
		return new DueBucketRow() {
			@Override
			public Integer getBucketIndex() {
				return index;
			}

			@Override
			public UUID getDtrackProjectUuid() {
				return PROJECT;
			}
		};
	}

	private void dueBuckets(DueBucketRow... rows) {
		when(components.findLatestVersionDueBuckets(eq(ORG.toString()), any(ZonedDateTime.class), any(ZonedDateTime.class), anyInt()))
				.thenReturn(List.of(rows));
	}

	@Test
	void anIngestedBucketGetsItsListingStampedOnEveryMember() throws Exception {
		dueBuckets(due(0));
		when(components.findCanonicalPurlsByOrgAndBucket(ORG.toString(), 0)).thenReturn(List.of(
				row(AGENT, "pkg:npm/%40npmcli/agent@3.0.0"),
				row(BASE_FILES, "pkg:deb/debian/base-files@12.4+deb12u15?distro=debian-12.15"),
				// a member Dependency-Track does not list
				row(GONE, "pkg:npm/left-pad@1.3.0")));
		when(dtrack.syntheticFetchRepositoryMeta(ORG, PROJECT)).thenReturn(new RepositoryMetaListing(List.of(
				new ComponentLatestVersion("pkg:npm/@npmcli/agent@3.0.0", "5.0.2"),
				new ComponentLatestVersion("pkg:deb/debian/base-files@12.4%2Bdeb12u15?distro=debian-12.15", null)), 1));

		RefreshResult result = service.refreshOrg(ORG);

		Map<UUID, String> expected = new HashMap<>();
		expected.put(AGENT, "5.0.2");
		expected.put(BASE_FILES, null);
		expected.put(GONE, null);
		assertEquals(expected, stamps.get(0));
		assertEquals(new RefreshResult(1, 3, 1), result);
	}

	@Test
	void dueMeansCheckedADayAgoOrNeverAndIngestedAnHourAgo() {
		ZonedDateTime before = ZonedDateTime.now();
		service.refreshOrg(ORG);
		ZonedDateTime after = ZonedDateTime.now();
		ArgumentCaptor<ZonedDateTime> cutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
		ArgumentCaptor<ZonedDateTime> settled = ArgumentCaptor.forClass(ZonedDateTime.class);
		verify(components).findLatestVersionDueBuckets(eq(ORG.toString()), cutoff.capture(), settled.capture(), anyInt());
		assertBetween(cutoff.getValue(), before.minus(Duration.ofHours(24)), after.minus(Duration.ofHours(24)));
		assertBetween(settled.getValue(), before.minus(Duration.ofHours(1)), after.minus(Duration.ofHours(1)));
	}

	private static void assertBetween(ZonedDateTime actual, ZonedDateTime from, ZonedDateTime to) {
		assertTrue(!actual.isBefore(from) && !actual.isAfter(to), () -> actual + " not in [" + from + ", " + to + "]");
	}

	@Test
	void aRefusedBucketIsStampedAndTheRunMovesOn() throws Exception {
		UUID other = UUID.randomUUID();
		dueBuckets(due(0), due(1));
		when(components.findCanonicalPurlsByOrgAndBucket(ORG.toString(), 0)).thenReturn(List.of(row(AGENT, "pkg:npm/a@1.0.0")));
		when(components.findCanonicalPurlsByOrgAndBucket(ORG.toString(), 1)).thenReturn(List.of(row(other, "pkg:npm/b@1.0.0")));
		when(dtrack.syntheticFetchRepositoryMeta(ORG, PROJECT))
				.thenThrow(new RepositoryMetaRejectedException("no project"))
				.thenReturn(new RepositoryMetaListing(List.of(new ComponentLatestVersion("pkg:npm/b@1.0.0", "2.0.0")), 1));

		RefreshResult result = service.refreshOrg(ORG);

		// the refused bucket's member is checked (keeping any version it had), the next one read
		Map<UUID, String> refused = new HashMap<>();
		refused.put(AGENT, null);
		assertEquals(List.of(refused, Map.of(other, "2.0.0")), stamps);
		assertEquals(2, result.buckets());
	}

	@Test
	void aRunStopsSpendingAtTheRequestBudget() throws Exception {
		ReflectionTestUtils.setField(service, "fetchLimit", 5);
		dueBuckets(due(0), due(1));
		when(components.findCanonicalPurlsByOrgAndBucket(eq(ORG.toString()), anyInt())).thenReturn(List.of(row(AGENT, "pkg:npm/a@1.0.0")));
		// a 500-component bucket: 5 pages
		when(dtrack.syntheticFetchRepositoryMeta(ORG, PROJECT)).thenReturn(new RepositoryMetaListing(List.of(), 5));

		RefreshResult result = service.refreshOrg(ORG);

		assertEquals(1, result.buckets());
		verify(dtrack, times(1)).syntheticFetchRepositoryMeta(ORG, PROJECT);
		verify(components, never()).findLatestVersionDueUnbucketed(anyString(), any(ZonedDateTime.class), anyInt());
	}

	@Test
	void unbucketedComponentsAskOneByOneAndATypeWithoutRepositoryIsLearnedOnce() throws Exception {
		UUID deb1 = UUID.randomUUID();
		UUID deb2 = UUID.randomUUID();
		UUID maven = UUID.randomUUID();
		UUID unknown = UUID.randomUUID();
		when(components.findLatestVersionDueUnbucketed(eq(ORG.toString()), any(ZonedDateTime.class), anyInt())).thenReturn(List.of(
				row(deb1, "pkg:deb/debian/tar@1.34?distro=debian-12"),
				row(maven, "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"),
				row(deb2, "pkg:deb/debian/zlib@1.2.13?distro=debian-12"),
				row(unknown, "pkg:npm/private-thing@1.0.0")));
		when(dtrack.fetchLatestVersion(ORG, "pkg:deb/debian/tar@1.34?distro=debian-12"))
				.thenReturn(new LatestVersionLookup(LatestVersionAnswer.NO_REPOSITORY_FOR_TYPE, null));
		when(dtrack.fetchLatestVersion(ORG, "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"))
				.thenReturn(new LatestVersionLookup(LatestVersionAnswer.FOUND, "2.26.1"));
		when(dtrack.fetchLatestVersion(ORG, "pkg:npm/private-thing@1.0.0"))
				.thenReturn(new LatestVersionLookup(LatestVersionAnswer.UNKNOWN_PACKAGE, null));

		RefreshResult result = service.refreshOrg(ORG);

		Map<UUID, String> expected = new HashMap<>();
		expected.put(deb1, null);
		expected.put(maven, "2.26.1");
		expected.put(deb2, null);
		expected.put(unknown, null);
		assertEquals(expected, stamps.get(0));
		// the second deb component costs no request
		assertEquals(3, result.requests());
		verify(dtrack, never()).fetchLatestVersion(ORG, "pkg:deb/debian/zlib@1.2.13?distro=debian-12");
	}

	@Test
	void componentsOfATypeWithoutRepositoryAreStampedPastTheRequestBudget() throws Exception {
		ReflectionTestUtils.setField(service, "fetchLimit", 1);
		List<CanonicalPurlRow> generics = new ArrayList<>();
		for (int i = 0; i < 5; i++) generics.add(row(UUID.randomUUID(), "pkg:generic/acme/tool" + i + "@1.0.0"));
		when(components.findLatestVersionDueUnbucketed(eq(ORG.toString()), any(ZonedDateTime.class), anyInt())).thenReturn(generics);
		when(dtrack.fetchLatestVersion(eq(ORG), anyString()))
				.thenReturn(new LatestVersionLookup(LatestVersionAnswer.NO_REPOSITORY_FOR_TYPE, null));

		RefreshResult result = service.refreshOrg(ORG);

		// one request learns the type; all five are stamped
		assertEquals(1, result.requests());
		assertEquals(5, stamps.get(0).size());
	}

	@Test
	void aRefusedPurlIsStampedAndTheRunMovesOn() throws Exception {
		UUID odd = UUID.randomUUID();
		UUID next = UUID.randomUUID();
		when(components.findLatestVersionDueUnbucketed(eq(ORG.toString()), any(ZonedDateTime.class), anyInt())).thenReturn(List.of(
				row(odd, "pkg:npm/odd@1.0.0"), row(next, "pkg:npm/next@1.0.0")));
		when(dtrack.fetchLatestVersion(ORG, "pkg:npm/odd@1.0.0")).thenThrow(new RepositoryMetaRejectedException("400"));
		when(dtrack.fetchLatestVersion(ORG, "pkg:npm/next@1.0.0")).thenReturn(new LatestVersionLookup(LatestVersionAnswer.FOUND, "3.0.0"));

		service.refreshOrg(ORG);

		Map<UUID, String> expected = new HashMap<>();
		expected.put(odd, null);
		expected.put(next, "3.0.0");
		assertEquals(expected, stamps.get(0));
	}

	@Test
	void aDependencyTrackErrorStopsTheRunAndStampsOnlyWhatWasAnswered() throws Exception {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		when(components.findLatestVersionDueUnbucketed(eq(ORG.toString()), any(ZonedDateTime.class), anyInt())).thenReturn(List.of(
				row(first, "pkg:npm/a@1.0.0"), row(second, "pkg:npm/b@1.0.0")));
		when(dtrack.fetchLatestVersion(ORG, "pkg:npm/a@1.0.0")).thenReturn(new LatestVersionLookup(LatestVersionAnswer.FOUND, "2.0.0"));
		when(dtrack.fetchLatestVersion(ORG, "pkg:npm/b@1.0.0")).thenThrow(new RelizaException("unreachable"));

		service.refreshOrg(ORG);

		assertEquals(Map.of(first, "2.0.0"), stamps.get(0));
		// and the org is left alone for a while rather than failing again next tick
		assertEquals(new RefreshResult(0, 0, 0), service.refreshOrg(ORG));
		verify(dtrack, times(1)).fetchLatestVersion(ORG, "pkg:npm/b@1.0.0");
	}

	@Test
	void aFailedBucketListingStampsNothing() throws Exception {
		dueBuckets(due(0));
		when(components.findCanonicalPurlsByOrgAndBucket(ORG.toString(), 0)).thenReturn(List.of(row(AGENT, "pkg:npm/a@1.0.0")));
		when(dtrack.syntheticFetchRepositoryMeta(ORG, PROJECT)).thenThrow(new RelizaException("503"));

		service.refreshOrg(ORG);

		verify(components, never()).stampLatestVersions(anyString(), anyString());
		verify(components, never()).findLatestVersionDueUnbucketed(anyString(), any(ZonedDateTime.class), anyInt());
	}
}
