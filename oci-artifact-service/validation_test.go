package main
import "testing"
func TestValidateRepo(t *testing.T) {
    t.Setenv("OCIARTIFACTS_REGISTRY_NAMESPACE", "rearm-artifacts-private")
    cases := []struct{ name, repo string; ok bool }{
        {"valid rebom", "rearm-artifacts-private/rebom-artifacts-2026-08", true},
        {"valid downloadable", "rearm-artifacts-private/downloadable-artifacts-2026-08", true},
        {"bare namespace", "rearm-artifacts-private", true},
        {"traversal", "rearm-artifacts-private/../other", false},
        {"tag injection", "rearm-artifacts-private/foo:latest", false},
        {"digest injection", "rearm-artifacts-private/foo@sha256:abc", false},
        {"escapes namespace", "someone-elses-private/foo", false},
        {"prefix trick", "rearm-artifacts-private-evil/foo", false},
        {"uppercase", "rearm-artifacts-private/Foo", false},
        {"empty", "", false},
    }
    for _, c := range cases {
        _, err := validateRepo(c.repo)
        if (err == nil) != c.ok { t.Errorf("%s: repo %q ok=%v err=%v", c.name, c.repo, c.ok, err) }
    }
    t.Setenv("OCIARTIFACTS_REGISTRY_NAMESPACE", "")
    if _, err := validateRepo("any-namespace/foo"); err != nil { t.Errorf("no-env grammar-only should pass: %v", err) }
    if _, err := validateRepo("any/../foo"); err == nil { t.Error("no-env traversal should still fail") }
}
