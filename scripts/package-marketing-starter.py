#!/usr/bin/env python3
"""Package an image-only marketing starter using the shared archive writer."""
import argparse
import importlib.util
import json
import re
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


def package(version, images, output, local_verification=False, source_revision=None, scenario="scheduled",
            platform_release=None):
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
    launch = selected.get("launchRequestTemplate", {})
    domain_present = "domainId" in launch
    packaging.require(not domain_present or type(launch["domainId"]) is str and
            re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", launch["domainId"]) is not None,
            "launch.domainId must be a lowercase domain identifier when present")
    packaging.require(not domain_present or launch["domainId"] == "marketing-ops",
                      "launch.domainId must match the marketing-ops provider identity")
    policy = selected.get("launchRequestTemplate", {}).get("completionPolicy", {})
    finish_present = "requireCustomerFinish" in policy
    finish = policy.get("requireCustomerFinish")
    packaging.require(not finish_present or type(finish) is bool,
                      "completionPolicy.requireCustomerFinish must be a Boolean when present")
    platform_release = packaging.declared_platform_release(local_verification, platform_release)
    packaging.require(local_verification or not domain_present,
            "Explicit launch.domainId requires a supporting platform release; use local verification until that capability is published, or a compatible scenario omitting it")
    configuration = {"schemaVersion": "astraform.marketing-starter.configuration.v1",
                     "scenario": scenario, "domainId": "marketing-ops",
                     "domainPort": 8090, "agencyPort": 8091,
                     "requiresUnreleasedPlatform": domain_present or local_verification and finish_present,
                     "requiredPlatformCapabilities": (["completionPolicy.requireCustomerFinish"] if finish_present else []) + (["domainId"] if domain_present else []),
                     "requiresUnreleasedCustomerSdk": False,
                     "requiredCustomerSdkCapabilities": (["completionPolicy.requireCustomerFinish"] if finish_present else []) + (["domainId"] if domain_present else []),
                     "customerEvidenceHelper": "customer-run-evidence.py"}
    compatibility = {**(packaging.LOCAL_COMPATIBILITY if local_verification else {
                         **packaging.COMPATIBILITY, "platform": platform_release.removeprefix("v")}),
                     "javaSdk": "0.4.4"}
    if local_verification and finish_present:
        compatibility["platform"] = "unreleased-local-customer-finish"
    required_platform = {"completionPolicy.requireCustomerFinish": finish} if finish_present else {}
    if domain_present:
        required_platform["domainId"] = configuration["domainId"]
    if platform_release:
        required_platform["release"] = platform_release
    return packaging.package_bundle(version, images, output, source_revision, files,
        f"astraform-marketing-{scenario}-starter-{version}",
        compatibility, "astraform.marketing-starter.bundle.v1", local_verification, configuration,
        required_platform or None,
        "Local compatibility records current source dependency targets: Java SDK 0.4.4, Python SDK 0.3.4 and contracts 1.2.2. Actual image contents require separate installed-package verification against the recorded image IDs. Published Python SDK 0.3.4 preserves explicit launch.domainId and native customer completion. The selected platform must implement each declared capability; publishing an SDK does not add platform behavior."
        if local_verification else
        f"Declared compatibility requires Astraform Platform {platform_release}, Java SDK 0.4.4, Python SDK 0.3.4 and contracts 1.2.2. This dependency declaration is not proof of platform publication or availability. Verify the selected public platform release and the installed SDK packages in the pinned images before publication.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision")
    parser.add_argument("--image-lock", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=("scheduled", "current"), default="scheduled")
    parser.add_argument("--platform-release", help="Required compatible public platform release, such as v0.3.0; a declaration, not publication proof")
    parser.add_argument("--local-verification", action="store_true")
    arguments = parser.parse_args()
    revision = arguments.source_revision
    if revision is None and arguments.local_verification:
        revision = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True, timeout=30).strip()
    bundle = package(arguments.version, json.loads(arguments.image_lock.read_text()), arguments.output,
                     arguments.local_verification, revision, arguments.scenario, arguments.platform_release)
    print(f"Prepared {bundle}; no images or releases published.")
    if arguments.local_verification:
        print("Local verification only; this archive requires the recorded images already installed.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from None
