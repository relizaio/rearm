//go:build trustgate

// Package trustgate is the Go probe of the trust-store gate: the client that
// ships is the client that is tested. Run it from the module root with exactly
//
//	go test -count=1 -tags trustgate -run TestTrustGate ./ci/trust-gate/
//
// never without -tags trustgate: this is the directory's only Go file, so the
// package would not build. Identical in every repository that carries
// ci/trust-gate/.
package trustgate

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"go/version"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

// fixedGo is the first toolchain whose verifier refuses the compromised
// eMudhra intermediates.
const fixedGo = "go1.26.10"

var hosts = []string{
	"dv-g3b-emudhra.we-have-a-problem.com",
	"dv-g3b-aaa-emudhra.we-have-a-problem.com",
}

// verifyTime is the offline evaluation time, 2026-10-24T00:00:00Z (the vendored
// leaves expire on 2027-05-03).
var verifyTime = time.Date(2026, 10, 24, 0, 0, 0, 0, time.UTC)

func TestTrustGate(t *testing.T) {
	t.Logf("probe: runtime %s", runtime.Version())
	if version.Compare(runtime.Version(), fixedGo) < 0 {
		t.Errorf("probe: runtime %s is older than %s: FAIL", runtime.Version(), fixedGo)
	}
	for _, host := range hosts {
		live := liveCheck(t, host)
		offline := offlineCheck(t, host)
		if live == "unreachable" && offline != "pass" {
			t.Errorf("probe: %s: no verdict (live unreachable, offline %s): FAIL", host, offline)
		}
	}
}

// liveCheck dials the host with the default TLS configuration. A certificate
// verification error is a pass, a completed handshake a FAIL, a network error
// no verdict, and any other error a FAIL.
func liveCheck(t *testing.T, host string) string {
	dialer := &net.Dialer{Timeout: 20 * time.Second}
	conn, err := tls.DialWithDialer(dialer, "tcp", net.JoinHostPort(host, "443"), nil)
	if err == nil {
		conn.Close()
		t.Errorf("probe: %s: TLS handshake succeeded: FAIL (chain trusted)", host)
		return "fail"
	}
	var verifyErr *tls.CertificateVerificationError
	var unknownAuthority x509.UnknownAuthorityError
	if errors.As(err, &verifyErr) || errors.As(err, &unknownAuthority) {
		t.Logf("probe: %s: pass (%v)", host, err)
		return "pass"
	}
	var netErr net.Error
	if errors.As(err, &netErr) {
		t.Logf("probe: %s: unreachable (%v)", host, err)
		return "unreachable"
	}
	t.Errorf("probe: %s: %v: FAIL (not a certificate verification error)", host, err)
	return "fail"
}

// offlineCheck verifies the vendored chain against the system roots at the
// pinned time; it must be refused, and refused for trust, not for the name or
// the dates.
func offlineCheck(t *testing.T, host string) string {
	certs, err := readChain(filepath.Join("chains", host+".pem"))
	if err != nil {
		t.Errorf("probe: %s: offline: %v: FAIL", host, err)
		return "fail"
	}
	intermediates := x509.NewCertPool()
	for _, c := range certs[1:] {
		intermediates.AddCert(c)
	}
	_, err = certs[0].Verify(x509.VerifyOptions{
		Intermediates: intermediates,
		CurrentTime:   verifyTime,
		DNSName:       host,
	})
	if err == nil {
		t.Errorf("probe: %s: offline: chain verifies against the system roots: FAIL", host)
		return "fail"
	}
	var hostnameErr x509.HostnameError
	var invalidErr x509.CertificateInvalidError
	if errors.As(err, &hostnameErr) || (errors.As(err, &invalidErr) && invalidErr.Reason == x509.Expired) {
		t.Errorf("probe: %s: offline: %v: FAIL (refused, but not for trust)", host, err)
		return "fail"
	}
	t.Logf("probe: %s: offline: pass (%v)", host, err)
	return "pass"
}

func readChain(path string) ([]*x509.Certificate, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var certs []*x509.Certificate
	for {
		var block *pem.Block
		block, data = pem.Decode(data)
		if block == nil {
			break
		}
		if block.Type != "CERTIFICATE" {
			continue
		}
		c, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, err
		}
		certs = append(certs, c)
	}
	if len(certs) < 2 {
		return nil, errors.New(path + ": expected a leaf and at least one issuer")
	}
	return certs, nil
}
