plugins {
    id("bignum-conventions")
    id("org.jetbrains.kotlin.plugin.serialization")
}

description = "kotlinx.serialization serializers for Kotlin BigNum BigInteger and BigDecimal"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":bignum"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
