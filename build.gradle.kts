import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    `java-library`
    `maven-publish`
}

// Build variant: default (Minecraft 1.21.x, JDK 21) or MC 26.x, selected with
// -PmcVariant=26. The mc26 jar still targets Java 21 bytecode for maximum server
// compatibility; the newer JDK is only needed to read the Paper 26 API stubs
// (class version 69) at compile time. The plugin.yml api-version is switched per
// variant in pluginpulse-companion (26.1 vs 1.20).
//   ./gradlew :pluginpulse-companion:shadowJar                 -> ...-0.6.0.jar
//   ./gradlew :pluginpulse-companion:shadowJar -PmcVariant=26  -> ...-0.6.0-mc26.jar
val mcVariant: String = (findProperty("mcVariant") as String? ?: "1.21")
val isMc26: Boolean = mcVariant == "26"
val paperApiVersion: String = if (isMc26) "26.1.2.build.+" else "1.21.1-R0.1-SNAPSHOT"
val jdkVersion: Int = if (isMc26) 25 else 21

allprojects {
    group = "io.github.darkstarworks"
    version = "0.8.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(jdkVersion)
        }
        withSourcesJar()
        withJavadocJar()
    }

    // Keep bytecode at Java 21 even on the JDK 25 toolchain, so one variant isn't
    // needlessly locked to newer servers.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    dependencies {
        compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

        testImplementation(platform("org.junit:junit-bom:5.11.4"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    }

    // Paper 26 API stubs are class-version 69 and advertise "JVM 25 only", but we
    // deliberately keep bytecode at Java 21 (options.release = 21), which makes
    // Gradle 9 tag these classpaths as JVM-21 and refuse the dependency. Accept
    // the JVM-25 variant explicitly on the mc26 build.
    if (isMc26) {
        configurations.matching {
            it.name in setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
        }.configureEach {
            attributes {
                attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
            }
        }
    }

    tasks.test {
        useJUnitPlatform()
    }

    tasks.javadoc {
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}
