"""MTEC-37: the Azure staging configuration keeps every flag off and carries no secrets."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
AZURE = ROOT / "scripts/azure"
SCRIPT = AZURE / "ms-users-staging.sh"
TEMPLATE = json.loads((AZURE / "appsettings.staging.json").read_text())
APPLICATION_YML = (ROOT / "src/main/resources/application.yml").read_text()


def load_verify_http():
    spec = importlib.util.spec_from_file_location("verify_http", AZURE / "verify_http.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sample_config(directory):
    config = Path(directory) / "staging.env"
    config.write_text("\n".join([
        "AZ_SUBSCRIPTION_ID=00000000-0000-0000-0000-000000000000",
        "AZ_LOCATION=canadacentral",
        "APP_RESOURCE_GROUP=rg-app", "APP_SERVICE_PLAN=plan",
        "SQL_RESOURCE_GROUP=rg-sql", "SQL_SERVER_NAME=sql-example", "SQL_DATABASE_NAME=db-example",
        "WEBAPP_NAME=app-example", "KEY_VAULT_NAME=kv-example", "KEY_VAULT_RESOURCE_GROUP=rg-app",
        "USERS_CSRF_ACTIVE_KID=kid-1", 'JAVA_OPTS="-Xmx384m -XX:+UseSerialGC"',
        f'ARTIFACT_DIR="{directory}/artifacts"', ""]))
    return config


def run_script(config, *args, tools_dir=None):
    path = os.environ.get("PATH", "")
    if tools_dir is not None:
        path = f"{tools_dir}{os.pathsep}{path}"
    environment = {"PATH": path, "HOME": os.environ.get("HOME", "/tmp")}
    return subprocess.run(["bash", str(SCRIPT), "--config", str(config), *args],
                          capture_output=True, text=True, env=environment, timeout=60)


class AzureStagingConfigTest(unittest.TestCase):
    def test_every_default_false_flag_is_explicitly_false(self):
        flags = set(re.findall(r"\$\{(USERS_[A-Z_]+):false\}", APPLICATION_YML))
        self.assertEqual(13, len(flags), flags)
        for flag in flags:
            self.assertEqual("false", TEMPLATE.get(flag), flag)
        self.assertEqual(flags, {name for name, value in TEMPLATE.items() if value == "false"})

    def test_settings_reference_key_vault_and_hold_no_secret_material(self):
        self.assertEqual("sql-appservice", TEMPLATE["SPRING_PROFILES_ACTIVE"])
        self.assertTrue(TEMPLATE["USERS_CSRF_KEYS_JSON"].startswith("@Microsoft.KeyVault(VaultName=${KEY_VAULT_NAME};"))
        for absent in ("USERS_INTERNAL_JWT_KEYS_JSON", "USERS_EMAIL_PROVIDER_API_KEY", "USERS_GOOGLE_AUDIENCES",
                       "USERS_INTERNAL_JWT_ISSUER", "USERS_INTERNAL_JWT_AUDIENCE"):
            self.assertNotIn(absent, TEMPLATE)
        for name, value in TEMPLATE.items():
            if re.search(r"KEYS?_JSON|SECRET|PASSWORD|API_KEY|TOKEN", name):
                self.assertTrue(value.startswith("@Microsoft.KeyVault("), name)
            self.assertNotRegex(value, r"[A-Za-z0-9+/]{40,}={0,2}", name)
        for name in ("JAVA_OPTS", "USERS_CSRF_ACTIVE_KID"):
            self.assertRegex(TEMPLATE[name], r"^\$\{[A-Z_]+\}$")

    def test_closed_route_list_covers_every_route_in_the_code(self):
        sources = "\n".join(p.read_text() for p in (ROOT / "src/main/java").rglob("*.java"))
        routes = set(re.findall(r'"(/(?:v1|\.well-known)/[^"\[]*)"', sources))
        checked = {path for _, path in load_verify_http().CLOSED_ROUTES}
        for route in routes:
            concrete = route.replace("{userId}", "00000000-0000-0000-0000-000000000000")
            self.assertIn(concrete, checked, route)

    def test_script_syntax_and_dry_run_never_executes(self):
        self.assertEqual(0, subprocess.run(["bash", "-n", str(SCRIPT)]).returncode)
        with tempfile.TemporaryDirectory() as directory:
            config = sample_config(directory)
            result = run_script(config, "configure")
            self.assertEqual(0, result.returncode, result.stderr)
            lines = [line for line in result.stdout.splitlines() if line.strip()]
            self.assertTrue(lines and all(line.startswith("[dry-run] az ") for line in lines), result.stdout)
            self.assertNotIn("EXECUTE", result.stderr)
            for step in ("create-webapp", "sql-grant", "stop"):
                result = run_script(config, step)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertTrue(result.stdout.startswith("[dry-run] "), step)

    def test_rendered_settings_keep_flags_off(self):
        with tempfile.TemporaryDirectory() as directory:
            result = run_script(sample_config(directory), "render-settings")
            self.assertEqual(0, result.returncode, result.stderr)
            rendered = {item["name"]: item["value"] for item in json.loads(result.stdout)}
            self.assertEqual("sql-example.database.windows.net", rendered["USERS_SQL_SERVER"])
            self.assertEqual("@Microsoft.KeyVault(VaultName=kv-example;SecretName=users-csrf-keys-json)",
                             rendered["USERS_CSRF_KEYS_JSON"])
            self.assertTrue(all(rendered[k] == "false" for k, v in TEMPLATE.items() if v == "false"))

    def test_script_rejects_missing_configuration_and_invalid_sha(self):
        with tempfile.TemporaryDirectory() as directory:
            config = sample_config(directory)
            self.assertNotEqual(0, run_script(config, "deploy", "--sha", "main").returncode)
            self.assertNotEqual(0, run_script(config, "no-such-step").returncode)
            empty = Path(directory) / "empty.env"
            empty.write_text("")
            self.assertNotEqual(0, run_script(empty, "check").returncode)

    def test_config_cannot_run_commands_or_enable_execute(self):
        with tempfile.TemporaryDirectory() as directory:
            config = sample_config(directory)
            marker = Path(directory) / "ran"
            with config.open("a") as stream:
                stream.write(f'JAVA_OPTS="$(touch {marker})"\nEXECUTE=true\n')
            az = Path(directory) / "az"
            az.write_text("#!/bin/sh\nexit 97\n")
            az.chmod(0o700)
            result = run_script(config, "stop", tools_dir=directory)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("unsupported configuration key", result.stderr)
            self.assertFalse(marker.exists())

    def test_migrate_requires_explicit_database_confirmation(self):
        with tempfile.TemporaryDirectory() as directory:
            config = sample_config(directory)
            for args in ((), ("--confirm-database=another-db",)):
                result = run_script(config, "migrate", "--execute", *args)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("matching SQL_DATABASE_NAME", result.stderr)

    def test_deploy_and_rollback_require_matching_artifact_and_green_ci(self):
        with tempfile.TemporaryDirectory() as directory:
            config = sample_config(directory)
            tools_dir = Path(directory) / "bin"
            tools_dir.mkdir()
            for name, body in {"gh": "echo 0", "az": "echo 'unexpected Azure call'; exit 97"}.items():
                stub = tools_dir / name
                stub.write_text(f"#!/bin/sh\n{body}\n")
                stub.chmod(0o700)
            sha = "a" * 40
            artifacts = Path(directory) / "artifacts"
            artifacts.mkdir()
            jar = artifacts / f"ms-users-{sha}.jar"
            jar.write_bytes(b"dummy jar")
            sidecar = jar.with_suffix(".jar.sha256")
            digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            for step in ("deploy", "rollback"):
                for filename, error in (("different.jar", "checksum mismatch"), (jar.name, "no successful")):
                    sidecar.write_text(f"{digest}  {filename}\n")
                    result = run_script(config, step, "--execute", "--sha", sha, tools_dir=tools_dir)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(error, result.stderr)
                    self.assertNotIn("unexpected Azure call", result.stdout)


if __name__ == "__main__":
    unittest.main()
