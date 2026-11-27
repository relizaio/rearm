package main

import (
	"bytes"
	"os"
	"sort"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

var wantRoutes = []string{"GET /health", "GET /pull", "POST /push"}

// buildRouter runs newRouter with gin's debug output captured. The writer and
// the process-wide mode are restored when the test ends.
func buildRouter(t *testing.T) (*gin.Engine, string) {
	t.Helper()
	origWriter := gin.DefaultWriter
	origMode := gin.Mode()
	t.Cleanup(func() {
		gin.DefaultWriter = origWriter
		gin.SetMode(origMode)
	})
	var buf bytes.Buffer
	gin.DefaultWriter = &buf
	r := newRouter()
	return r, buf.String()
}

func routesOf(r *gin.Engine) []string {
	var got []string
	for _, ri := range r.Routes() {
		got = append(got, ri.Method+" "+ri.Path)
	}
	sort.Strings(got)
	return got
}

func assertReleaseRouter(t *testing.T, r *gin.Engine, out string) {
	t.Helper()
	if got := gin.Mode(); got != gin.ReleaseMode {
		t.Errorf("gin.Mode() = %q, want %q", got, gin.ReleaseMode)
	}
	if strings.Contains(out, "[GIN-debug]") {
		t.Errorf("release mode printed gin debug output:\n%s", out)
	}
	if got := routesOf(r); strings.Join(got, ",") != strings.Join(wantRoutes, ",") {
		t.Errorf("routes = %v, want %v", got, wantRoutes)
	}
}

// T-970: no GIN_MODE (empty or unset) gives release mode and no gin debug output.
func TestNewRouterDefaultsToReleaseMode(t *testing.T) {
	t.Run("empty", func(t *testing.T) {
		t.Setenv(gin.EnvGinMode, "")
		if got := ginMode(); got != gin.ReleaseMode {
			t.Errorf("ginMode() = %q, want %q", got, gin.ReleaseMode)
		}
		r, out := buildRouter(t)
		assertReleaseRouter(t, r, out)
	})
	t.Run("unset", func(t *testing.T) {
		t.Setenv(gin.EnvGinMode, "")
		os.Unsetenv(gin.EnvGinMode)
		if got := ginMode(); got != gin.ReleaseMode {
			t.Errorf("ginMode() = %q, want %q", got, gin.ReleaseMode)
		}
		r, out := buildRouter(t)
		assertReleaseRouter(t, r, out)
	})
}

// T-971: a GIN_MODE given from outside wins.
func TestNewRouterHonorsGinModeFromEnv(t *testing.T) {
	t.Run("debug", func(t *testing.T) {
		t.Setenv(gin.EnvGinMode, gin.DebugMode)
		r, out := buildRouter(t)
		if got := gin.Mode(); got != gin.DebugMode {
			t.Errorf("gin.Mode() = %q, want %q", got, gin.DebugMode)
		}
		if !strings.Contains(out, `Running in "debug" mode`) {
			t.Errorf("debug mode did not print the gin warning:\n%s", out)
		}
		for _, line := range []string{"POST   /push", "GET    /pull", "GET    /health"} {
			if !strings.Contains(out, "[GIN-debug] "+line+" ") {
				t.Errorf("debug output has no route line for %q:\n%s", line, out)
			}
		}
		if got := routesOf(r); strings.Join(got, ",") != strings.Join(wantRoutes, ",") {
			t.Errorf("routes = %v, want %v", got, wantRoutes)
		}
	})
	t.Run("release", func(t *testing.T) {
		t.Setenv(gin.EnvGinMode, gin.ReleaseMode)
		r, out := buildRouter(t)
		assertReleaseRouter(t, r, out)
	})
}
