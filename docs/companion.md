# Path 2 — The companion plugin (for server owners)

**Audience:** you run a Minecraft server and want your installed plugins kept up
to date. **No code, no building.** You install one plugin and list the others in
a config file.

If a term is unfamiliar, see the [glossary](glossary.md).

## What it is

**PluginPulse Companion** is a normal plugin you drop into `plugins/`. It reads a
list of your *other* plugins and where each one publishes updates, then checks
(and optionally downloads) updates on their behalf — without those plugins
needing to know anything about it.

It never swaps a running jar live: downloaded updates are **staged** and applied
the next time you restart. Safe by default.

## 1. Download

Get **`PluginPulseCompanion-<version>.jar`** from the
[Releases page](https://github.com/darkstarworks/PluginPulse/releases).

## 2. Install

1. Stop your server (or have it running — either is fine for the first install).
2. Put the jar in your server's `plugins/` folder.
3. Start (or restart) the server.

On a successful start you'll see in the console:

```
[PluginPulseCompanion] Enabling PluginPulseCompanion vX.Y.Z
[PluginPulseCompanion] No plugins configured yet — edit config.yml and run /pluginpulse reload.
```

That last line is expected on a fresh install — you configure it next.

## 3. Configure

Open `plugins/PluginPulseCompanion/config.yml`. Here's a complete, worked example
for three real-world-shaped plugins:

```yaml
plugins:
  # Key = the plugin's EXACT name as shown by /plugins.
  EssentialsX:
    modrinth: essentialsx        # its Modrinth slug
    mode: notify                 # tell me on join; don't download

  WorldGuard:
    github: EngineHub/WorldGuard  # its GitHub owner/repo (uses Releases)
    mode: download                # download + stage; applies on restart

  MyPaidPlugin:
    hangar: Author/My-Plugin      # its Hangar project slug
    mode: check-only              # silent; only shown via /pluginpulse

# Sent with API requests. Modrinth's rules require a way to reach you
# (an email or a website/URL).
user-agent-contact: "you@example.com"

# How often to check each plugin, in hours (minimum 1).
check-interval-hours: 6
```

**Every field explained:**

| Field | Meaning |
|---|---|
| `plugins:` | the list of plugins to manage; each key is a plugin's exact name |
| `modrinth` / `github` / `hangar` | where *that* plugin's updates come from — see [finding your update source](update-sources.md). Use exactly one per plugin |
| `mode` | what to do when an update is found (see below) |
| `track` | *(optional, per plugin)* follow a `-<track>` release line, e.g. `track: mc26` |
| `user-agent-contact` | how the update APIs can reach you; required by Modrinth |
| `check-interval-hours` | how often to check (minimum 1) |

**What each `mode` does** — in plain terms:

- `off` — ignore this plugin.
- `check-only` — check quietly; the result only shows in `/pluginpulse`.
- `notify` — check and tell admins (console + on join). **Nothing is downloaded.**
- `download` — check, download, verify, and **stage** the update. It applies the
  next time you restart the server.
- `auto-stage` — like `download`, but staged automatically as soon as it's found.

After editing, either restart or run `/pluginpulse reload`.

## 4. Use

The command is `/pluginpulse` (aliases `/ppc`, `/pulse`), permission
`pluginpulse.admin` (ops have it by default).

- `/pluginpulse` or `/pluginpulse list` — show each managed plugin and whether an
  update is available.
- `/pluginpulse check [plugin|all]` — check now.
- `/pluginpulse download [plugin|all]` — download + stage available updates.
- `/pluginpulse restore [plugin|all]` — stage the previous backup of a plugin
  (undo a bad update), applied on restart.
- `/pluginpulse reload` — re-read `config.yml`.

**How staged updates apply:** downloading stages the new jar into the server's
update folder. **Restart the server** and the server swaps it in on boot. There's
no live jar-swapping — that's deliberate, so nothing changes underneath a running
server.

## FAQ

- **Does it work on Spigot?** Yes — Paper, Spigot, and Folia.
- **My plugin isn't on Modrinth/Hangar.** Use `github: owner/repo` if its releases
  are on GitHub. If it's published nowhere with an API, the companion can't track
  it.
- **Is it safe?** By default (`notify`), nothing is downloaded — you're only
  informed. Even in `download`/`auto-stage`, updates are checksum-verified, the
  old jar is backed up, and nothing is swapped until you restart.
- **A plugin shows as "skipped".** The companion prints why (not installed, no
  source configured, `mode: off`, etc.). Check the name matches `/plugins`
  exactly and it has one source.
