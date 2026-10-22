// Java probe of the trust-store gate: the client that ships is the client that
// is tested. Java 17 source, compiled in a JDK stage and run with the image's
// runtime:
//
//   javac -d /gate TrustGate.java
//   java -cp /gate TrustGate
//
// The vendored chains are read from $GATE_CHAINS, default the chains/
// directory beside the class. Identical in every repository that carries
// ci/trust-gate/.

import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class TrustGate {

    private static final String[] HOSTS = {
        "dv-g3b-emudhra.we-have-a-problem.com",
        "dv-g3b-aaa-emudhra.we-have-a-problem.com",
    };

    // SHA-256 of emSign ECC Root CA - G3 and AAA Certificate Services (roots.json).
    private static final Set<String> ROOT_FINGERPRINTS = Set.of(
        "86A1ECBA089C4A8D3BBE2734C612BA341D813E043CF9E8A862CD5C57A36BBE6B",
        "D7A7A0FB5D7E2731D771E9484EBCDEF71D5F0C3E0A2948782BC83EE0EA699EF4");

    // Offline evaluation time (the vendored leaves expire on 2027-05-03).
    private static final Instant VERIFY_TIME = Instant.parse("2026-10-24T00:00:00Z");

    private static int failures = 0;

    private TrustGate() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("probe: java.version " + System.getProperty("java.version"));
        System.out.println("probe: java.home " + System.getProperty("java.home"));

        Set<TrustAnchor> anchors = defaultAnchors();
        int matching = 0;
        for (TrustAnchor anchor : anchors) {
            if (ROOT_FINGERPRINTS.contains(sha256(anchor.getTrustedCert()))) {
                matching++;
                System.out.println("probe: trust anchor " + anchor.getTrustedCert().getSubjectX500Principal().getName()
                    + " is one of the two roots");
            }
        }
        if (matching == 0) {
            System.out.println("probe: trust anchors matching the two roots: 0: pass");
        } else {
            fail("trust anchors matching the two roots: " + matching);
        }

        Path chains = chainsDir();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        for (String host : HOSTS) {
            String live = live(client, host);
            String offline = offline(anchors, chains.resolve(host + ".pem"), host);
            if ("unreachable".equals(live) && !"pass".equals(offline)) {
                fail(host + ": no verdict (live unreachable, offline " + offline + ")");
            }
        }

        if (failures == 0) {
            System.out.println("probe: PASS");
            return;
        }
        System.out.println("probe: FAIL (" + failures + " findings)");
        System.exit(1);
    }

    private static String live(HttpClient client, String host) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + host + "/"))
            .timeout(Duration.ofSeconds(20)).GET().build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            fail(host + ": TLS handshake succeeded, HTTP " + response.statusCode() + " (chain trusted)");
            return "fail";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(host + ": interrupted");
            return "fail";
        } catch (Exception e) {
            Throwable handshake = find(e, SSLHandshakeException.class);
            if (handshake != null) {
                Throwable cert = find(handshake, CertPathValidatorException.class);
                if (cert == null) {
                    cert = find(handshake, CertificateException.class);
                }
                if (cert != null) {
                    System.out.println("probe: " + host + ": pass (" + cert.getClass().getName() + ")");
                    return "pass";
                }
            }
            if (find(e, HttpTimeoutException.class) != null || find(e, ConnectException.class) != null
                || find(e, UnknownHostException.class) != null) {
                System.out.println("probe: " + host + ": unreachable (" + e + ")");
                return "unreachable";
            }
            fail(host + ": " + e + " (not a certificate verification error)");
            return "fail";
        }
    }

    private static String offline(Set<TrustAnchor> anchors, Path chain, String host) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<Certificate> certs;
            try (InputStream in = Files.newInputStream(chain)) {
                certs = new ArrayList<>(factory.generateCertificates(in));
            }
            if (certs.size() < 2) {
                fail(host + ": offline: " + chain + " holds " + certs.size() + " certificate(s)");
                return "fail";
            }
            CertPath path = factory.generateCertPath(certs);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false);
            params.setDate(Date.from(VERIFY_TIME));
            try {
                CertPathValidator.getInstance("PKIX").validate(path, params);
            } catch (CertPathValidatorException e) {
                System.out.println("probe: " + host + ": offline: pass (" + e.getMessage() + ")");
                return "pass";
            }
            fail(host + ": offline: chain validates against the default trust store");
            return "fail";
        } catch (Exception e) {
            fail(host + ": offline: " + e);
            return "fail";
        }
    }

    private static Set<TrustAnchor> defaultAnchors() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        Set<TrustAnchor> anchors = new HashSet<>();
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                for (X509Certificate c : x509.getAcceptedIssuers()) {
                    anchors.add(new TrustAnchor(c, null));
                }
            }
        }
        if (anchors.isEmpty()) {
            throw new IllegalStateException("the default trust store has no anchors");
        }
        return anchors;
    }

    private static Path chainsDir() throws Exception {
        String env = System.getenv("GATE_CHAINS");
        if (env != null && !env.isEmpty()) {
            return Paths.get(env);
        }
        Path classDir = Paths.get(TrustGate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return classDir.resolve("chains");
    }

    private static String sha256(X509Certificate c) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
        return HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
    }

    private static Throwable find(Throwable t, Class<? extends Throwable> type) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (type.isInstance(cur)) {
                return cur;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return null;
    }

    private static void fail(String message) {
        System.out.println("probe: " + message + ": FAIL");
        failures++;
    }
}
