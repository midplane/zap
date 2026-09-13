#!/usr/bin/env python3
"""Query OSV for a freshly resolved Gradle inventory. Requires Python 3.9+."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request


def query(queries):
    request = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch",
        data=json.dumps({"queries": queries}).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "zap-dependency-audit"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        results = json.load(response)["results"]
    if len(results) != len(queries):
        raise ValueError("OSV returned an incomplete batch")
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-only", action="store_true", help="Scan only the release runtime graph")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    directory = root / "dist/audit"
    inventory = {}
    for name in ("Zap", "app"):
        # Explicit filenames: do not accidentally ingest previous audit reports.
        data = json.loads((directory / f"android-{name}.json").read_text())
        for coordinate, scopes in data.items():
            selected = [scope for scope in scopes if not args.runtime_only or scope == "releaseRuntimeClasspath"]
            if selected:
                inventory.setdefault(coordinate, set()).update(f"{name}:{scope}" for scope in selected)
    if not inventory:
        raise ValueError("The selected dependency inventory is empty")
    findings = {}
    coordinates = sorted(inventory)
    for offset in range(0, len(coordinates), 100):
        batch = coordinates[offset:offset + 100]
        queries = []
        for coordinate in batch:
            group, artifact, version = coordinate.split(":")
            queries.append({"package": {"ecosystem": "Maven", "name": f"{group}:{artifact}"}, "version": version})
        results = query(queries)
        for coordinate, request, result in zip(batch, queries, results):
            vulnerabilities = {item["id"] for item in result.get("vulns", [])}
            seen_tokens = set()
            while result.get("next_page_token"):
                token = result["next_page_token"]
                if token in seen_tokens:
                    raise ValueError("OSV repeated a pagination token")
                seen_tokens.add(token)
                result = query([{**request, "page_token": token}])[0]
                vulnerabilities.update(item["id"] for item in result.get("vulns", []))
            if vulnerabilities:
                findings[coordinate] = {"scopes": sorted(inventory[coordinate]), "advisories": sorted(vulnerabilities)}
    report = {
        "queried_at": datetime.now(timezone.utc).isoformat(),
        "source": "https://api.osv.dev/v1/querybatch",
        "scope": "releaseRuntimeClasspath" if args.runtime_only else "runtime, tests, kapt, buildscript classpath",
        "coordinates": coordinates,
        "findings": findings,
    }
    suffix = "runtime" if args.runtime_only else "all"
    output = directory / f"android-osv-{suffix}.json"
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Scanned {len(coordinates)} coordinates; {len(findings)} have advisory matches. Report: {output}")
    for coordinate, finding in findings.items():
        print(f"{coordinate}: {', '.join(finding['advisories'])}")
    return 1 if findings else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, urllib.error.URLError) as error:
        print(f"Audit incomplete: {error}", file=sys.stderr)
        sys.exit(2)
