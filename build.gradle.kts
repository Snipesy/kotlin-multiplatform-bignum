plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlinx.serialization) apply false
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.spotless)
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
  ratchetFrom = "origin/main"
  // The header's $YEAR is the year of the OHS modifications. It is resolved statically because
  // Spotless would otherwise read the original 2019 copyright year out of each file and turn it
  // into a 2019-<now> range, which misstates when the modifications were made.
  val currentYear = java.time.LocalDate.now().year.toString()
  val licenseHeader = file("license-header.txt").readText().replace("\$YEAR", currentYear)
  kotlin {
    target("**/*.kt")
    ktfmt().googleStyle()
    licenseHeader(licenseHeader)
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
