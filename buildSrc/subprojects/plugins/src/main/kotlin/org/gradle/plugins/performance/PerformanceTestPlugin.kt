package org.gradle.plugins.performance

import accessors.groovy
import accessors.java
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.junit.JUnitOptions
import org.gradle.internal.hash.HashUtil
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.eclipse.model.EclipseModel
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.gradle.testing.DistributedPerformanceTest
import org.gradle.testing.PerformanceTest
import org.gradle.testing.RebaselinePerformanceTests
import org.gradle.testing.performance.generator.tasks.AbstractProjectGeneratorTask
import org.gradle.testing.performance.generator.tasks.JavaExecProjectGeneratorTask
import org.gradle.testing.performance.generator.tasks.JvmProjectGeneratorTask
import org.gradle.testing.performance.generator.tasks.ProjectGeneratorTask
import org.gradle.testing.performance.generator.tasks.RemoteProject
import org.gradle.testing.performance.generator.tasks.TemplateProjectGeneratorTask

import org.w3c.dom.Document
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

import org.gradle.kotlin.dsl.*


private
object PropertyNames {

    const val dbUrl = "org.gradle.performance.db.url"
    const val dbUsername = "org.gradle.performance.db.username"
    const val dbPassword = "org.gradle.performance.db.password"

    const val flameGraphTargetDir = "org.gradle.performance.flameGraphTargetDir"

    const val workerTestTaskName = "org.gradle.performance.workerTestTaskName"
    const val channel = "org.gradle.performance.execution.channel"
    const val coordinatorBuildId = "org.gradle.performance.coordinatorBuildId"
    const val performanceTestVerbose = "performanceTest.verbose"
    const val baselines = "org.gradle.performance.baselines"
    const val buildTypeId = "org.gradle.performance.buildTypeId"
    const val branchName = "org.gradle.performance.branchName"

    const val teamCityUsername = "teamCityUsername"
    const val teamCityPassword = "teamCityPassword"
}


private
object Config {

    val baseLineList = listOf("1.1", "1.12", "2.0", "2.1", "2.4", "2.9", "2.12", "2.14.1", "last")

    const val performanceTestScenarioListFileName = "performance-tests/scenario-list.csv"

    const val performanceTestScenarioReportFileName = "performance-tests/scenario-report.html"

    const val performanceTestReportsDir = "performance-tests/report"

    const val teamCityUrl = "https://builds.gradle.org/"

    const val adhocTestDbUrl = "jdbc:h2:./build/database"
}


private
const val performanceExperimentCategory = "org.gradle.performance.categories.PerformanceExperiment"


@Suppress("unused")
class PerformanceTestPlugin : Plugin<Project> {

    override fun apply(project: Project): Unit = project.run {
        apply(plugin = "java")

        registerEmptyPerformanceReportTask()

        val performanceTestSourceSet = createPerformanceTestSourceSet()
        addConfigurationAndDependencies()
        createCheckNoIdenticalBuildFilesTask()
        configureGeneratorTasks()

        val prepareSamplesTask = createPrepareSamplesTask()
        createCleanSamplesTask()

        createLocalPerformanceTestTasks(performanceTestSourceSet, prepareSamplesTask)
        createDistributedPerformanceTestTasks(performanceTestSourceSet, prepareSamplesTask)

        createRebaselineTask(performanceTestSourceSet)

        configureIdePlugins(performanceTestSourceSet)
    }

    private
    fun Project.registerEmptyPerformanceReportTask() {
        // Some of CI builds have parameter `-x performanceReport` so simply removing `performanceReport` would result in an error
        // This task acts as a workaround for transition
        tasks.register("performanceReport")
    }

    private
    fun Project.createRebaselineTask(performanceTestSourceSet: SourceSet) {
        project.tasks.register("rebaselinePerformanceTests", RebaselinePerformanceTests::class.java) {
            source(performanceTestSourceSet.allSource)
        }
    }

    private
    fun Project.createPerformanceTestSourceSet(): SourceSet = java.sourceSets.run {
        val main by getting
        val test by getting
        val performanceTest by creating {
            compileClasspath += main.output + test.output
            runtimeClasspath += main.output + test.output
        }
        performanceTest
    }

    private
    fun Project.addConfigurationAndDependencies() {

        configurations {

            val testCompile by getting

            "performanceTestCompile" {
                extendsFrom(testCompile)
            }

            val testRuntime by getting

            "performanceTestRuntime" {
                extendsFrom(testRuntime)
            }

            val performanceTestRuntimeClasspath by getting

            "partialDistribution" {
                extendsFrom(performanceTestRuntimeClasspath)
            }

            create("junit")
        }

        dependencies {
            "performanceTestCompile"(project(":internalPerformanceTesting"))
            "junit"("junit:junit:4.12")
        }
    }

    private
    fun Project.createCheckNoIdenticalBuildFilesTask() {
        tasks.register("checkNoIdenticalBuildFiles") {
            doLast {
                val filesBySha1 = mutableMapOf<String, MutableList<File>>()
                buildDir.walkTopDown().forEach { file ->
                    if (file.name.endsWith(".gradle")) {
                        val sha1 = sha1StringFor(file)
                        val files = filesBySha1[sha1]
                        when (files) {
                            null -> filesBySha1[sha1] = mutableListOf(file)
                            else -> files.add(file)
                        }
                    }
                }

                filesBySha1.forEach { hash, candidates ->
                    if (candidates.size > 1) {
                        logger.lifecycle("Duplicate build files found for hash '$hash' : $candidates")
                    }
                }
            }
        }
    }

    private
    fun Project.configureGeneratorTasks() {

        tasks.withType<ProjectGeneratorTask>().configureEach {
            group = "Project setup"
        }

        tasks.withType<AbstractProjectGeneratorTask>().configureEach {
            (project.findProperty("maxProjects") as? Int)?.let { maxProjects ->
                setProjects(maxProjects)
            }
        }

        tasks.withType<JvmProjectGeneratorTask>().configureEach {
            testDependencies = configurations["junit"]
        }

        tasks.withType<TemplateProjectGeneratorTask>().configureEach {
            sharedTemplateDirectory = project(":internalPerformanceTesting").file("src/templates")
        }
    }

    private
    fun Project.createPrepareSamplesTask(): TaskProvider<Task> =
        tasks.register("prepareSamples") {
            group = "Project Setup"
            description = "Generates all sample projects for automated performance tests"
            dependsOn(tasks.withType<ProjectGeneratorTask>())
            dependsOn(tasks.withType<RemoteProject>())
            dependsOn(tasks.withType<JavaExecProjectGeneratorTask>())
        }

    private
    fun Project.createCleanSamplesTask() =
        tasks.register("cleanSamples", Delete::class.java) {
            delete(deferred { tasks.withType<ProjectGeneratorTask>().map { it.outputs } })
            delete(deferred { tasks.withType<RemoteProject>().map { it.outputDirectory } })
            delete(deferred { tasks.withType<JavaExecProjectGeneratorTask>().map { it.outputs } })
        }


    private
    fun Project.createLocalPerformanceTestTasks(
        performanceSourceSet: SourceSet,
        prepareSamplesTask: TaskProvider<Task>
    ) {

        fun create(name: String, configure: PerformanceTest.() -> Unit = {}) {
            createLocalPerformanceTestTask(name, performanceSourceSet, prepareSamplesTask).configure(configure)
        }

        create("performanceTest") {
            (options as JUnitOptions).excludeCategories(performanceExperimentCategory)
        }

        create("performanceExperiment") {
            (options as JUnitOptions).includeCategories(performanceExperimentCategory)
        }

        create("fullPerformanceTest")

        create("performanceAdhocTest") {
            systemProperty(PropertyNames.dbUrl, Config.adhocTestDbUrl)
            channel = "adhoc"
            outputs.doNotCacheIf("Adhoc performance doesn't need cache", { true })
        }
    }

    private
    fun Project.createDistributedPerformanceTestTasks(
        performanceSourceSet: SourceSet,
        prepareSamplesTask: TaskProvider<Task>
    ) {

        fun create(name: String, configure: PerformanceTest.() -> Unit = {}) {
            createDistributedPerformanceTestTask(name, performanceSourceSet, prepareSamplesTask).configure(configure)
        }

        create("distributedPerformanceTest") {
            (options as JUnitOptions).excludeCategories(performanceExperimentCategory)
            channel = "commits"
        }
        create("distributedPerformanceExperiment") {
            (options as JUnitOptions).includeCategories(performanceExperimentCategory)
            channel = "experiments"
        }
        create("distributedFullPerformanceTest") {
            baselines = Config.baseLineList.toString()
            checks = "none"
            channel = "historical"
        }
    }

    private
    fun Project.configureIdePlugins(performanceTestSourceSet: SourceSet) {
        val performanceTestCompile by configurations
        val performanceTestRuntime by configurations
        plugins.withType<EclipsePlugin> {
            configure<EclipseModel> {
                classpath {
                    plusConfigurations.apply {
                        add(performanceTestCompile)
                        add(performanceTestRuntime)
                    }
                }
            }
        }

        plugins.withType<IdeaPlugin> {
            configure<IdeaModel> {
                module {
                    testSourceDirs = testSourceDirs + performanceTestSourceSet.groovy.srcDirs
                    testSourceDirs = testSourceDirs + performanceTestSourceSet.resources.srcDirs
                    scopes["TEST"]!!["plus"]!!.apply {
                        add(performanceTestCompile)
                        add(performanceTestRuntime)
                    }
                }
            }
        }
    }

    private
    fun Project.createDistributedPerformanceTestTask(
        name: String,
        performanceSourceSet: SourceSet,
        prepareSamplesTask: TaskProvider<Task>
    ): TaskProvider<DistributedPerformanceTest> {

        val result = tasks.register(name, DistributedPerformanceTest::class.java) {
            configureForAnyPerformanceTestTask(this, performanceSourceSet, prepareSamplesTask)
            scenarioList = buildDir / Config.performanceTestScenarioListFileName
            scenarioReport = buildDir / Config.performanceTestScenarioReportFileName
            buildTypeId = stringPropertyOrNull(PropertyNames.buildTypeId)
            workerTestTaskName = stringPropertyOrNull(PropertyNames.workerTestTaskName) ?: "fullPerformanceTest"
            coordinatorBuildId = stringPropertyOrNull(PropertyNames.coordinatorBuildId)
            branchName = stringPropertyOrNull(PropertyNames.branchName)
            teamCityUrl = Config.teamCityUrl
            teamCityUsername = stringPropertyOrNull(PropertyNames.teamCityUsername)
            teamCityPassword = stringPropertyOrNull(PropertyNames.teamCityPassword)
        }

        afterEvaluate {
            result.configure {
                branchName?.takeIf { it.isNotEmpty() }?.let { branchName ->
                    channel = channel + "-" + branchName
                }
            }
        }

        return result
    }

    private
    fun Project.createLocalPerformanceTestTask(
        name: String,
        performanceSourceSet: SourceSet,
        prepareSamplesTask: TaskProvider<Task>
    ): TaskProvider<PerformanceTest> {

        val performanceTest = tasks.register(name, PerformanceTest::class.java) {
            configureForAnyPerformanceTestTask(this, performanceSourceSet, prepareSamplesTask)

            if (project.hasProperty(PropertyNames.performanceTestVerbose)) {
                testLogging.showStandardStreams = true
            }
        }

        val testResultsZipTask = testResultsZipTaskFor(performanceTest, name)

        performanceTest.configure {
            finalizedBy(testResultsZipTask)
        }

        // TODO: Make this lazy, see https://github.com/gradle/gradle-native/issues/718
        tasks.getByName("clean${name.capitalize()}") {
            delete(performanceTest)
            dependsOn(testResultsZipTask.map { "clean${it.name.capitalize()}" }) // Avoid realizing because of issue
        }

        return performanceTest
    }

    private
    fun Project.testResultsZipTaskFor(performanceTest: TaskProvider<PerformanceTest>, name: String): TaskProvider<Zip> {
        return tasks.register("${name}ResultsZip", Zip::class.java) {
            val junitXmlDir = performanceTest.get().reports.junitXml.destination
            from(junitXmlDir) {
                include("**/TEST-*.xml")
                includeEmptyDirs = false
                eachFile {
                    try {
                        // skip files where all tests were skipped
                        if (allTestsWereSkipped(file)) {
                            exclude()
                        }
                    } catch (e: Exception) {
                        exclude()
                    }
                }
            }
            from(performanceTest.get().debugArtifactsDirectory)
            destinationDir = buildDir
            archiveName = "test-results-${junitXmlDir.name}.zip"
        }
    }

    private
    fun Project.configureForAnyPerformanceTestTask(
        task: PerformanceTest,
        performanceSourceSet: SourceSet,
        prepareSamplesTask: TaskProvider<Task>
    ) {

        task.apply {
            group = "verification"
            systemProperties(propertiesForPerformanceDb())
            testClassesDirs = performanceSourceSet.output.classesDirs
            classpath = performanceSourceSet.runtimeClasspath

            binaryDistributions.binZipRequired = true
            libsRepository.required = true
            maxParallelForks = 1

            project.findProperty(PropertyNames.baselines)?.let { baselines ->
                systemProperty(PropertyNames.baselines, baselines)
            }

            jvmArgs("-Xmx3g", "-XX:+HeapDumpOnOutOfMemoryError")

            dependsOn(prepareSamplesTask)

            configureReportProperties()
            registerTemplateInputsToPerformanceTest()

            mustRunAfter(tasks.withType<ProjectGeneratorTask>())
            mustRunAfter(tasks.withType<RemoteProject>())
            mustRunAfter(tasks.withType<JavaExecProjectGeneratorTask>())
        }
    }

    private
    fun PerformanceTest.configureReportProperties() {
        reportDir = project.buildDir / Config.performanceTestReportsDir
        channel?.let { channel ->
            systemProperty(PropertyNames.channel, channel)
        }
    }

    private
    fun PerformanceTest.registerTemplateInputsToPerformanceTest() {
        apply {
            val registerInputs: (Task) -> Unit = { prepareSampleTask ->
                val prepareSampleTaskInputs = prepareSampleTask.inputs.properties.mapKeys { entry -> "${prepareSampleTask.name}_${entry.key}" }
                inputs.properties(prepareSampleTaskInputs)
            }
            project.tasks.withType<ProjectGeneratorTask>().forEach(registerInputs)
            project.tasks.withType<RemoteProject>().forEach(registerInputs)
            project.tasks.withType<JavaExecProjectGeneratorTask>().forEach(registerInputs)
        }
    }

    private
    fun Project.propertiesForPerformanceDb(): Map<String, String> =
        selectStringProperties(
            PropertyNames.dbUrl,
            PropertyNames.dbUsername,
            PropertyNames.dbPassword)
}


internal
fun allTestsWereSkipped(junitXmlFile: File): Boolean =
    parseXmlFile(junitXmlFile).documentElement.run {
        getAttribute("tests") == getAttribute("skipped")
    }


private
fun parseXmlFile(file: File): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)


private
fun sha1StringFor(file: File) =
    HashUtil.createHash(file, "sha1").asHexString()
