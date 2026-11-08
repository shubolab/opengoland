//go:build !windows

package methods

import (
	"errors"
	"os"
	"os/exec"
	"syscall"
)

func configureWorker(cmd *exec.Cmd) { cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true} }
func killWorker(cmd *exec.Cmd) error {
	if cmd.Process == nil {
		return nil
	}
	err := syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
	if errors.Is(err, syscall.ESRCH) {
		return os.ErrProcessDone
	}
	return err
}

func terminateOwnWorkerGroup() {
	if syscall.Getpgrp() == os.Getpid() {
		_ = syscall.Kill(-os.Getpid(), syscall.SIGKILL)
	}
}
