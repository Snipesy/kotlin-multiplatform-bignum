import org.jetbrains.kotlin.gradle.dsl.JsMainFunctionExecutionMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val mavenGroupId: String by project
val mavenVersion: String by project

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.maven.publish)
}

kotlin {
  jvmToolchain(25)
  jvm {
    compilerOptions {
      // Pin the published bytecode level so it doesn't silently track the toolchain.
      jvmTarget.set(JvmTarget.JVM_11)
    }
  }
  js {
    compilations.configureEach {
      val isMainCompilation = name == "main"
      compileTaskProvider.configure {
        compilerOptions {
          sourceMap.set(true)
          if (isMainCompilation) {
            main.set(JsMainFunctionExecutionMode.CALL)
          }
        }
      }
    }
    nodejs()
    browser {
      testTask {
        useKarma {
          useChromeHeadless()
        }
      }
    }
  }
  wasmJs {
    browser()
  }
  wasmWasi()
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

  sourceSets {
    commonMain.dependencies {
      api(project(":bignum"))
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.core)
    }
    all {
      languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
      languageSettings.optIn("kotlin.ExperimentalStdlibApi")
    }
  }
  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
  }
}

tasks.withType<Test>().configureEach {
  testLogging {
    events("FAILED", "SKIPPED")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

mavenPublishing {
  publishToMavenCentral()
  signAllPublications()
  coordinates(mavenGroupId, "bignum-serialization-kotlinx", mavenVersion)
  pom {
    name = "Kotlin BigNum kotlinx.serialization support"
    description = "kotlinx.serialization serializers for Kotlin BigNum BigInteger and BigDecimal"
    inceptionYear = "2026"
    url = "https://github.com/ohs-foundation/kotlin-bignum"
    licenses {
      license {
        name = "The Apache License, Version 2.0"
        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
      }
    }
    developers {
      developer {
        id = "ohs-foundation"
        name = "Open Health Stack Foundation"
        url = "https://ohs.dev/"
      }
      developer {
        id = "ionspin"
        name = "Ugljesa Jovanovic"
        url = "https://github.com/ionspin"
      }
    }
    scm {
      url = "https://github.com/ohs-foundation/kotlin-bignum/"
      connection = "scm:git:git://github.com/ohs-foundation/kotlin-bignum.git"
      developerConnection = "scm:git:ssh://git@github.com/ohs-foundation/kotlin-bignum.git"
    }
  }
}
