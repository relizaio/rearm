/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.EncryptionService;
import io.reliza.service.SbomSigningKeyService;

/**
 * The SBOM signing key GraphQL surface (SCORE-32, T-5): org ADMIN runs all four operations, an org
 * member lists only, a user of another org is refused everything including a revoke of this org's
 * key, and no response carries private material.
 *
 * <p>Over {@link SbomScoreGraphqlTestBase} for its Spring context and its {@code as} helper.
 */
class SbomSigningKeyDataFetcherTest extends SbomScoreGraphqlTestBase {

	private static final String FIELDS = "{ uuid org keyId algorithm status source publicKeyJwk publicKeyPem"
			+ " createdDate retiredAt revokedAt }";
	private static final String LIST = "query($o: ID!) { sbomSigningKeys(orgUuid: $o) " + FIELDS + " }";
	private static final String GENERATE = "mutation($o: ID!) { generateSbomSigningKey(orgUuid: $o) " + FIELDS + " }";
	private static final String IMPORT = "mutation($o: ID!, $p: String!) { importSbomSigningKey(orgUuid: $o,"
			+ " privateKeyPem: $p) " + FIELDS + " }";
	private static final String REVOKE = "mutation($u: ID!) { revokeSbomSigningKey(uuid: $u) " + FIELDS + " }";

	@Autowired private SbomSigningKeyService sbomSigningKeyService;
	@Autowired private EncryptionService encryptionService;

	private UserData member(Organization org, PermissionType type) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Signing " + tag, "score32-" + tag + "@score.io", true, List.of(org.getUuid()),
				"score32-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), type,
				List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private static Map<String, Object> orgVars(Organization org) {
		return Map.of("o", org.getUuid().toString());
	}

	private static String pem() throws Exception {
		return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(
				KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> key(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (Map<String, Object>) ((Map<String, Object>) r.getData()).get(field);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> keys(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("sbomSigningKeys");
	}

	@Test
	void orgAdmin_runsAllFour() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);

		Map<String, Object> generated = key(as(admin, GENERATE, orgVars(org)), "generateSbomSigningKey");
		assertEquals("CURRENT", generated.get("status"));
		assertEquals("GENERATED", generated.get("source"));
		assertEquals("Ed25519", generated.get("algorithm"));
		assertEquals(generated.get("uuid"), generated.get("keyId"));
		assertEquals(org.getUuid().toString(), generated.get("org"));
		Map<String, Object> jwk = Utils.OM.readValue((String) generated.get("publicKeyJwk"), Map.class);
		assertEquals(List.of("kty", "crv", "x"), List.copyOf(jwk.keySet()));
		assertEquals("OKP", jwk.get("kty"));
		assertTrue(((String) generated.get("publicKeyPem")).startsWith("-----BEGIN PUBLIC KEY-----"));

		Map<String, Object> imported = key(as(admin, IMPORT, Map.of("o", org.getUuid().toString(), "p", pem())),
				"importSbomSigningKey");
		assertEquals("IMPORTED", imported.get("source"));
		assertEquals("CURRENT", imported.get("status"));

		Map<String, Object> revoked = key(as(admin, REVOKE, Map.of("u", generated.get("uuid"))), "revokeSbomSigningKey");
		assertEquals("REVOKED", revoked.get("status"));
		assertTrue(null != revoked.get("retiredAt") && null != revoked.get("revokedAt"), revoked.toString());

		List<Map<String, Object>> listed = keys(as(admin, LIST, orgVars(org)));
		assertEquals(List.of(imported.get("uuid"), generated.get("uuid")), listed.stream().map(k -> k.get("uuid")).toList());
	}

	@Test
	void member_canListOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		SbomSigningKeyData k = sbomSigningKeyService.generate(org.getUuid(), WU);
		for (PermissionType type : List.of(PermissionType.READ_ONLY, PermissionType.READ_WRITE)) {
			UserData m = member(org, type);
			assertEquals(1, keys(as(m, LIST, orgVars(org))).size(), type.name());
			assertEquals("Not authorized", errorMessage(as(m, GENERATE, orgVars(org))), type.name());
			assertEquals("Not authorized", errorMessage(as(m, IMPORT, Map.of("o", org.getUuid().toString(),
					"p", pem()))), type.name());
			assertEquals("Not authorized", errorMessage(as(m, REVOKE, Map.of("u", k.getUuid().toString()))), type.name());
		}
		assertEquals(List.of(k.getUuid()), sbomSigningKeyService.listByOrg(org.getUuid()).stream()
				.map(SbomSigningKeyData::getUuid).toList(), "nothing changed");
		assertEquals("CURRENT", sbomSigningKeyService.getKey(k.getUuid()).orElseThrow().getStatus().name());
	}

	@Test
	void foreignUser_isRefusedOnAllFour() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		SbomSigningKeyData k = sbomSigningKeyService.generate(org.getUuid(), WU);
		UserData foreignAdmin = member(other, PermissionType.ADMIN);

		assertEquals("Not authorized", errorMessage(as(foreignAdmin, LIST, orgVars(org))));
		assertEquals("Not authorized", errorMessage(as(foreignAdmin, GENERATE, orgVars(org))));
		assertEquals("Not authorized", errorMessage(as(foreignAdmin, IMPORT, Map.of("o", org.getUuid().toString(),
				"p", pem()))));
		assertEquals("Not authorized", errorMessage(as(foreignAdmin, REVOKE, Map.of("u", k.getUuid().toString()))));
		// an unknown key is refused the same way, so a refusal tells nothing about which keys exist
		assertEquals("Not authorized", errorMessage(as(foreignAdmin, REVOKE, Map.of("u", UUID.randomUUID().toString()))));
		// and an unknown org too
		assertEquals("Not authorized", errorMessage(as(foreignAdmin, LIST, Map.of("o", UUID.randomUUID().toString()))));

		assertEquals("CURRENT", sbomSigningKeyService.getKey(k.getUuid()).orElseThrow().getStatus().name());
		assertEquals(1, sbomSigningKeyService.listByOrg(org.getUuid()).size());
		assertTrue(sbomSigningKeyService.listByOrg(other.getUuid()).isEmpty());
		// the foreign admin's own org is theirs to manage
		assertEquals("CURRENT", key(as(foreignAdmin, GENERATE, orgVars(other)), "generateSbomSigningKey").get("status"));
		assertEquals(1, keys(as(foreignAdmin, LIST, orgVars(other))).size());
	}

	@Test
	void responsesCarryNoPrivateMaterial() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		String importedPem = pem();
		String refusedPem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(
				KeyPairGenerator.getInstance("EC").generateKeyPair().getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";

		List<ExecutionResult> results = new ArrayList<>();
		ExecutionResult gen = as(admin, GENERATE, orgVars(org));
		results.add(gen);
		results.add(as(admin, IMPORT, Map.of("o", org.getUuid().toString(), "p", importedPem)));
		ExecutionResult refused = as(admin, IMPORT, Map.of("o", org.getUuid().toString(), "p", refusedPem));
		assertEquals(SbomSigningKeyService.IMPORT_FORMAT_MESSAGE, errorMessage(refused));
		results.add(refused);
		results.add(as(admin, REVOKE, Map.of("u", key(gen, "generateSbomSigningKey").get("uuid"))));
		results.add(as(admin, LIST, orgVars(org)));
		// asking for the private field is a validation error that names no value
		ExecutionResult asked = as(admin, "query($o: ID!) { sbomSigningKeys(orgUuid: $o) { uuid encryptedPrivateKey } }",
				orgVars(org));
		assertFalse(asked.getErrors().isEmpty());
		assertNull(asked.getData());

		String text = results.stream().map(r -> Utils.OM.writeValueAsString(r.toSpecification()))
				.collect(Collectors.joining("\n"));
		for (SbomSigningKeyData k : sbomSigningKeyService.listByOrg(org.getUuid())) {
			String pkcs8 = encryptionService.decrypt(k.getEncryptedPrivateKey());
			assertFalse(text.contains(pkcs8), "base64 PKCS#8 in a response");
			assertFalse(text.contains(k.getEncryptedPrivateKey()), "ciphertext in a response");
		}
		// the refusal names the expected armour in words; no response carries the armour itself
		assertFalse(text.contains("-----BEGIN PRIVATE KEY") || text.contains("-----END PRIVATE KEY"),
				"PEM armour in a response");
		assertFalse(text.contains(importedPem.split("\n")[1].substring(0, 40)), "the imported PEM body in a response");
		assertFalse(text.contains(refusedPem.split("\n")[1].substring(0, 40)), "the refused PEM body in a response");
		assertFalse(text.contains("encryptedPrivateKey"), text);
		// the refused query names the field and nothing else of the key
		String askedText = Utils.OM.writeValueAsString(asked.toSpecification());
		for (SbomSigningKeyData k : sbomSigningKeyService.listByOrg(org.getUuid())) {
			assertFalse(askedText.contains(k.getEncryptedPrivateKey()), askedText);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void theTypeDeclaresPublicFieldsOnly() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ExecutionResult r = as(member(org, PermissionType.READ_ONLY),
				"{ __type(name: \"SbomSigningKey\") { fields { name } } }", Map.of());
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		List<Map<String, Object>> fields = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) r
				.getData()).get("__type")).get("fields");
		assertEquals(Set.of("uuid", "org", "keyId", "algorithm", "status", "source", "publicKeyJwk", "publicKeyPem",
				"createdDate", "retiredAt", "revokedAt"),
				fields.stream().map(f -> (String) f.get("name")).collect(Collectors.toSet()));
	}

	@Test
	void list_ordersCurrentFirst() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData reader = member(org, PermissionType.READ_ONLY);
		SbomSigningKeyData a = sbomSigningKeyService.generate(org.getUuid(), WU);
		SbomSigningKeyData b = sbomSigningKeyService.generate(org.getUuid(), WU);
		SbomSigningKeyData c = sbomSigningKeyService.generate(org.getUuid(), WU);
		sbomSigningKeyService.revoke(c.getUuid(), WU);
		SbomSigningKeyData d = sbomSigningKeyService.generate(org.getUuid(), WU);
		// d CURRENT, then c, b, a newest first whatever their status
		assertEquals(List.of(d.getUuid(), c.getUuid(), b.getUuid(), a.getUuid()).stream().map(UUID::toString).toList(),
				keys(as(reader, LIST, orgVars(org))).stream().map(k -> k.get("uuid")).toList());
		assertEquals(List.of("CURRENT", "REVOKED", "RETIRED", "RETIRED"),
				keys(as(reader, LIST, orgVars(org))).stream().map(k -> k.get("status")).toList());
	}
}
