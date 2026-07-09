# PluginPulse

Keep Minecraft server plugins up to date, three ways — depending on who you are.

## Which of these are you?

| You… | Use | Guide |
|---|---|---|
| **build your own plugin** and want it to self-update | shade the library | **[docs/adopt-library.md](docs/adopt-library.md)** |
| **run a server** and just want your installed plugins updated | the companion plugin | **[docs/companion.md](docs/companion.md)** |
| **have a jar** you can't or won't rebuild | the browser tool | **[docs/web-tool.md](docs/web-tool.md)** |

Each guide stands alone and assumes no prior knowledge — follow only *yours*.
New to a term? See the **[glossary](docs/glossary.md)**. Not sure where your
plugin's updates live? See **[finding your update source](docs/update-sources.md)**.

Everything below is the **developer reference** for the library (Path 1). Server
owners and jar-only users should follow their guide above instead.

---

A small, dependency-free update-checker library for Paper **and Spigot**
plugins. Shade it in, point it at where you publish releases, and your plugin
gains:

- **Multi-source update checking** — Modrinth, GitHub Releases, Hangar, Jenkins
  CI, or any self-hosted JSON manifest, with ordered fallbacks.
- **Clickable admin notifications** — MiniMessage console + in-game notices on
  login, permission-gated, fully re-brandable.
- **Version intelligence** — semver-ish comparison, pre-release awareness
  (`1.2.0-beta1 < 1.2.0`), and distribution *tracks* for projects that ship
  parallel builds (e.g. `1.7.3` and `1.7.3-mc26`).
- **Rate-limit citizenship** — identifying User-Agent (required by Modrinth),
  persisted check state, jittered intervals.
- **Folia support** — automatic scheduler detection, or plug in your own adapter.
- **Verified one-command installs** — downloads are checksum-verified
  (sha512 > sha256 > sha1), the running jar is backed up, and the new jar is
  staged into the server's `plugins/update/` folder to apply on the next
  restart. One-command rollback to the latest backup.

## Requirements

- Paper (or a fork) 1.20.5+, **or Spigot 1.20.5+**, Java 21+.
- No runtime dependencies. On Paper, notices render as rich clickable
  MiniMessage; on Spigot (no Adventure) they automatically fall back to plain
  text with the download URL spelled out — everything else works identically.

## Installation

Via [JitPack](https://jitpack.io):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.darkstarworks.PluginPulse:pluginpulse-core:v0.5.0")
}
```

Shade and **relocate** it (Gradle Shadow shown):

```kotlin
tasks.shadowJar {
    relocate("io.github.darkstarworks.pluginpulse", "my.plugin.libs.pluginpulse")
}
```

## Quick start (no code, one file)

**1.** Drop a `pluginpulse.yml` into `src/main/resources/`:

```yaml
# Fill in whichever sources apply — the first one is primary, the rest are
# fallbacks. You need at least one.
modrinth: my-project-slug        # Modrinth project slug (optional)
github: me/my-plugin             # GitHub "owner/repo" for Releases (optional)
hangar: my-project               # Hangar project slug (optional)
# jenkins: https://ci.example.org/job/MyPlugin/   # Jenkins job URL (optional)
# jenkins-artifact: "Paper"      # optional regex: which jar when a build ships several

permission: myplugin.admin       # who sees notices / can run /myplugin update
command-root: /myplugin          # enables clickable buttons; self-registered if free
user-agent-contact: you@example.com   # required by Modrinth's API rules
mode: notify                     # off | check-only | notify | download | auto-stage
check-interval-hours: 6
# track: mc26                    # optional: follow a "-<track>" release line
# self-register-command: true    # default true; see below
```

**2.** Three lines in your plugin:

```java
@Override public void onEnable()  { PluginPulse.bootstrap(this); }
@Override public void onDisable() { PluginPulse.shutdown(this); }

// in your command executor, when the first arg is "update":
//   PluginPulse.handleUpdateCommand(this, sender, Arrays.copyOfRange(args, 1, args.length));
```

**Self-registered command.** When `command-root` is set, PluginPulse registers a
matching command (`/myplugin update ...`) straight into the server's command map
if — and only if — that name is not already taken. So:

- If your plugin **already declares** `command-root` in its `plugin.yml`, that
  command is left untouched and you delegate `update` to
  `PluginPulse.handleUpdateCommand(...)` as shown above.
- If it **doesn't** (injected jars, or a one-file adopter who'd rather not write a
  command class), you get a working `/myplugin update` for free — no descriptor
  entry, no executor.

Registration is fail-soft across Spigot/Paper/Folia and never throws into your
lifecycle; set `self-register-command: false` to opt out.

That's it. Server owners can override `mode` and `check-interval-hours` from an
`update:` section in your plugin's own `config.yml` without you doing anything.

## Advanced usage (builder)

For custom sources (self-hosted manifests), message overrides, a supplied
scheduler, or the hot-reload engine, use the builder directly:

```java
public final class MyPlugin extends JavaPlugin {
    private Updater updater;

    @Override
    public void onEnable() {
        updater = Updater.builder(this)
            .source(new ModrinthSource("myplugin"))
            .fallbackSource(new GitHubReleasesSource("me/myplugin"))
            .mode(UpdateMode.NOTIFY)
            .checkInterval(Duration.ofHours(6))
            .permission("myplugin.admin")
            .userAgentContact("you@example.com")   // required by Modrinth's API rules
            .build();
        updater.start();
    }

    @Override
    public void onDisable() {
        if (updater != null) updater.shutdown();
    }
}
```

### Commands

Delegate your `/myplugin update ...` subcommand to the bundled handler:

```java
UpdateSubcommand updateCmd = new UpdateSubcommand(updater);
// in your command executor:
case "update" -> updateCmd.handle(sender, Arrays.copyOfRange(args, 1, args.length));
```

which provides `check`, `download`/`install`, `apply` (hot reload, when
enabled), `ignore <v>`, `unignore <v>`, `restore` (stage the latest backup
for rollback), and `status`.

### Update modes

| Mode | Behavior |
|---|---|
| `CHECK_ONLY` | silent checks; results via API only |
| `NOTIFY` | + console & in-game notices (default) |
| `DOWNLOAD` | + admins may `update download` to stage a verified jar for the next restart |
| `AUTO_STAGE` | + new releases are downloaded, verified and staged automatically |

Staging writes the new jar into the server's update folder under the running
jar's exact filename (required by Bukkit's swap-on-restart mechanism), after
backing up the current jar to `plugins/<plugin>/pluginpulse/backups/`
(retention configurable via `.backupRetention(n)`). Downloads without a
published checksum are refused unless you opt out with `.requireHash(false)`.
A `pending-update.json` marker tracks whether a staged update actually applied
on the next boot; if it didn't, the plugin warns instead of re-staging forever.

## Sources

| Source | Constructor | Hashes | Notes |
|---|---|---|---|
| Modrinth | `new ModrinthSource("slug")` | sha1 + sha512 | 300 req/min limit; optional loader/game-version filters |
| GitHub Releases | `new GitHubReleasesSource("owner/repo")` | `digest` field or `.sha256` sidecar asset | 60 req/hr unauthenticated — keep intervals long; optional token |
| Hangar | `new HangarSource("slug")` | sha256 | platform selectable (`PAPER`/`VELOCITY`/`WATERFALL`) |
| Jenkins | `new JenkinsSource("https://ci.…/job/X/")` | none — download modes need `require-hash: false` | last successful build; optional artifact-name filter |
| Custom JSON | `new CustomJsonSource(url, headers)` | any | self-hosted manifest; headers carry auth (e.g. licence keys) |

### Custom manifest format

```json
{
  "version": "1.0.4",
  "changelog": "Fixed ...",
  "download": "https://example.com/dl/plugin-1.0.4.jar",
  "filename": "plugin-1.0.4.jar",
  "sha256": "…",
  "size": 123456,
  "restart-required": true,
  "page": "https://example.com/plugin",
  "tracks": { "mc26": { "version": "1.0.4-mc26", "download": "…", "sha256": "…" } }
}
```

### Tracks

If you publish parallel builds for different Minecraft generations (tags like
`v1.7.3` and `v1.7.3-mc26`), set `.track("mc26")` on the build that should
follow the suffixed releases. Version comparison ignores the suffix; source
selection uses it to pick the right release/asset/manifest entry.

## Customizing messages

All notices are MiniMessage templates with `<prefix>`, `<current>`, `<latest>`,
`<page>`, `<cmdroot>` placeholders:

```java
.prefix("<gradient:gold:yellow>[MyPlugin]</gradient>")
.message(UpdateNotifier.KEY_PLAYER, "<prefix> <latest> is out! <click:open_url:'<page>'>[Get it]</click>")
```

## Hot reload (opt-in, use with care)

The separate `pluginpulse-hotreload` artifact can apply a staged update
**without a restart**: it unloads the running plugin (disable, unregister
listeners/tasks/services/channels/commands, remove plugin-manager bookkeeping,
close the classloader — which also releases the Windows jar lock), swaps the
jar, and loads + enables the new version. If the new version fails to load it
rolls back to the automatic backup.

```kotlin
implementation("com.github.darkstarworks.PluginPulse:pluginpulse-hotreload:v0.3.0")
```

```java
.reloadEngine(HotReloadEngine.create())   // on the Updater builder
```

Admins then get `update apply` (after `update download`) in addition to the
restart path.

**Hard limits, by design:**

- Refused on **Folia** — regionized schedulers can't be torn down safely.
- Refused while other enabled plugins **depend on yours** (hard or soft).
- Your plugin must shut down cleanly: static state, thread pools, coroutine
  dispatchers and objects other plugins captured from the old instance are
  your responsibility. Repeated reloads can leak metaspace.
- Works on Paper's legacy and 1.20.5+ plugin-manager internals; on unknown
  future layouts it refuses with a clear message instead of corrupting state.

Restart-install remains the recommended default; treat hot reload as a
convenience for small, self-contained updates.

## License

[MIT](LICENSE)
