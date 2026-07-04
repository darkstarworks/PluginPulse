# PluginPulse

A small, dependency-free update-checker library for Paper plugins. Shade it in,
point it at where you publish releases, and your plugin gains:

- **Multi-source update checking** — Modrinth, GitHub Releases, Hangar, or any
  self-hosted JSON manifest, with ordered fallbacks.
- **Clickable admin notifications** — MiniMessage console + in-game notices on
  login, permission-gated, fully re-brandable.
- **Version intelligence** — semver-ish comparison, pre-release awareness
  (`1.2.0-beta1 < 1.2.0`), and distribution *tracks* for projects that ship
  parallel builds (e.g. `1.7.3` and `1.7.3-mc26`).
- **Rate-limit citizenship** — identifying User-Agent (required by Modrinth),
  persisted check state, jittered intervals.
- **Folia support** — automatic scheduler detection, or plug in your own adapter.

Verified downloads, staging into the server's `plugins/update/` folder, backups,
and an opt-in hot-reload engine are on the roadmap (see below).

## Requirements

- Paper (or a fork) 1.20.5+, Java 21+.
- No runtime dependencies: Adventure/MiniMessage and Gson are provided by Paper.

## Installation

Via [JitPack](https://jitpack.io):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.darkstarworks.pluginpulse:pluginpulse-core:v0.1.0")
}
```

Shade and **relocate** it (Gradle Shadow shown):

```kotlin
tasks.shadowJar {
    relocate("io.github.darkstarworks.pluginpulse", "my.plugin.libs.pluginpulse")
}
```

## Usage

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

Hook `updater.checkNow(sender)` into a `/myplugin update` subcommand for manual
checks, and `updater.ignoreVersion(v)` to let admins mute a release.

## Sources

| Source | Constructor | Hashes | Notes |
|---|---|---|---|
| Modrinth | `new ModrinthSource("slug")` | sha1 + sha512 | 300 req/min limit; optional loader/game-version filters |
| GitHub Releases | `new GitHubReleasesSource("owner/repo")` | `digest` field or `.sha256` sidecar asset | 60 req/hr unauthenticated — keep intervals long; optional token |
| Hangar | `new HangarSource("slug")` | sha256 | platform selectable (`PAPER`/`VELOCITY`/`WATERFALL`) |
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

## Roadmap

- **v0.2** — verified download pipeline: sha512/sha256 check, backup of the
  current jar, staging into `plugins/update/` for install-on-restart, and an
  `UpdateSubcommand` helper.
- **v0.3** — opt-in hot-reload engine (`pluginpulse-hotreload`): unload, swap,
  reload without a restart, with hard safety gates (refused on Folia and for
  plugins with dependents).

## License

[MIT](LICENSE)
