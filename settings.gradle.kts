import java.util.Locale

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

if (!file(".git").exists()) {
    val errorText = """
        
        =====================[ ERROR ]=====================
         The cookie project directory is not a properly cloned Git repository.
         
         In order to build cookie from source you must clone
         the cookie repository using Git, not download a code
         zip from GitHub.
         
         Built cookie jars are available for download at
         https://cookiemc.io/downloads
         
         See https://github.com/Craftcookiemc/cookie/blob/HEAD/CONTRIBUTING.md
         for further information on building and modifying cookie.
        ===================================================
    """.trimIndent()
    error(errorText)
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "cookie"
for (name in listOf("cookie-api", "cookie-server", "cookie-api-generator")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    findProject(":$projName")!!.projectDir = file(name)
}
