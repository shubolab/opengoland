//go:build windows

package signature

import "os/exec"

// The current standalone distribution is macOS. Windows currently owns only
// the worker process; a Job Object implementation and native acceptance are
// required before claiming descendant cancellation on that platform.
func configureWorker(cmd *exec.Cmd) {}
func killWorker(cmd *exec.Cmd) error {
	if cmd.Process == nil {
		return nil
	}
	return cmd.Process.Kill()
}

func terminateOwnWorkerGroup() {}
