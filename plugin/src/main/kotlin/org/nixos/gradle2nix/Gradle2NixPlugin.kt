package org.nixos.gradle2nix

import com.squareup.moshi.Moshi
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.kotlin.dsl.withType
import org.gradle.plugin.management.PluginRequest
import org.gradle.plugin.use.internal.PluginDependencyResolutionServices
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.GradleVersion
import java.net.URL
import java.util.*

@Suppress("unused")
open class Gradle2NixPlugin : Plugin<Gradle> {
    override fun apply(gradle: Gradle): Unit = gradle.run {
        val pluginRequests = collectPlugins()

        projectsLoaded {
            val modelProperties = rootProject.loadModelProperties()
            rootProject.serviceOf<ToolingModelBuilderRegistry>()
                .register(NixToolingModelBuilder(modelProperties, pluginRequests))

            rootProject.tasks.registerCompat("nixModel") {
                doLast {
                    val outFile = project.mkdir(project.buildDir.resolve("nix")).resolve("model.json")
                    val model = project.buildModel(modelProperties, pluginRequests)
                    outFile.bufferedWriter().use { out ->
                        out.write(
                            Moshi.Builder().build()
                                .adapter(DefaultBuild::class.java)
                                .indent("  ")
                                .toJson(model)
                        )
                        out.flush()
                    }
                }
            }
        }
    }
}

private fun TaskContainer.registerCompat(name: String, configureAction: Task.() -> Unit) {
    if (GradleVersion.current() >= GradleVersion.version("4.9")) {
        register(name, configureAction)
    } else {
        create(name, configureAction)
    }
}

private class NixToolingModelBuilder(
    private val modelProperties: ModelProperties,
    private val pluginRequests: List<PluginRequest>
) : ToolingModelBuilder {
    override fun canBuild(modelName: String): Boolean {
        return modelName == NIX_MODEL_NAME
    }

    override fun buildAll(modelName: String, project: Project): Build =
        project.buildModel(modelProperties, pluginRequests)
}

private fun Gradle.collectPlugins(): List<PluginRequest> {
    val pluginRequests = mutableListOf<PluginRequest>()
    gradle.settingsEvaluated {
        pluginManagement.resolutionStrategy.eachPlugin {
            if (requested.id.namespace != null && requested.id.namespace != "org.gradle") {
                pluginRequests.add(target)
            }
        }
    }
    return pluginRequests
}

private fun Project.buildModel(
    modelProperties: ModelProperties,
    pluginRequests: List<PluginRequest>
): DefaultBuild {
    val settingsDependencies = settingsDependencies()
    val plugins = buildPlugins(pluginRequests)

    val subprojects = if (modelProperties.subprojects.isEmpty()) {
        project.subprojects
    } else {
        project.subprojects
            .filter { it.path in modelProperties.subprojects }
            // Include dependent subprojects as well
            .flatMap { setOf(it) + it.dependentSubprojects(modelProperties.configurations) }
            .toSet()
    }

    return DefaultBuild(
        gradle = buildGradle(),
        settingsDependencies = settingsDependencies,
        pluginDependencies = plugins,
        rootProject = buildProject(modelProperties.configurations, subprojects, plugins),
        includedBuilds = includedBuilds()
    )
}

@Suppress("UnstableApiUsage")
private fun Project.buildGradle(): DefaultGradle =
    with(tasks.getByName<Wrapper>("wrapper")) {
        DefaultGradle(
            version = gradleVersion,
            type = distributionType.name.toLowerCase(Locale.US),
            url = distributionUrl,
            sha256 = sha256,
            nativeVersion = gradle.gradleHomeDir?.resolve("lib")?.listFiles()
                ?.firstOrNull { f -> nativePlatformJarRegex matches f.name }?.let { nf ->
                    nativePlatformJarRegex.find(nf.name)?.groupValues?.get(1)
                }
                ?: throw IllegalStateException(
                    """
                    Failed to find native-platform jar in ${gradle.gradleHomeDir}.
                    Ask Tad to fix this.
                    """.trimIndent()
                )
        )
    }

private fun Project.settingsDependencies(): List<DefaultArtifact> {
    val buildscript = (gradle as GradleInternal).settings.buildscript

    val resolverFactory = ConfigurationResolverFactory(
        this,
        ConfigurationScope.SETTINGS,
        buildscript.repositories.filterIsInstance<ResolutionAwareRepository>()
    )
    val resolver = resolverFactory.create(buildscript.dependencies)

    logger.lifecycle("    Settings script")

    val dependencies = buildscript.configurations
        .flatMap(resolver::resolve)
        .distinct()
        .sorted()

    if (resolver.unresolved.isNotEmpty()) {
        logger.warn(buildString {
            append("        Failed to resolve settings script dependencies:\n")
            for (id in resolver.unresolved) {
                append("        - $id\n")
            }
        })
    }

    return dependencies
}

private fun Project.buildPlugins(pluginRequests: List<PluginRequest>): List<DefaultArtifact> {
    return objects.newInstance<PluginResolver>(this).resolve(pluginRequests).distinct().sorted()
}

private fun Project.includedBuilds(): List<DefaultIncludedBuild> =
    gradle.includedBuilds.map {
        DefaultIncludedBuild(it.name, it.projectDir.toRelativeString(project.projectDir))
    }

private fun Project.buildProject(
    explicitConfigurations: List<String>,
    explicitSubprojects: Collection<Project>,
    pluginArtifacts: List<DefaultArtifact>
): DefaultProject {
    logger.lifecycle("    Subproject: $path")

    val (buildscriptDependencies, buildscriptUnresolved) = buildscriptDependencies(pluginArtifacts)

    if (buildscriptUnresolved.isNotEmpty()) {
        logger.warn(buildString {
            append("        Failed to resolve buildscript dependencies:\n")
            for (id in buildscriptUnresolved) {
                append("        - $id\n")
            }
        })
    }

    val (projectDependencies, projectUnresolved) = projectDependencies(explicitConfigurations)

    if (projectUnresolved.isNotEmpty()) {
        logger.warn(buildString {
            append("        Failed to resolve project dependencies:\n")
            for (id in projectUnresolved) {
                append("        - $id\n")
            }
        })
    }

    return DefaultProject(
        name = name,
        version = version.toString(),
        path = path,
        projectDir = projectDir.toRelativeString(rootProject.projectDir),
        buildscriptDependencies = buildscriptDependencies,
        projectDependencies = projectDependencies,
        children = explicitSubprojects.map {
            it.buildProject(explicitConfigurations, emptyList(), pluginArtifacts)
        }
    )
}

private fun Project.buildscriptDependencies(
    pluginArtifacts: List<DefaultArtifact>
): Pair<List<DefaultArtifact>, List<ArtifactIdentifier>> {
    val explicitRepositories = buildscript.repositories.filterIsInstance<ResolutionAwareRepository>()

    val builtinRepositories = serviceOf<PluginDependencyResolutionServices>()
        .resolveRepositoryHandler
        .filterIsInstance<ResolutionAwareRepository>()

    val resolverFactory = ConfigurationResolverFactory(
        this,
        ConfigurationScope.BUILDSCRIPT,
        (explicitRepositories + builtinRepositories).distinct()
    )
    val resolver = resolverFactory.create(buildscript.dependencies)
    val pluginIds = pluginArtifacts.map(DefaultArtifact::id)
    return buildscript.configurations
        .flatMap(resolver::resolve)
        .distinct()
        .filter { it.id !in pluginIds }
        .sorted() to resolver.unresolved
}

private fun Project.projectDependencies(
    explicitConfigurations: List<String>
): Pair<List<DefaultArtifact>, List<ArtifactIdentifier>> {
    val resolver = ConfigurationResolverFactory(
        this,
        ConfigurationScope.PROJECT,
        RepositoriesCollector.create(project).collectRepositories()
    ).create(dependencies)
    return collectConfigurations(explicitConfigurations)
        .flatMap(resolver::resolve)
        .distinct()
        .sorted() to resolver.unresolved
}

private fun Project.dependentSubprojects(explicitConfigurations: List<String>): Set<Project> {
    return collectConfigurations(explicitConfigurations)
        .flatMap { it.allDependencies.withType<ProjectDependency>() }
        .map { it.dependencyProject }
        .toSet()
        .flatMap { setOf(it) + it.dependentSubprojects(explicitConfigurations) }
        .toSet()
}

private fun Project.collectConfigurations(
    explicitConfigurations: List<String>
): Set<Configuration> {
    return if (explicitConfigurations.isEmpty()) {
        configurations.filter { it.isCanBeResolved }.toSet()
    } else {
        configurations.filter { it.name in explicitConfigurations }.toSet()
    }
}

private fun fetchDistSha256(url: String): String {
    return URL("$url.sha256").openConnection().run {
        connect()
        getInputStream().reader().use { it.readText() }
    }
}

private val nativePlatformJarRegex = Regex("""native-platform-([\d.]+(-(alpha|beta|milestone)-\d+)?)\.jar""")

private val Wrapper.sha256: String
    get() {
        return if (GradleVersion.current() < GradleVersion.version("4.5")) {
            fetchDistSha256(distributionUrl)
        } else {
            @Suppress("UnstableApiUsage")
            distributionSha256Sum ?: fetchDistSha256(distributionUrl)
        }
    }

private const val NIX_MODEL_NAME = "org.nixos.gradle2nix.Build"
