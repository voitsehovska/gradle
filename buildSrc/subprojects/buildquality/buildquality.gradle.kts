plugins {
    `java-gradle-plugin`
}

apply(plugin = "org.gradle.kotlin.kotlin-dsl")

dependencies {
    implementation(project(":binaryCompatibility"))
    implementation(project(":cleanup"))
    implementation(project(":configuration"))
    implementation(project(":kotlinDsl"))
    implementation(project(":profiling"))
    implementation(project(":integrationTesting"))
    implementation(project(":plugins"))
    implementation(project(":performance"))
    implementation("org.owasp:dependency-check-gradle:3.1.0")
    implementation("org.codenarc:CodeNarc:1.0")
}

gradlePlugin {
    plugins {
        register("addVerifyProductionEnvironmentTask") {
            id = "gradlebuild.add-verify-production-environment-task"
            implementationClass = "org.gradle.gradlebuild.buildquality.VerifyBuildEnvironmentPlugin"
        }
        register("ciReporting") {
            id = "gradlebuild.ci-reporting"
            implementationClass = "org.gradle.gradlebuild.buildquality.CiReportingPlugin"
        }
        register("classycle") {
            id = "gradlebuild.classycle"
            implementationClass = "org.gradle.gradlebuild.buildquality.classycle.ClassyclePlugin"
        }
        register("dependencyVulnerabilities") {
            id = "gradlebuild.dependency-vulnerabilities"
            implementationClass = "org.gradle.gradlebuild.buildquality.DependencyVulnerabilitiesPlugin"
        }
        register("noResolutionAtConfigurationTime") {
            id = "gradlebuild.no-resolution-at-configuration"
            implementationClass = "org.gradle.gradlebuild.buildquality.NoResolutionAtConfigurationTimePlugin"
        }
        register("taskPropertyValidation") {
            id = "gradlebuild.task-properties-validation"
            implementationClass = "org.gradle.gradlebuild.buildquality.TaskPropertyValidationPlugin"
        }
    }
}


