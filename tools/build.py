#!/usr/bin/env python3
"""Build, package and sign the GCBlock LSPosed module APK."""
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SDK = os.environ.get('GCBLOCK_SDK', '/root/sdk')

sys.path.insert(0, HERE)
import axml  # noqa: E402

OUT = os.path.join(ROOT, 'out')
DATA_SRC = os.path.join(ROOT, 'src')
CLASSES = os.path.join(OUT, 'classes')
DEXDIR = os.path.join(OUT, 'dex')
VARIANT = os.environ.get('VARIANT', 'libxposed')
UNSIGNED = os.path.join(OUT, f'unsigned-{VARIANT}.apk')
SIGNED = os.path.join(OUT, 'GCBlock.apk' if VARIANT == 'libxposed' else 'GCBlock-legacy.apk')
KEY = os.path.join(OUT, 'gcblock.pk8')
CERT = os.path.join(OUT, 'gcblock.pem')

PKG = 'com.dsh.gcblock'
VERSION_CODE = 19
VERSION_NAME = '2.8'
MIN_SDK = 24
TARGET_SDK = 29
LABEL = 'GCBlock'

META_LEGACY = [
    ('xposedmodule', 'bool', True),
    ('xposeddescription', 'str', '阻断游戏管家对游戏中心的调用'),
    ('xposedminversion', 'int', 82),
]
META = META_LEGACY if VARIANT == 'legacy' else []


def run(cmd, **kw):
    print('+', ' '.join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit('failed: ' + ' '.join(cmd))
    if r.stdout.strip():
        print(r.stdout.strip()[-800:])
    return r


def make_key():
    if os.path.exists(KEY) and os.path.exists(CERT):
        return
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    import datetime

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, 'GCBlock'),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, 'GCBlock'),
    ])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    with open(KEY, 'wb') as f:
        f.write(key.private_bytes(serialization.Encoding.DER,
                                  serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    with open(CERT, 'wb') as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    print('generated key/cert')


def main():
    os.makedirs(OUT, exist_ok=True)

    # 0. compile the compile-only libxposed API stubs (NOT packaged)
    stub_src = os.path.join(ROOT, 'stubs')
    stub_out = os.path.join(OUT, 'lxapi')
    stub_srcs = []
    for root, _, files in os.walk(stub_src):
        for fn in files:
            if fn.endswith('.java'):
                stub_srcs.append(os.path.join(root, fn))
    if os.path.isdir(stub_out):
        subprocess.run(['rm', '-rf', stub_out])
    run(['javac', '-source', '8', '-target', '8',
         '-classpath', f'{SDK}/android-api16.jar',
         '-d', stub_out] + stub_srcs)

    # 1. compile module
    srcs = []
    for root, _, files in os.walk(DATA_SRC):
        for fn in files:
            if fn.endswith('.java'):
                srcs.append(os.path.join(root, fn))
    if os.path.isdir(CLASSES):
        subprocess.run(['rm', '-rf', CLASSES])
    run(['javac', '-source', '8', '-target', '8',
         '-classpath', f'{SDK}/android-api16.jar:{SDK}/api-82.jar:{stub_out}',
         '-d', CLASSES] + srcs)

    # 2. dex
    if os.path.isdir(DEXDIR):
        subprocess.run(['rm', '-rf', DEXDIR])
    os.makedirs(DEXDIR)
    class_files = []
    for root, _, files in os.walk(CLASSES):
        for fn in files:
            if fn.endswith('.class'):
                class_files.append(os.path.join(root, fn))
    run(['java', '-cp', f'{SDK}/bt/d8.jar', 'com.android.tools.r8.D8',
         '--min-api', str(MIN_SDK), '--lib', f'{SDK}/android-api16.jar',
         '--output', DEXDIR] + class_files)

    # 3. manifest + zip  (variant: libxposed | legacy)
    manifest = axml.build_manifest(PKG, VERSION_CODE, VERSION_NAME,
                                   MIN_SDK, TARGET_SDK, LABEL, META)
    if os.path.exists(UNSIGNED):
        os.remove(UNSIGNED)
    with zipfile.ZipFile(UNSIGNED, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('AndroidManifest.xml', manifest)
        z.write(os.path.join(DEXDIR, 'classes.dex'), 'classes.dex')
        if VARIANT == 'libxposed':
            z.writestr('META-INF/xposed/module.prop',
                       'minApiVersion=101\ntargetApiVersion=102\n')
            z.writestr('META-INF/xposed/java_init.list', 'com.dsh.gcblock.GcModule\n')
    print('packed', UNSIGNED, os.path.getsize(UNSIGNED), 'bytes')

    # 4. sign
    make_key()
    if os.path.exists(SIGNED):
        os.remove(SIGNED)
    run(['java', '-jar', f'{SDK}/bt/apksigner.jar', 'sign',
         '--key', KEY, '--cert', CERT,
         '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
         '--out', SIGNED, UNSIGNED])

    # 5. verify
    run(['java', '-jar', f'{SDK}/bt/apksigner.jar', 'verify', '--verbose',
         '--print-certs', SIGNED])
    print('\nOK ->', SIGNED, os.path.getsize(SIGNED), 'bytes')


if __name__ == '__main__':
    main()
