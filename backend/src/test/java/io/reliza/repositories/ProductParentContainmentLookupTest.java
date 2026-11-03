/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.reliza.model.Release;
import io.reliza.repositories.ReleaseRepository.MetricsComputeCandidate;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The parent-release lookups moved from jsonpath (jsonb_path_match on
 * '$.parentReleases[*].release', and jsonb_contains over jsonb_path_query_array) to whole-column
 * {@code record_data @> ...}, which idx_releases_record_data_gin serves. Each rewritten statement
 * must return exactly the rows the old form returned. The old forms are kept here verbatim and run
 * next to the repository methods on the same seeded products: several parents per product, a
 * product without the child, an empty product, a product of another organization over the same
 * child uuid, and for the ALL lookup full and partial matches.
 */
@SpringBootTest(classes = {App.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductParentContainmentLookupTest {

	private static final String OLD_ONE = " where r.record_data->>'org' = :orgUuidAsString AND jsonb_path_match(r.record_data, "
			+ "cast(concat('$.parentReleases[*].release == ', '\"', :releaseUuidAsString, '\"') as jsonpath))";

	private static final String OLD_THESE = "select r.uuid from rearm.releases r where r.record_data->>'org' = :orgUuidAsString"
			+ " AND jsonb_contains(jsonb_path_query_array(record_data, '$.parentReleases[*].release'),"
			+ " cast (:releaseArrString as jsonb))";

	@Autowired private JdbcTemplate jdbc;
	@Autowired private NamedParameterJdbcTemplate named;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ReleaseRepository releaseRepository;

	private UUID org;
	private UUID otherOrg;
	/** Leaf releases (no parents): the child c and the other components x, y, z. */
	private UUID c, x, y, z;
	private final Map<String, UUID> products = new LinkedHashMap<>();

	@BeforeAll
	void seed() {
		org = testInitializer.obtainOrganization().getUuid();
		otherOrg = testInitializer.obtainOrganization().getUuid();
		c = insert(org, List.of());
		x = insert(org, List.of());
		y = insert(org, List.of());
		z = insert(org, List.of());
		products.put("P1 x,c,y", insert(org, List.of(x, c, y)));
		products.put("P2 c,z", insert(org, List.of(c, z)));
		products.put("P3 x,y (no child)", insert(org, List.of(x, y)));
		products.put("P4 empty", insert(org, List.of()));
		products.put("P5 x,z,c,y", insert(org, List.of(x, z, c, y)));
		products.put("Q1 other org c,x", insert(otherOrg, List.of(c, x)));
	}

	/** A release row of {@code o} whose parentReleases name {@code parents}; ASSEMBLED, no branch or component. */
	private UUID insert(UUID o, List<UUID> parents) {
		UUID u = UUID.randomUUID();
		String parentsJson = parents.stream().map(p -> "{\"release\": \"" + p + "\", \"artifact\": null}")
				.collect(Collectors.joining(",", "[", "]"));
		// lastScanned far in the future: settled, so no metrics finder picks the row up.
		jdbc.update("INSERT INTO rearm.releases (uuid, record_data, metrics) VALUES (?, jsonb_build_object('org', ?::text,"
				+ " 'version', ?::text, 'lifecycle', 'ASSEMBLED', 'parentReleases', ?::jsonb),"
				+ " jsonb_build_object('lastScanned', extract(epoch from now() + interval '10 years')))",
				u, o.toString(), "ppc-" + u, parentsJson);
		return u;
	}

	private Set<UUID> p(String... names) {
		Set<UUID> s = new HashSet<>();
		for (String n : names) s.add(products.get(n));
		return s;
	}

	private Set<UUID> oldOne(String select, UUID release) {
		return new HashSet<>(named.queryForList(select + OLD_ONE, new MapSqlParameterSource()
				.addValue("orgUuidAsString", org.toString()).addValue("releaseUuidAsString", release.toString()), UUID.class));
	}

	private Set<UUID> oldThese(String arr) {
		return new HashSet<>(named.queryForList(OLD_THESE, new MapSqlParameterSource()
				.addValue("orgUuidAsString", org.toString()).addValue("releaseArrString", arr), UUID.class));
	}

	private static String arr(UUID... uuids) {
		return Arrays.stream(uuids).map(u -> "\"" + u + "\"").collect(Collectors.joining(",", "[", "]"));
	}

	/** Asserts all three single-release statements against the old form and the expected set. */
	private void assertOneLookup(UUID release, Set<UUID> expected) {
		String o = org.toString();
		String r = release.toString();
		assertEquals(expected, oldOne("select r.uuid from rearm.releases r", release), "sanity: the old form");
		assertEquals(expected, releaseRepository.findProductsByRelease(o, r).stream().map(Release::getUuid)
				.collect(Collectors.toSet()), "findProductsByRelease");
		assertEquals(expected, releaseRepository.findProductMetricsCandidatesByRelease(o, r).stream()
				.map(MetricsComputeCandidate::getUuid).collect(Collectors.toSet()), "findProductMetricsCandidatesByRelease");
		assertEquals(oldOne("select r.uuid from rearm.releases r", release),
				new HashSet<>(releaseRepository.findProductUuidsToRequeueByRelease(o, r)),
				"findProductUuidsToRequeueByRelease (every fixture row is push-eligible)");
	}

	@Test
	void theChildIsFoundInEveryProductOfItsOrganizationThatNamesIt() {
		assertOneLookup(c, p("P1 x,c,y", "P2 c,z", "P5 x,z,c,y"));
	}

	@Test
	void anotherParentFindsItsOwnProducts() {
		assertOneLookup(x, p("P1 x,c,y", "P3 x,y (no child)", "P5 x,z,c,y"));
		assertOneLookup(z, p("P2 c,z", "P5 x,z,c,y"));
	}

	@Test
	void aReleaseInNoProductFindsNothing() {
		assertOneLookup(UUID.randomUUID(), Set.of());
		assertOneLookup(products.get("P1 x,c,y"), Set.of());
	}

	@Test
	void theOtherOrganizationFindsOnlyItsOwnProduct() {
		assertEquals(Set.of(products.get("Q1 other org c,x")), releaseRepository.findProductsByRelease(otherOrg.toString(),
				c.toString()).stream().map(Release::getUuid).collect(Collectors.toSet()));
	}

	@Test
	void theAllLookupKeepsOnlyProductsHoldingEveryRelease() {
		Map<String, Set<UUID>> cases = new LinkedHashMap<>();
		cases.put(arr(c), p("P1 x,c,y", "P2 c,z", "P5 x,z,c,y"));
		cases.put(arr(c, x), p("P1 x,c,y", "P5 x,z,c,y"));
		cases.put(arr(x, c), p("P1 x,c,y", "P5 x,z,c,y"));
		cases.put(arr(c, z), p("P2 c,z", "P5 x,z,c,y"));
		cases.put(arr(x, y, z), p("P5 x,z,c,y"));
		cases.put(arr(c, x, y, z), p("P5 x,z,c,y"));
		cases.put(arr(c, UUID.randomUUID()), Set.of());
		cases.forEach((input, expected) -> {
			assertEquals(expected, oldThese(input), "sanity: the old form for " + input);
			assertEquals(expected, releaseRepository.findProductsByReleases(org.toString(), input).stream()
					.map(Release::getUuid).collect(Collectors.toSet()), "findProductsByReleases " + input);
		});
	}

	@Test
	void theAllLookupOfNoReleasesStillReturnsEveryReleaseOfTheOrganization() {
		Set<UUID> old = oldThese("[]");
		assertEquals(9, old.size(), "sanity: the old form returns all nine rows of the organization (Q1 is the other one)");
		assertEquals(old, releaseRepository.findProductsByReleases(org.toString(), "[]").stream()
				.map(Release::getUuid).collect(Collectors.toSet()));
	}
}
