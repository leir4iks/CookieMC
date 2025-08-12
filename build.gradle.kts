import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    `maven-publish`
    id("io.papermc.paperweight.patcher") version "2.0.0-SNAPSHOT"
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(22)
        }
    }

    tasks.compileJava {
        options.compilerArgs.add("-Xlint:-deprecation")
        options.isWarnings = false
    }

    tasks.withType(JavaCompile::class.java).configureEach {
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "4G"
    }
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(22)
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 22
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "4g"
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven("https://jitpack.io")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven("https://maven.shedaniel.me/")
        maven("https://maven.terraformersmc.com/releases/")
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }

    val subproject = this;
    // we don't have any form of publishing for cookie-server, because that's the dev bundle
    if (subproject.name == "cookie-api") {
        publishing {
            repositories {
                maven {
                    name = "central"
                    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                    credentials {
                        username=System.getenv("PUBLISH_USER")
                        password=System.getenv("PUBLISH_TOKEN")
                    }
                }
            }

            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])

                    afterEvaluate {
                        pom {
                            name.set("cookie-api")
                            description.set(subproject.description)
                            url.set("https://github.com/Craftcookiemc/cookie")
                            licenses {
                                license {
                                    name.set("GNU Affero General Public License v3.0")
                                    url.set("https://github.com/Craftcookiemc/cookie/blob/master/LICENSE")
                                    distribution.set("repo")
                                }
                            }
                            developers {
                                developer {
                                    id.set("cookie-team")
                                    name.set("cookie Team")
                                    organization.set("cookiemc")
                                    organizationUrl.set("https://cookiemc.io")
                                    roles.add("developer")
                                }
                            }
                            scm {
                                url.set("https://github.com/Craftcookiemc/cookie")
                            }
                        }
                    }
                }
            }
        }
    }

}

repositories {
    mavenCentral()
    jcenter()
    maven(paperMavenPublicUrl)
}

paperweight {
    upstreams.register("purpur") {
        repo = github("PurpurMC", "Purpur")
        ref = providers.gradleProperty("purpurCommit")

        patchFile {
            path = "purpur-server/build.gradle.kts"
            outputFile = file("cookie-server/build.gradle.kts")
            patchFile = file("cookie-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "purpur-api/build.gradle.kts"
            outputFile = file("cookie-api/build.gradle.kts")
            patchFile = file("cookie-api/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("cookie-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("purpurApi") {
            upstreamPath = "purpur-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("cookie-api/purpur-patches")
            outputDir = file("purpur-api")
        }
    }
}

// build publication
tasks.register<Jar>("createMojmapClipboardJar") {
    dependsOn(":cookie-server:createMojmapPaperclipJar")
}

tasks.register("buildPublisherJar") {
    dependsOn(":createMojmapClipboardJar")

    doLast {
        val buildNumber = System.getenv("BUILD_NUMBER") ?: "local"

        val paperclipJarTask = project(":cookie-server").tasks.getByName("createMojmapPaperclipJar")
        val outputJar = paperclipJarTask.outputs.files.singleFile
        val outputDir = outputJar.parentFile

        if (outputJar.exists()) {
            val newJarName = "cookie-build.$buildNumber.jar"
            val newJarFile = File(outputDir, newJarName)

            outputDir.listFiles()
                ?.filter { it.name.startsWith("cookie-build.") && it.name.endsWith(".jar") }
                ?.forEach { it.delete() }
            outputJar.renameTo(newJarFile)
            println("Renamed ${outputJar.name} to $newJarName in ${outputDir.absolutePath}")
        }
    }
}

// patching scripts
tasks.register("fixupMinecraftFilePatches") {
    dependsOn(":cookie-server:fixupMinecraftSourcePatches")
}
