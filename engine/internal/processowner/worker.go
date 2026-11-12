// Package processowner owns one finite Windows command independently of the
// JSON-RPC engine. Its stdin is a parent-lifetime pipe, not command input.
package processowner

import (
	"errors"
	"strings"
)

const workerFlag = "--opengoland-owned-command"

// WorkerMain recognizes only this private CLI mode. The caller exits with the
// returned command status after this function has released its ownership handle.
func WorkerMain(arguments []string) (handled bool, code int, err error) {
	if len(arguments) == 0 || arguments[0] != workerFlag {
		return false, 0, nil
	}
	if len(arguments) < 3 || arguments[1] != "--" || arguments[2] == "" {
		return true, 2, errors.New("owned command requires -- followed by an executable and its arguments")
	}
	for _, argument := range arguments[2:] {
		if strings.ContainsRune(argument, 0) {
			return true, 2, errors.New("owned command arguments cannot contain NUL")
		}
	}
	code, err = run(arguments[2:])
	return true, code, err
}
