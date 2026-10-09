plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlinx.serialization) apply false
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.spotless)
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
  ratchetFrom = "origin/main"
  kotlin {
    target("**/*.kt")
    ktfmt().googleStyle()
    licenseHeaderFile("license-header.txt")
  }
  kotlinGradle {
    target("**/*.gradle.kts")
    ktfmt().googleStyle()
  }
  format("markdown") {
    target("**/*.md")
    trimTrailingWhitespace()
    leadingTabsToSpaces()
    endWithNewline()
  }
}
