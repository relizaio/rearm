package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	v1 "github.com/opencontainers/image-spec/specs-go/v1"
	"oras.land/oras-go/v2/content/file"
)

const (
	testMediaType = "application/vnd.cyclonedx+json"
	// Content-addressed, the shape retainRawUpload pushes under.
	testTag     = "rearm-raw-8b1a9953c4611296"
	testPayload = `{"bomFormat":"CycloneDX","specVersion":"1.6","version":1}`
)

// pack runs the same steps PushArtifact does, up to and including the manifest
// pack: a file store rooted in its own directory, fs.Add under the caller's
// tag, then packArtifactManifest. Each call gets a fresh store so nothing is
// carried between them except the content itself.
func packTestManifest(t *testing.T, payload []byte, tag string) (v1.Descriptor, *file.Store) {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "payload.json")
	if err := os.WriteFile(path, payload, 0o600); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	fs, err := file.New(dir)
	if err != nil {
		t.Fatalf("file store: %v", err)
	}
	t.Cleanup(func() { fs.Close() })

	ctx := context.Background()
	layer, err := fs.Add(ctx, tag, testMediaType, path)
	if err != nil {
		t.Fatalf("add layer: %v", err)
	}
	desc, err := packArtifactManifest(ctx, fs, testMediaType, []v1.Descriptor{layer})
	if err != nil {
		t.Fatalf("pack manifest: %v", err)
	}
	return desc, fs
}

// packTestDigest is the common case: callers that only compare digests.
func packTestDigest(t *testing.T, payload []byte, tag string) v1.Descriptor {
	t.Helper()
	desc, _ := packTestManifest(t, payload, tag)
	return desc
}

// The regression guard, and the one to keep.
//
// Asserting on the annotation rather than on two digests matching is
// deliberate: org.opencontainers.image.created is RFC 3339 with ONE SECOND of
// resolution, so two packs in the same test land in the same second and agree
// even with the fix reverted. A digest-comparison test passes 5 times out of 5
// against the bug it is supposed to catch. This one reads what actually went
// into the manifest, so it fails the moment the annotation stops being fixed.
func TestManifestCarriesAFixedCreatedAnnotation(t *testing.T) {
	desc, fs := packTestManifest(t, []byte(testPayload), testTag)

	rc, err := fs.Fetch(context.Background(), desc)
	if err != nil {
		t.Fatalf("fetch manifest: %v", err)
	}
	defer rc.Close()
	raw, err := io.ReadAll(rc)
	if err != nil {
		t.Fatalf("read manifest: %v", err)
	}

	var manifest v1.Manifest
	if err := json.Unmarshal(raw, &manifest); err != nil {
		t.Fatalf("unmarshal manifest: %v", err)
	}

	got := manifest.Annotations[v1.AnnotationCreated]
	if got != manifestCreatedAnnotationValue {
		t.Errorf("manifest created annotation = %q, want the fixed %q -- a wall-clock value here "+
			"makes identical content pack to different manifest digests, which orphans the manifest "+
			"a stored digest still points at", got, manifestCreatedAnnotationValue)
	}
}

// The property the change exists for, exercised end to end.
//
// The sleep is load-bearing and not a flake workaround: the timestamp oras-go
// would otherwise stamp has one-second resolution, so without a real second
// between the two packs this test cannot tell the fix from its absence. Costs
// a second, and buys a test that fails when reverted. TestManifestCarries...
// above is the fast guard; this one is the demonstration.
func TestManifestDigestDependsOnContentAlone(t *testing.T) {
	if testing.Short() {
		t.Skip("spans a wall-clock second by design")
	}
	first := packTestDigest(t, []byte(testPayload), testTag)
	time.Sleep(1100 * time.Millisecond)
	second := packTestDigest(t, []byte(testPayload), testTag)

	if first.Digest != second.Digest {
		t.Errorf("identical content packed to different manifests: %s vs %s", first.Digest, second.Digest)
	}
	if first.Size != second.Size {
		t.Errorf("identical content packed to different manifest sizes: %d vs %d", first.Size, second.Size)
	}
}

// Determinism must not come from collapsing distinct content onto one digest.
func TestDifferentContentStillPacksToDifferentManifests(t *testing.T) {
	first := packTestDigest(t, []byte(`{"bomFormat":"CycloneDX","version":1}`), testTag)
	second := packTestDigest(t, []byte(`{"bomFormat":"CycloneDX","version":2}`), testTag)

	if first.Digest == second.Digest {
		t.Errorf("different content packed to the same manifest: %s", first.Digest)
	}
}

// The tag reaches the manifest as the layer's title annotation, so it is part
// of what the digest covers. Pinned because our raw-upload tags are derived
// from the content: if that ever stops being true, two callers uploading the
// same bytes under different tags would produce different manifests and
// re-tagging would orphan one of them again.
func TestTagIsPartOfWhatTheManifestDigestCovers(t *testing.T) {
	first := packTestDigest(t, []byte(testPayload), testTag)
	second := packTestDigest(t, []byte(testPayload), "rearm-some-other-tag")

	if first.Digest == second.Digest {
		t.Errorf("tag does not reach the manifest digest; revisit the comment on this test")
	}
}

// fakeRegistry is the smallest OCI distribution surface oras.Copy needs, and
// it keeps every manifest body it is handed so a test can assert on what was
// actually pushed rather than on what a helper returned.
type fakeRegistry struct {
	mu        sync.Mutex
	manifests map[string][]byte
}

func newFakeRegistry(t *testing.T) (*fakeRegistry, string) {
	t.Helper()
	reg := &fakeRegistry{manifests: map[string][]byte{}}
	srv := httptest.NewServer(reg)
	t.Cleanup(srv.Close)
	return reg, strings.TrimPrefix(srv.URL, "http://")
}

func (r *fakeRegistry) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	path := req.URL.Path
	switch {
	case path == "/v2/" || path == "/v2":
		w.WriteHeader(http.StatusOK)

	case strings.Contains(path, "/blobs/uploads"):
		// Start an upload, then accept whatever is sent to the session URL.
		if req.Method == http.MethodPost {
			w.Header().Set("Location", path+"session")
			w.WriteHeader(http.StatusAccepted)
			return
		}
		io.Copy(io.Discard, req.Body)
		w.Header().Set("Docker-Content-Digest", req.URL.Query().Get("digest"))
		w.WriteHeader(http.StatusCreated)

	case strings.Contains(path, "/blobs/"):
		// Nothing is ever already present, so every blob gets uploaded.
		w.WriteHeader(http.StatusNotFound)

	case strings.Contains(path, "/manifests/"):
		ref := path[strings.LastIndex(path, "/manifests/")+len("/manifests/"):]
		if req.Method == http.MethodPut {
			body, _ := io.ReadAll(req.Body)
			r.mu.Lock()
			r.manifests[ref] = body
			r.mu.Unlock()
			sum := sha256.Sum256(body)
			w.Header().Set("Docker-Content-Digest", "sha256:"+hex.EncodeToString(sum[:]))
			w.WriteHeader(http.StatusCreated)
			return
		}
		w.WriteHeader(http.StatusNotFound)

	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (r *fakeRegistry) manifestFor(t *testing.T, ref string) v1.Manifest {
	t.Helper()
	r.mu.Lock()
	defer r.mu.Unlock()
	raw, ok := r.manifests[ref]
	if !ok {
		t.Fatalf("no manifest pushed for %q (have %d)", ref, len(r.manifests))
	}
	var m v1.Manifest
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("unmarshal pushed manifest: %v", err)
	}
	return m
}

// The guard that covers the CALL SITE rather than the helper.
//
// Every other test here calls packArtifactManifest directly, so reverting
// PushArtifact to an inline oras.PackManifest -- which is precisely the bug --
// leaves them all green. Verified: it does. This one drives the real
// PushArtifact against a registry and reads the manifest off the wire, so the
// fix cannot be bypassed at the one place it has to apply.
func TestPushArtifactSendsAFixedCreatedAnnotation(t *testing.T) {
	reg, host := newFakeRegistry(t)
	t.Setenv("REGISTRY_HOST", host)
	t.Setenv("USE_PLAIN_HTTP", "true")
	t.Setenv("REGISTRY_USERNAME", "")
	t.Setenv("REGISTRY_TOKEN", "")

	oc, err := NewOrasClient("testns/testrepo")
	if err != nil {
		t.Fatalf("oras client: %v", err)
	}

	path := filepath.Join(t.TempDir(), "payload.json")
	if err := os.WriteFile(path, []byte(testPayload), 0o600); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open payload: %v", err)
	}
	defer f.Close()

	if _, err := oc.PushArtifact(context.Background(), f, testTag, testMediaType, nil); err != nil {
		t.Fatalf("push: %v", err)
	}

	got := reg.manifestFor(t, testTag).Annotations[v1.AnnotationCreated]
	if got != manifestCreatedAnnotationValue {
		t.Errorf("pushed manifest created annotation = %q, want the fixed %q -- PushArtifact is "+
			"not going through packArtifactManifest", got, manifestCreatedAnnotationValue)
	}
}
