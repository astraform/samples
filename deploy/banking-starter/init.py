"""Connect the banking starter to a fresh, initialized Astraform bundle."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import sys
import subprocess

ROOT = Path(__file__).resolve().parent


def env(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if line.strip() and not line.lstrip().startswith("#"))


def initialize(platform, model):
    platform = platform.resolve()
    if (ROOT / ".env").exists():
        raise ValueError("Starter is already initialized; existing credentials and connections were preserved")
    if not (ROOT / "release.env").is_file() or not (ROOT / "image-lock.json").is_file():
        raise ValueError("Use the packaged starter archive, which includes its image lock")
    settings = env(platform / ".env")
    project = settings.get("COMPOSE_PROJECT_NAME", "")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", project):
        raise ValueError("Platform .env has no valid initialized Compose project name")
    runtime = platform / "private/runtime.properties"
    existing = runtime.read_text()
    active = [line.strip() for line in existing.splitlines()
              if line.strip() and not line.lstrip().startswith(("#", "!"))]
    conflicts = ("simulation.event.runtime.mcp.bindings.banking.",
                 "simulation.event.runtime.a2a.targets.banking-advice.")
    if any(line.startswith(conflicts) for line in active):
        raise ValueError("Platform already has banking bindings; use a fresh bundle or configure connections manually")
    timeout_key = "simulation.event.runtime.a2a.request-timeout"
    if any(line.startswith(timeout_key) for line in active):
        raise ValueError("Platform already has an A2A timeout; preserve it and configure this starter manually")
    name = "astraform-bank-" + secrets.token_hex(5)
    schema = json.dumps(json.loads((ROOT / "bank-backend.input.schema.json").read_text()), separators=(",", ":"))
    schema = schema.replace(chr(92), chr(92) * 2)
    revision = name + "-v1"
    bindings = "\n# Banking starter: " + name + "\n" + "\n".join([
        f"simulation.event.runtime.mcp.bindings.banking.endpoint=http://{name}-bank:8092/mcp",
        f"simulation.event.runtime.mcp.bindings.banking.binding-revision={revision}",
        "simulation.event.runtime.mcp.bindings.banking.allowed-tool-names=get_customer_accounts",
        "simulation.event.runtime.mcp.bindings.banking.allow-insecure-http=true",
        f"simulation.event.runtime.a2a.targets.banking-advice.base-url=http://{name}-advice:8099",
        f"simulation.event.runtime.a2a.targets.banking-advice.binding-revision={revision}",
        "simulation.event.runtime.a2a.targets.banking-advice.allow-insecure-http=true",
        "simulation.event.runtime.a2a.targets.banking-advice.input-schema-json=" + schema,
        f"{timeout_key}={'650s' if model == 'ollama' else '150s'}",
    ]) + "\n"
    private = ROOT / "private"
    private.mkdir(mode=0o700, exist_ok=True)
    private.chmod(0o700)
    (ROOT / "runs").mkdir(mode=0o700, exist_ok=True)
    configuration = {
        "COMPOSE_PROJECT_NAME": name,
        "ASTRAFORM_NETWORK": project + "_default",
        "BANKING_DB_PASSWORD": secrets.token_hex(24),
        "LOCAL_UID": str(os.getuid()), "LOCAL_GID": str(os.getgid()),
    }
    # Fail before changing anything if an old partial initialization exists.
    paths = [ROOT / ".env", private / "partner.env", private / "runtime.properties.before-starter",
             private / "runtime-bindings.properties", private / "connection.json"]
    if any(path.exists() for path in paths):
        raise ValueError("Partial starter configuration exists; inspect private/ before retrying")
    contents = ["".join(f"{key}={value}\n" for key, value in configuration.items()),
                (ROOT / f"models/{model}.env.example").read_text(), existing, bindings,
                json.dumps({"platformDirectory": str(platform), "project": project,
                            "starterProject": name, "partnerModelProfile": model}, indent=2) + "\n"]
    for path, content in zip(paths, contents):
        with open(path, "x", encoding="utf-8", opener=lambda p, flags: os.open(p, flags, 0o600)) as file:
            file.write(content)
    # Preserve the file inode because a running Runtime may already bind-mount it.
    with runtime.open("a", encoding="utf-8") as file:
        file.write(bindings)
    print("Initialized " + name + ". Saved the previous Runtime connections in private/.")
    print("Configure private/partner.env, then follow README.md to start the bank and recreate Runtime.")
    print("No services were started and no simulation was launched.")


def compose(arguments):
    images = env(ROOT / "release.env")
    local = env(ROOT / ".env")
    partner = env(ROOT / "private/partner.env")
    lock = json.loads((ROOT / "image-lock.json").read_text())
    expected = {"BANKING_IMAGE": lock["banking"], "ADVICE_IMAGE": lock["advice"],
                "POSTGRES_IMAGE": lock["postgres"]}
    if images != expected or set(local).intersection(images) or set(partner).intersection({*images, *local}):
        raise ValueError("Keep image references in release.env and deployment identity in .env")
    settings = {**images, **local, **partner}
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", local.get("COMPOSE_PROJECT_NAME", "")):
        raise ValueError("Invalid initialized starter project name")
    # Persisted settings take precedence over the caller's shell, including on down.
    command = ["docker", "compose", "--project-name", local["COMPOSE_PROJECT_NAME"],
               "--env-file", str(ROOT / "release.env"), "--env-file", str(ROOT / ".env"),
               "--env-file", str(ROOT / "private/partner.env"), "-f", str(ROOT / "compose.yaml"), *arguments]
    return subprocess.run(command, cwd=ROOT, env={**os.environ, **settings}).returncode


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "compose":
        try:
            sys.exit(compose(sys.argv[2:]))
        except (OSError, ValueError, KeyError) as error:
            print("Starter command stopped: " + str(error), file=sys.stderr)
            sys.exit(1)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", type=Path, required=True, help="Extracted, initialized Astraform bundle")
    parser.add_argument("--model", choices=("gemini", "ollama"), required=True, help="Partner-agent model profile")
    args = parser.parse_args()
    try:
        initialize(args.platform, args.model)
    except (OSError, ValueError, KeyError) as error:
        print("Initialization stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
