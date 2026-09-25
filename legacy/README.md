# Legacy phone-control tooling

This workspace holds the original USB adb tooling that predates DHD's on-device control path:

- `phone-control/` · the adb-backed phone control service and policy guard.
- `phone-control-mcp/` · an MCP server over `phone-control`.
- `phone-viewer/` · an Electron scrcpy viewer with a cursor overlay.
- `config/` · example policy files for `phone-control`.

DHD does not use any of it. The Android app talks to the phone through its own Wireless debugging
client, and the desktop companion only depends on `packages/screenshot-markers`. Keeping this code in
its own pnpm workspace means a normal DHD install no longer downloads Electron.

## Running it

Install the main workspace first, because `phone-control-mcp` and `phone-viewer` link to
`packages/screenshot-markers`:

```bash
pnpm install
pnpm --filter @dhd/screenshot-markers build
cd legacy
pnpm install
pnpm test
```

`pnpm phone-control-mcp:start`, `pnpm phone-control-mcp:dev` and `pnpm phone-control-viewer:start` run
from this folder. Policy files, audit logs and a bundled `scrcpy-win64-v4.1/` folder resolve relative to
`legacy/`.

## Known issues

These are unfixed. Read them before pointing this tooling at a real phone.

- `type` text and package names reach `adb shell` without shell quoting, so device-side shell syntax in
  either one runs on the phone (`phone-control/src/adb/process-adapter.ts`).
- `%` is sent as `%25`, so typed text containing a percent sign is wrong.
- Device selection matches the configured serial by substring and can fall back to a different
  authorized device.
- The foreground-display check always reports the requested display id, so display ownership guards
  cannot fail.
- The observation store has no size cap, and the MCP server does not close virtual displays or the
  overlay on exit.
- The standalone viewer resolves its audit log relative to its own working directory, so it does not
  see events written by the MCP server.
