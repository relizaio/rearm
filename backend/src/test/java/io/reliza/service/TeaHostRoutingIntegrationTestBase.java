/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.reliza.model.ApiKey;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.OrganizationData.TeaWellKnownHosting;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.ws.App;

/**
 * TPF-142, architecture section 4: the context of the host-routing classes. The allow-list fixture lists tea-a.test,
 * tea-b.test and Tea-C.test. (that spelling proves the startup normalisation); tea-x.test is the off-list host and
 * localhost (the test baseuri's host) the instance host. A test chooses its host with X-Forwarded-Host from
 * 127.0.0.1, an internal proxy of the native forwarded-headers strategy, or with a plain Host header over a raw
 * socket. The host map is instance-global, so it is cleared before and after every case. The browser chain's JWT
 * decoder is mocked so a case can present a valid JWT (row 17).
 */
@SpringBootTest(classes = {App.class}, webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "relizaprops.teaCustomDomains=tea-a.test,tea-b.test,Tea-C.test.")
abstract class TeaHostRoutingIntegrationTestBase extends TeaSurfaceArtifactIntegrationTestBase {

	static final String A_HOST = "tea-a.test";
	static final String B_HOST = "tea-b.test";
	static final String C_HOST = "tea-c.test";
	static final String X_HOST = "tea-x.test";
	static final String HOST_BASE = "/tea/v1.0.0";

	@Autowired TeaHostAssignmentService teaHostAssignmentService;
	@Autowired JdbcTemplate jdbcTemplate;
	@MockitoBean JwtDecoder jwtDecoder;

	@BeforeEach
	@AfterEach
	void clearHostMap() {
		jdbcTemplate.update("UPDATE rearm.system_info SET data = data - 'teaHostAssignments' WHERE id = 1");
	}

	static Map<String, String> on(String host) {
		return Map.of("X-Forwarded-Host", host);
	}

	void assign(String host, UUID org) throws Exception {
		teaHostAssignmentService.assign(host, org, null);
	}

	void serve(UUID org, TeaServing serving, TeaWellKnownHosting hosting) throws Exception {
		organizationService.setTeaServing(org, serving, hosting, WU);
	}

	/** An organization of a case besides the rig one: its ids, a PUBLIC and a PRIVATE published head, an EXTERNAL key. */
	record HostOrg(UUID org, String teaUuid, Obj pub, Obj priv, String keyId, String secret) {
		String uuidBase(String root) {
			return root + "/tea/" + teaUuid + "/v1.0.0";
		}
	}

	/**
	 * A fresh organization with an ENABLED PRIVATE organization profile (the mint), a PUBLIC published component release
	 * and a PRIVATE one, and an EXTERNAL key with a secret; its serving mode is left NONE (no helper is called).
	 */
	HostOrg hostOrg() throws Exception {
		Organization o = testInitializer.obtainOrganization();
		teaProfileService.saveProfile(o.getUuid(), TeaProfileScope.ORGANIZATION, null,
				profileInput(TeaPublishing.ENABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.NONE,
						TeaProductComponents.PUBLISH_WITH_PRODUCT).build(), TeaCaller.ORG_ADMIN, WU);
		String t = tag();
		String pubPurl = "pkg:generic/tpf142-pub-" + t + "@1.0.0";
		World pub = componentReleaseWithPurl(o, pubPurl, List.of());
		teaProfileService.saveProfile(o.getUuid(), TeaProfileScope.COMPONENT, pub.component(),
				profileInput(TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT)
						.mode(TeaComponentProfileMode.OVERRIDE).build(), TeaCaller.ORG_ADMIN, WU);
		publish(pub.release(), TeaPublishMode.PUBLISH);
		String privPurl = "pkg:generic/tpf142-priv-" + t + "@1.0.0";
		World priv = componentReleaseWithPurl(o, privPurl, List.of());
		publish(priv.release(), TeaPublishMode.PUBLISH);
		ApiKey key = externalApiKeyService.create(o.getUuid(), "tpf142-" + t, null, WU);
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		String keyId = ApiKeyService.keyIdOf(apiKeyService.getApiKey(key.getUuid()).orElseThrow());
		String tea = getOrganizationService.getOrganizationData(o.getUuid()).orElseThrow().getTeaUuid().toString();
		return new HostOrg(o.getUuid(), tea, obj(pub, pubPurl), obj(priv, privPurl), keyId, secret);
	}

	/** POST {@code path} (the uuid or the host token path) with the key as HTTP Basic, on {@code host} (null: none). */
	HttpResponse<String> tokenRequest(String path, String host, String keyId, String secret) throws Exception {
		String basic = "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8));
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(root() + path)).header("Authorization", basic)
				.header("X-Forwarded-For", randomIp()).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"));
		if (null != host) b.header("X-Forwarded-Host", host);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	String accessToken(HttpResponse<String> r) {
		assertEquals(200, r.statusCode(), r.body());
		return (String) json(r).get("access_token");
	}

	/** One response read off a raw socket: what the JDK client will not send (a Host header, a dot segment). */
	record Raw(int status, Map<String, String> headers, String body) {
		String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}
	}

	/** Sends one HTTP/1.1 request exactly as written (path and headers unnormalised) and reads the whole answer. */
	Raw raw(String method, String path, Map<String, String> headers, String body) throws IOException {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoTimeout(30_000);
			StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
			Map<String, String> h = new LinkedHashMap<>(headers);
			h.putIfAbsent("Host", "localhost:" + port);
			h.put("Connection", "close");
			h.put("X-Forwarded-For", randomIp());
			byte[] payload = null == body ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
			if (null != body) h.put("Content-Length", String.valueOf(payload.length));
			h.forEach((k, v) -> req.append(k).append(": ").append(v).append("\r\n"));
			req.append("\r\n");
			OutputStream out = s.getOutputStream();
			out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
			out.write(payload);
			out.flush();
			InputStream in = s.getInputStream();
			ByteArrayOutputStream all = new ByteArrayOutputStream();
			in.transferTo(all);
			return parse(all.toByteArray());
		}
	}

	private static Raw parse(byte[] bytes) {
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		int split = text.indexOf("\r\n\r\n");
		String head = split < 0 ? text : text.substring(0, split);
		String rest = split < 0 ? "" : text.substring(split + 4);
		String[] lines = head.split("\r\n");
		int status = Integer.parseInt(lines[0].split(" ")[1]);
		Map<String, String> headers = new LinkedHashMap<>();
		for (int i = 1; i < lines.length; i++) {
			int c = lines[i].indexOf(':');
			if (c > 0) headers.putIfAbsent(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
		}
		if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) rest = unchunk(rest);
		return new Raw(status, headers, new String(rest.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
	}

	private static String unchunk(String body) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < body.length()) {
			int eol = body.indexOf("\r\n", i);
			if (eol < 0) break;
			int size = Integer.parseInt(body.substring(i, eol).split(";")[0].trim(), 16);
			if (0 == size) break;
			out.append(body, eol + 2, eol + 2 + size);
			i = eol + 2 + size + 2;
		}
		return out.toString();
	}
}
