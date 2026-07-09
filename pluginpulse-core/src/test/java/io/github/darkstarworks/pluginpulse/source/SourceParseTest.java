package io.github.darkstarworks.pluginpulse.source;

import io.github.darkstarworks.pluginpulse.UpdateInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceParseTest {

    // ==== Modrinth ====

    private static final String MODRINTH_JSON = """
            [
              {
                "id": "abc123",
                "version_number": "1.7.3",
                "changelog": "Fixed vault bug",
                "files": [
                  {"url": "https://cdn.modrinth.com/data/x/versions/abc123/plugin-1.7.3.jar",
                   "filename": "plugin-1.7.3.jar", "primary": true, "size": 123456,
                   "hashes": {"sha1": "aa", "sha512": "bb"}},
                  {"url": "https://cdn.modrinth.com/other.jar", "filename": "other.jar",
                   "primary": false, "size": 1, "hashes": {}}
                ]
              },
              {
                "id": "old456",
                "version_number": "1.7.2",
                "changelog": "older",
                "files": [{"url": "u", "filename": "f", "primary": true, "size": 2, "hashes": {}}]
              }
            ]
            """;

    @Test
    void modrinthPicksNewestAndPrimaryFile() {
        UpdateInfo info = ModrinthSource.parse(MODRINTH_JSON, null, "myplugin");
        assertNotNull(info);
        assertEquals("1.7.3", info.version());
        assertEquals("plugin-1.7.3.jar", info.fileName());
        assertEquals("bb", info.hashes().get("sha512"));
        assertEquals(123456, info.sizeBytes());
        assertEquals("Fixed vault bug", info.changelog());
        assertTrue(info.releasePageUrl().contains("myplugin/version/abc123"));
    }

    private static final String MODRINTH_DUAL_TRACK_JSON = """
            [
              {"id": "a", "version_number": "1.7.3-mc26", "changelog": "",
               "files": [{"url": "u26", "filename": "p-1.7.3-mc26.jar", "primary": true, "size": 1, "hashes": {}}]},
              {"id": "b", "version_number": "1.7.3", "changelog": "",
               "files": [{"url": "u", "filename": "p-1.7.3.jar", "primary": true, "size": 1, "hashes": {}}]}
            ]
            """;

    @Test
    void modrinthWithoutTrackSkipsSuffixedVersions() {
        UpdateInfo info = ModrinthSource.parse(MODRINTH_DUAL_TRACK_JSON, null, "p");
        assertEquals("1.7.3", info.version());
        assertEquals("p-1.7.3.jar", info.fileName());
    }

    @Test
    void modrinthWithTrackPicksSuffixedVersion() {
        UpdateInfo info = ModrinthSource.parse(MODRINTH_DUAL_TRACK_JSON, "mc26", "p");
        assertEquals("1.7.3-mc26", info.version());
        assertEquals("p-1.7.3-mc26.jar", info.fileName());
    }

    @Test
    void modrinthFallsBackWhenTrackUnpublished() {
        UpdateInfo info = ModrinthSource.parse(MODRINTH_JSON, "mc26", "myplugin");
        assertNotNull(info);
        assertEquals("1.7.3", info.version());
    }

    // ==== GitHub ====

    private static final String GITHUB_JSON = """
            [
              {"draft": false, "prerelease": false, "tag_name": "v1.7.3-mc26",
               "html_url": "https://github.com/o/r/releases/tag/v1.7.3-mc26",
               "body": "mc26 build",
               "assets": [
                 {"name": "plugin-1.7.3-mc26.jar", "size": 111,
                  "browser_download_url": "https://github.com/o/r/releases/download/v1.7.3-mc26/plugin-1.7.3-mc26.jar",
                  "digest": "sha256:deadbeef"}
               ]},
              {"draft": false, "prerelease": false, "tag_name": "v1.7.3",
               "html_url": "https://github.com/o/r/releases/tag/v1.7.3",
               "body": "master build",
               "assets": [
                 {"name": "plugin-1.7.3.jar", "size": 222,
                  "browser_download_url": "https://github.com/o/r/releases/download/v1.7.3/plugin-1.7.3.jar"},
                 {"name": "plugin-1.7.3.jar.sha256", "size": 64,
                  "browser_download_url": "https://github.com/o/r/releases/download/v1.7.3/plugin-1.7.3.jar.sha256"},
                 {"name": "plugin-1.7.3-sources.jar", "size": 5,
                  "browser_download_url": "https://github.com/o/r/x-sources.jar"}
               ]},
              {"draft": true, "prerelease": false, "tag_name": "v9.9.9",
               "html_url": "u", "body": "", "assets": []}
            ]
            """;

    @Test
    void githubNoTrackSkipsSuffixedTagsAndDrafts() {
        GitHubReleasesSource.Parsed parsed = GitHubReleasesSource.parse(GITHUB_JSON, null,
                n -> n.endsWith(".jar") && !n.contains("-sources") && !n.contains("-javadoc"));
        assertNotNull(parsed);
        assertEquals("v1.7.3", parsed.info().version());
        assertEquals("plugin-1.7.3.jar", parsed.info().fileName());
        assertTrue(parsed.info().hashes().isEmpty());
        assertNotNull(parsed.sha256SidecarUrl());
        assertTrue(parsed.sha256SidecarUrl().endsWith(".sha256"));
    }

    @Test
    void githubTrackMatchesSuffixedTagAndDigest() {
        GitHubReleasesSource.Parsed parsed = GitHubReleasesSource.parse(GITHUB_JSON, "mc26",
                n -> n.endsWith(".jar") && !n.contains("-sources"));
        assertNotNull(parsed);
        assertEquals("v1.7.3-mc26", parsed.info().version());
        assertEquals("deadbeef", parsed.info().hashes().get("sha256"));
        assertNull(parsed.sha256SidecarUrl());
    }

    // ==== Hangar ====

    private static final String HANGAR_JSON = """
            {
              "name": "5.10.0",
              "description": "release notes here",
              "author": "kennytv",
              "downloads": {
                "PAPER": {
                  "fileInfo": {"name": "ViaVersion-5.10.0.jar", "sizeBytes": 6434343,
                               "sha256Hash": "e5a63f"},
                  "externalUrl": null,
                  "downloadUrl": "https://hangarcdn.papermc.io/plugins/x/PAPER/ViaVersion-5.10.0.jar"
                }
              }
            }
            """;

    @Test
    void hangarParsesPaperDownload() {
        UpdateInfo info = HangarSource.parse(HANGAR_JSON, "PAPER", "ViaVersion");
        assertEquals("5.10.0", info.version());
        assertEquals("ViaVersion-5.10.0.jar", info.fileName());
        assertEquals("e5a63f", info.hashes().get("sha256"));
        assertEquals(6434343, info.sizeBytes());
        assertEquals("https://hangar.papermc.io/kennytv/ViaVersion", info.releasePageUrl());
        assertTrue(info.downloadUrl().startsWith("https://hangarcdn.papermc.io/"));
    }

    // ==== Jenkins ====

    // Trimmed real payloads from ci.athion.net (FAWE) and ci.lucko.me (LuckPerms),
    // captured 2026-07-10 from <job>/lastSuccessfulBuild/api/json?tree=...
    private static final String JENKINS_FAWE_JSON = """
            {"_class":"hudson.model.FreeStyleBuild","artifacts":[
              {"fileName":"FastAsyncWorldEdit-Bukkit-2.15.3-SNAPSHOT-1348.jar","relativePath":"artifacts/FastAsyncWorldEdit-Bukkit-2.15.3-SNAPSHOT-1348.jar"},
              {"fileName":"FastAsyncWorldEdit-CLI-2.15.3-SNAPSHOT-1348.jar","relativePath":"artifacts/FastAsyncWorldEdit-CLI-2.15.3-SNAPSHOT-1348.jar"},
              {"fileName":"FastAsyncWorldEdit-Paper-2.15.3-SNAPSHOT-1348.jar","relativePath":"artifacts/FastAsyncWorldEdit-Paper-2.15.3-SNAPSHOT-1348.jar"}],
             "number":1348,"result":"SUCCESS",
             "url":"https://ci.athion.net/job/FastAsyncWorldEdit/1348/"}
            """;

    private static final String JENKINS_LUCKPERMS_JSON = """
            {"_class":"hudson.model.FreeStyleBuild","artifacts":[
              {"fileName":"LuckPerms-Bukkit-5.5.59.jar","relativePath":"bukkit/loader/build/libs/LuckPerms-Bukkit-5.5.59.jar"},
              {"fileName":"LuckPerms-Bukkit-Legacy-5.5.59.jar","relativePath":"bukkit-legacy/loader/build/libs/LuckPerms-Bukkit-Legacy-5.5.59.jar"},
              {"fileName":"LuckPerms-Velocity-5.5.59.jar","relativePath":"velocity/build/libs/LuckPerms-Velocity-5.5.59.jar"}],
             "number":1647,"result":"SUCCESS","url":"https://ci.lucko.me/job/LuckPerms/1647/"}
            """;

    @Test
    void jenkinsArtifactRegexPicksPaperJarAndDerivesVersion() {
        UpdateInfo info = JenkinsSource.parse(JENKINS_FAWE_JSON, JenkinsSource.artifactRegex("Paper"));
        assertNotNull(info);
        assertEquals("2.15.3-SNAPSHOT-1348", info.version());
        assertEquals("FastAsyncWorldEdit-Paper-2.15.3-SNAPSHOT-1348.jar", info.fileName());
        assertEquals("https://ci.athion.net/job/FastAsyncWorldEdit/1348/artifact/artifacts/FastAsyncWorldEdit-Paper-2.15.3-SNAPSHOT-1348.jar",
                info.downloadUrl());
        assertTrue(info.hashes().isEmpty());
        assertTrue(info.restartRequired());
        assertEquals("https://ci.athion.net/job/FastAsyncWorldEdit/1348/", info.releasePageUrl());
    }

    @Test
    void jenkinsDefaultFilterTakesFirstJar() {
        UpdateInfo info = JenkinsSource.parse(JENKINS_FAWE_JSON, n -> n.toLowerCase().endsWith(".jar"));
        assertEquals("FastAsyncWorldEdit-Bukkit-2.15.3-SNAPSHOT-1348.jar", info.fileName());
    }

    @Test
    void jenkinsRegexIsAnchoredEnoughToExcludeLegacy() {
        UpdateInfo info = JenkinsSource.parse(JENKINS_LUCKPERMS_JSON, JenkinsSource.artifactRegex("Bukkit-\\d"));
        assertNotNull(info);
        assertEquals("5.5.59", info.version());
        assertEquals("LuckPerms-Bukkit-5.5.59.jar", info.fileName());
        assertEquals("https://ci.lucko.me/job/LuckPerms/1647/artifact/bukkit/loader/build/libs/LuckPerms-Bukkit-5.5.59.jar",
                info.downloadUrl());
    }

    @Test
    void jenkinsNoMatchingArtifactReturnsNull() {
        assertNull(JenkinsSource.parse(JENKINS_FAWE_JSON, JenkinsSource.artifactRegex("Velocity")));
    }

    @Test
    void jenkinsNonSuccessBuildRejected() {
        String failing = JENKINS_FAWE_JSON.replace("\"SUCCESS\"", "\"FAILURE\"");
        assertNull(JenkinsSource.parse(failing, JenkinsSource.artifactRegex("Paper")));
    }

    @Test
    void jenkinsRelativePathWithSpacesIsEncoded() {
        String json = """
                {"artifacts":[{"fileName":"My Plugin-1.2.jar","relativePath":"build/libs dir/My Plugin-1.2.jar"}],
                 "number":7,"result":"SUCCESS","url":"https://ci.example.org/job/My%20Plugin/7/"}
                """;
        UpdateInfo info = JenkinsSource.parse(json, name -> true);
        assertEquals("https://ci.example.org/job/My%20Plugin/7/artifact/build/libs%20dir/My%20Plugin-1.2.jar",
                info.downloadUrl());
        assertEquals("1.2", info.version());
    }

    @Test
    void jenkinsVersionFallsBackToBuildNumber() {
        assertEquals("2.15.3-SNAPSHOT-1348",
                JenkinsSource.deriveVersion("FastAsyncWorldEdit-Paper-2.15.3-SNAPSHOT-1348.jar", 1348));
        assertEquals("5.5.59", JenkinsSource.deriveVersion("LuckPerms-Bukkit-5.5.59.jar", 1647));
        assertEquals("42", JenkinsSource.deriveVersion("plugin.jar", 42));
    }

    // ==== Custom JSON ====

    private static final String CUSTOM_JSON = """
            {
              "version": "1.0.4",
              "changelog": "Fixed spawn bug",
              "download": "https://esmp.fun/dl/ws-1.0.4.jar",
              "filename": "ws-1.0.4.jar",
              "sha256": "ABCDEF",
              "size": 999,
              "restart-required": false,
              "page": "https://esmp.fun/plugins",
              "tracks": {
                "mc26": {"version": "1.0.4-mc26", "download": "https://esmp.fun/dl/ws-1.0.4-mc26.jar",
                         "sha256": "123456"}
              }
            }
            """;

    @Test
    void customJsonTopLevel() {
        UpdateInfo info = CustomJsonSource.parse(CUSTOM_JSON, null);
        assertEquals("1.0.4", info.version());
        assertEquals("abcdef", info.hashes().get("sha256"));
        assertEquals(999, info.sizeBytes());
        assertFalse(info.restartRequired());
        assertEquals("https://esmp.fun/plugins", info.releasePageUrl());
    }

    @Test
    void customJsonTrackOverrides() {
        UpdateInfo info = CustomJsonSource.parse(CUSTOM_JSON, "mc26");
        assertEquals("1.0.4-mc26", info.version());
        assertEquals("https://esmp.fun/dl/ws-1.0.4-mc26.jar", info.downloadUrl());
        assertEquals("123456", info.hashes().get("sha256"));
        // Non-overridden fields inherited from the top level.
        assertEquals("Fixed spawn bug", info.changelog());
    }
}
