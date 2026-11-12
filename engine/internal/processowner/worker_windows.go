//go:build windows

package processowner

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
)

// WinBase.h: ProcThreadAttributeValue(ProcThreadAttributeJobList=13,
// FALSE, TRUE, FALSE). Windows 10 / Server 2016 and newer assign this job
// during CreateProcess, before any command code can start or spawn children.
// https://learn.microsoft.com/windows/win32/api/processthreadsapi/nf-processthreadsapi-updateprocthreadattribute
const procThreadAttributeJobList = 0x0002000d

func run(arguments []string) (int, error) {
	path, err := exec.LookPath(arguments[0])
	if err != nil {
		return 1, err
	}
	application, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return 1, err
	}
	command, err := windows.UTF16PtrFromString(windows.ComposeCommandLine(append([]string{path}, arguments[1:]...)))
	if err != nil {
		return 1, err
	}
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return 1, fmt.Errorf("create command ownership: %w", err)
	}
	// Never inherit this handle or allow breakaway. Only the command enters the
	// job, never the IDE, owner process, unrelated processes, or a parent job.
	var closeOnce sync.Once
	closeJob := func() { closeOnce.Do(func() { _ = windows.CloseHandle(job) }) }
	defer closeJob()
	limits := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	limits.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&limits)), uint32(unsafe.Sizeof(limits))); err != nil {
		return 1, fmt.Errorf("configure command ownership: %w", err)
	}
	attributes, err := windows.NewProcThreadAttributeList(2)
	if err != nil {
		return 1, fmt.Errorf("create command attributes: %w", err)
	}
	defer attributes.Delete()
	jobs := []windows.Handle{job}
	if err := attributes.Update(procThreadAttributeJobList, unsafe.Pointer(&jobs[0]), unsafe.Sizeof(jobs[0])); err != nil {
		return 1, fmt.Errorf("assign command ownership before startup: %w", err)
	}
	// SDK discovery/module commands are noninteractive. The IDE keeps owner stdin
	// open as a lifetime pipe; EOF on IDE death must also end the command tree.
	input, err := os.Open(os.DevNull)
	if err != nil {
		return 1, err
	}
	defer input.Close()
	current, err := windows.GetCurrentProcess()
	if err != nil {
		return 1, err
	}
	files := []*os.File{input, os.Stdout, os.Stderr}
	handles := make([]windows.Handle, 3)
	defer func() {
		for _, handle := range handles {
			if handle != 0 {
				_ = windows.CloseHandle(handle)
			}
		}
	}()
	for index, file := range files {
		if err := windows.DuplicateHandle(current, windows.Handle(file.Fd()), current,
			&handles[index], 0, true, windows.DUPLICATE_SAME_ACCESS); err != nil {
			return 1, fmt.Errorf("prepare command standard stream: %w", err)
		}
	}
	if err := attributes.Update(windows.PROC_THREAD_ATTRIBUTE_HANDLE_LIST, unsafe.Pointer(&handles[0]), uintptr(len(handles))*unsafe.Sizeof(handles[0])); err != nil {
		return 1, fmt.Errorf("restrict inherited command handles: %w", err)
	}
	startup := windows.StartupInfoEx{}
	startup.Cb = uint32(unsafe.Sizeof(startup))
	startup.Flags = windows.STARTF_USESTDHANDLES
	startup.StdInput, startup.StdOutput, startup.StdErr = handles[0], handles[1], handles[2]
	startup.ProcThreadAttributeList = attributes.List()
	process := windows.ProcessInformation{}
	// A failed atomic assignment fails startup. There is deliberately no fallback
	// to a child that could escape before a later descendant scan or assignment.
	if err := windows.CreateProcess(application, command, nil, nil, true,
		windows.EXTENDED_STARTUPINFO_PRESENT|windows.CREATE_NO_WINDOW, nil, nil,
		&startup.StartupInfo, &process); err != nil {
		return 1, fmt.Errorf("start owned Windows command: %w", err)
	}
	defer windows.CloseHandle(process.Process)
	defer windows.CloseHandle(process.Thread)
	go func() {
		_, _ = io.Copy(io.Discard, os.Stdin)
		closeJob()
	}()
	state, err := windows.WaitForSingleObject(process.Process, windows.INFINITE)
	if err != nil || state != windows.WAIT_OBJECT_0 {
		return 1, fmt.Errorf("wait for owned command (state %d): %v", state, err)
	}
	var code uint32
	if err := windows.GetExitCodeProcess(process.Process, &code); err != nil {
		return 1, fmt.Errorf("read owned command exit status: %w", err)
	}
	// Even a normally completed launcher may leave descendants holding the output
	// pipes. Closing the job ends those descendants before this owner returns.
	closeJob()
	return int(code), nil
}
