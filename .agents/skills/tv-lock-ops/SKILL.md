---
name: tv-lock-ops
description: Operate, diagnose, adapt, recover, and acceptance-test the Xiaomi TV Lock project on MiTV-MFTR0. Use when working in this repository or interacting with its Android TV device.
---

# TV Lock Operations Skill

You are the operator of this repository. The user gives goals and approves risky actions; do not delegate routine command execution back to the user when your environment can execute it.

## Mandatory context
1. Read repository-root `AGENTS.md`.
2. Read `docs/HANDOFF.md`.
3. Read the relevant device/architecture/recovery/test documents.
4. Load exactly the task playbook needed:
   - install or upgrade: `agent/install.md`
   - diagnosis: `agent/diagnose.md`
   - firmware adaptation: `agent/adapt.md`
   - recovery: `agent/recovery.md`
   - acceptance: `agent/acceptance.md`

## Operating loop
1. Inspect Git status, current source, and recent project notes.
2. Inspect the real device state if ADB / computer access is available.
3. State the intended action and risk before any disruptive or security-sensitive operation.
4. Obtain explicit user approval for reboot, standby tests, system-setting writes, Device Owner / Kiosk changes, app removal, or factory reset.
5. Execute one controlled change at a time; capture actual output and exit status.
6. Verify the result on the device; use physical remote-control testing for TV key behavior.
7. Roll back unsuccessful experiments and confirm restored values.
8. Update test evidence and `docs/HANDOFF.md`.
9. Commit to a task branch and report commit / PR links, test status, and remaining risks.

## Hard constraints
- Never factory-reset as a normal install/provisioning step or automatically after Device Owner failure.
- Never assume Device Owner protects the app from Xiaomi's vendor force-stop.
- Never equate an overlay with system-level access control.
- Never disable or uninstall PatchWall by default.
- Never claim a test passed without its real evidence.
- If the current environment cannot access the user's computer or ADB device, say so and request an execution-capable agent environment; do not fabricate command output.

## Completion report
Report: changed files, commit SHA, build result, device/firmware tested, PASS/FAIL/SKIP/NOT RUN matrix, evidence, rollback status, and unresolved risks.
