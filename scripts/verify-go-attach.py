#!/usr/bin/env python3
"""Real Delve DAP attach baseline against processes created only by this script.

This proves protocol and tool lifecycle behavior, not native IDE integration.
No existing app is attached to, no security settings are changed, and every
adapter/target cleanup is scoped to the exact process launched by this script.
"""
from __future__ import annotations
import argparse
import collections
import importlib.util
import json
import os
import pathlib
import queue
import signal
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("go_tools_baseline", ROOT / "scripts/verify-go-tools.py")
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
require = baseline.require

SOURCE = '''package main
import ("fmt"; "os"; "strconv"; "time")
type Counter struct { Value int }
var sink int
//go:noinline
func checkpoint(cart *Counter) {
    sink = cart.Value // ATTACH_BREAKPOINT
    cart.Value++
}
func worker() { for { time.Sleep(10*time.Millisecond) } }
func main() {
    go worker()
    cart := Counter{Value:37}
    fmt.Printf("ATTACH_READY %d\\n",os.Getpid())
    for { checkpoint(&cart); os.WriteFile(os.Args[1],[]byte(strconv.Itoa(cart.Value)),0600); time.Sleep(20*time.Millisecond) }
}
'''

def wait_line(events, process, fragment, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            line = events.get(timeout=.1)
            if fragment in line:
                return line
        except queue.Empty:
            pass
        require(process.poll() is None, f"owned process exited before {fragment}")
    raise baseline.ProbeFailure(f"timed out waiting for {fragment}")

def wait_initialized(peer, request, after):
    deadline=time.monotonic()+20
    while True:
        with peer.condition:
            if request in peer.responses and not peer.responses[request].get("success"):
                peer.response(request)
            if any(event.get("event")=="initialized" for event in peer.events[after:]):
                return
            require(not peer.closed, f"DAP closed during attach: {peer.failure}")
            require(time.monotonic()<deadline,"DAP attach did not initialize")
            peer.condition.wait(.1)

def wait_heartbeat(path, previous, target, timeout=5):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        require(owned_alive(target),"attached fixture was terminated by disconnect")
        if path.exists():
            current=path.read_text()
            if current and current!=previous:return current
        time.sleep(.03)
    raise baseline.ProbeFailure("fixture stayed alive but did not continue its heartbeat after detach")

def owned_alive(process):
    """macOS debugserver may reparent an attached process; Popen.poll then reports ECHILD as exit 0."""
    if process is None:return False
    row=subprocess.run(["ps","-p",str(process.pid),"-o","stat=","-o","command="],text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=3).stdout.strip()
    if not row:return False
    state,command=row.split(maxsplit=1)
    return "Z" not in state and all(str(argument) in command for argument in process.args)

def stop_exact_owned(process):
    if not owned_alive(process):return
    os.killpg(process.pid,signal.SIGTERM)
    deadline=time.monotonic()+4
    while time.monotonic()<deadline:
        if not owned_alive(process):
            try:process.wait(timeout=.1)
            except (subprocess.TimeoutExpired,ChildProcessError):pass
            return
        time.sleep(.03)
    if owned_alive(process):os.killpg(process.pid,signal.SIGKILL)
    deadline=time.monotonic()+4
    while owned_alive(process) and time.monotonic()<deadline:time.sleep(.03)
    require(not owned_alive(process),f"owned process survived cleanup: {process.pid}")

def run_case(kind, binary, source_root, client_root, dlv):
    checks=baseline.Checks()
    result={"mode":kind,"status":"failed","checks":checks.items}
    target=adapter=peer=connection=None
    lines=collections.deque(maxlen=100)
    try:
        heartbeat=source_root/f"heartbeat-{kind}"
        target_events=queue.Queue()
        target=subprocess.Popen([str(binary),str(heartbeat)],cwd=source_root,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
        for stream in [target.stdout,target.stderr]:threading.Thread(target=baseline.collect_lines,args=(stream,lines,target_events),daemon=True).start()
        wait_line(target_events,target,"ATTACH_READY")
        result["ownedTargetPid"]=target.pid
        adapter_events=queue.Queue()
        command=[dlv,"dap","--listen=127.0.0.1:0"]
        if kind=="remote_headless_multiclient":command=[dlv,"attach",str(target.pid),"--headless","--accept-multiclient","--listen=127.0.0.1:0","--log","--log-output=dap,debugger"]
        adapter=subprocess.Popen(command,cwd=source_root,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
        for stream in [adapter.stdout,adapter.stderr]:threading.Thread(target=baseline.collect_lines,args=(stream,lines,adapter_events),daemon=True).start()
        marker="API server listening at:" if kind=="remote_headless_multiclient" else "DAP server listening at:"
        endpoint=wait_line(adapter_events,adapter,marker).split(marker,1)[1].strip()
        host,port=endpoint.rsplit(":",1)
        require(host=="127.0.0.1","adapter did not bind loopback")
        connection=socket.create_connection((host,int(port)),timeout=5);connection.settimeout(None)
        peer=baseline.Peer(connection.makefile("rb"),connection.makefile("wb"),kind="dap")
        with checks.check("dap_initialize_real_attach_and_source_mapped_breakpoint") as detail:
            capabilities=peer.request("initialize",{"adapterID":"go","clientID":"opengoland-attach-baseline","linesStartAt1":True,"columnsStartAt1":True,"pathFormat":"path","supportsVariableType":True})
            require(capabilities.get("supportsConfigurationDoneRequest"),"configurationDone missing")
            before=peer.mark()
            parameters={"mode":"remote" if kind=="remote_headless_multiclient" else "local","stopOnEntry":True,"showGlobalVariables":True,"hideSystemGoroutines":False}
            if kind!="remote_headless_multiclient":parameters["processId"]=target.pid
            client=source_root if kind=="local_owned_dap" else client_root
            if client!=source_root:parameters["substitutePath"]=[{"from":str(client),"to":str(source_root)}]
            attach=peer.send_request("attach",parameters)
            wait_initialized(peer,attach,before)
            line=SOURCE.splitlines().index("    sink = cart.Value // ATTACH_BREAKPOINT")+1
            response=peer.request("setBreakpoints",{"source":{"path":str(client/"main.go")},"breakpoints":[{"line":line}]})
            require(response.get("breakpoints") and response["breakpoints"][0].get("verified"),f"mapped breakpoint was not verified: {response}")
            peer.request("configurationDone");peer.response(attach)
            index,stopped=peer.event(lambda event:event.get("event")=="stopped",after=before)
            if stopped.get("body",{}).get("reason")!="breakpoint":
                before=peer.mark();peer.request("continue",{"threadId":stopped["body"]["threadId"]})
                index,stopped=peer.event(lambda event:event.get("event")=="stopped",after=before)
            require(stopped["body"].get("reason")=="breakpoint",f"unexpected stop: {stopped}")
            thread_id=stopped["body"]["threadId"]
            detail.update({"wireRequest":"attach","attachMode":parameters["mode"],"breakpointLine":line,"sourceMapped":client!=source_root,"adapterPid":adapter.pid})
        with checks.check("goroutines_stack_scopes_and_watch") as detail:
            threads=peer.request("threads").get("threads",[])
            require(len(threads)>=2 and any(item["id"]==thread_id for item in threads),f"goroutines missing: {threads}")
            frames=peer.request("stackTrace",{"threadId":thread_id,"levels":20}).get("stackFrames",[])
            frame=next((f for f in frames if f.get("source",{}).get("path")==str(client/"main.go") and f.get("line")==line),None)
            require(frame is not None,f"source-mapped breakpoint frame missing: {frames}")
            scopes=peer.request("scopes",{"frameId":frame["id"]}).get("scopes",[])
            variables=[]
            for scope in scopes:
                if scope.get("variablesReference"):variables+=peer.request("variables",{"variablesReference":scope["variablesReference"]}).get("variables",[])
            require(any(variable["name"]=="cart" for variable in variables),"cart local missing")
            watch=peer.request("evaluate",{"expression":"cart.Value","frameId":frame["id"],"context":"watch"})
            require(int(watch["result"])>=37,f"wrong watch result: {watch}")
            detail.update({"goroutineCount":len(threads),"stackDepth":len(frames),"watchExpression":"cart.Value","watchResult":watch["result"]})
        with checks.check("detach_preserves_owned_fixture_and_cleanup_scope") as detail:
            peer.request("setBreakpoints",{"source":{"path":str(client/"main.go")},"breakpoints":[]})
            peer.request("continue",{"threadId":thread_id})
            previous=heartbeat.read_text() if heartbeat.exists() else ""
            peer.request("disconnect",{"terminateDebuggee":False},timeout=8)
            current=wait_heartbeat(heartbeat,previous,target)
            if kind=="remote_headless_multiclient":require(adapter.poll() is None,"multi-client external server unexpectedly exited")
            else:adapter.wait(timeout=8)
            detail.update({"terminateDebuggee":False,"targetAliveAfterDetach":owned_alive(target),"heartbeatContinued":current!=previous,"externalMulticlientServerPreserved":adapter.poll() is None if kind=="remote_headless_multiclient" else None})
            stop_exact_owned(adapter)
            previous=heartbeat.read_text();wait_heartbeat(heartbeat,previous,target)
            detail["targetAliveAfterAdapterCleanup"]=owned_alive(target)
        result["status"]="passed"
    except Exception as error:
        result["error"]=str(error)
        evidence=(str(error)+"\n".join(lines)).lower()
        if sys.platform=="darwin" and any(marker in evidence for marker in ["operation not permitted","not allowed","task_for_pid","permission denied","could not attach to pid","developer mode","authorization"]):
            result["status"]="blocked";result["blocker"]="macOS attach/debugserver permission rejected; no security settings were changed"
    finally:
        result["targetOsAliveBeforeHarnessCleanup"]=owned_alive(target)
        if peer and adapter and adapter.poll() is None:
            try:peer.request("disconnect",{"terminateDebuggee":False},timeout=3)
            except Exception:pass
        if connection:
            try:connection.shutdown(socket.SHUT_RDWR)
            except OSError:pass
            connection.close()
        stop_exact_owned(adapter);stop_exact_owned(target)
        result["ownedAdapterExited"]=not owned_alive(adapter)
        result["ownedFixtureExitedAfterHarnessCleanup"]=not owned_alive(target)
        result["outputTail"]=list(lines)[-8:]
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=pathlib.Path,default=ROOT/"docs/verification/go-attach.json")
    args=parser.parse_args()
    report={"scope":"direct-tool-DAP-attach-baseline; not native IDE integration evidence","versions":{"go":baseline.command(["go","version"]),"dlv":baseline.command(["dlv","version"])},"cases":[]}
    started=time.monotonic()
    with tempfile.TemporaryDirectory(prefix="opengoland-attach-") as folder:
        root=pathlib.Path(folder).resolve();source=root/"server";source.mkdir();client=root/"client";client.mkdir()
        (source/"go.mod").write_text("module example.org/opengolandattach\n\ngo 1.24.0\n")
        (source/"main.go").write_text(SOURCE);shutil.copyfile(source/"main.go",client/"main.go")
        binary=root/"attach-fixture"
        baseline.command(["go","build","-gcflags=-N -l","-o",str(binary),"."],cwd=source)
        for kind in ["local_owned_dap","remote_external_dap","remote_headless_multiclient"]:
            report["cases"].append(run_case(kind,binary,source,client,shutil.which("dlv")))
    report["seconds"]=round(time.monotonic()-started,3)
    report["status"]="passed" if all(item["status"]=="passed" for item in report["cases"]) else "failed" if any(item["status"]=="failed" for item in report["cases"]) else "blocked"
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps({"status":report["status"],"seconds":report["seconds"],"cases":[{"mode":c["mode"],"status":c["status"],"error":c.get("error")} for c in report["cases"]],"output":str(args.output)},ensure_ascii=False))
    return 0 if report["status"]=="passed" else 2
if __name__=="__main__":raise SystemExit(main())
