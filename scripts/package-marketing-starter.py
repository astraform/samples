#!/usr/bin/env python3
"""Prepare a local image-only marketing starter using the shared archive writer."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("banking_starter_package", ROOT / "scripts/package-banking-starter.py")
packaging = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packaging)
FILES = {
    **{name: f"deploy/marketing-starter/{name}" for name in (
        "compose.yaml", "init.py", "starter.sh", "README.md", "LICENSE", "NOTICE",
        "models/gemini.env.example", "models/ollama.env.example",
    )},
    "run-customer.py": "integration/marketing-ops-a2a/run-customer.py",
    "customer-run-evidence.py": "integration/customer-run-evidence.py",
    "input.schema.json": "integration/marketing-ops-a2a/input.schema.json",
}


def package(version, images, output, local_verification=False, source_revision=None, scenario="scheduled"):
    packaging.require(local_verification, "Marketing starter is a local verification candidate; use --local-verification")
    packaging.require(scenario in {"scheduled", "current"}, "scenario must be scheduled or current")
    packaging.require(isinstance(images, dict) and set(images) == {"domain", "agency", "tools"},
                      "image lock must contain exactly domain, agency and tools references")
    files = {**FILES, "scenario.json": "integration/marketing-ops-a2a/" + (
        "sample-configuration.json" if scenario == "scheduled" else "current-state-configuration.json"),
        "RELEASE-NOTES.md": f"docs/releases/{version}.md"}
    source = ROOT / files["scenario.json"]
    packaging.require(source.is_file() and not source.is_symlink(),
                      f"Missing or unsafe starter source: {files['scenario.json']}")
    selected = json.loads(source.read_text())
    policy = selected.get("launchRequestTemplate", {}).get("completionPolicy", {})
    finish_present = "requireCustomerFinish" in policy
    finish = policy.get("requireCustomerFinish")
    packaging.require(not finish_present or type(finish) is bool,
                      "completionPolicy.requireCustomerFinish must be a Boolean when present")
    configuration = {"schemaVersion": "astraform.marketing-starter.configuration.v1",
                     "scenario": scenario, "domainId": "marketing-ops",
                     "domainPort": 8090, "agencyPort": 8091,
                     "requiresUnreleasedPlatform": finish_present,
                     "requiredPlatformCapabilities": ["completionPolicy.requireCustomerFinish"] if finish_present else [],
                     "requiresUnreleasedCustomerSdk": False,
                     "requiredCustomerSdkCapabilities": ["completionPolicy.requireCustomerFinish"] if finish_present else [],
                     "customerEvidenceHelper": "customer-run-evidence.py"}
    compatibility = {**packaging.LOCAL_COMPATIBILITY, "javaSdk": "0.4.3"}
    if finish_present:
        compatibility["platform"] = "unreleased-local-customer-finish"
    return packaging.package_bundle(version, images, output, source_revision, files,
        f"astraform-marketing-{scenario}-starter-{version}",
        compatibility, "astraform.marketing-starter.bundle.v1", True, configuration,
        {"completionPolicy.requireCustomerFinish": finish} if finish_present else None,
        "Local compatibility records current source dependency targets: Java SDK 0.4.3, Python SDK 0.3.3 and contracts 1.2.1. Actual image contents require separate installed-package verification against the recorded image IDs. Native customer completion is available in published Python SDK 0.3.3; a supporting local platform is still required when completionPolicy.requireCustomerFinish is present.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision")
    parser.add_argument("--image-lock", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=("scheduled", "current"), default="scheduled")
    parser.add_argument("--local-verification", action="store_true")
    arguments = parser.parse_args()
    revision = arguments.source_revision
    if revision is None and arguments.local_verification:
        revision = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True, timeout=30).strip()
    bundle = package(arguments.version, json.loads(arguments.image_lock.read_text()), arguments.output,
                     arguments.local_verification, revision, arguments.scenario)
    print(f"Prepared {bundle}; local verification only, no images or releases published.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from None
