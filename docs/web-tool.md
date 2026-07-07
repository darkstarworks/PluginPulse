# Path 3 — The browser tool (for jar-only users)

**Audience:** you have a compiled plugin `.jar` but can't or won't rebuild it
from source, and you want to add auto-updates to it.

If a term is unfamiliar, see the [glossary](glossary.md).

## What it does

The tool adds PluginPulse to an existing plugin jar and hands you a new jar. It
runs **entirely in your web browser** — **your jar is never uploaded to any
server.** There's nothing to install and no account.

**Open it:** <https://darkstarworks.github.io/PluginPulse/>

## Step by step

1. **Open** the tool (link above).

2. **Choose your plugin jar** and, optionally, click **Inspect**. Inspect reads
   the jar locally and shows the detected plugin name, its main class, and whether
   the tool can process it — without changing anything.

3. **Fill in the fields:**

   | Field | What to enter | Where to find it |
   |---|---|---|
   | Modrinth / GitHub / Hangar | at least one update source | [finding your update source](update-sources.md) |
   | Update mode | how it should behave (see below) | your choice |
   | Command root | *(optional)* e.g. `/myplugin` — a command to manage updates | your choice |
   | Permission | *(optional)* who sees notices, e.g. `myplugin.admin` | your choice |
   | Contact | *(recommended)* email or URL | required by Modrinth's API |
   | Release track | *(optional)* e.g. `mc26` | only if the plugin ships parallel builds |
   | Check interval | *(optional)* hours between checks | default is fine |

   **`mode` for beginners:**
   - `notify` — just tells admins when an update exists (safest; nothing downloads).
   - `check-only` — silent; the result shows only via the command.
   - `download` — downloads and stages the update; it applies when you restart.
   - `auto-stage` — downloads automatically as soon as an update appears.

4. **Tick the rights checkbox** — confirm you're allowed to modify and
   redistribute this jar. (You're altering someone's compiled software; only do
   this for jars you have the right to change.)

5. **Generate** — the tool builds `<yourplugin>-pulse.jar` and your browser
   downloads it.

6. **Test it on your own server first** — drop the new jar in `plugins/`, start
   the server, and confirm the plugin still enables and logs an update check. The
   tool can't verify the jar boots; that's on you before you share it.

## Limits (read these)

- **Wrapper strategy.** If the plugin's main class is `final` (the default for
  Kotlin plugins), the tool clears that flag so the wrapper can subclass it —
  removing `final` only permits subclassing and is safe for a plugin main class.
  If you'd rather edit a final main in place, the command-line tool
  `pluginpulse-inject` uses the instrument strategy instead.
- **It can't confirm the jar runs.** Always test before distributing.
- **Any class-file version works,** including Java 25 / mc26 jars, and both
  `plugin.yml` and `paper-plugin.yml` descriptors.

## "It said my jar can't be processed"

Common reasons and what to do:

- **"main class is final"** — expected for some plugins; use the CLI tool
  (`pluginpulse-inject`), which handles this case.
- **"already contains PluginPulse"** — it was injected before. Tick the
  **re-inject** box only if you mean to redo it.
- **"No plugin.yml or paper-plugin.yml with a main:"** — the file isn't a Bukkit
  plugin jar (or its descriptor is missing/renamed). Double-check you selected the
  right file.
