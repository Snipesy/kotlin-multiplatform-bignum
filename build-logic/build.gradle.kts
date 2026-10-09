plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // Plugin implementation JARs — these allow the convention plugin to apply
    // and configure these Gradle plugins on consumer projects.
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.maven.publish.plugin)
    implementation(gradleApi())
    implementation(gradleKotlinDsl())
}

configure<GradlePluginDevelopmentExtension> {
    plugins {
        create("bignumConventions") {
            id = "bignum-conventions"
            implementationClass = "dev.ohs.bignum.gradle.BignumConventionsPlugin"
        }
    }
}
