import datetime,json,os,signal,subprocess,time,sys,hashlib
from pathlib import Path
base=Path(__file__).resolve().parent;label=sys.argv[1];out=base/'native'/label;launch=json.loads((out/'launch.json').read_text());root=launch['pid'];prefix=launch['app']+'/Contents/'
table={}
for line in subprocess.check_output(['/bin/ps','-axo','pid=,ppid=,lstart=,command='],text=True).splitlines():
 f=line.split(None,7)
 if len(f)==8:table[int(f[0])]={'parent':int(f[1]),'start':' '.join(f[2:7]),'command':f[7]}
assert root in table and table[root]['command'].startswith(prefix+'MacOS/idea ')
owned={root}
while True:
 n=owned|{pid for pid,row in table.items() if row['parent'] in owned}
 if n==owned:break
 owned=n
record={'atUtc':datetime.datetime.now(datetime.UTC).isoformat(),'normalMenuQuit':False,'reason':'CUA unavailable after desktop update; terminate this completed owned fixture instance only','before':{str(p):table[p]for p in sorted(owned)},'signal':'SIGTERM to owned App only'}
os.kill(root,signal.SIGTERM)
for _ in range(50):
 remain={str(p):subprocess.run(['/bin/ps','-p',str(p),'-o','lstart=,command='],capture_output=True,text=True).stdout.strip()for p in owned}
 if not any(remain.values()):break
 time.sleep(.2)
record['after']=remain;record['allSampledPidsGone']=not any(remain.values());record['signalsToChildren']=[]
record['callerSha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest()for p in Path(launch['project']).iterdir()if p.name in ('go.mod','main.go','main_test.go')};assert record['callerSha256']==launch['callerSha256']
(out/'termination.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({'label':label,'ownedPids':sorted(owned),'allSampledPidsGone':record['allSampledPidsGone']}))
assert record['allSampledPidsGone'],'Do not start another instance with lingering old owned processes'
