/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.netflix.graphql.dgs.DgsData;

import graphql.ExecutionResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.saas.DeviceData;
import io.reliza.ws.saas.DeviceDataFetcher;
import io.reliza.ws.saas.FeatureSetDataFetcher;
import io.reliza.ws.saas.InstanceDataFetcher;

/**
 * TPF-22 (TPF-17 child 4): the eleven key fields of {@code InstanceDataFetcher},
 * {@code FeatureSetDataFetcher} and {@code DeviceDataFetcher} verify the credential, then check the
 * system state, then look their object up. A sealed or unlicensed instance answers one sentence for a
 * missing and an existing instance, product or device to every valid key; a Basic credential that
 * does not verify (a forged FREEFORM, INSTANCE, CLUSTER or ORGANIZATION_RW id, a forged FREEFORM id
 * naming a real organisation, a real id with a wrong secret) reads {@code Not authorized} for every
 * object, URI and namespace, on any instance; a valid key on a healthy instance reads what it read
 * before. Calls go through the DGS executor (the refusal text as the wire carries it), in the bearer
 * form and the Basic form.
 *
 * <p>Not pinned here: the CLUSTER key's {@code rearm-cd} fallbacks of rows 1 and 2 (no CLUSTER
 * fixture; the per-row statements the completeness check and case 1 hold every row to cover them), a
 * successful {@code switchInstanceProductFeatureSet} (needs a plan entry; harness
 * {@code instance_cluster.feature}).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ProgrammaticInstanceOrderTest extends ProgrammaticInstanceOrderTestBase {

	// ---------- completeness ----------

	@Test
	public void theTableCoversEveryKeyFieldOfTheThreeFetchers() throws IOException {
		String sdl = new String(new ClassPathResource("schema/programmatic.graphqls").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		Map<String, Set<String>> roots = ProgrammaticSchemaRegistry.rootFieldsOfExtensions(sdl);
		Set<String> programmaticRoots = new TreeSet<>(roots.getOrDefault("Query", Set.of()));
		programmaticRoots.addAll(roots.getOrDefault("Mutation", Set.of()));

		Set<String> expected = new TreeSet<>();
		for (Class<?> fetcher : List.of(InstanceDataFetcher.class, FeatureSetDataFetcher.class, DeviceDataFetcher.class)) {
			for (Method method : fetcher.getDeclaredMethods()) {
				DgsData d = method.getAnnotation(DgsData.class);
				if (null != d && ("Query".equals(d.parentType()) || "Mutation".equals(d.parentType()))
						&& programmaticRoots.contains(d.field())) {
					expected.add(d.field());
				}
			}
		}
		// No exclusion: every programmatic root field of the three classes has a key lane and a row.
		assertEquals(11, expected.size(), expected.toString());
		assertEquals(expected, new TreeSet<>(ROWS.stream().map(Row::field).toList()));
	}

	// ---------- case 1: the sealed sentence first, for every valid key ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void case1SealedSentenceFirstForEveryValidKey(Row r) throws RelizaException {
		World w = world();
		Ids a = w.a().ids();
		licenseStatus.setSystemSealed(true);

		String ownReal = refusal(call(r, w.a().key(r.kind()), a), r.field());
		assertEquals(SEALED, ownReal, r.field());
		if (r.keyInstance()) {
			// the key id names the instance: B's INSTANCE key names only its own
			assertEquals(SEALED, refusal(call(r, w.b().key(r.kind()), w.b().ids()), r.field()), r.field());
			// a forged INSTANCE id naming no instance (today: a server error on rows 6 and 8, a success
			// on row 7) reads Not authorized: the state is told to verified credentials only
			assertEquals(NOT_AUTHORIZED, refusal(asBasic(ApiTypeEnum.INSTANCE.name() + "__" + UUID.randomUUID(),
					randomSecret(), r.op(), r.vars().apply(a)), r.field()), r.field());
			return;
		}
		String ownMissing = refusal(call(r, w.a().key(r.kind()), a.random(r.names())), r.field());
		assertEquals(ownReal, ownMissing, r.field() + ": missing and existing read alike while sealed");

		String foreignReal = refusal(call(r, w.b().key(r.kind()), a), r.field());
		String foreignMissing = refusal(call(r, w.b().key(r.kind()), a.random(r.names())), r.field());
		assertEquals(SEALED, foreignReal, r.field());
		assertEquals(foreignReal, foreignMissing, r.field() + ": a foreign key learns nothing while sealed");
	}

	@Test
	public void case1InstDataNamingAnotherInstanceReadsTheSealedSentence() throws RelizaException {
		World w = world();
		Row r = row(6);
		licenseStatus.setSystemSealed(true);
		// the Wrong instance check (a request-shape refusal) now runs after the state check
		assertEquals(SEALED, refusal(asKey(w.a().instanceKey(), r.op(),
				Map.of("inst", Map.of("uuid", UUID.randomUUID().toString()))), r.field()));
	}

	@Test
	public void case1TheLicenceSentenceComesFirstToo() throws RelizaException {
		Row r = row(5);
		World w = world();
		Ids a = w.a().ids();
		licenseStatus.setLicenseValid(false);
		String ownMissing = refusal(call(r, w.a().key(r.kind()), a.random(r.names())), r.field());
		assertEquals(UNLICENSED, ownMissing);
		assertEquals(ownMissing, refusal(call(r, w.a().key(r.kind()), a), r.field()));
		assertEquals(ownMissing, refusal(call(r, w.b().key(r.kind()), a.random(r.names())), r.field()));
		assertEquals(ownMissing, refusal(call(r, w.b().key(r.kind()), a), r.field()));
	}

	// ---------- case 2: a foreign key, and a key one permission short, on a healthy instance ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	@SuppressWarnings("unchecked")
	public void case2ForeignKeyAndMissingPermissionStillRefused(Row r) throws RelizaException {
		World w = world();
		Ids a = w.a().ids();
		if (!r.keyInstance()) {
			assertEquals(NOT_AUTHORIZED, refusal(call(r, w.b().key(r.kind()), a), r.field()), r.field() + ": foreign key");
			assertEquals(NOT_AUTHORIZED, refusal(call(r, w.a().bare(), a), r.field()), r.field() + ": one permission short");
			return;
		}
		// rows 6 to 8 take INSTANCE or CLUSTER keys; a FREEFORM key's object uuid is its organisation,
		// which is no instance (rows 6 and 8 read a server error before the null-safety edit)
		ExecutionResult freeform = call(r, w.a().devopsWrite(), a);
		if (r.n() == 7) {
			Map<String, Object> out = (Map<String, Object>) result(freeform, r.field());
			assertEquals(Map.of("status", "received", "accepted", "0"), out, "row 7: unchanged (TPF-18)");
		} else {
			assertEquals(NOT_AUTHORIZED, refusal(freeform, r.field()), r.field() + ": a FREEFORM key");
		}
	}

	@Test
	public void case2RequestShapeTextsAreUnchanged() throws RelizaException {
		World w = world();
		Row inst = row(6);
		assertEquals(NOT_AUTHORIZED, refusal(asKey(w.a().instanceKey(), inst.op(),
				Map.of("inst", Map.of("uuid", UUID.randomUUID().toString()))), inst.field()),
				"Wrong instance, after the two statements");
		Row export = row(1);
		assertEquals("Requested instance does not match key", refusal(call(export, w.b().instanceKey(), w.a().ids()),
				export.field()));
	}

	// ---------- case 3: the happy path per row ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	@SuppressWarnings("unchecked")
	public void case3HappyPath(Row r) throws RelizaException {
		World w = world();
		Tenant t = w.a();
		Ids a = t.ids();
		// row 9 is called with a random product only (a real switch needs a plan entry)
		ExecutionResult res = r.n() == 9 ? null : call(r, t.key(r.kind()), a);
		switch (r.n()) {
			case 1 -> assertTrue(String.valueOf(result(res, r.field())).contains("CycloneDX"), "the live revision's BOM export");
			case 2 -> assertTrue(((Map<String, Object>) result(res, r.field())).containsKey("properties"), r.field());
			case 3 -> assertEquals("Wrong deliverable", refusal(res, r.field()), "the first text after the gates");
			case 4 -> assertEquals(Boolean.FALSE, result(res, r.field()));
			case 5 -> assertEquals(List.of(), result(res, r.field()));
			case 6 -> assertEquals(Map.of("status", "received"), result(res, r.field()));
			case 7 -> assertEquals(Map.of("status", "received", "accepted", "1"), result(res, r.field()));
			case 8 -> assertEquals(Boolean.TRUE, result(res, r.field()));
			case 9 -> assertEquals("Feature set does not belong to the specified product",
					refusal(call(r, t.devopsWrite(), a.random(Names.PRODUCT)), r.field()), "the service's own text, after the gates");
			case 10 -> assertEquals("At least one dependency-branch override is required", refusal(res, r.field()),
					"the service's own text, after the gates");
			case 11 -> {
				assertEquals(t.device().toString(), ((Map<String, Object>) result(res, r.field())).get("uuid"));
				Map<String, Object> legacy = (Map<String, Object>) result(call(r, t.orgRwKey(), a), r.field());
				assertEquals(t.device().toString(), legacy.get("uuid"), "the ORGANIZATION_RW branch");
			}
			default -> throw new IllegalStateException(r.toString());
		}
	}

	// ---------- case 4: today's not-found texts for a key of the organisation, healthy ----------

	@Test
	public void case4NotFoundTextsUnchangedOnAHealthyInstance() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		Ids a = t.ids();
		for (int n : List.of(1, 5)) {
			Row r = row(n);
			assertEquals("Instance not found", refusal(call(r, t.devopsRead(), a.random(Names.INSTANCE)), r.field()),
					r.field());
		}
		Ids missingProduct = a.random(Names.PRODUCT);
		assertEquals("Product " + missingProduct.product() + " not found",
				refusal(call(row(10), t.versioner(), missingProduct), row(10).field()));
		Ids missingDevice = a.random(Names.DEVICE);
		assertEquals("Device not found: " + missingDevice.device(),
				refusal(call(row(11), t.distributor(), missingDevice), row(11).field()));
		for (int n : List.of(1, 2, 3, 4)) {
			Row r = row(n);
			assertEquals("Instance UUID or URI must be supplied for this API key type",
					refusal(asKey(t.devopsRead(), r.uriOp(), m("u", NO_SUCH_URI)), r.field()), r.field());
		}
		assertEquals("Instance not found for URI " + NO_SUCH_URI,
				refusal(asKey(t.devopsRead(), row(5).uriOp(), m("u", NO_SUCH_URI)), row(5).field()));
	}

	// ---------- case 5: a refused call writes nothing ----------

	@Test
	public void case5ARefusedCallWritesNothing() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		Ids a = t.ids();
		DeviceData before = deviceService.getDeviceData(t.device()).orElseThrow();
		licenseStatus.setSystemSealed(true);

		for (int n : List.of(7, 8, 11)) {
			Row r = row(n);
			assertEquals(SEALED, refusal(call(r, t.key(r.kind()), a), r.field()), r.field());
		}
		licenseStatus.setSystemSealed(false);
		assertEquals(Boolean.FALSE, result(call(row(4), t.devopsRead(), a), row(4).field()), "no sealed cert is set");
		assertTrue(instanceDeploymentFailureService.getOpenFailures(t.instance()).isEmpty(), "no deployment failure is recorded");
		DeviceData after = deviceService.getDeviceData(t.device()).orElseThrow();
		assertTrue(Objects.equals(before.getActual(), after.getActual()), "the device's actual is unchanged");
		assertTrue(Objects.equals(before.getObservedState(), after.getObservedState()), "no observed state is reconciled");
	}

	// ---------- case 6: an unverified Basic credential is refused before any lookup ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void case6AnUnverifiedBasicCredentialReadsNotAuthorizedForEveryObject(Row r) throws RelizaException {
		World w = world();
		Tenant t = w.a();
		Ids a = t.ids();
		String secret = randomSecret();

		if (r.keyInstance()) {
			// the forged id alone (its random uuid is the key's instance), and A's real id with a wrong secret
			String forged = refusal(asBasic(ApiTypeEnum.INSTANCE.name() + "__" + UUID.randomUUID(), secret, r.op(),
					r.vars().apply(a)), r.field());
			String wrongSecret = refusal(asBasic(t.instanceKeyId(), secret, r.op(), r.vars().apply(a)), r.field());
			assertEquals(NOT_AUTHORIZED, forged, r.field());
			assertEquals(forged, wrongSecret, r.field());
		} else {
			String id = freeformId(UUID.randomUUID(), "0");
			String missing = refusal(asBasic(id, secret, r.op(), r.vars().apply(a.random(r.names()))), r.field());
			String real = refusal(asBasic(id, secret, r.op(), r.vars().apply(a)), r.field());
			assertEquals(NOT_AUTHORIZED, missing, r.field());
			assertEquals(missing, real, r.field() + ": missing and existing read alike to a forged FREEFORM id");
		}
		if (r.n() <= 4) {
			// the key id names the instance; the argument names the same uuid so no request-shape text answers
			UUID random = UUID.randomUUID();
			for (UUID object : List.of(random, t.instance())) {
				assertEquals(NOT_AUTHORIZED, refusal(asBasic(ApiTypeEnum.INSTANCE.name() + "__" + object, secret, r.op(),
						m("i", object)), r.field()), r.field() + " INSTANCE id on " + object);
			}
		}
		if (null != r.clusterOp()) {
			for (UUID object : List.of(UUID.randomUUID(), t.instance())) {
				assertEquals(NOT_AUTHORIZED, refusal(asBasic(ApiTypeEnum.CLUSTER.name() + "__" + object, secret,
						r.clusterOp(), r.vars().apply(a)), r.field()), r.field() + " CLUSTER id on " + object);
			}
		}
	}

	@Test
	public void case6AForgedIdNamingARealOrganisationLearnsNoUri() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		String id = ApiTypeEnum.FREEFORM.name() + "__" + UUID.randomUUID() + "__ORGANIZATION__" + t.org() + "__ord__0";
		for (int n : List.of(1, 2, 3, 4)) {
			Row r = row(n);
			String real = refusal(asBasic(id, randomSecret(), r.uriOp(), m("u", t.instanceUri())), r.field());
			String unknown = refusal(asBasic(id, randomSecret(), r.uriOp(), m("u", NO_SUCH_URI)), r.field());
			assertEquals(NOT_AUTHORIZED, real, r.field());
			assertEquals(real, unknown, r.field() + ": a URI in the organisation the id names");
		}
	}

	@Test
	public void case6TheLegacyLaneOfReportDeviceStateLearnsNoDevice() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		Row r = row(11);
		for (String id : List.of(ApiTypeEnum.ORGANIZATION_RW.name() + "__" + UUID.randomUUID(), t.orgRwKeyId())) {
			String missing = refusal(asBasic(id, randomSecret(), r.op(), r.vars().apply(t.ids().random(Names.DEVICE))),
					r.field());
			String real = refusal(asBasic(id, randomSecret(), r.op(), r.vars().apply(t.ids())), r.field());
			assertEquals(NOT_AUTHORIZED, missing, id);
			assertEquals(missing, real, id + ": missing and existing device read alike");
		}
	}

	// ---------- case 7: a real id with a wrong secret, the state, and no access row ----------

	@Test
	public void case7WrongSecretLearnsNoUriNoObjectAndNoState() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		long readBefore = accessRows(t.org(), t.devopsRead().getUuid());
		long instanceBefore = accessRows(t.org(), t.instanceKey().getUuid());
		long orgRwBefore = accessRows(t.org(), t.orgRwKey().getUuid());
		String textOrgId = ApiTypeEnum.FREEFORM.name() + "__" + UUID.randomUUID() + "__ORGANIZATION__" + t.org()
				+ "__ord__0";

		for (boolean sealed : List.of(false, true)) {
			licenseStatus.setSystemSealed(sealed);
			String state = "sealed=" + sealed;
			Row list = row(5);
			String realUri = refusal(asBasic(t.devopsReadKeyId(), randomSecret(), list.uriOp(), m("u", t.instanceUri())),
					list.field());
			String unknownUri = refusal(asBasic(t.devopsReadKeyId(), randomSecret(), list.uriOp(), m("u", NO_SUCH_URI)),
					list.field());
			assertEquals(NOT_AUTHORIZED, realUri, state + ": the state is told to verified credentials only");
			assertEquals(realUri, unknownUri, state);
			for (int n : List.of(1, 8)) {
				Row r = row(n);
				assertEquals(NOT_AUTHORIZED, refusal(asBasic(t.instanceKeyId(), randomSecret(), r.op(),
						r.vars().apply(t.ids())), r.field()), state + " " + r.field());
			}
			Row device = row(11);
			assertEquals(NOT_AUTHORIZED, refusal(asBasic(t.orgRwKeyId(), randomSecret(), device.op(),
					device.vars().apply(t.ids())), device.field()), state + " " + device.field());
			if (sealed) {
				Row export = row(1);
				assertEquals(NOT_AUTHORIZED, refusal(asBasic(textOrgId, randomSecret(), export.uriOp(),
						m("u", t.instanceUri())), export.field()), state + ": a forged id naming the organisation");
			}
		}
		licenseStatus.setSystemSealed(false);
		assertEquals(readBefore, accessRows(t.org(), t.devopsRead().getUuid()), "no access row for the FREEFORM key");
		assertEquals(instanceBefore, accessRows(t.org(), t.instanceKey().getUuid()), "no access row for the INSTANCE key");
		assertEquals(orgRwBefore, accessRows(t.org(), t.orgRwKey().getUuid()), "no access row for the ORGANIZATION_RW key");
	}

	// ---------- case 8: a valid Basic credential still works ----------

	@Test
	@SuppressWarnings("unchecked")
	public void case8AValidBasicCredentialStillWorks() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		Ids a = t.ids();

		Row inst = row(6);
		assertEquals(Map.of("status", "received"), result(asBasic(t.instanceKeyId(), t.instanceSecret(), inst.op(),
				inst.vars().apply(a)), inst.field()));
		Row export = row(1);
		assertEquals("Requested instance does not match key", refusal(asBasic(t.instanceKeyId(), t.instanceSecret(),
				export.op(), export.vars().apply(a.random(Names.INSTANCE))), export.field()));

		Row list = row(5);
		assertEquals(List.of(), result(asBasic(t.devopsReadKeyId(), t.devopsReadSecret(), list.op(), list.vars().apply(a)),
				list.field()));

		Row device = row(11);
		Map<String, Object> reported = (Map<String, Object>) result(asBasic(t.orgRwKeyId(), t.orgRwSecret(), device.op(),
				device.vars().apply(a)), device.field());
		assertEquals(t.device().toString(), reported.get("uuid"), "a device phone-home over Basic");
	}
}
