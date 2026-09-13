#!/usr/bin/env python3
"""Generate Android notices from the resolved runtime graph and cached artifacts."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import gzip
import io
import json
import os
from pathlib import Path
import struct
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def pom_license(coordinate, seen=None):
    seen = set() if seen is None else seen
    if coordinate in seen:
        raise ValueError(f"Cyclic POM parents: {coordinate}")
    seen.add(coordinate)
    group, artifact, version = coordinate.split(":")
    base = "https://dl.google.com/dl/android/maven2/" if group.startswith("androidx.") else "https://repo.maven.apache.org/maven2/"
    url = f"{base}{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    cached = list((CACHE / group / artifact / version).glob("*/*.pom"))
    if cached:
        data = cached[0].read_bytes()
    else:
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read()
    pom = ET.fromstring(data)
    licenses = [item.findtext("m:name", namespaces=NS) for item in pom.findall("m:licenses/m:license", NS)]
    if not licenses:
        parent = pom.find("m:parent", NS)
        if parent is None:
            raise ValueError(f"Missing license: {coordinate}")
        parent_coordinate = ":".join(parent.findtext(f"m:{key}", namespaces=NS) for key in ("groupId", "artifactId", "version"))
        return pom_license(parent_coordinate, seen)
    accepted = {"The Apache Software License, Version 2.0", "The Apache License, Version 2.0"}
    if any(name not in accepted for name in licenses):
        raise ValueError(f"Review new license before updating notices: {coordinate}: {licenses}")
    return "Apache-2.0", url


def suffix_source(data, coordinate):
    data = gzip.decompress(data)
    size = struct.unpack(">I", data[:4])[0]
    rules = data[4:4 + size]
    exception_size = struct.unpack(">I", data[4 + size:8 + size])[0]
    exceptions = data[8 + size:]
    if len(exceptions) != exception_size:
        raise ValueError("Unexpected OkHttp public suffix data format")
    header = (
        f"// Public Suffix List rule data from {coordinate}.\n"
        "// Decoded from okhttp3/internal/publicsuffix/publicsuffixes.gz to editable PSL text;\n"
        "// exception rules regain their leading !.\n"
        "// This Source Code Form is subject to the terms of the Mozilla Public\n"
        "// License, v. 2.0. If a copy of the MPL was not distributed with this\n"
        "// file, You can obtain one at https://mozilla.org/MPL/2.0/.\n"
        "// https://publicsuffix.org/\n\n"
    ).encode()
    return header + rules + b"\n" + b"".join(b"!" + rule + b"\n" for rule in exceptions.splitlines())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if committed notices differ; do not write")
    args = parser.parse_args()
    inventory = json.loads((ROOT / "dist/audit/android-app.json").read_text())
    coordinates = sorted(key for key, scopes in inventory.items() if "releaseRuntimeClasspath" in scopes)
    if not coordinates:
        raise ValueError("Empty runtime graph; regenerate the Gradle inventory first")
    with ThreadPoolExecutor(max_workers=8) as pool:
        licenses = dict(zip(coordinates, pool.map(pom_license, coordinates)))
    output = ROOT / "licenses/android"
    apache = (output / "Apache-2.0.txt").read_text().strip()
    notices = {}
    suffix = None
    for coordinate in coordinates:
        group, artifact, version = coordinate.split(":")
        files = sorted((CACHE / group / artifact / version).glob("*/*"))
        files = [file for file in files if file.suffix in (".jar", ".aar") and not file.name.endswith(("-sources.jar", "-javadoc.jar"))]
        # Platforms and multiplatform metadata modules can have no binary artifact.
        for file in files:
            def scan(archive, prefix=""):
                nonlocal suffix
                for name in archive.namelist():
                    if name.endswith("/"):
                        continue
                    if name == "classes.jar":
                        with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                            scan(nested, prefix + name + "!")
                    elif name == "okhttp3/internal/publicsuffix/publicsuffixes.gz":
                        if suffix is not None:
                            raise ValueError("Multiple public suffix artifacts; review manually")
                        suffix = suffix_source(archive.read(name), coordinate)
                    elif any(word in name.lower() for word in ("license", "notice", "copying", "copyright")) and not name.endswith(".class"):
                        text = archive.read(name).decode("utf-8").strip()
                        if text and text != apache:
                            notices[f"{coordinate}!{prefix}{name}"] = text
            with zipfile.ZipFile(file) as archive:
                scan(archive)
    if suffix is None:
        raise ValueError("OkHttp public suffix data missing; resolve runtime artifacts before generating notices")
    lines = [
        "Zap Android — third-party notices", "",
        "Generated by scripts/android-notices.py from releaseRuntimeClasspath.",
        "The following resolved modules use Apache-2.0 according to their Maven POMs",
        "(including inherited licenses). Platform/metadata entries may have no APK code.",
        "The full license is in Apache-2.0.txt. Original copyrights belong to the",
        "respective authors: Android Open Source Project/AndroidX contributors,",
        "Google/Guava authors, ZXing authors, JourneyApps, Square, Coil contributors,",
        "and JetBrains/Kotlin contributors. Zap's MIT license does not relicense them.", "",
    ]
    for coordinate, (license_name, url) in licenses.items():
        lines.extend([f"{coordinate} — {license_name}", f"  License metadata: {url}"])
    lines.extend(["", "Additional embedded notices (preserved verbatim):", ""])
    for name, text in sorted(notices.items()):
        lines.extend([name, "-" * 72, text, ""])
    lines.extend([
        "OkHttp's Public Suffix List data is covered by MPL-2.0; see MPL-2.0.txt.",
        "Its corresponding rule source accompanies this app in okhttp-public-suffix-list.txt.",
        "The rules were decoded from the distributed gzip data without changing them.", "",
    ])
    generated = {"THIRD_PARTY_NOTICES.txt": "\n".join(lines).encode(), "okhttp-public-suffix-list.txt": suffix}
    for name, data in generated.items():
        path = output / name
        if args.check:
            if not path.exists() or path.read_bytes() != data:
                raise ValueError(f"Stale notices: {path}; review dependencies and regenerate")
        else:
            path.write_bytes(data)
    print(f"{'Checked' if args.check else 'Generated'} notices for {len(coordinates)} resolved Android runtime modules")


if __name__ == "__main__":
    main()
