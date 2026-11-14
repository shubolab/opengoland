import base64,hashlib,json,re,sys
from pathlib import Path
bundle,flow,label,evidence,pid,window=sys.argv[1:];base=Path(__file__).resolve().parent
assert re.fullmatch(r'[-a-z0-9]+\.json',bundle) and re.fullmatch(r'[-a-z0-9]+',flow) and re.fullmatch(r'[-a-z0-9]+',label)
assert re.fullmatch(r'ev_[0-9a-f]{64}',evidence)
r=json.loads((base/bundle).read_text());matches=[x for x in r['records']if x['evidence_id']==evidence];assert len(matches)==1;x=matches[0];n=x['normalized_result']['initial']
assert x['operation']=='observe_native_ui' and x['parameters']['pid']==int(pid) and x['parameters']['window_id']==int(window)
assert n['window']['pid']==int(pid) and n['window']['window_id']==int(window)
assert x['subject']['digest']['sha256']=='a694d3890155051269825d2acf6674f0351074ae1b4282af5296b000dcce4006'
cap=n['screenshot'];raw=base64.b64decode(cap['base64']);assert hashlib.sha256(raw).hexdigest()==cap['sha256']
out=base/'native'/flow;out.mkdir(exist_ok=True);target=out/(label+'.png');meta=out/(label+'.capture.json');assert not target.exists() and not meta.exists()
target.write_bytes(raw);cap['base64']='[stored in '+target.name+'; original bytes and SHA preserved]';meta.write_text(json.dumps(x,indent=2)+'\n');print(json.dumps({'evidence':evidence,'pid':int(pid),'window':int(window),'sha256':cap['sha256'],'path':str(target)}))
