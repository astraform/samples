"""Connect a local image-only marketing starter to a fresh initialized platform bundle."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def env(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if line.strip() and not line.lstrip().startswith("#"))


def initialize(platform, model):
    platform = platform.resolve()
    if (ROOT / ".env").exists():
        raise ValueError("Starter is already initialized; existing credentials and connections were preserved")
    if not all((ROOT / name).is_file() for name in ("release.env", "image-lock.json", "starter-config.json")):
        raise ValueError("Use the packaged marketing starter archive with its image lock and configuration")
    configuration = json.loads((ROOT / "starter-config.json").read_text())
    if (configuration.get("schemaVersion") != "astraform.marketing-starter.configuration.v1"
            or configuration.get("scenario") not in {"scheduled", "current"}
            or configuration.get("domainId") != "marketing-ops"
            or configuration.get("domainPort") != 8090 or configuration.get("agencyPort") != 8091):
        raise ValueError("Invalid packaged marketing starter configuration")
    project = env(platform / ".env").get("COMPOSE_PROJECT_NAME", "")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", project):
        raise ValueError("Platform .env has no valid initialized Compose project name")
    runtime = platform / "private/runtime.properties"
    existing = runtime.read_text()
    active = [line.strip() for line in existing.splitlines()
              if line.strip() and not line.lstrip().startswith(("#", "!"))]
    conflicts = ("simulation.event.runtime.domain.bindings.marketing-ops.",
                 "simulation.event.runtime.a2a.targets.creative-agency.",
                 "simulation.event.runtime.a2a.request-timeout", "simulation.event.runtime.a2a.poll-timeout")
    if any(line.startswith(conflicts) for line in active):
        raise ValueError("Platform already has marketing bindings or an A2A timeout; preserve it and configure manually")
    name = "astraform-marketing-" + secrets.token_hex(5)
    token = secrets.token_hex(32)
    revision = name + "-v1"
    schema = json.dumps(json.loads((ROOT / "input.schema.json").read_text()), separators=(",", ":"))
    schema = schema.replace(chr(92), chr(92) * 2)
    timeout = "150s"  # Agency adapter permits at most 120s model reads; both profiles fit.
    bindings = "\n# Marketing starter: " + name + "\n" + "\n".join([
        f"simulation.event.runtime.domain.bindings.marketing-ops.base-url=http://{name}-domain:8090",
        "simulation.event.runtime.domain.bindings.marketing-ops.domain-id=marketing-ops",
        f"simulation.event.runtime.domain.bindings.marketing-ops.binding-revision={revision}",
        "simulation.event.runtime.domain.bindings.marketing-ops.allow-insecure-http=true",
        f"simulation.event.runtime.a2a.targets.creative-agency.base-url=http://{name}-agency:8091",
        f"simulation.event.runtime.a2a.targets.creative-agency.binding-revision={revision}",
        "simulation.event.runtime.a2a.targets.creative-agency.allow-insecure-http=true",
        "simulation.event.runtime.a2a.targets.creative-agency.input-schema-json=" + schema,
        "simulation.event.runtime.a2a.targets.creative-agency.token=" + token,
        "simulation.event.runtime.a2a.request-timeout=" + timeout,
        "simulation.event.runtime.a2a.poll-timeout=" + timeout,
    ]) + "\n"
    private = ROOT / "private"
    private.mkdir(mode=0o700, exist_ok=True)
    private.chmod(0o700)
    (ROOT / "runs").mkdir(mode=0o700, exist_ok=True)
    paths = [ROOT / ".env", private / "partner.env", private / "runtime.properties.before-starter",
             private / "runtime-bindings.properties", private / "connection.json"]
    if any(path.exists() for path in paths):
        raise ValueError("Partial starter configuration exists; inspect private/ before retrying")
    deployment = {"COMPOSE_PROJECT_NAME": name, "ASTRAFORM_NETWORK": project + "_default",
                  "LOCAL_UID": str(os.getuid()), "LOCAL_GID": str(os.getgid())}
    partner = (ROOT / f"models/{model}.env.example").read_text()
    partner += f"\nCREATIVE_AGENCY_A2A_TOKEN={token}\nCREATIVE_AGENCY_LLM_BINDING_REVISION={revision}\n"
    contents = ["".join(f"{key}={value}\n" for key, value in deployment.items()), partner, existing, bindings,
                json.dumps({"platformDirectory": str(platform), "project": project,
                            "starterProject": name, "partnerModelProfile": model,
                            "starterConfiguration": configuration}, indent=2) + "\n"]
    for path, content in zip(paths, contents):
        with open(path, "x", encoding="utf-8", opener=lambda p, flags: os.open(p, flags, 0o600)) as file:
            file.write(content)
    with runtime.open("a", encoding="utf-8") as file:
        file.write(bindings)
    print("Initialized " + name + ". Configure private/partner.env, then follow README.md.")
    print("The matching agency/Runtime token remains in private configuration. No services or simulations started.")


def compose(arguments):
    images = env(ROOT / "release.env")
    local = env(ROOT / ".env")
    partner = env(ROOT / "private/partner.env")
    lock = json.loads((ROOT / "image-lock.json").read_text())
    expected = {"DOMAIN_IMAGE": lock["domain"], "AGENCY_IMAGE": lock["agency"], "TOOLS_IMAGE": lock["tools"]}
    if images != expected or set(local).intersection(images) or set(partner).intersection({*images, *local}):
        raise ValueError("Keep image references in release.env and deployment identity in .env")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", local.get("COMPOSE_PROJECT_NAME", "")):
        raise ValueError("Invalid initialized starter project name")
    command = ["docker", "compose", "--project-name", local["COMPOSE_PROJECT_NAME"],
               "--env-file", str(ROOT / "release.env"), "--env-file", str(ROOT / ".env"),
               "--env-file", str(ROOT / "private/partner.env"), "-f", str(ROOT / "compose.yaml"), *arguments]
    return subprocess.run(command, cwd=ROOT, env={**os.environ, **images, **local, **partner}).returncode


if __name__ == "__main__":
    try:
        if len(sys.argv) > 1 and sys.argv[1] == "compose":
            raise SystemExit(compose(sys.argv[2:]))
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("--platform", type=Path, required=True)
        parser.add_argument("--model", choices=("gemini", "ollama"), required=True)
        arguments = parser.parse_args()
        initialize(arguments.platform, arguments.model)
    except (OSError, ValueError, KeyError) as error:
        print("Marketing starter stopped: " + str(error), file=sys.stderr)
        raise SystemExit(1) from None
