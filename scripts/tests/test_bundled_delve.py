"""Input tampering, source ownership and installed receipt controls; no fake DAP."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('derived_delve_bundle', SCRIPTS / 'bundle-go-tools.py')
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)
import go_tool_receipts as receipts


class DerivedDelveTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='Delve 中文😀 ')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repo = self.root / 'repo'
        self.cache = self.root / 'cache/module'
        self.staging = self.root / 'tools'
        self.staging.mkdir(); (self.staging / 'bin').mkdir()
        self.cache.mkdir(parents=True)
        for name in ('go_tool_receipts.py', 'source_patch.py'):
            p = self.repo / 'scripts' / name; p.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(SCRIPTS / name, p)
        self.source = {'go.mod': b'module github.com/go-delve/delve\n\ngo 1.25.0\n',
                       'pkg/proc/stack.go': b'package proc\nvar Value = "old"\n',
                       'LICENSE': b'Actual upstream license fixture, never executable\n'}
        for name, value in self.source.items():
            p=self.cache/name; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(value)
        self.zip = self.root/'official-module.zip'
        prefix = 'github.com/go-delve/delve@v1.27.2/'
        with zipfile.ZipFile(self.zip,'w') as archive:
            for name, value in self.source.items(): archive.writestr(prefix+name,value)
        self.patch = self.repo/'product/patches/delve.patch'; self.patch.parent.mkdir(parents=True)
        self.patch.write_bytes(b'--- a/pkg/proc/stack.go\n+++ b/pkg/proc/stack.go\n@@ -1,2 +1,2 @@\n package proc\n-var Value = "old"\n+var Value = "new"\n')
        self.tool = {'module':'github.com/go-delve/delve','version':'v1.27.2','package':'github.com/go-delve/delve/cmd/dlv',
                     'sum':'h1:fixture-module','goModSum':'h1:fixture-gomod','goModSha256':bundle.digest(self.cache/'go.mod')}
        original = bundle.files(self.cache); derived=copy.deepcopy(original)
        derived['pkg/proc/stack.go']={'bytes':len(self.source['pkg/proc/stack.go']),
                                    'sha256':hashlib.sha256(self.source['pkg/proc/stack.go'].replace(b'old',b'new')).hexdigest()}
        self.pin = {'schemaVersion':1,'id':'open-goland-delve-arm64-cfi-v1',
                    'upstream':{**self.tool,'zipSha256':bundle.digest(self.zip),'zipBytes':self.zip.stat().st_size},
                    'patchFile':self.patch.name,'patchSha256':bundle.digest(self.patch),
                    'sources':{'pkg/proc/stack.go':{'upstreamSha256':original['pkg/proc/stack.go']['sha256'],
                                                   'patchedSha256':derived['pkg/proc/stack.go']['sha256']}},
                    'architectureGuard':'arm64','buildInfoModuleVersion':'(devel)',
                    'sourceTree':{'upstreamSha256':receipts.tree_digest(original),'patchedSha256':receipts.tree_digest(derived),'fileCount':len(original)}}
        self.pin_path=self.patch.with_suffix('.json');self.update_pin()
        self.lock={'schemaVersion':1,'builderVersion':'go1.27.3','runtimeSdkVersion':'go1.26.10',
                   'tools':{'dlv':self.tool},'sdk':{'windows-amd64':{}},'builders':{'darwin-arm64':{'sha256':'1'*64}}}
        self.lock_path=self.repo/'product/go-tools.lock.json';self.update_lock()
        self.go=self.root/'compiler-go';self.go.write_bytes(b'Fixture compiler, never executed')
        self.record={'Zip':str(self.zip),'Dir':str(self.cache),'GoMod':str(self.cache/'go.mod')}
        self.info={'goVersion':'go1.27.3','module':{'module':self.tool['module'],'version':'(devel)'},'dependencies':[],
                   'build':{'GOOS':'windows','GOARCH':'amd64','CGO_ENABLED':'0','-trimpath':'true'}}
        self.calls=[]

    def update_pin(self):
        self.pin_path.write_text(json.dumps(self.pin),encoding='utf-8')
        self.tool['sourcePatch']={'pin':self.pin_path.relative_to(self.repo).as_posix(),'pinSha256':bundle.digest(self.pin_path)}

    def update_lock(self): self.lock_path.write_text(json.dumps(self.lock),encoding='utf-8')

    def execute(self,command,environment,cwd):
        self.calls.append((list(command),Path(cwd)))
        if 'build' in command:
            Path(command[command.index('-o')+1]).write_bytes(b'Fixture PE artifact, never executed')
            return ''
        self.assertIn('download',command)
        return json.dumps({'Sum':self.tool['sum'],'Dir':str(self.cache)})

    def derive(self,compiler=None):
        inputs=receipts.source_patch_inputs(self.repo,'dlv',self.tool)
        with patch.object(bundle,'host_target',return_value='darwin-arm64'), patch.object(bundle,'execute',side_effect=compiler or self.execute), patch.object(bundle,'build_info',return_value=self.info):
            record=bundle.build_derived_tool(self.go,self.tool,self.record,{},self.staging,'windows-amd64',inputs,self.lock)
        return record

    def manifest(self,record):
        return {'schemaVersion':1,'lockSha256':bundle.digest(self.lock_path),'target':'windows-amd64',
                'builderVersion':'go1.27.3','tools':{'dlv':record},'receiptVerifierSha256':bundle.RECEIPT_VERIFIER_SHA256,
                'files':bundle.files(self.staging)}

    def test_owned_copy_real_patch_cross_target_devel_and_upstream_license(self):
        before=bundle.files(self.cache);z=self.zip.read_bytes();record=self.derive()
        self.assertEqual(before,bundle.files(self.cache));self.assertEqual(z,self.zip.read_bytes())
        command,source=self.calls[0]
        self.assertNotEqual(self.cache,source);self.assertFalse(source.exists())
        self.assertEqual(['build','-mod=readonly','-trimpath','-buildvcs=false'],command[1:5])
        self.assertEqual('dlv.exe',Path(command[command.index('-o')+1]).name)
        self.assertEqual('(devel)',record['buildInfo']['module']['version'])
        license_record=record['licenses'][0]
        self.assertEqual('v1.27.2',license_record['version'])
        self.assertEqual(self.source['LICENSE'],(self.staging/'licenses'/license_record['files'][0]['path']).read_bytes())
        self.assertEqual('source-derived',receipts.validate_bundle(self.staging,self.repo,self.manifest(record))['tools']['dlv']['kind'])

    def test_wrong_module_archive_rejected_before_compiler(self):
        self.zip.write_bytes(self.zip.read_bytes()+b'tamper')
        with self.assertRaisesRegex(ValueError,'ZIP changed'):self.derive()
        self.assertEqual([],self.calls)

    def test_wrong_original_source_hash_rejected(self):
        self.pin['sources']['pkg/proc/stack.go']['upstreamSha256']='0'*64;self.update_pin()
        with self.assertRaisesRegex(ValueError,'source digest differs'):self.derive()
        self.assertEqual([],self.calls)

    def test_preexisting_cache_license_tamper_cannot_enter_bundle(self):
        (self.cache/'LICENSE').write_bytes(b'tampered before build')
        with self.assertRaisesRegex(ValueError,'cache differs from official'):self.derive()
        self.assertEqual([],self.calls)

    def test_patch_tamper_rejected_before_any_cached_or_build_use(self):
        self.patch.write_bytes(self.patch.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError,'patch mismatch'):self.derive()
        self.assertEqual([],self.calls)

    def test_patched_tree_mismatch_rejected(self):
        self.pin['sourceTree']['patchedSha256']='0'*64;self.update_pin()
        with self.assertRaisesRegex(ValueError,'undeclared source changes'):self.derive()
        self.assertEqual([],self.calls)

    def test_undeclared_patch_created_source_is_rejected(self):
        self.patch.write_bytes(self.patch.read_bytes()+b'--- /dev/null\n+++ b/extra.go\n@@ -0,0 +1 @@\n+package proc\n')
        self.pin['patchSha256']=bundle.digest(self.patch);self.update_pin()
        with self.assertRaisesRegex(ValueError,'undeclared source changes'):self.derive()
        self.assertEqual([],self.calls)

    def test_compiler_must_not_mutate_owned_sources_or_original_module(self):
        for original in (False,True):
            def mutate(command,environment,cwd):
                value=self.execute(command,environment,cwd)
                if 'build'in command:
                    p=self.cache/'go.mod' if original else Path(cwd)/'extra.go';p.write_bytes(b'changed')
                return value
            with self.subTest(original=original),self.assertRaisesRegex(ValueError,'source changed|cache changed'):
                self.derive(mutate)
            (self.cache/'go.mod').write_bytes(self.source['go.mod'])

    def test_wrong_build_info_cannot_be_called_stock_or_other_target(self):
        self.info['module']['version']='v1.27.2'
        with self.assertRaisesRegex(ValueError,'build identity differs'):self.derive()

    def test_installed_artifact_tamper_rejected(self):
        record=self.derive();manifest=self.manifest(record);(self.staging/record['executable']).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'inventory'):receipts.validate_bundle(self.staging,self.repo,manifest)

    def test_self_consistent_receipt_tree_tamper_still_rejected_by_repository_pin(self):
        record=self.derive();p=self.staging/record['derivation']['receipt'];r=json.loads(p.read_text());r['sourceTreeDigests']['derived']='0'*64;p.write_text(json.dumps(r))
        record['derivation']['receiptSha256']=bundle.digest(p)
        with self.assertRaisesRegex(ValueError,'reviewed source'):receipts.validate_bundle(self.staging,self.repo,self.manifest(record))

    def test_self_consistent_bundled_pin_tamper_cannot_replace_reviewed_pin(self):
        record=self.derive();(self.staging/record['derivation']['pin']).write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'reviewed inputs'):receipts.validate_bundle(self.staging,self.repo,self.manifest(record))

    def test_unknown_derivation_schema_rejected(self):
        self.pin['schemaVersion']=2;self.update_pin()
        with self.assertRaisesRegex(ValueError,'schema'):self.derive()

    def test_stock_record_supported_only_with_stock_reviewed_lock(self):
        record=self.derive();record.pop('distributionKind');record.pop('derivation')
        record['buildInfo']=copy.deepcopy(self.info);record['buildInfo']['module']={key:self.tool[key]for key in ('module','version','sum')}
        self.tool.pop('sourcePatch');self.update_lock()
        self.assertEqual('upstream-source-built',receipts.validate_bundle(self.staging,self.repo,self.manifest(record))['tools']['dlv']['kind'])


if __name__=='__main__':unittest.main()
