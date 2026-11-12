package processowner

import "testing"

func TestExistingEngineModesRemainUnclaimed(t *testing.T) {
	for _, args := range [][]string{nil, {"--version"}, {"--help"}, {"--opengoland-struct-tags-worker"}} {
		handled, code, err := WorkerMain(args)
		if handled || code != 0 || err != nil {
			t.Fatalf("owner intercepted an unrelated engine request %q: %t %d %v", args, handled, code, err)
		}
	}
}

func TestMalformedOwnerRequestsCannotReachAPlatformLauncher(t *testing.T) {
	for _, args := range [][]string{{workerFlag}, {workerFlag, "go"}, {workerFlag, "--"}, {workerFlag, "--", ""}, {workerFlag, "--", "go", "bad\x00argument"}} {
		handled, code, err := WorkerMain(args)
		if !handled || code != 2 || err == nil {
			t.Fatalf("invalid owner request was not rejected: %q => %t %d %v", args, handled, code, err)
		}
	}
}
