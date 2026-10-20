/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.benmanes.caffeine.cache.Ticker;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.reliza.common.LogSafe;
import io.reliza.common.OAuthErrors;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.FederatedTrustRule.Provider;
import io.reliza.repositories.FederatedTrustRuleRepository;
import io.reliza.service.FederatedAssertionService.AssertionException;
import io.reliza.service.FederatedAssertionService.Refusal;
import io.reliza.service.FederatedAssertionService.VerifiedAssertion;
import io.reliza.service.FederatedTrustRuleService.ExchangeException;
import io.reliza.ws.RelizaConfigProps;

/**
 * A failed key discovery is remembered per issuer for a minute, so an outage costs one attempt and one ERROR per
 * window, and each issuer's decoder makes at most two key-set requests per 30 seconds after its build (task TPF-53,
 * architecture-1 4.1 and 4.2). Spring-free: the window runs on an injected Caffeine ticker that only the test
 * advances, every HTTP request goes to a stub factory, and no case sleeps.
 */
@ExtendWith(MockitoExtension.class)
class FederatedKeyDiscoveryLimitsTest {

	@Mock private FederatedTrustRuleRepository repository;
	@InjectMocks private FederatedTrustRuleService exchangeService;

	private static final String BASE = "https://rearm.example.com";
	private static final String NOT_VERIFIED_TEXT = "assertion rejected: signature, key or format not accepted";
	private static KeyPair keys;

	private final AtomicLong nanos = new AtomicLong();
	private final Ticker ticker = nanos::get;
	private final AtomicInteger calls = new AtomicInteger();
	private final AtomicInteger discoveryRequests = new AtomicInteger();
	private final AtomicInteger keySetRequests = new AtomicInteger();
	private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
	private final String issuer = issuer();
	private AbstractAppender appender;
	private Level previousLevel;

	@BeforeAll
	static void keyPair() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		keys = g.generateKeyPair();
	}

	@BeforeEach
	void captureServiceLog() {
		previousLevel = serviceLogger().getLevel();
		// a plain JUnit run uses log4j2's default ERROR root, which would drop WARN lines and make "no WARN" vacuous
		Configurator.setLevel(FederatedAssertionService.class.getName(), Level.ALL);
		appender = new AbstractAppender("tpf53-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				events.add(event.toImmutable());
			}
		};
		appender.start();
		serviceLogger().addAppender(appender);
	}

	@AfterEach
	void releaseServiceLog() {
		serviceLogger().removeAppender(appender);
		appender.stop();
		Configurator.setLevel(FederatedAssertionService.class.getName(), previousLevel);
	}

	private static org.apache.logging.log4j.core.Logger serviceLogger() {
		return (org.apache.logging.log4j.core.Logger) LogManager.getLogger(FederatedAssertionService.class);
	}

	// ---- rig ---------------------------------------------------------------------------------

	private static String issuer() {
		return "https://issuer-" + UUID.randomUUID().toString().substring(0, 8) + ".tpf53.invalid";
	}

	private void advance(Duration d) {
		nanos.addAndGet(d.toNanos());
	}

	/** Moves the test clock to {@code seconds} after the first call of the case. */
	private void at(long seconds) {
		long target = Duration.ofSeconds(seconds).toNanos();
		assertTrue(target >= nanos.get(), "the clock only moves forward");
		nanos.set(target);
	}

	private FederatedAssertionService service(Function<String, JwtDecoder> f) {
		RelizaConfigProps props = new RelizaConfigProps();
		props.setBaseuri(BASE + "/");
		return new FederatedAssertionService(f, props, ticker);
	}

	private static String token(String iss, String kid) throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(iss).subject("repo:relizaio/rearm:ref:refs/heads/main")
				.audience(BASE).issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
				.jwtID(UUID.randomUUID().toString()).claim("repository", "relizaio/rearm").claim("repository_owner", "relizaio")
				.claim("repository_id", "42").claim("repository_owner_id", "7").build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
		return jwt.serialize();
	}

	private static JwtDecoder publicKeyDecoder() {
		return NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
	}

	private static IllegalArgumentException discoveryFailure(String iss) {
		// the class and text JwtDecoderProviderConfigurationUtils throws when no metadata URI answers
		return new IllegalArgumentException("Unable to resolve the Configuration with the provided Issuer of \"" + iss + "\"");
	}

	private final Function<String, JwtDecoder> down = iss -> {
		calls.incrementAndGet();
		throw discoveryFailure(iss);
	};

	private Function<String, JwtDecoder> upAfter(AtomicBoolean up) {
		return iss -> {
			calls.incrementAndGet();
			if (!up.get()) throw discoveryFailure(iss);
			return publicKeyDecoder();
		};
	}

	private static void assertRefused(Refusal reason, String message, FederatedAssertionService svc, String token, String iss) {
		AssertionException e = assertThrows(AssertionException.class, () -> svc.verify(token, iss, Provider.GITHUB_ACTIONS));
		assertEquals(reason, e.reason());
		assertEquals(message, e.getMessage());
	}

	private static void assertKeysUnavailable(FederatedAssertionService svc, String iss) throws Exception {
		assertRefused(Refusal.KEYS_UNAVAILABLE, "issuer keys unavailable", svc, token(iss, "k1"), iss);
	}

	private static void assertNotVerified(FederatedAssertionService svc, String iss, String kid) throws Exception {
		assertRefused(Refusal.NOT_VERIFIED, NOT_VERIFIED_TEXT, svc, token(iss, kid), iss);
	}

	private static String expectedError(String iss) {
		return "cannot set up key discovery for federated issuer " + LogSafe.text(iss)
				+ "; exchanges naming it are refused without a new discovery for the next 60 seconds";
	}

	private List<LogEvent> errorsFor(String iss) {
		synchronized (events) {
			return events.stream().filter(e -> e.getLevel() == Level.ERROR && e.getMessage().getFormattedMessage().contains(LogSafe.text(iss))).toList();
		}
	}

	private List<LogEvent> serviceLinesFor(String iss) {
		synchronized (events) {
			return events.stream().filter(e -> e.getLevel().isMoreSpecificThan(Level.WARN) && e.getMessage().getFormattedMessage().contains(LogSafe.text(iss))).toList();
		}
	}

	/** One stub issuer over HTTP: discovery metadata, a key set holding the key as k1, 404 for anything else. */
	private ClientHttpRequestFactory stubIssuer(String iss, boolean everythingNotFound) {
		return (uri, method) -> {
			String path = uri.getPath();
			if (path.contains("/.well-known/")) discoveryRequests.incrementAndGet();
			if (path.equals("/jwks")) keySetRequests.incrementAndGet();
			MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
			MockClientHttpResponse response;
			if (!everythingNotFound && path.endsWith("/.well-known/openid-configuration")) {
				response = json("{\"issuer\":\"" + iss + "\",\"jwks_uri\":\"" + iss + "/jwks\"}", HttpStatus.OK);
			} else if (!everythingNotFound && path.equals("/jwks")) {
				response = json(new JWKSet(new RSAKey.Builder((RSAPublicKey) keys.getPublic()).keyID("k1").build()).toString(), HttpStatus.OK);
			} else {
				response = json("{}", HttpStatus.NOT_FOUND);
			}
			request.setResponse(response);
			return request;
		};
	}

	private static MockClientHttpResponse json(String body, HttpStatus status) {
		MockClientHttpResponse r = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
		r.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
		return r;
	}

	private static FederatedTrustRule rule(String iss) {
		FederatedTrustRule r = new FederatedTrustRule();
		r.setOrg(UUID.randomUUID());
		r.setName("tpf53-rule");
		r.setProvider(Provider.GITHUB_ACTIONS);
		r.setIssuer(iss);
		FederatedMatcher m = new FederatedMatcher();
		m.setOwner("relizaio");
		r.setMatcherOf(m);
		return r;
	}

	// ---- cases 1 to 3: the window, its end, one ERROR per window ----------------------------

	@Test
	void anOutageCostsOneDiscoveryAndOneErrorPerWindow() throws Exception {
		FederatedAssertionService svc = service(down);

		// case 1: several verifies inside the window cost one discovery
		for (long s : new long[] {0, 0, 10, 30, 59}) {
			at(s);
			assertKeysUnavailable(svc, issuer);
		}
		assertEquals(1, calls.get());

		// case 3: one ERROR for the window, the exact text, the factory's throwable, nothing for the four refusals from the window
		List<LogEvent> errors = errorsFor(issuer);
		assertEquals(1, errors.size());
		assertEquals(expectedError(issuer), errors.get(0).getMessage().getFormattedMessage());
		IllegalArgumentException thrown = assertInstanceOf(IllegalArgumentException.class, errors.get(0).getThrown());
		assertEquals(discoveryFailure(issuer).getMessage(), thrown.getMessage());
		assertEquals(1, serviceLinesFor(issuer).size());

		// case 2: after the window, one more discovery, and a new window from the end of that attempt
		at(61);
		assertKeysUnavailable(svc, issuer);
		assertEquals(2, calls.get());
		assertEquals(2, errorsFor(issuer).size());

		at(120);
		assertKeysUnavailable(svc, issuer);
		assertEquals(2, calls.get());
		assertEquals(2, errorsFor(issuer).size());

		at(122);
		assertKeysUnavailable(svc, issuer);
		assertEquals(3, calls.get());
		assertEquals(3, errorsFor(issuer).size());
		assertEquals(3, serviceLinesFor(issuer).size());
	}

	// ---- case 4: a recovered issuer is cached as today ---------------------------------------

	@Test
	void aSuccessfulDiscoveryAfterTheWindowIsCachedAsToday() throws Exception {
		AtomicBoolean up = new AtomicBoolean(false);
		FederatedAssertionService svc = service(upAfter(up));

		at(0);
		assertKeysUnavailable(svc, issuer);
		assertEquals(1, calls.get());
		up.set(true);

		at(30);
		assertKeysUnavailable(svc, issuer);
		assertEquals(1, calls.get());

		at(61);
		VerifiedAssertion va = svc.verify(token(issuer, "k1"), issuer, Provider.GITHUB_ACTIONS);
		assertEquals("relizaio/rearm", va.identity().repository());
		assertEquals(2, calls.get());

		for (long s : new long[] {61, 3_600, 7_200}) {
			at(s);
			assertEquals("relizaio/rearm", svc.verify(token(issuer, "k1"), issuer, Provider.GITHUB_ACTIONS).identity().repository());
		}
		assertEquals(2, calls.get());
		assertEquals(1, errorsFor(issuer).size());
	}

	// ---- case 5: per issuer ------------------------------------------------------------------

	@Test
	void theWindowIsPerIssuer() throws Exception {
		String a = issuer();
		String b = issuer();
		Map<String, AtomicInteger> perIssuer = new ConcurrentHashMap<>();
		FederatedAssertionService svc = service(iss -> {
			perIssuer.computeIfAbsent(iss, k -> new AtomicInteger()).incrementAndGet();
			if (iss.equals(a)) throw discoveryFailure(iss);
			return publicKeyDecoder();
		});

		at(0);
		assertKeysUnavailable(svc, a);
		assertEquals("relizaio/rearm", svc.verify(token(b, "k1"), b, Provider.GITHUB_ACTIONS).identity().repository());
		assertKeysUnavailable(svc, a);

		assertEquals(1, perIssuer.get(a).get());
		assertEquals(1, perIssuer.get(b).get());
		assertEquals(1, errorsFor(a).size());
		assertTrue(errorsFor(b).isEmpty());
	}

	// ---- case 6: through the exchange --------------------------------------------------------

	@Test
	void exchangesDuringTheWindowReadAsALiveFailureAndStillLookUpFirst() throws Exception {
		when(repository.findActiveByIssuer(issuer, FederatedTrustRule.Status.ACTIVE.name())).thenReturn(new ArrayList<>(List.of(rule(issuer))));
		ReflectionTestUtils.setField(exchangeService, "assertionService", service(down));

		for (long s : new long[] {0, 10, 59}) {
			at(s);
			String assertion = token(issuer, "k1");
			ExchangeException e = assertThrows(ExchangeException.class, () -> exchangeService.exchange(assertion, null));
			assertEquals(OAuthErrors.INVALID_GRANT, e.code());
			assertEquals(FederatedTrustRuleService.NOT_ACCEPTED, e.getMessage());
			assertEquals(Refusal.KEYS_UNAVAILABLE, e.reason());
			assertEquals(issuer, e.issuer());
			assertNull(e.identity());
		}
		assertEquals(1, calls.get());
		verify(repository, times(3)).findActiveByIssuer(issuer, "ACTIVE");
	}

	// ---- case 7: the key-set refetch limit over the production wiring -----------------------

	@Test
	void keySetRefetchesAreLimitedToTwoPerWindowAndAKnownKeyIsServedThroughout() throws Exception {
		ClientHttpRequestFactory http = stubIssuer(issuer, false);
		FederatedAssertionService svc = service(iss -> FederatedAssertionService.discoveringDecoder(iss, http, ticker));

		at(0);
		assertEquals("relizaio/rearm", svc.verify(token(issuer, "k1"), issuer, Provider.GITHUB_ACTIONS).identity().repository());
		assertEquals(1, discoveryRequests.get());
		assertEquals(1, keySetRequests.get());

		for (int i = 1; i <= 5; i++) assertNotVerified(svc, issuer, "x" + i);
		assertEquals(3, keySetRequests.get(), "two refetches in the window, three refused without a request");

		assertEquals("relizaio/rearm", svc.verify(token(issuer, "k1"), issuer, Provider.GITHUB_ACTIONS).identity().repository());
		assertEquals(3, keySetRequests.get());

		advance(Duration.ofSeconds(31));
		assertNotVerified(svc, issuer, "x6");
		assertEquals(4, keySetRequests.get());
		assertNotVerified(svc, issuer, "x7");
		assertEquals(5, keySetRequests.get());
		assertNotVerified(svc, issuer, "x8");
		assertEquals(5, keySetRequests.get());

		assertEquals(1, discoveryRequests.get());
		assertTrue(errorsFor(issuer).isEmpty());
	}

	// ---- case 8: a failure of the production factory opens the window -----------------------

	@Test
	void aFailedProductionDiscoveryOpensTheWindow() throws Exception {
		ClientHttpRequestFactory http = stubIssuer(issuer, true);
		FederatedAssertionService svc = service(iss -> FederatedAssertionService.discoveringDecoder(iss, http, ticker));

		for (long s : new long[] {0, 10, 59}) {
			at(s);
			assertKeysUnavailable(svc, issuer);
		}
		assertEquals(3, discoveryRequests.get(), "the three metadata URIs of one attempt, nothing during the window");
		assertEquals(1, errorsFor(issuer).size());
		assertInstanceOf(IllegalArgumentException.class, errorsFor(issuer).get(0).getThrown());

		at(61);
		assertKeysUnavailable(svc, issuer);
		assertEquals(6, discoveryRequests.get());
		assertEquals(2, errorsFor(issuer).size());
		assertEquals(0, keySetRequests.get());
	}

	// ---- concurrency: exchanges arriving during the live attempt wait for it ----------------

	@Test
	void manyVerifiesDuringOneLiveAttemptCostOneDiscovery() throws Exception {
		int waiters = 8;
		CountDownLatch inFlight = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FederatedAssertionService svc = service(iss -> {
			calls.incrementAndGet();
			inFlight.countDown();
			try {
				if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			throw discoveryFailure(iss);
		});
		String assertion = token(issuer, "k1");
		List<Throwable> outcomes = Collections.synchronizedList(new ArrayList<>());
		Runnable call = () -> {
			try {
				svc.verify(assertion, issuer, Provider.GITHUB_ACTIONS);
				outcomes.add(new AssertionError("verify returned"));
			} catch (Throwable t) {
				outcomes.add(t);
			}
		};

		Thread first = new Thread(call, "tpf53-first");
		first.start();
		assertTrue(inFlight.await(10, TimeUnit.SECONDS), "the live attempt started");

		CountDownLatch arrived = new CountDownLatch(waiters);
		List<Thread> others = new ArrayList<>();
		for (int i = 0; i < waiters; i++) {
			Thread t = new Thread(() -> { arrived.countDown(); call.run(); }, "tpf53-waiter-" + i);
			others.add(t);
			t.start();
		}
		assertTrue(arrived.await(10, TimeUnit.SECONDS));
		// every waiter is parked behind the in-flight attempt inside the cache's per-key computation
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!others.stream().allMatch(t -> t.getState() == Thread.State.BLOCKED || t.getState() == Thread.State.WAITING)) {
			if (System.nanoTime() > deadline) fail("waiters did not block behind the live attempt: " + others.stream().map(Thread::getState).toList());
			Thread.onSpinWait();
		}
		assertEquals(1, calls.get());

		release.countDown();
		first.join(10_000);
		for (Thread t : others) t.join(10_000);

		assertEquals(waiters + 1, outcomes.size());
		for (Throwable t : outcomes) {
			AssertionException e = assertInstanceOf(AssertionException.class, t);
			assertEquals(Refusal.KEYS_UNAVAILABLE, e.reason());
			assertEquals("issuer keys unavailable", e.getMessage());
		}
		assertEquals(1, calls.get());
		assertEquals(1, errorsFor(issuer).size());
		assertEquals(1, serviceLinesFor(issuer).size());
	}

}
