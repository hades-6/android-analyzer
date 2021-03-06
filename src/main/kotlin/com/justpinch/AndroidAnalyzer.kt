package com.justpinch

import groovy.json.JsonSlurper
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import okhttp3.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.sonarqube.gradle.SonarQubeExtension

/**
 * External interface of the plugin.
 */
open class Params {

    lateinit var applicationId: String
    lateinit var projectName: String

    var projectKeySuffix = Default.projectKeySuffix
    var projectVersion = Default.projectVersion
    var projectVisibility = Default.projectVisibility

    var sonarqubeUsername: String = System.getenv(usernameEnvKey) ?: Default.sonarqubeUsername
    var sonarqubePassword: String = System.getenv(passwordEnvKey) ?: Default.sonarqubePassword
    var sonarqubeToken: String? = System.getenv(tokenEnvKey)
    var sonarqubeAuth: Boolean = Default.sonarqubeAuth
    var sonarqubeGitBranches: Boolean = System.getenv(branchesEnvKey)?.equals("true") ?: Default.sonarqubeGitBranches

    var serverUrl = System.getenv(serverUrlEnvKey) ?: Default.serverUrl

    var buildBreaker = Default.buildBreaker
    var buildBreakerTimeout = Default.buildBreakerTimeout

    var useDefaultExclusions = true
    var customExclusions = emptyList<String>()

    val exclusions: String
        get() {
            val all = if (useDefaultExclusions) {
                customExclusions + Default.exclusions
            } else {
                customExclusions
            }

            return all.joinToString(",")
        }

    var detekt: Boolean = Default.detekt
    var detektBaseline: String? = null
    var detektConfigFileName = Default.detektConfigFileName
    val detektReportsPath = Default.detektReportsPath

    var unitTestCoverage = Default.unitTestCoverage
    val testCoveragePlugin = Default.testCoveragePlugin

    var buildVariant: String? = null

    val projectKey: String
        get() = "$applicationId$projectKeySuffix"

    val testBuildFlavor: String
        get() = "test${buildVariant?.capitalize()}UnitTest"

    val testJacocoReportsPath: String
        get() = "build/jacoco/$testBuildFlavor.exec"

    val testJunitReportsPath: String
        get() = "build/test-results/$testBuildFlavor"

    var packageName: String? = null

    private val packageDirs: String
        get() = (packageName ?: "").replace(".", "/")

    val javaClassDirs: String
        get() = "build/intermediates/javac/$buildVariant/compileDebugJavaWithJavac/classes/$packageDirs"

    val kotlinClassDirs: String
        get() = "build/tmp/kotlin-classes/$buildVariant/$packageDirs"

    val sourceDirs: String
        get() = "src/main/java/$packageDirs"

    fun validate() {
        if (!::applicationId.isInitialized || !::projectName.isInitialized) {
            fail("""
                    applicationId and projectName properties must be specified in your project-level build.gradle file
                    E.g.
                    androidAnalyzer {
                        applicationId = 'nl.pinch.myproject'
                        projectName = 'My Project'
                    }
                """
            )
        }

        if (unitTestCoverage) {
            if (buildVariant == null) {
                fail("AndroidAnalyzer: buildVariant must be specified when unitTestCoverage = true")
            }

            if (packageName == null) {
                fail("AndroidAnalyzer: packageName must be specified when unitTestCoverage = true")
            }
        }
    }

    companion object Default {

        /**
         * Default Sonarqube username
         */
        private const val sonarqubeUsername = "admin"

        /**
         * Default Sonarqube password
         */
        private const val sonarqubePassword = "admin"

        /**
         * Default git branch detection strategy
         */
        private const val sonarqubeGitBranches = false

        /**
         * Default sonarqube auth strategy.
         * When set to true, the plugin will automatically try to authorize with the given credentials.
         * When set to false, the plugin will not try to authorize in any way.
         */
        private const val sonarqubeAuth = true

        /**
         * Default Sonarqube project version
         */
        private const val projectKeySuffix = "-android"

        /**
         * Default Sonarqube project version
         */
        private const val projectVersion = "undefined"

        /**
         * Default Sonarqube project visibility
         */
        private const val projectVisibility = "private"

        /**
         * Default Sonarqube server URL
         */
        private const val serverUrl = "http://localhost:9000"

        /**
         * Fail the build if quality gate does not pass
         */
        private const val buildBreaker = false

        /**
         * Max polling time in seconds for builds requiring build breaker capability
         */
        private const val buildBreakerTimeout = 180

        /**
         * Default Detekt analysis enabled/disabled (Kotlin only)
         */
        private const val detekt = false

        /**
         * Default detekt report path
         */
        private const val detektReportsPath = "build/reports/detekt/detekt.xml"

        /**
         * Default detekt config file name
         */
        private const val detektConfigFileName = "detekt-config.yml"

        /**
         * Default test coverage enabled/disabled
         */
        private const val unitTestCoverage = false

        /**
         * Default code coverage plugin
         */
        private const val testCoveragePlugin = "jacoco"

        /**
         * Default paths excluded from code analysis and coverage reports
         */
        val exclusions = listOf(
                "**/databinding/**/*.*",
                "**/android/databinding/*Binding.*",
                "**/BR.*",
                "**/R.*",
                "**/R$*.*",
                "**/BuildConfig.*",
                "**/Manifest*.*",
                "**/*_MembersInjector.*",
                "**/Dagger*Component.*",
                "**/Dagger*Component\$Builder.*",
                "**/*Module_*Factory.*",
                "**/*Module*.*",
                "**/*apollo*.*",
                "**/*.xml",
                "**/*.html",
                "**/*.css",
                "**/res/**/*",
                "**/assets/**/*"
        )

        /**
         * Sonarqube username environment variable
         */
        private const val usernameEnvKey = "ANDROID_ANALYZER_SONARQUBE_USERNAME"

        /**
         * Sonarqube password environment variable
         */
        private const val passwordEnvKey = "ANDROID_ANALYZER_SONARQUBE_PASSWORD"

        /**
         * Sonarqube token environment variable
         */
        private const val tokenEnvKey = "ANDROID_ANALYZER_SONARQUBE_TOKEN"

        /**
         * Sonarqube server URL environment variable
         */
        private const val serverUrlEnvKey = "ANDROID_ANALYZER_SONARQUBE_URL"

        /**
         * Automatic git branch detection toggle environment variable
         */
        private const val branchesEnvKey = "ANDROID_ANALYZER_SONARQUBE_BRANCHES"
    }
}

class AndroidAnalyzer : Plugin<Project> {

    private lateinit var params: Params

    private lateinit var token: String

    private val buildString: String by lazy { generateRandomString() }

    override fun apply(project: Project) {
        project.apply {
            it.plugin("org.sonarqube")
            it.plugin("jacoco")
            it.plugin("io.gitlab.arturbosch.detekt")
        }

        (project.extensions.findByName("jacoco") as JacocoPluginExtension).apply {
            this.toolVersion = "0.8.3"
        }

        params = project.extensions.create(ExtensionName, Params::class.java)

        project.afterEvaluate { proj ->
            params.validate()

            /**
             * Task for registering a new sonarqube project.
             * Does nothing if the project already exists.
             */
            proj.tasks.register(TaskSonarqubeProjectRegister) {
                it.outputs.upToDateWhen { false }
                it.group = TaskGroup
                it.description = "Creates a Sonarqube project"

                it.doLast {
                    val body = FormBody.Builder()
                            .add("project", params.projectKey)
                            .add("name", params.projectName)
                            .add("visibility", params.projectVisibility)
                            .build()

                    val request = Request.Builder()
                            .url("${params.serverUrl}/api/projects/create")
                            .post(body)
                            .build()

                    val client = if (params.sonarqubeAuth) tokenOkHttpClient(token) else unauthenticatedOkHttpClient()
                    val response = client.newCall(request).execute()

                    if (response.isSuccessful) {
                        println("Project successfully created")
                    } else {
                        println("ERROR ${response.code()}")
                        println(response.body()?.string())
                    }

                    response.close()
                }
            }

            /**
             * Task for obtaining Sonarqube user token.
             *
             * If a token is provided using an environment variable, uses the provided token.
             * Otherwise, the old token (if any) is revoked and a new one is generated.
             */
            proj.tasks.register(TaskSonarqubeAuth) {
                it.outputs.upToDateWhen { false }
                it.group = TaskGroup
                it.description = "Generates Sonarqube user token"

                it.doLast {
                    /** No need for auth if it is disabled */
                    if (!params.sonarqubeAuth) {
                        return@doLast
                    }

                    /** If Sonarqube token was provided, use it for authorization */
                    params.sonarqubeToken?.let { envToken ->
                        token = envToken
                        return@doLast
                    }

                    val revokeBody = FormBody.Builder()
                            .add("name", TokenName)
                            .build()

                    val revokeRequest = Request.Builder()
                            .url("${params.serverUrl}/api/user_tokens/revoke")
                            .post(revokeBody)
                            .build()

                    passwordOkHttpClient().newCall(revokeRequest).execute().close()

                    val body = FormBody.Builder()
                            .add("name", TokenName)
                            .build()

                    val request = Request.Builder()
                            .url("${params.serverUrl}/api/user_tokens/generate")
                            .post(body)
                            .build()

                    val response = passwordOkHttpClient().newCall(request).execute()

                    if (response.isSuccessful) {
                        token = response.extractToken()
                        println("Token successfully extracted")
                    } else {
                        fail("Could not fetch user token")
                    }

                    response.close()
                }
            }

            /**
             * Configures a JacocoReport task.
             * Runs unit tests with coverage and generates xml and html reports.
             * Only gets registered if unitTestCoverage is set to true.
             */
            if (params.unitTestCoverage) {
                proj.tasks.register(TaskUnitTestJacoco, JacocoReport::class.java) {
                    it.outputs.upToDateWhen { false }
                    it.setDependsOn(listOf(params.testBuildFlavor))
                    it.group = TaskGroup
                    it.description = "Runs unit tests and generates Jacoco coverage report"

                    it.reports { report ->
                        report.xml.isEnabled = true
                        report.html.isEnabled = true
                    }

                    val javaClassDirs = proj.fileTree(params.javaClassDirs).matching {
                        it.exclude(params.exclusions)
                    }

                    val kotlinClassDirs = proj.fileTree(params.kotlinClassDirs).matching {
                        it.exclude(params.exclusions)
                    }

                    it.classDirectories = proj.files(javaClassDirs, kotlinClassDirs)
                    it.sourceDirectories = proj.files(params.sourceDirs)
                    it.executionData = proj.files(params.testJacocoReportsPath)

                    it.doLast { task ->
                        (task as JacocoReport).generate()
                    }
                }
            }

            /**
             * Configures sonarqube extension.
             */
            proj.tasks.register(TaskSonarqubeConfig) {
                it.outputs.upToDateWhen { false }
                it.group = TaskGroup
                it.description = "Configures Sonarqube inspection"

                it.doLast {
                    (proj.extensions.findByName("sonarqube") as SonarQubeExtension).apply {
                        this.properties { props ->
                            // project settings
                            props.property("sonar.projectKey", params.projectKey)
                            props.property("sonar.projectName", params.projectName)
                            props.property("sonar.projectVersion", params.projectVersion)

                            // server settings
                            if (params.sonarqubeAuth) {
                                props.property("sonar.login", token)
                            }
                            props.property("sonar.host.url", params.serverUrl)

                            // analysis exclusion settings
                            props.property("sonar.exclusions", params.exclusions)
                            props.property("sonar.coverage.customExclusions", params.exclusions)

                            // sonarqube branch settings
                            if (params.sonarqubeGitBranches) {
                                proj.gitBranchName()?.let { name -> props.property("sonar.branch.name", name) }
                            }

                            // test coverage settings
                            if (params.unitTestCoverage) {
                                props.property("sonar.java.coveragePlugin", params.testCoveragePlugin)
                                props.property("sonar.jacoco.reportPaths", params.testJacocoReportsPath)
                                props.property("sonar.junit.reportsPath", params.testJunitReportsPath)
                                props.property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/$TaskUnitTestJacoco/$TaskUnitTestJacoco.xml")
                            }

                            // detekt settings
                            if (params.detekt) {
                                props.property("sonar.kotlin.detekt.reportPaths", params.detektReportsPath)
                            }

                            // build breaker settings
                            if (params.buildBreaker) {
                                props.property("sonar.buildString", buildString)
                            }
                        }
                    }
                }
            }

            /**
             * Configures detekt extension.
             */
            proj.tasks.register(TaskDetektConfig) {
                it.outputs.upToDateWhen { false }
                it.group = TaskGroup
                it.description = "Configures and runs Detekt inspection"

                it.doLast {
                    // create a detekt configuration file if not present
                    val configFile = proj.file(params.detektConfigFileName)

                    if (!configFile.exists()) {
                        configFile.writeText(DetektConfig)
                    }

                    (proj.extensions.findByName("detekt") as DetektExtension).apply {
                        this.config = proj.files(params.detektConfigFileName)
                        params.detektBaseline?.let { baseline -> this.baseline = proj.file(baseline) }
                    }
                }
            }

            /**
             * Polls Sonarqube API for quality gate results
             */
            proj.tasks.register(TaskSonarqubePollAnalysis) {
                it.outputs.upToDateWhen { false }
                it.group = TaskGroup
                it.description = "Polls for the latest project analysis"

                it.doLast {
                    fun tryGetAnalysisId(buildString: String): String? {
                        var url = "${params.serverUrl}/api/project_analyses/search?project=${params.projectKey}&ps=20"

                        if (params.sonarqubeGitBranches) {
                            url += "&branch=${proj.gitBranchName()}"
                        }

                        val request = Request.Builder()
                                .url(url)
                                .apply {
                                    if (params.sonarqubeAuth) {
                                        authenticated(token)
                                    }
                                }
                                .build()

                        val client = unauthenticatedOkHttpClient()

                        return client.execute(request, "Could not extract analysis id with buildString: $buildString") {
                            extractAnalysisId(buildString)
                        }
                    }

                    fun isQualityGateSuccessful(analysisId: String): Boolean {
                        val request = Request.Builder()
                                .url("${params.serverUrl}/api/qualitygates/project_status?analysisId=$analysisId")
                                .apply {
                                    if (params.sonarqubeAuth) {
                                        authenticated(token)
                                    }
                                }
                                .build()

                        val client = unauthenticatedOkHttpClient()

                        return client.execute(request, "Could not retrieve analysis by id: $analysisId") {
                            extractAnalysisQualityGateStatus()
                        }
                    }

                    val pollingIntervalSeconds = 5
                    val maxAttempts = params.buildBreakerTimeout / pollingIntervalSeconds
                    var analysisId: String? = null
                    var count = 0
                    while (analysisId == null) {
                        if (++count > maxAttempts) {
                            fail("Sonarqube analysis polling timed out. Try increasing buildBreakerTimeout parameter.")
                        }

                        Thread.sleep(pollingIntervalSeconds * 1000L)
                        analysisId = tryGetAnalysisId(buildString)
                    }

                    if (!isQualityGateSuccessful(analysisId)) {
                        fail("Sonarqube Quality gate failed. Please check the discovered issues and fix them at ${params.serverUrl}.")
                    }
                }
            }

            /**
             * Run AndroidAnalyzer plugin without coverage
             */
            proj.tasks.register(TaskAndroidAnalyzer) {
                it.outputs.upToDateWhen { false }
                it.group = TaskGroup
                it.description = "Runs AndroidAnalyzer plugin"

                it.setDependsOn(mutableListOf<Task>().apply {
                    add(proj.findTask(TaskSonarqube))

                    if (params.buildBreaker) {
                        add(proj.findTask(TaskSonarqubePollAnalysis))
                    }
                })
            }

            /**
             * Prints a list of default exclusions
             */
            proj.tasks.register(TaskDefaultExclusions) {
                it.outputs.upToDateWhen { false }
                it.group = TaskGroup
                it.description = "Prints a list of default coverage and analysis exclusions"

                it.doLast {
                    println("Android Analyzer default exclusions:")
                    println("------------------------------------")
                    Params.exclusions.forEach { exclusion ->
                        println(exclusion)
                    }
                    println("------------------------------------")
                }
            }

            /**
             * Prints a default detekt configuration file
             */
            proj.tasks.register(TaskDefaultDetektConfig) {
                it.outputs.upToDateWhen { false }
                it.group = TaskGroup
                it.description = "Prints a default detekt configuration file"

                it.doLast {
                    println("Android Analyzer default detekt config:")
                    println("------------------------------------")
                    println(DetektConfig)
                    println("------------------------------------")
                }
            }

            proj.findTask(TaskSonarqube).dependsOn(mutableListOf<Task>().apply {
                add(proj.findTask(TaskSonarqubeConfig))

                if (params.unitTestCoverage) {
                    add(proj.findTask(TaskUnitTestJacoco))
                }

                if (params.detekt) {
                    add(proj.findTask(TaskDetekt))
                }
            })

            proj.findTask(TaskDetekt).dependsOn(mutableListOf<Task>(proj.findTask(TaskDetektConfig)))

            proj.findTask(TaskSonarqubeConfig).dependsOn(mutableListOf<Task>().apply {
                add(proj.findTask(TaskSonarqubeProjectRegister))
            })

            proj.findTask(TaskSonarqubeProjectRegister).dependsOn(mutableListOf<Task>().apply {
                add(proj.findTask(TaskSonarqubeAuth))
            })

            proj.findTask(TaskSonarqubePollAnalysis).dependsOn(mutableListOf<Task>().apply {
                add(proj.findTask(TaskSonarqube))
            })
        }
    }

    private fun unauthenticatedOkHttpClient() = OkHttpClient.Builder().build()

    private fun passwordOkHttpClient() =
            OkHttpClient.Builder().authenticator { _, response ->
                val credentials = Credentials.basic(params.sonarqubeUsername, params.sonarqubePassword)
                response.request().newBuilder().header("Authorization", credentials).build()
            }.build()

    private fun tokenOkHttpClient(token: String) =
            OkHttpClient.Builder().authenticator { _, response ->
                val credentials = Credentials.basic(token, "")
                response.request().newBuilder().header("Authorization", credentials).build()
            }.build()

    private fun Request.Builder.authenticated(token: String): Request.Builder {
        return addHeader("Authorization", Credentials.basic(token, ""))
    }

    companion object {

        private const val ExtensionName = "androidAnalyzer"
        private const val TaskGroup = "AndroidAnalyzer"
        private const val TokenName = "AndroidAnalyzerToken"

        private const val TaskAndroidAnalyzer = "androidAnalyzer"
        private const val TaskDefaultExclusions = "androidAnalyzerDefaultExclusions"

        private const val TaskSonarqubeProjectRegister = "androidAnalyzerRegisterProject"
        private const val TaskSonarqubeAuth = "androidAnalyzerRequestAuth"
        private const val TaskUnitTestJacoco = "androidAnalyzerJacoco"
        private const val TaskSonarqubeConfig = "androidAnalyzerSonarqubeConfig"
        private const val TaskDetektConfig = "androidAnalyzerDetektConfig"
        private const val TaskDefaultDetektConfig = "androidAnalyzerDefaultDetektConfig"

        private const val TaskSonarqubePollAnalysis = "androidAnalyzerPollAnalysis"

        private const val TaskSonarqube = "sonarqube"
        private const val TaskDetekt = "detekt"
    }
}

/**
 * Extract user token from sonarqube authentication response
 */
private fun Response.extractToken() = (JsonSlurper().parseText(body()?.string()) as Map<*, *>)["token"] as String

/**
 * Extract id of the analysis with a given [buildString]
 */
private fun Response.extractAnalysisId(buildString: String): String? {
    val list = (JsonSlurper().parseText(body()?.string()) as Map<*, *>)["analyses"] as List<*>

    list.forEach { analysis ->
        val json = analysis as Map<String, *>
        val analysisBuildString = json["buildString"]

        if (buildString == analysisBuildString) {
            return json["key"] as String
        }
    }

    return null
}

/**
 * Extract analysis status, returns [true] if status is "OK"
 */
private fun Response.extractAnalysisQualityGateStatus(): Boolean {
    val projectStatus = (JsonSlurper().parseText(body()?.string()) as Map<*, *>)["projectStatus"] as Map<String, *>
    val analysisStatus = projectStatus["status"] as String

    return analysisStatus == "OK"
}

private fun Project.findTask(taskName: String) = tasks.findByName(taskName)!!