/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.saas.ClientData;
import io.reliza.model.saas.DeviceData;
import io.reliza.model.saas.Instance;
import io.reliza.model.saas.ShippedProduct;
import io.reliza.model.saas.ShippedProductData;
import io.reliza.model.saas.SiteData;
import io.reliza.repositories.saas.ShippedProductRepository;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.saas.ClientService;
import io.reliza.service.saas.DeviceService;
import io.reliza.service.saas.InstanceDeploymentFailureService;
import io.reliza.service.saas.InstanceService;
import io.reliza.service.saas.SiteService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Shared rig of {@link ProgrammaticInstanceOrderTest} (TPF-22, TPF-17 child 4): the field table of
 * the eleven key fields of {@code InstanceDataFetcher}, {@code FeatureSetDataFetcher} and
 * {@code DeviceDataFetcher}, two tenants with an instance, a product with a feature set, a device and
 * the keys those fields serve, and the two call forms (the principal the programmatic filter leaves
 * after a bearer, and a Basic header the resolver parses and verifies itself).
 */
abstract class ProgrammaticInstanceOrderTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	protected static final String NOT_AUTHORIZED = "Not authorized";
	protected static final String SEALED = "System is sealed. Please unseal the system first.";
	protected static final String UNLICENSED = "License is invalid or expired. Please upload a valid license.";
	protected static final String NO_SUCH_URI = "https://no.such/";
	protected static final String DIGEST = "sha256:" + "0".repeat(64);

	@Autowired protected TestInitializer testInitializer;
	@Autowired protected DgsQueryExecutor dgsQueryExecutor;
	@Autowired protected InstanceService instanceService;
	@Autowired protected ComponentService componentService;
	@Autowired protected BranchService branchService;
	@Autowired protected ClientService clientService;
	@Autowired protected SiteService siteService;
	@Autowired protected DeviceService deviceService;
	@Autowired protected ShippedProductRepository shippedProductRepository;
	@Autowired protected InstanceDeploymentFailureService instanceDeploymentFailureService;
	@Autowired protected ApiKeyService apiKeyService;
	@Autowired protected ApiKeyAccessService apiKeyAccessService;
	@Autowired protected LicenseStatus licenseStatus;
	@Autowired protected PlatformTransactionManager txManager;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; each case sets the state it needs. */
	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	// ---------- the field table ----------

	/** Which key a row is called with. */
	enum KeyKind { DEVOPS_READ, DEVOPS_WRITE, VERSIONER, DISTRIBUTOR, INSTANCE }

	/** Which object a row names in its arguments, so a case can swap it for a random uuid. */
	enum Names { INSTANCE, PRODUCT, DEVICE, KEY_INSTANCE }

	/** The objects a call can name. */
	record Ids(UUID instance, UUID product, UUID featureSet, UUID device) {
		Ids random(Names names) {
			return switch (names) {
				case INSTANCE -> new Ids(UUID.randomUUID(), product, featureSet, device);
				case PRODUCT -> new Ids(instance, UUID.randomUUID(), featureSet, device);
				case DEVICE -> new Ids(instance, product, featureSet, UUID.randomUUID());
				case KEY_INSTANCE -> this;
			};
		}
	}

	/**
	 * One key field. {@code clusterOp} is the operation a CLUSTER key id sends (with a namespace where
	 * the field takes one); {@code uriOp} names the instance by {@code instanceUri: $u} instead of a
	 * uuid (rows 1 to 5 only).
	 */
	record Row(int n, String field, String op, String clusterOp, String uriOp,
			Function<Ids, Map<String, Object>> vars, KeyKind kind, Names names) {
		@Override
		public String toString() {
			return n + " " + field;
		}

		boolean keyInstance() {
			return Names.KEY_INSTANCE == names;
		}
	}

	protected static Map<String, Object> m(Object... kv) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], null == kv[i + 1] ? null : String.valueOf(kv[i + 1]));
		return out;
	}

	private static Map<String, Object> emptyInstanceInput(Ids i) {
		Map<String, Object> out = new HashMap<>();
		out.put("inst", new HashMap<String, Object>());
		return out;
	}

	protected static final List<Row> ROWS = List.of(
			new Row(1, "getInstanceRevisionCycloneDxExportProg",
					"query($i: ID) { getInstanceRevisionCycloneDxExportProg(instanceUuid: $i, revision: -1) }",
					"query($i: ID) { getInstanceRevisionCycloneDxExportProg(instanceUuid: $i, revision: -1, namespace: \"ns\") }",
					"query($u: String) { getInstanceRevisionCycloneDxExportProg(instanceUri: $u, revision: -1) }",
					i -> m("i", i.instance()), KeyKind.DEVOPS_READ, Names.INSTANCE),
			new Row(2, "getInstancePropSecrets",
					"query($i: ID) { getInstancePropSecrets(instanceUuid: $i, namespace: \"ns\", revision: -1) { properties { key } } }",
					"query($i: ID) { getInstancePropSecrets(instanceUuid: $i, namespace: \"ns\", revision: -1) { properties { key } } }",
					"query($u: String) { getInstancePropSecrets(instanceUri: $u, namespace: \"ns\", revision: -1) { properties { key } } }",
					i -> m("i", i.instance()), KeyKind.DEVOPS_READ, Names.INSTANCE),
			new Row(3, "deliverableDownloadSecrets",
					"query($i: ID) { deliverableDownloadSecrets(instanceUuid: $i, deliverableDigest: \"sha256:0\") { type } }",
					"query($i: ID) { deliverableDownloadSecrets(instanceUuid: $i, deliverableDigest: \"sha256:0\", namespace: \"ns\") { type } }",
					"query($u: String) { deliverableDownloadSecrets(instanceUri: $u, deliverableDigest: \"sha256:0\") { type } }",
					i -> m("i", i.instance()), KeyKind.DEVOPS_READ, Names.INSTANCE),
			new Row(4, "isInstanceHasSealedSecretCert",
					"query($i: ID) { isInstanceHasSealedSecretCert(instanceUuid: $i) }",
					"query($i: ID) { isInstanceHasSealedSecretCert(instanceUuid: $i) }",
					"query($u: String) { isInstanceHasSealedSecretCert(instanceUri: $u) }",
					i -> m("i", i.instance()), KeyKind.DEVOPS_READ, Names.INSTANCE),
			new Row(5, "listInstanceProductFeatureSets",
					"query($i: ID) { listInstanceProductFeatureSets(instanceUuid: $i, namespace: \"default\") { namespace } }",
					null,
					"query($u: String) { listInstanceProductFeatureSets(instanceUri: $u, namespace: \"default\") { namespace } }",
					i -> m("i", i.instance()), KeyKind.DEVOPS_READ, Names.INSTANCE),
			new Row(6, "instData",
					"mutation($inst: InstanceDataInput!) { instData(instance: $inst) }",
					null, null,
					ProgrammaticInstanceOrderTestBase::emptyInstanceInput, KeyKind.INSTANCE, Names.KEY_INSTANCE),
			new Row(7, "instanceDeploymentEventsProgrammatic",
					"mutation { instanceDeploymentEventsProgrammatic(events: [{ deploymentName: \"d\" }]) }",
					"mutation { instanceDeploymentEventsProgrammatic(events: [{ deploymentName: \"d\", namespace: \"ns\" }]) }",
					null,
					i -> m(), KeyKind.INSTANCE, Names.KEY_INSTANCE),
			new Row(8, "setInstanceSealedSecretCert",
					"mutation { setInstanceSealedSecretCert(sealedCert: \"c\") }",
					null, null,
					i -> m(), KeyKind.INSTANCE, Names.KEY_INSTANCE),
			new Row(9, "switchInstanceProductFeatureSet",
					"mutation($i: ID, $p: ID!, $f: ID!) { switchInstanceProductFeatureSet(instanceUuid: $i, productUuid: $p,"
							+ " featureSetUuid: $f) { uuid } }",
					null, null,
					i -> m("i", i.instance(), "p", i.product(), "f", i.featureSet()), KeyKind.DEVOPS_WRITE, Names.INSTANCE),
			new Row(10, "versionFeatureSet",
					"mutation($p: ID!) { versionFeatureSet(productUuid: $p, overrides: []) { uuid } }",
					null, null,
					i -> m("p", i.product()), KeyKind.VERSIONER, Names.PRODUCT),
			new Row(11, "reportDeviceState",
					"mutation($d: ID!) { reportDeviceState(report: { device: $d, observed: [{ digest: \"" + DIGEST + "\" }] })"
							+ " { uuid } }",
					null, null,
					i -> m("d", i.device()), KeyKind.DISTRIBUTOR, Names.DEVICE));

	static Stream<Row> rows() {
		return ROWS.stream();
	}

	protected static Row row(int n) {
		return ROWS.get(n - 1);
	}

	// ---------- fixtures ----------

	/**
	 * An organisation with a STANDALONE instance and its INSTANCE key (with its secret), a product with
	 * a second feature set, a device on a shipment, FREEFORM keys holding DEVOPS_READ, DEVOPS_READ and
	 * DEVOPS_WRITE, VERSION_FEATURESET, DISTRIBUTION or nothing at organisation scope, and an
	 * ORGANIZATION_RW key; the secrets of the INSTANCE, DEVOPS_READ and ORGANIZATION_RW keys.
	 */
	record Tenant(UUID org, UUID instance, String instanceUri, ApiKey instanceKey, String instanceSecret,
			UUID product, UUID featureSet, UUID device, ApiKey devopsRead, String devopsReadSecret, ApiKey devopsWrite,
			ApiKey versioner, ApiKey distributor, ApiKey bare, ApiKey orgRwKey, String orgRwSecret) {

		Ids ids() {
			return new Ids(instance, product, featureSet, device);
		}

		ApiKey key(KeyKind kind) {
			return switch (kind) {
				case DEVOPS_READ -> devopsRead;
				case DEVOPS_WRITE -> devopsWrite;
				case VERSIONER -> versioner;
				case DISTRIBUTOR -> distributor;
				case INSTANCE -> instanceKey;
			};
		}

		String instanceKeyId() {
			return ApiTypeEnum.INSTANCE.name() + "__" + instance;
		}

		String devopsReadKeyId() {
			return freeformId(org, devopsRead.getKeyOrder());
		}

		String orgRwKeyId() {
			return ApiTypeEnum.ORGANIZATION_RW.name() + "__" + org;
		}
	}

	record World(Tenant a, Tenant b) {}

	protected World world() throws RelizaException {
		return new World(tenant(), tenant());
	}

	protected Tenant tenant() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String uri = "https://tpf22-" + UUID.randomUUID() + ".example.com";
		// createInstance runs @Modifying updates that need a surrounding transaction.
		Instance inst = new TransactionTemplate(txManager).execute(status -> {
			try {
				return instanceService.createInstance(uri, org, null, WU);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		UUID instance = inst.getUuid();
		String instanceSecret = apiKeyService.setObjectApiKey(instance, ApiTypeEnum.INSTANCE, org, null,
				"instance order test", false, WU);
		ApiKey instanceKey = apiKeyService.getApiKeyByObjUuidTypeOrder(instance, ApiTypeEnum.INSTANCE, null, org)
				.orElseThrow();

		Component product = componentService.createComponent("tpf22_" + UUID.randomUUID(), org,
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		branchService.createBranch("tpf22-fs", product.getUuid(), BranchType.REGULAR, WU);
		UUID featureSet = branchService.getBaseBranchOfComponent(product.getUuid()).orElseThrow().getUuid();
		UUID device = device(org);

		ApiKey devopsRead = freeformKey(org, PermissionType.READ_ONLY, Set.of(PermissionFunction.DEVOPS_READ));
		String devopsReadSecret = apiKeyService.addApiKeySecret(devopsRead.getUuid(), null, WU);
		ApiKey devopsWrite = freeformKey(org, PermissionType.READ_WRITE,
				Set.of(PermissionFunction.DEVOPS_READ, PermissionFunction.DEVOPS_WRITE));
		ApiKey versioner = freeformKey(org, PermissionType.READ_ONLY, Set.of(PermissionFunction.VERSION_FEATURESET));
		ApiKey distributor = freeformKey(org, PermissionType.READ_WRITE, Set.of(PermissionFunction.DISTRIBUTION));
		ApiKey bare = freeformKey(org, PermissionType.READ_ONLY, Set.of());
		String orgRwSecret = apiKeyService.setObjectApiKey(org, ApiTypeEnum.ORGANIZATION_RW, org, null,
				"instance order test org key", false, WU);
		ApiKey orgRwKey = apiKeyService.getApiKeyByObjUuidTypeOrder(org, ApiTypeEnum.ORGANIZATION_RW, null, org)
				.orElseThrow();
		return new Tenant(org, instance, uri, instanceKey, instanceSecret, product.getUuid(), featureSet, device,
				apiKeyService.getApiKey(devopsRead.getUuid()).orElseThrow(), devopsReadSecret, devopsWrite, versioner,
				distributor, bare, orgRwKey, orgRwSecret);
	}

	/** A FREEFORM key in {@code org} with {@code functions} at organisation scope. */
	private ApiKey freeformKey(UUID org, PermissionType type, Set<PermissionFunction> functions)
			throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org,
				UUID.randomUUID().toString(), "instance order test key", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org,
				PermissionScope.ORGANIZATION, org, type, functions, null)), WU);
		return apiKeyService.getApiKey(key.getUuid()).orElseThrow();
	}

	/** A device on a bare shipment of a client's site (the device upsert reads only its org, site and release). */
	private UUID device(UUID org) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		ClientData client = new ClientData();
		client.setOrg(org);
		client.setName("tpf22-client-" + tag);
		client = clientService.upsert(client, WU);
		SiteData site = new SiteData();
		site.setOrg(org);
		site.setClient(client.getUuid());
		site.setName("tpf22-site-" + tag);
		site = siteService.upsert(site, WU);
		ShippedProductData spd = new ShippedProductData();
		spd.setOrg(org);
		spd.setSite(site.getUuid());
		spd.setFeatureSet(UUID.randomUUID());
		spd.setRelease(UUID.randomUUID());
		ShippedProduct sp = new ShippedProduct();
		sp.setRecordData(Utils.dataToRecord(spd));
		sp = (ShippedProduct) WhoUpdated.injectWhoUpdatedData(sp, WU);
		UUID shipment = shippedProductRepository.save(sp).getUuid();
		Map<String, Object> input = new HashMap<>();
		input.put("org", org.toString());
		input.put("shippedProduct", shipment.toString());
		input.put("identifiers", List.of(Map.of("idType", RearmIdentifierType.SERIAL.name(), "idValue", "SN-" + tag)));
		input.put("notes", "instance order test");
		return deviceService.upsert(Utils.OM.convertValue(input, DeviceData.class), WU).getUuid();
	}

	// ---------- calls ----------

	private ExecutionResult execute(MockHttpServletRequest request, HttpHeaders headers, String op,
			Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		return dgsQueryExecutor.execute(op, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	/** Verified-principal form: what the filter leaves after it checked a bearer token. */
	protected ExecutionResult asKey(ApiKey key, String op, Map<String, Object> vars) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.22"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return execute(request, headers, op, vars);
	}

	/** Basic form: no principal attribute; the resolver parses and verifies the header itself. */
	protected ExecutionResult asBasic(String keyId, String secret, String op, Map<String, Object> vars) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
				.encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		return execute(request, headers, op, vars);
	}

	protected ExecutionResult call(Row r, ApiKey key, Ids ids) {
		return asKey(key, r.op(), r.vars().apply(ids));
	}

	protected static String freeformId(UUID org, String keyOrder) {
		return ApiTypeEnum.FREEFORM.name() + "__" + org + "__ord__" + keyOrder;
	}

	protected static String randomSecret() {
		return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
	}

	/**
	 * Exactly one refusal, nothing under the field; returns its message. A field of non-null type
	 * ({@code listInstanceProductFeatureSets}) also carries the engine's own NullValueInNonNullableField
	 * error for the null the refusal leaves; that one is not a refusal and is set aside.
	 */
	protected static String refusal(ExecutionResult r, String field) {
		assertFalse(r.getErrors().isEmpty(), field + ": expected a refusal, got " + String.valueOf((Object) r.getData()));
		List<GraphQLError> refusals = r.getErrors().stream()
				.filter(e -> !"NullValueInNonNullableField".equals(String.valueOf(e.getExtensions() == null ? null
						: e.getExtensions().get("classification"))))
				.toList();
		assertEquals(1, refusals.size(), field + ": " + r.getErrors());
		Map<String, Object> data = r.getData();
		assertNull(null == data ? null : data.get(field), field + ": a refused call returns nothing");
		return refusals.get(0).getMessage();
	}

	protected static Object result(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), field + ": " + r.getErrors());
		Map<String, Object> data = r.getData();
		return data.get(field);
	}

	protected long accessRows(UUID org, UUID keyUuid) {
		return apiKeyAccessService.listKeyAccessByOrg(org).stream()
				.filter(a -> keyUuid.equals(a.getApiKeyUuid())).count();
	}
}
