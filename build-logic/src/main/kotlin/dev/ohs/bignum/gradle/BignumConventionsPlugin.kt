/*
 * Copyright 2019 Ugljesa Jovanovic
 * Modifications Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.ohs.bignum.gradle

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Shared build convention for the bignum and bignum-serialization-kotlinx modules.
 *
 * Configures the Kotlin Multiplatform targets, common test dependencies, and Maven Central
 * publishing. The artifact ID is the Gradle module name and the POM description comes from the
 * module's `description`.
 */
class BignumConventionsPlugin : Plugin<Project> {
  @OptIn(ExperimentalWasmDsl::class)
  override fun apply(project: Project) =
    with(project) {
      pluginManager.apply("org.jetbrains.kotlin.multiplatform")
      pluginManager.apply("com.vanniktech.maven.publish")
      pluginManager.apply("maven-publish")

      val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
      val mavenGroupId = property("mavenGroupId") as String
      val mavenVersion = property("mavenVersion") as String

      // ── Kotlin Multiplatform ──────────────────────────────────────────────
      extensions.configure<KotlinMultiplatformExtension> {
        jvmToolchain(25)

        jvm {
          compilerOptions {
            // Pin the published bytecode level so it doesn't silently track the toolchain (25).
            jvmTarget.set(JvmTarget.JVM_11)
          }
        }
        js {
          nodejs()
          browser { browser ->
            browser.testTask { test -> test.useKarma { karma -> karma.useChromeHeadless() } }
          }
        }
        wasmJs { browser() }
        wasmWasi { nodejs() }
        linuxX64()
        linuxArm64()
        androidNativeX64()
        androidNativeX86()
        androidNativeArm32()
        androidNativeArm64()
        iosX64()
        iosArm64()
        iosSimulatorArm64()
        macosX64()
        macosArm64()
        tvosArm64()
        tvosSimulatorArm64()
        tvosX64()
        watchosArm32()
        watchosArm64()
        watchosDeviceArm64()
        watchosX64()
        watchosSimulatorArm64()
        mingwX64()

        sourceSets.getByName("commonTest").dependencies {
          implementation(libs.findLibrary("kotlin-test").get())
          implementation(libs.findLibrary("kotlinx-coroutines-core").get())
          implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
        sourceSets.configureEach {
          it.languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
          it.languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }
        compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
      }

      tasks.withType<Test>().configureEach { test ->
        test.testLogging {
          it.events("FAILED", "SKIPPED")
          it.exceptionFormat = TestExceptionFormat.FULL
        }
      }

      // ── Maven Central Publishing ──────────────────────────────────────────
      extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()
        coordinates(mavenGroupId, project.name, mavenVersion)
        pom { pom ->
          pom.name.set(
            when (project.name) {
              "bignum" -> "Kotlin BigNum"
              else -> "Kotlin BigNum ${project.name.removePrefix("bignum-")}"
            }
          )
          pom.description.set(provider { project.description })
          pom.inceptionYear.set("2026")
          pom.url.set("https://github.com/ohs-foundation/kotlin-bignum")
          pom.licenses { licenses ->
            licenses.license { license ->
              license.name.set("The Apache License, Version 2.0")
              license.url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
              license.distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
          }
          pom.developers { developers ->
            developers.developer { developer ->
              developer.id.set("ohs-foundation")
              developer.name.set("Open Health Stack Foundation")
              developer.url.set("https://ohs.dev/")
            }
            developers.developer { developer ->
              developer.id.set("ionspin")
              developer.name.set("Ugljesa Jovanovic")
              developer.url.set("https://github.com/ionspin")
            }
          }
          pom.scm { scm ->
            scm.url.set("https://github.com/ohs-foundation/kotlin-bignum/")
            scm.connection.set("scm:git:git://github.com/ohs-foundation/kotlin-bignum.git")
            scm.developerConnection.set(
              "scm:git:ssh://git@github.com/ohs-foundation/kotlin-bignum.git"
            )
          }
        }
      }
    }
}
