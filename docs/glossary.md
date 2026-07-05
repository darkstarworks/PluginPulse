# Glossary

Short, plain-English definitions of the terms used across the PluginPulse guides.

- **Paper / Spigot / Folia** — server software that runs Bukkit plugins. Paper is
  the most common; Spigot is its older sibling; Folia is a multithreaded Paper
  fork. PluginPulse works on all three.

- **Plugin jar** — the `.jar` file you drop into a server's `plugins/` folder.
  It's a ZIP archive containing compiled code and a descriptor.

- **Descriptor (`plugin.yml` / `paper-plugin.yml`)** — a small text file inside a
  plugin jar that tells the server the plugin's name, version, and **main class**
  (its entry point).

- **JitPack** — a free service (<https://jitpack.io>) that turns any public GitHub
  repository tag into a downloadable Gradle/Maven dependency on demand. **You do
  not need a JitPack account to *use* a library** — you just add a repository URL
  to your build file. (A GitHub account only matters if *you* publish your own
  project through JitPack later.)

- **Gradle / Maven** — build tools for Java/Kotlin projects. Your build file
  (`build.gradle.kts`, `build.gradle`, or `pom.xml`) lists dependencies and how
  to package your plugin.

- **Shading** — bundling a library's classes *inside* your plugin jar so it ships
  as one self-contained file (the server won't load the library separately).

- **Relocation** — renaming a shaded library's packages (e.g.
  `io.github.darkstarworks.pluginpulse` → `my.plugin.libs.pluginpulse`) so two
  plugins that both shade the same library don't collide at runtime. Always
  relocate shaded libraries.

- **MiniMessage** — Paper's rich-text format for colored, clickable chat messages
  (`<gold>`, `<click:...>`). PluginPulse uses it for update notices; on Spigot it
  falls back to plain text automatically.

- **Bukkit update folder (`plugins/update/`)** — a folder the server checks on
  startup. If it finds a jar there whose filename **exactly matches** a loaded
  plugin's jar, it swaps the old one for the new one. This is how PluginPulse
  applies a staged update on the next restart — no live jar swapping by default.

- **Modrinth / Hangar** — plugin hosting sites with public APIs PluginPulse can
  check for new versions. GitHub Releases works too.

- **Hot reload** — applying an update *without* restarting the server, by
  unloading and reloading the plugin in place. Opt-in and used with care;
  restart-install is the safe default.
