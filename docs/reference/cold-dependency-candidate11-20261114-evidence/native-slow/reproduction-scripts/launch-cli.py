import os,sys,json,shutil,subprocess,datetime,hashlib
from pathlib import Path
root=Path('/Users/wangshubo/develop/openidea-go');base=root/'.tools/continuation-20261114/two-hour-resume';flow=sys.argv[1];assert flow in ('atomic','slow','steady')
app=root/'build/product-candidate11/open Goland.app';assert app.is_dir()
out=base/'native'/(flow+'-cli');out.mkdir(parents=True,exist_ok=False);profile=out/'profile';profile.mkdir()
source=root/'.tools/continuation-20261114/initial-missing-repair/native-candidate10/atomic/profile/config'
shutil.copytree(source,profile/'config');properties=profile/'idea.properties';properties.write_text(''.join(f'idea.{key}.path={profile/value}\n'for key,value in [('config','config'),('system','system'),('plugins','plugins'),('log','log')]))
tools=app/'Contents/plugins/opengoland/tools';env=os.environ.copy();env.pop('GOWORK',None)
scoped={'IDEA_PROPERTIES':str(properties),'IDEA_VM_OPTIONS':str(app/'Contents/bin/opengoland.vmoptions'),'PATH':str(tools/'go/bin')+':/usr/bin:/bin:/usr/sbin:/sbin','GOROOT':str(tools/'go'),'GOCACHE':str(root/'.tools/go-tools-cache/build-cache'),'GOMODCACHE':str(root/'.tools/go-tools-cache/modules'),'GOTOOLCHAIN':'local','GOENV':'off','GOFLAGS':'','GOPROXY':'off','GOSUMDB':'off','GOPRIVATE':'','GONOPROXY':'','GONOSUMDB':'','GOVCS':'*:off','GOTELEMETRY':'off','CGO_ENABLED':'0'};env.update(scoped)
project=base/'native-c11-fixtures'/flow/'app';caller={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in project.iterdir() if p.is_file()}
project=base/'native-c11-fixtures'/flow/'app'
import xml.etree.ElementTree as ET
application=ET.Element('application');component=ET.SubElement(application,'component',name='Trusted.Paths');option=ET.SubElement(component,'option',name='TRUSTED_PROJECT_PATHS');mapping=ET.SubElement(option,'map');ET.SubElement(mapping,'entry',key=str(project),value='true');ET.ElementTree(application).write(profile/'config/options/trusted-paths.xml',encoding='utf-8',xml_declaration=True)
command=['/usr/bin/open','-n','-a',str(app),'--stdout',str(out/'launcher.stdout'),'--stderr',str(out/'launcher.stderr')]
for key,value in scoped.items():command+=['--env',key+'='+value]
command+=['--args',str(project),str(project/'main.go')]
completed=subprocess.run(command,env=env,capture_output=True,text=True,check=True)
import time
pid=None
for _ in range(100):
 lines=subprocess.check_output(['/bin/ps','-axo','pid=,command='],text=True).splitlines()
 found=[int(line.split(None,1)[0]) for line in lines if line.strip().split(None,1)[-1]==str(app/'Contents/MacOS/idea')+' '+str(project)+' '+str(project/'main.go')]
 if len(found)==1:pid=found[0];break
 time.sleep(0.2)
assert pid is not None
record={'atUtc':datetime.datetime.now(datetime.UTC).isoformat(),'pid':pid,'launchMethod':'LaunchServices open with explicit scoped --env and explicit main.go argument; only this owned fixture directory pretrusted in isolated profile', 'command':command,'flow':flow,'app':str(app),'profile':str(profile),'project':str(project),'scopedEnvironment':scoped,'GOWORKAbsentRequested':True,'callerSha256':caller};(out/'launch.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record,indent=2))
