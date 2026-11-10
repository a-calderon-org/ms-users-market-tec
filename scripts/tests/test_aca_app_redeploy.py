"""MTEC-37: `ms-users-aca.sh app --sha` on an existing app keeps its tags, probes, flags and other settings."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/azure/aca/ms-users-aca.sh"
SHA = "f" * 40
APP_ID = "/subscriptions/s/resourceGroups/rg/providers/Microsoft.App/containerApps/ca-ms-users-stg"

# Records every az call (one per line), answers id lookups, serves $AZ_TEMPLATE for `rest --method get`
# and copies the body of `rest --method patch` to $AZ_PATCH.
AZ_STUB = f"""#!{sys.executable}
import os, shutil, sys
args = sys.argv[1:]
with open(os.environ["AZ_LOG"], "a") as log:
    log.write(" ".join(args) + "\\n")
line = " ".join(args)
if line.startswith("containerapp show") and os.environ.get("AZ_APP_MISSING") and "--query" not in line:
    sys.exit(3)
elif line.startswith("containerapp show") and "--query id" in line:
    print("{APP_ID}")
elif line.startswith("rest --method get"):
    if os.environ.get("AZ_REST_FAIL"):
        sys.exit(1)
    sys.stdout.write(open(os.environ["AZ_TEMPLATE"]).read())
elif line.startswith("rest --method patch"):
    shutil.copy(args[args.index("--body") + 1][1:], os.environ["AZ_PATCH"])
"""

TUNED_STARTUP = {"type": "Startup", "httpGet": {"path": "/health", "port": 8080}, "initialDelaySeconds": 1,
                 "periodSeconds": 3, "timeoutSeconds": 5, "failureThreshold": 30}
CUSTOM_STARTUP = dict(TUNED_STARTUP, periodSeconds=4, failureThreshold=40)
READINESS = {"type": "Readiness", "httpGet": {"path": "/health", "port": 8080}, "periodSeconds": 15,
             "timeoutSeconds": 5, "failureThreshold": 3}
LIVENESS = {"type": "Liveness", "httpGet": {"path": "/health", "port": 8080}, "periodSeconds": 30,
            "timeoutSeconds": 5, "failureThreshold": 3}


class AcaAppRedeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        for name, body in {"az": AZ_STUB, "gh": "#!/bin/sh\necho 1\n"}.items():
            stub = self.bin / name
            stub.write_text(body)
            stub.chmod(0o700)
        self.log = self.tmp / "az.log"
        self.template = self.tmp / "template.json"
        self.patch = self.tmp / "patch.json"

    def run_app(self, probes, rest_fail=False, expect_ok=True, missing=False):
        self.template.write_text(json.dumps({"properties": {"template": {
            "revisionSuffix": f"r{SHA[:7]}", "containers": [{"name": "ca-ms-users-stg", "probes": probes}]}}}))
        env = {"PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}", "HOME": str(self.tmp),
               "AZ_LOG": str(self.log), "AZ_TEMPLATE": str(self.template), "AZ_PATCH": str(self.patch)}
        if rest_fail:
            env["AZ_REST_FAIL"] = "1"
        if missing:
            env["AZ_APP_MISSING"] = "1"
        result = subprocess.run(["bash", str(SCRIPT), "app", "--sha", SHA], capture_output=True, text=True,
                                env=env, timeout=60)
        if expect_ok:
            self.assertEqual(0, result.returncode, result.stderr)
        else:
            self.assertNotEqual(0, result.returncode)
        return self.log.read_text().splitlines()

    def test_update_does_not_replace_tags(self):
        calls = self.run_app([TUNED_STARTUP, READINESS, LIVENESS])
        updates = [c for c in calls if c.startswith("containerapp update")]
        self.assertEqual(1, len(updates), calls)
        self.assertNotIn("--tags", updates[0])

    def test_update_keeps_existing_flags_and_settings(self):
        update = next(c for c in self.run_app([TUNED_STARTUP, READINESS, LIVENESS])
                      if c.startswith("containerapp update"))
        self.assertNotIn("--replace-env-vars", update)
        self.assertIn("--set-env-vars", update)
        self.assertNotIn("_ENABLED=", update)
        self.assertNotIn("_VERIFIED=", update)
        self.assertIn(f"--image acrmsusersstg.azurecr.io/ms-users:{SHA}", update)

    def test_new_app_starts_with_every_flag_false(self):
        create = next(c for c in self.run_app([], missing=True) if c.startswith("containerapp create"))
        for flag in ("USERS_PASSWORD_ENABLED", "USERS_GOOGLE_LOGIN_ENABLED", "USERS_EMAIL_ENABLED",
                     "USERS_INTERNAL_JWT_ENABLED"):
            self.assertIn(f"{flag}=false", create)

    def test_sha_tag_is_merged_incrementally_into_existing_tags(self):
        calls = self.run_app([TUNED_STARTUP, READINESS, LIVENESS])
        tags = [c for c in calls if c.startswith("resource tag")]
        self.assertEqual([f"resource tag --ids {APP_ID} --is-incremental --tags sha={SHA} -o none"], tags)
        update = next(c for c in calls if c.startswith("containerapp update"))
        self.assertLess(calls.index(update), calls.index(tags[0]))

    def test_configured_probes_are_kept_and_no_extra_revision_is_created(self):
        calls = self.run_app([CUSTOM_STARTUP, READINESS, LIVENESS])
        self.assertEqual([], [c for c in calls if c.startswith("rest --method patch")])
        self.assertFalse(self.patch.exists())

    def test_only_missing_probe_types_are_added_and_existing_ones_are_untouched(self):
        self.run_app([CUSTOM_STARTUP])
        template = json.loads(self.patch.read_text())["properties"]["template"]
        probes = template["containers"][0]["probes"]
        self.assertEqual(CUSTOM_STARTUP, probes[0])
        self.assertEqual(["Startup", "Readiness", "Liveness"], [p["type"] for p in probes])
        self.assertEqual(f"r{SHA[:7]}p", template["revisionSuffix"])

    def test_app_without_probes_gets_the_validated_staging_defaults(self):
        self.run_app([])
        probes = json.loads(self.patch.read_text())["properties"]["template"]["containers"][0]["probes"]
        self.assertEqual([TUNED_STARTUP, READINESS, LIVENESS], probes)

    def test_template_read_failure_stops_the_step_instead_of_being_ignored(self):
        calls = self.run_app([TUNED_STARTUP], rest_fail=True, expect_ok=False)
        self.assertEqual([], [c for c in calls if c.startswith("rest --method patch")])


if __name__ == "__main__":
    unittest.main()
