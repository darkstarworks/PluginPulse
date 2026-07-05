plugins {
    `java-library`
    `maven-publish`
}

allprojects {
    group = "io.github.darkstarworks"
    version = "0.4.0"
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
            languageVersion = JavaLanguageVersion.of(21)
        }
        withSourcesJar()
        withJavadocJar()
    }

    dependencies {
        compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

        testImplementation(platform("org.junit:junit-bom:5.11.4"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
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
