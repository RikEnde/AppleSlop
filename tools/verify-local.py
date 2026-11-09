#!/usr/bin/env python3
"""Offline fallback when Gradle cannot open its local lock socket. Requires cached dependencies."""
from pathlib import Path
import os, subprocess, shutil
root = Path(__file__).resolve().parents[1]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
out = root / 'build/local'
out.mkdir(parents=True, exist_ok=True)
java = root / '.tools/jdk/bin/java'
if not java.exists():
    java = Path(os.environ['JAVA_HOME']) / 'bin/java' if 'JAVA_HOME' in os.environ else Path(shutil.which('java'))
def jar(group, artifact, version):
    return str(next((cache/group/artifact/version).glob('*/'+artifact+'-'+version+'.jar')))
stdlib = jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.21')
compiler = [stdlib] + [jar(*x) for x in [
    ('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.21'),
    ('org.jetbrains.kotlin','kotlin-script-runtime','2.4.21'),
    ('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
    ('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.4.21'),
    ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),
    ('org.jetbrains','annotations','13.0')]]
tests = [jar(*x) for x in [
    ('org.jetbrains.kotlin','kotlin-test','2.4.21'),
    ('org.jetbrains.kotlin','kotlin-test-junit','2.4.21'),
    ('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3'),
    ('org.jetbrains.kotlinx','kotlinx-serialization-json-jvm','1.9.0'),
    ('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.9.0')]]
def run(args):
    subprocess.run([str(x) for x in args], cwd=root, check=True)
def compile(target, sources, cp, extra=()):
    dest=out/target
    if dest.exists(): shutil.rmtree(dest)
    dest.mkdir()
    run([java, '-Djava.io.tmpdir='+str(out), '-cp', os.pathsep.join(compiler),
         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
         '-jvm-target', '17', '-module-name', target, '-Xmulti-platform', '-cp', os.pathsep.join(map(str,cp)),
         '-d', dest, *extra, *sources])
cp=[stdlib]
for module in ['cpu','apple2','desktop']:
    sources=list((root/module/'src').glob('*Main/kotlin/**/*.kt'))
    compile(module,sources,cp)
    cp.append(out/module)
resources=out/'desktop/appleiislop/roms'
resources.mkdir(parents=True,exist_ok=True)
for file in (root/'roms').glob('*.rom'): shutil.copy2(file,resources)
classes=[]
for module in ['cpu','apple2','desktop']:
    sources=list((root/module/'src').glob('*Test/kotlin/**/*.kt'))
    if not sources: continue
    compile(module+'test',sources,cp+tests,['-Xfriend-paths='+str(out/module)])
    cp.append(out/(module+'test'))
    if (root/module/'src/jvmTest/resources').exists(): cp.append(root/module/'src/jvmTest/resources')
    classes.extend(str(f.relative_to(out/(module+'test'))).replace(os.sep,'.')[:-6]
                   for f in (out/(module+'test')).rglob('*.class') if '$' not in f.name and f.stem.endswith(('Test','Tests')))
run([java, '-Djava.awt.headless=true', '-Djava.io.tmpdir='+str(out),
     '-Dappleiislop.romDirectory='+str(root/'roms'), '-Dappleiislop.diskDirectory='+str(root/'disks'),
     '-cp',os.pathsep.join(map(str,cp+tests)), 'org.junit.runner.JUnitCore',*classes])
(out/'classpath.txt').write_text(os.pathsep.join(map(str,cp+tests)))
# A runnable jar for smoke testing, assembled without invoking Gradle.
import zipfile
with zipfile.ZipFile(out/'appleiislop.jar','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nMain-Class: appleiislop.desktop.MainKt\n\n')
    for name in ['LICENSE','NOTICE']: z.write(root/name,'META-INF/appleiislop/'+name)
    for module in ['cpu','apple2','desktop']:
        for f in (out/module).rglob('*'):
            if f.is_file(): z.write(f,f.relative_to(out/module).as_posix())
    with zipfile.ZipFile(stdlib) as lib:
        for name in lib.namelist():
            if not name.startswith('META-INF/') and not name.endswith('/'): z.writestr(name,lib.read(name))
print('Verified; runnable jar:', out/'appleiislop.jar')
