#!/usr/bin/env python3
"""Build Quicksilver: python3 build.py  ->  build/libs/quicksilver-<version>.jar

Needs a JDK 17+ (javac on PATH, or JAVA_HOME) and Python 3.8+. The Minecraft b1.7.3 client and the libraries
below are downloaded once into build/cache/ (override with QS_CACHE) and checked against their SHA-256.

Steps: remap the client from obfuscated names to Babric intermediary, then to the readable names in
mappings/named.tiny; compile src/main/java against it; remap the result back to intermediary (with --mixin, so
mixin targets are remapped too).
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import urllib.request
import zipfile

PROJECT = Path(__file__).resolve().parent
BUILD = PROJECT / 'build'
CACHE = Path(os.environ.get('QS_CACHE', str(BUILD / 'cache')))
TOOLS = BUILD / 'toolchain'
META = json.loads((PROJECT / 'src/main/resources/fabric.mod.json').read_text())

FABRIC = 'https://maven.fabricmc.net/'
CENTRAL = 'https://repo1.maven.org/maven2/'
LEGACY_FABRIC = 'https://maven.legacyfabric.net/'
GLASS = 'https://maven.glass-launcher.net/babric/'

# (repository, maven coordinate, sha256). Versions match what a Babric b1.7.3 instance runs.
REMAPPER = [
    (FABRIC, 'net.fabricmc:tiny-remapper:0.8.2', '160c436c19707b4ae9e87502e5f04d733d8e09b4143735400c3464cb04163476'),
    (CENTRAL, 'org.ow2.asm:asm:9.8', '876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051'),
    (CENTRAL, 'org.ow2.asm:asm-analysis:9.8', 'e640732fbcd3c6271925a504f125e38384688f4dfbbf92c8622dfcee0d09edb9'),
    (CENTRAL, 'org.ow2.asm:asm-commons:9.8', '3301a1c1cb4c59fcc5292648dac1d7c5aed4c0f067dfbe88873b8cdfe77404f4'),
    (CENTRAL, 'org.ow2.asm:asm-tree:9.8', '14b7880cb7c85eed101e2710432fc3ffb83275532a6a894dc4c4095d49ad59f1'),
    (CENTRAL, 'org.ow2.asm:asm-util:9.8', '8ba0460ecb28fd0e2980e5f3ef3433a513a457bc077f81a53bdc75b587a08d15'),
]
COMPILE = [
    (FABRIC, 'net.fabricmc:fabric-loader:0.19.5', '93044e4dd46de5d8136701292f05e868da096d2c9fddb4793e4fdbcc63efc695'),
    (FABRIC, 'net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7', '1f0ae44db7295f8626f33b1dc0ad7f043d8954a8d6847247875fcc5dfcecc934'),
    (LEGACY_FABRIC, 'org.lwjgl.lwjgl:lwjgl:2.9.4+legacyfabric.17', 'cce6470777f6503c8d6402f7998bd37620c06acde959cb96af2aa270ec65aff1'),
    (CENTRAL, 'org.apache.logging.log4j:log4j-api:2.16.0', 'cbbcef7b57fef115a788395e6e11cdf92c01c1d606652c493d40d3c81766b9fc'),
]
INTERMEDIARY = (GLASS, 'babric:intermediary:b1.7.3', '25b3f2b1efa1b9c5ec4b2fb0de6be7e1a5a031df70d6a48605d31b26262ad426')
CLIENT = ('https://launcher.mojang.com/v1/objects/43db9b498cb67058d2e12d394e6507722e71bb45/client.jar',
          'minecraft-b1.7.3-client.jar', 'af1fa04b8006d3ef78c7e24f8de4aa56f439a74d7f314827529062d5bab6db4c')


def java_home():
    if os.environ.get('JAVA_HOME'):
        return Path(os.environ['JAVA_HOME'])
    javac = shutil.which('javac')
    if not javac:
        raise SystemExit('Need a JDK 17+ (javac on PATH, or JAVA_HOME).')
    return Path(os.path.realpath(javac)).parent.parent


JAVA = java_home() / 'bin/java'
JAVAC = java_home() / 'bin/javac'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fetch(url, dst, digest):
    """Download url to dst (once) and check its SHA-256."""
    if dst.exists() and sha256(dst) == digest:
        return dst
    dst.parent.mkdir(parents=True, exist_ok=True)
    print('Downloading', url)
    tmp = dst.with_name(dst.name + '.part')
    request = urllib.request.Request(url, headers={'User-Agent': 'quicksilver-build'})
    with urllib.request.urlopen(request) as response, open(tmp, 'wb') as out:
        shutil.copyfileobj(response, out)
    if sha256(tmp) != digest:
        tmp.unlink()
        raise SystemExit(f'Checksum mismatch for {url}')
    tmp.replace(dst)
    return dst


def maven(entry):
    repo, coordinate, digest = entry
    group, artifact, version = coordinate.split(':')
    path = f'{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}.jar'
    return fetch(repo + path, CACHE / path, digest)


def run(args):
    subprocess.run([str(a) for a in args], check=True)


def remap(remapper, src, dst, mappings, source, target, classpath=(), mixin=False):
    if dst.exists(): dst.unlink()
    run([JAVA, '-cp', os.pathsep.join(map(str, remapper)), 'net.fabricmc.tinyremapper.Main',
         src, dst, mappings, source, target, *classpath, *(['--mixin'] if mixin else [])])


def main():
    remapper = [maven(e) for e in REMAPPER]
    libraries = [maven(e) for e in COMPILE]
    TOOLS.mkdir(parents=True, exist_ok=True)

    # Minecraft with readable names, rebuilt when the mappings change.
    mappings = PROJECT / 'mappings/named.tiny'
    marker = TOOLS / 'mapping.sha256'
    named_mc = TOOLS / 'mc-named.jar'
    intermediary_mc = TOOLS / 'mc-intermediary.jar'
    if not named_mc.exists() or not marker.exists() or marker.read_text() != sha256(mappings):
        intermediary = TOOLS / 'intermediary.tiny'
        with zipfile.ZipFile(maven(INTERMEDIARY)) as archive:
            intermediary.write_bytes(archive.read('mappings/mappings.tiny'))
        client = fetch(CLIENT[0], CACHE / CLIENT[1], CLIENT[2])
        remap(remapper, client, intermediary_mc, intermediary, 'client', 'intermediary')
        remap(remapper, intermediary_mc, named_mc, mappings, 'intermediary', 'named')
        marker.write_text(sha256(mappings))
    classpath = [named_mc, *remapper, *libraries]

    classes = BUILD / 'classes'
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir(parents=True)
    run([JAVAC, '--release', '17', '-encoding', 'UTF-8', '-proc:none',
         '-cp', os.pathsep.join(map(str, classpath)), '-d', classes,
         *sorted((PROJECT / 'src/main/java').rglob('*.java'))])

    compiled = BUILD / 'named.jar'
    with zipfile.ZipFile(compiled, 'w', zipfile.ZIP_DEFLATED) as archive:
        for directory in [classes, PROJECT / 'src/main/resources']:
            for p in sorted(directory.rglob('*')):
                if p.is_file(): archive.write(p, p.relative_to(directory))
        archive.write(PROJECT / 'LICENSE', 'LICENSE')
    result = BUILD / 'libs' / f'{META["id"]}-{META["version"]}.jar'
    result.parent.mkdir(parents=True, exist_ok=True)
    temporary = result.with_name(result.stem + '.tmp.jar')
    remap(remapper, compiled, temporary, mappings, 'named', 'intermediary', classpath, mixin=True)
    temporary.replace(result)
    print('Built', result)


if __name__ == '__main__':
    main()
