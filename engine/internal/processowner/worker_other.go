//go:build !windows

package processowner

import "errors"

func run([]string) (int, error) {
	return 2, errors.New("owned command mode is only used on Windows; Unix commands use their owned process group")
}
