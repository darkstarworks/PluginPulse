# Path 1 — Shade the library into your own plugin

**Audience:** you write and build your own Paper/Spigot plugin, and you want it
to check for and (optionally) install its own updates.

This guide is self-contained. If a term is unfamiliar, see the
[glossary](glossary.md).

## What you'll do

1. Add a repository + dependency to your build file.
2. Shade and **relocate** the library into your jar.
3. Wire it up — either one file (no code) or the builder (power users).
4. Verify it works.

---

## 1. Add the dependency (via JitPack)

**What is JitPack?** [JitPack](https://jitpack.io) is a free service that builds
a Gradle/Maven dependency directly from a GitHub tag. **You do not need a JitPack
account, and you don't need to sign up for anything** — you just add its
repository URL to your build file and reference the version by its Git tag. The
first time anyone requests a given version, JitPack builds it (~1–2 minutes) and
caches it forever after.

The current version is **`v0.5.0`**. Pick your build system:

### Gradle (Kotlin DSL — `build.gradle.kts`)

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")               // <-- add this
}

dependencies {
    implementation("com.github.darkstarworks.PluginPulse:pluginpulse-core:v0.5.0")
}
```

### Gradle (Groovy DSL — `build.gradle`)

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }         // <-- add this
}

dependencies {
    implementation 'com.github.darkstarworks.PluginPulse:pluginpulse-core:v0.5.0'
}
```

### Maven (`pom.xml`)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.darkstarworks.PluginPulse</groupId>
        <artifactId>pluginpulse-core</artifactId>
        <version>v0.5.0</version>
    </dependency>
</dependencies>
```

---

## 2. Shade **and relocate** it

Shading bundles the library's classes into your jar. **Relocation** renames its
package so that if another plugin also shades PluginPulse, the two copies don't
clash at runtime. Always relocate.

### Gradle — [Shadow](https://gradleup.com/shadow/) (Kotlin DSL)

```kotlin
plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

tasks.shadowJar {
    // rename io.github.darkstarworks.pluginpulse -> <your package>.libs.pluginpulse
    relocate("io.github.darkstarworks.pluginpulse", "com.example.myplugin.libs.pluginpulse")
}

tasks.build { dependsOn(tasks.shadowJar) }
```

### Gradle — Shadow (Groovy DSL)

```groovy
plugins {
    id 'com.gradleup.shadow' version '8.3.5'
}

shadowJar {
    relocate 'io.github.darkstarworks.pluginpulse', 'com.example.myplugin.libs.pluginpulse'
}

build.dependsOn shadowJar
```

### Maven — Shade plugin

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-shade-plugin</artifactId>
      <version>3.6.0</version>
      <executions>
        <execution>
          <phase>package</phase>
          <goals><goal>shade</goal></goals>
          <configuration>
            <relocations>
              <relocation>
                <pattern>io.github.darkstarworks.pluginpulse</pattern>
                <shadedPattern>com.example.myplugin.libs.pluginpulse</shadedPattern>
              </relocation>
            </relocations>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

> Adventure/MiniMessage and Gson are **not** shaded — Paper already bundles them,
> and on Spigot the notices fall back to plain text automatically. The shaded
> footprint is just PluginPulse's own classes (~50–80 KB).

---

## 3. Wire it up

### Style A — one file, no code

Drop a `pluginpulse.yml` into `src/main/resources/`:

```yaml
# At least one source is required; the first is primary, the rest fallbacks.
# See docs/update-sources.md for how to find these values.
modrinth: my-project-slug
github: me/my-plugin              # optional fallback

permission: myplugin.admin        # who sees notices / can run /myplugin update
command-root: /myplugin           # clickable buttons; self-registered if the name is free
user-agent-contact: you@example.com   # required by Modrinth's API rules
mode: notify                      # off | check-only | notify | download | auto-stage
check-interval-hours: 6
# track: mc26                     # optional: follow a "-<track>" release line
# self-register-command: true     # default true
```

Then, in your plugin's main class:

```java
import io.github.darkstarworks.pluginpulse.PluginPulse;

public final class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        PluginPulse.bootstrap(this);
    }

    @Override
    public void onDisable() {
        PluginPulse.shutdown(this);
    }
}
```

That's the whole integration. Because `command-root` is set and PluginPulse
self-registers it when the name is free, `/myplugin update` works without you
declaring a command. If you *do* declare `/myplugin` in your own `plugin.yml`,
PluginPulse leaves it alone — delegate the `update` subcommand yourself:

```java
import java.util.Arrays;

@Override
public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    if (args.length > 0 && args[0].equalsIgnoreCase("update")) {
        return PluginPulse.handleUpdateCommand(this, sender, Arrays.copyOfRange(args, 1, args.length));
    }
    // ... your other subcommands ...
    return true;
}
```

### Style B — the builder (power users)

For custom sources, message overrides, a supplied scheduler, or the hot-reload
engine:

```java
import io.github.darkstarworks.pluginpulse.*;
import io.github.darkstarworks.pluginpulse.source.*;
import java.time.Duration;

public final class MyPlugin extends JavaPlugin {
    private Updater updater;

    @Override
    public void onEnable() {
        updater = Updater.builder(this)
            .source(new ModrinthSource("my-project-slug"))
            .fallbackSource(new GitHubReleasesSource("me/my-plugin"))
            .mode(UpdateMode.NOTIFY)
            .checkInterval(Duration.ofHours(6))
            .permission("myplugin.admin")
            .commandRoot("/myplugin")
            .userAgentContact("you@example.com")
            .build();
        updater.start();
    }

    @Override
    public void onDisable() {
        if (updater != null) updater.shutdown();
    }
}
```

The full builder surface (modes, sources, tracks, message customization, and the
opt-in hot-reload artifact) is documented in the
[developer reference](../README.md).

---

## 4. Verify it worked

1. **Build:** `./gradlew build` (or `mvn package`).
2. **Inspect the jar** for the relocated package — it should be under *your*
   namespace, not the original:
   ```bash
   unzip -l build/libs/MyPlugin.jar | grep pluginpulse
   # expect: com/example/myplugin/libs/pluginpulse/PluginPulse.class
   # NOT:    io/github/darkstarworks/pluginpulse/...
   ```
3. **Boot a test server** with the jar. On enable you'll see an update-check log
   line, and — if a newer version exists — a console notice and an in-game notice
   for players with your permission.

---

## Troubleshooting

- **`Could not resolve com.github.darkstarworks.PluginPulse...`** — JitPack builds
  a version the first time it's requested. Wait 1–2 minutes and rebuild. Check
  <https://jitpack.io/#darkstarworks/PluginPulse> for the build log if it persists.

- **Relocation missing** (jar still contains `io/github/darkstarworks/...`) — the
  shade/relocate step didn't run. Confirm you build the shaded jar (Shadow's
  `shadowJar`, or Maven `package` with the shade plugin) and that `relocate` is
  configured as above.

- **Modrinth checks fail** — set `user-agent-contact` (Modrinth's API requires a
  caller to identify itself).

- **`/myplugin update` says "not active"** — `bootstrap` returned null (no
  `pluginpulse.yml`, no source, or `mode: off`). Check the server log for the
  PluginPulse warning line on enable.
