package main

import (
	"errors"
	"fmt"
	"os"
	"regexp"
	"strings"
)

// OCI distribution-spec repository-name grammar. Lowercase only; separators
// are ".", "_", "__" and runs of "-". Deliberately excludes ":" and "@",
// which would turn a repo name into a tag or digest reference.
var repoNameRe = regexp.MustCompile(
	`^[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*)*$`)

const maxRepoNameLen = 255 // per the distribution spec

func registryNamespace() string {
	return os.Getenv("OCIARTIFACTS_REGISTRY_NAMESPACE")
}

// validateRepo checks a caller-supplied repository name before it is
// interpolated into a registry reference.
//
// Two properties matter. The value is concatenated as host + "/" + repo and
// handed to remote.NewRepository, so anything that changes how that
// reference parses (":" for a tag, "@" for a digest, ".." for traversal)
// must be rejected rather than escaped. And this service pushes with one
// shared privileged credential, so when OCIARTIFACTS_REGISTRY_NAMESPACE is
// set the name must stay inside that namespace -- otherwise any caller that
// can reach the service can direct a push anywhere in the registry.
//
// The namespace check is deliberately soft-configured: when the env var is
// unset (every deployment shipped before it existed), only the grammar
// checks apply and a warning is logged at startup. Both existing callers
// (rebom-backend's rebom-artifacts-YYYY-MM and the Java backend's
// downloadable-artifacts-YYYY-MM buckets) build their repo names from the
// same helm value this env var is sourced from, so any install where the
// callers work will also pass containment.
func validateRepo(repo string) (string, error) {
	if repo == "" {
		return "", errors.New("repo is required")
	}
	if len(repo) > maxRepoNameLen {
		return "", fmt.Errorf("repo exceeds %d characters", maxRepoNameLen)
	}
	if !repoNameRe.MatchString(repo) {
		return "", errors.New("repo is not a valid OCI repository name")
	}
	// Defence in depth. The grammar above already excludes these, but the
	// value crosses into string concatenation, so assert directly rather
	// than rely on the regex staying correct through future edits.
	if strings.ContainsAny(repo, ":@\\") || strings.Contains(repo, "..") {
		return "", errors.New("repo contains disallowed characters")
	}
	if ns := registryNamespace(); ns != "" {
		if repo != ns && !strings.HasPrefix(repo, ns+"/") {
			return "", fmt.Errorf("repo must be within namespace %q", ns)
		}
	}
	return repo, nil
}
