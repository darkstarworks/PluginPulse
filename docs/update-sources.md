# Finding your update source

PluginPulse checks one or more **sources** to learn when a new version of a
plugin is out. You need at least one. This page explains how to find the right
identifier for each. It's shared by all three guides.

You can list more than one — one is the **primary** and the rest are
**fallbacks** tried in order if the primary is unreachable. By default the order
is Modrinth, then GitHub, then Hangar, then Jenkins. To choose your own order, add a
`source-order` list (primary first); any source you leave out is appended after
the ones you list:

```yaml
modrinth: my-plugin
github: me/my-plugin
source-order: [github, modrinth]   # check GitHub first, Modrinth as fallback
```

In the web tool, use the **priority** dropdown next to each source instead — it
writes `source-order` for you.

## Modrinth

For plugins published on [modrinth.com](https://modrinth.com).

- Open the plugin's page. The URL looks like
  `https://modrinth.com/plugin/`**`my-plugin`**.
- The bold part is the **slug** — that's your Modrinth value.

```
modrinth: my-plugin
```

> Modrinth's API asks every caller to identify itself. Provide a contact (an
> email or URL) via `user-agent-contact` so your checks aren't rejected.

## GitHub Releases

For plugins whose releases are published on GitHub's **Releases** page (each
release must attach the plugin `.jar` as an asset).

- Find the repository URL: `https://github.com/`**`owner/repo`**.
- Use the bold `owner/repo` part.

```
github: owner/repo
```

> GitHub allows only ~60 checks per hour from one server's IP when unauthenticated.
> PluginPulse spaces checks out and remembers the last result, so the default
> interval is fine — just don't set an aggressively short one.

## Hangar

For plugins on [hangar.papermc.io](https://hangar.papermc.io).

- Open the project. The URL looks like
  `https://hangar.papermc.io/`**`author`**`/`**`My-Plugin`**.
- The **project slug** is the last part (`My-Plugin`).

```
hangar: My-Plugin
```

## Jenkins (CI snapshots)

For plugins whose builds come from a **Jenkins** server — for example
FastAsyncWorldEdit ([ci.athion.net/job/FastAsyncWorldEdit](https://ci.athion.net/job/FastAsyncWorldEdit/)),
LuckPerms snapshots (ci.lucko.me), EssentialsX dev builds, and many others.
PluginPulse follows the job's **last successful build**.

- Use the **job page URL** — the page listing the build history, ending in
  `/job/<Name>/`.
- Many jobs archive **several jars** per build (FAWE ships Bukkit, CLI and
  Paper jars). Add `jenkins-artifact` — a pattern matched against the file
  name — to pick the right one.

```yaml
jenkins: https://ci.athion.net/job/FastAsyncWorldEdit/
jenkins-artifact: "Paper"     # optional; regex, case-insensitive
require-hash: false           # needed for download/auto modes — see below
```

> **Two things to know about CI builds.** (1) Jenkins publishes no checksums,
> so with the default `require-hash: true` PluginPulse will notify you but
> refuse to auto-download; set `require-hash: false` for this plugin if you
> accept unverified CI artifacts. (2) These are **development snapshots**, not
> releases — every successful build is offered, so expect frequent updates and
> the occasional broken build. Prefer Modrinth/Hangar/GitHub when the plugin
> publishes proper releases there.

### Known-good jobs and their filters

Verified against the live servers (July 2026). Where a build archives several
jars, the listed `jenkins-artifact` picks the right one:

| Plugin | `jenkins:` | `jenkins-artifact:` |
|---|---|---|
| FastAsyncWorldEdit | `https://ci.athion.net/job/FastAsyncWorldEdit/` | `Paper` (or `Bukkit`) |
| LuckPerms | `https://ci.lucko.me/job/LuckPerms/` | `Bukkit-5` (plain `Bukkit` would also match `Bukkit-Legacy`) |
| spark | `https://ci.lucko.me/job/spark/` | `paper` (or `bukkit`) |
| EssentialsX | `https://ci.ender.zone/job/EssentialsX/` | `^EssentialsX-` for the core; each module jar is also archived |
| ViaVersion | `https://ci.viaversion.com/job/ViaVersion/` | *(none needed — single jar)* |
| PlaceholderAPI | `https://ci.extendedclip.com/job/PlaceholderAPI/` | `PlaceholderAPI-[\d.]+\.jar$` — **required**; without it the `-plain` classifier jar is picked first |
| BungeeCord | `https://ci.md-5.net/job/BungeeCord/` | `^BungeeCord` — module command jars are archived alongside it |
| HolographicDisplays | `https://ci.codemc.io/job/filoghost/job/HolographicDisplays/` | *(none needed)* — nested CodeMC folder jobs work with the plain job-page URL |

The PlaceholderAPI row shows the general rule: when a job archives classifier
jars (`-plain`, `-shaded`, `-all`, per-platform variants), set an explicit
`jenkins-artifact` rather than trusting the default "first jar that isn't
`-sources`/`-javadoc`" pick.

Some Jenkins servers can't be used: **ci.citizensnpcs.co** (Citizens, Denizen)
blocks the JSON API at its web proxy, and **ci.dmulloy2.net** (ProtocolLib)
sits behind a Cloudflare challenge — both return HTML instead of JSON, so the
check fails.

## Self-hosted (advanced)

If the plugin author publishes their own JSON manifest (for example, a paid
plugin behind a licence key), that's a **custom source** — see the
[developer reference](../README.md#sources) `CustomJsonSource`. The companion
plugin and web tool don't configure custom sources; use the library directly.

## Which should I pick?

Whichever the plugin's author actually publishes to. If a plugin is on Modrinth,
use Modrinth. If it's only on GitHub, use `github`. Listing a second source as a
fallback (e.g. Modrinth primary, GitHub fallback) is a nice-to-have, not required.
