# device/local (gitignored)

The real per-house versions of files the repo only has as sanitised
templates. `install.sh` and `status.sh` use a file here in place of the
repo copy with the same name, and `install.sh` refuses to push a template
that still has its placeholders.

| File here | Replaces | What's real in it |
|---|---|---|
| `adbfw.sh` | `device/adbfw.sh` | `ALLOW_MAC` / `ALLOW_IP` of the admin workstation |
| `dashboard.json` | `device/config/dashboard.json` | Plex host (and token if set), calendar entity, anything else house-specific |

Nothing in this folder except this README is committed (see `.gitignore`).
Per-remote identity stays in `device/config/device-<octet>.json`, which has
no secrets in it.
