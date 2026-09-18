import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    base
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.sqldelight) apply false
}

abstract class SourceFormatCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val checkedFiles: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @TaskAction
    fun checkFormatting() {
        val root = rootDirectory.get().asFile
        val violations = checkedFiles.files.flatMap { file ->
            buildList {
                val text = file.readText()
                if (text.isNotEmpty() && !text.endsWith('\n')) {
                    add("${file.relativeTo(root)}: missing final newline")
                }
                text.lineSequence().forEachIndexed { index, line ->
                    if (line != line.trimEnd()) {
                        add("${file.relativeTo(root)}:${index + 1}: trailing whitespace")
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString(separator = "\n"))
        }
    }
}

abstract class ArchitectureCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildScripts: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stationTrainPresentationSources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @TaskAction
    fun checkDependencies() {
        val violations = mutableListOf<String>()
        val root = rootDirectory.get().asFile
        val projectDependency = Regex("""project\(\"([^\"]+)\"\)""")

        buildScripts.files.forEach { buildScript ->
            val path = buildScript.relativeTo(root).invariantSeparatorsPath
            val text = buildScript.readText()
            val dependencies = projectDependency.findAll(text).map { it.groupValues[1] }.toSet()

            val productionBlock = Regex("commonMain\\.dependencies\\s*\\{([^}]+)}").find(text)?.groupValues?.get(1).orEmpty()
            if (path != "shared/core/testing/build.gradle.kts" && productionBlock.contains(":shared:core:testing")) {
                violations += "$path production -> test-only fakes"
            }

            if (path == "shared/core/domain/build.gradle.kts") {
                dependencies.filter {
                    it in setOf(
                        ":shared:core:database",
                        ":shared:core:network",
                        ":shared:core:platform",
                        ":shared:core:ui",
                    )
                }.forEach { violations += "domain -> $it" }
                listOf("libs.ktor", "libs.sqldelight", "compose.", "libs.decompose")
                    .filter(text::contains)
                    .forEach { violations += "domain -> $it" }
            }

            if (path.startsWith("shared/feature/")) {
                dependencies.filter {
                    it.startsWith(":shared:provider:") || it == ":shared:core:database"
                }.forEach { violations += "$path -> $it" }
            }

            if (path == "shared/core/ui/build.gradle.kts") {
                dependencies.filter { it.startsWith(":shared:provider:") }
                    .forEach { violations += "core:ui -> $it" }
            }

            if (path.startsWith("shared/provider/")) {
                dependencies.filter { it.startsWith(":shared:feature:") }
                    .forEach { violations += "$path -> $it" }
            }
        }

        stationTrainPresentationSources.files.forEach { source ->
            val path = source.relativeTo(root).invariantSeparatorsPath
            val text = source.readText()
            if (path.endsWith(".kt") && Regex("""it\.danielebufarini\.trenify\.journey\.""").containsMatchIn(text)) {
                violations += "$path Station/Train presentation -> Android Journey presentation"
            }
            Regex("""\b[Jj]ourney""").find(text)?.let { match ->
                violations += "$path Station/Train presentation -> iOS Journey-owned identifier ${match.value}"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException("Forbidden dependencies:\n${violations.sorted().joinToString("\n")}")
        }
    }
}

abstract class ResourceParityCheck : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirectory: DirectoryProperty

    @TaskAction
    fun checkParity() {
        fun load(name: String): Map<String, String> {
            val file = resDirectory.file("$name/strings.xml").get().asFile
            require(file.isFile) { "Missing $name/strings.xml" }
            val db = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val doc = db.parse(file)
            val out = mutableMapOf<String, String>()
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as org.w3c.dom.Element
                out[node.getAttribute("name")] = node.textContent.trim()
            }
            return out
        }
        val en = load("values")
        val it = load("values-it")
        val missingInIt = (en.keys - it.keys).sorted()
        val missingInEn = (it.keys - en.keys).sorted()
        val empty = (en + it).filterValues { value -> value.isEmpty() }.keys.sorted()
        val spec = Regex("%(\\d+)\\$([a-z])")
        val placeholderMismatches = en.keys.intersect(it.keys).mapNotNull { key ->
            val a = spec.findAll(en.getValue(key)).map { match -> match.value }.toList()
            val b = spec.findAll(it.getValue(key)).map { match -> match.value }.toList()
            if (a != b) "$key: en=$a it=$b" else null
        }.sorted()
        val problems = buildList {
            if (missingInIt.isNotEmpty()) add("Missing in values-it: $missingInIt")
            if (missingInEn.isNotEmpty()) add("Missing in values: $missingInEn")
            if (empty.isNotEmpty()) add("Empty values: $empty")
            if (placeholderMismatches.isNotEmpty()) add("Placeholder mismatches: $placeholderMismatches")
        }
        println("Resource parity: ${en.size} default / ${it.size} Italian keys checked.")
        if (problems.isNotEmpty()) throw GradleException("Resource parity failures:\n${problems.joinToString("\n")}")
    }
}

/**
 * T8.12: EN/IT parity for the Android-owned presentation resources
 * (`androidApp/src/main/res`), which split strings across one file per
 * feature instead of a single strings.xml. Covers <string> keysets,
 * empties and printf-placeholder parity plus <plurals> name/quantity
 * parity. R.string references themselves are compile-checked; this task
 * guards the locale resources those references resolve against.
 */
abstract class AndroidResourceParityCheck : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirectory: DirectoryProperty

    @TaskAction
    fun checkParity() {
        fun load(dir: String): Triple<Map<String, String>, Map<String, List<String>>, Map<String, String>> {
            val strings = mutableMapOf<String, String>()
            val plurals = mutableMapOf<String, List<String>>()
            val pluralItems = mutableMapOf<String, String>()
            val folder = resDirectory.dir(dir).get().asFile
            require(folder.isDirectory) { "Missing $dir" }
            val db = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            folder.listFiles { file -> file.extension == "xml" }!!.sorted().forEach { file ->
                val doc = db.parse(file)
                val nodes = doc.getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as org.w3c.dom.Element
                    val name = node.getAttribute("name")
                    require(!strings.containsKey(name)) { "Duplicate string $name in $dir" }
                    strings[name] = node.textContent.trim()
                }
                val pluralNodes = doc.getElementsByTagName("plurals")
                for (i in 0 until pluralNodes.length) {
                    val node = pluralNodes.item(i) as org.w3c.dom.Element
                    val name = node.getAttribute("name")
                    require(!plurals.containsKey(name)) { "Duplicate plurals $name in $dir" }
                    val quantities = mutableListOf<String>()
                    val items = node.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as org.w3c.dom.Element
                        quantities += item.getAttribute("quantity")
                        pluralItems["$name[${item.getAttribute("quantity")}]"] = item.textContent.trim()
                    }
                    plurals[name] = quantities.sorted()
                }
            }
            return Triple(strings, plurals, pluralItems)
        }
        val (en, enPlurals, enItems) = load("values")
        val (it, itPlurals, itItems) = load("values-it")
        val spec = Regex("%(\\d+)\\$([a-z])")
        fun placeholders(value: String): List<String> =
            spec.findAll(value).map { match -> match.value }.toList()
        val problems = buildList {
            addAll((en.keys - it.keys).sorted().map { "Missing string in values-it: $it" })
            addAll((it.keys - en.keys).sorted().map { "Missing string in values: $it" })
            addAll((en + it).filterValues { value -> value.isEmpty() }.keys.sorted().map { "Empty string: $it" })
            addAll(
                en.keys.intersect(it.keys).mapNotNull { key ->
                    val a = placeholders(en.getValue(key))
                    val b = placeholders(it.getValue(key))
                    if (a != b) "Placeholder mismatch $key: en=$a it=$b" else null
                }.sorted(),
            )
            addAll((enPlurals.keys - itPlurals.keys).sorted().map { "Missing plurals in values-it: $it" })
            addAll((itPlurals.keys - enPlurals.keys).sorted().map { "Missing plurals in values: $it" })
            addAll(
                enPlurals.keys.intersect(itPlurals.keys).mapNotNull { key ->
                    if (enPlurals.getValue(key) != itPlurals.getValue(key)) {
                        "Quantity mismatch $key: en=${enPlurals.getValue(key)} it=${itPlurals.getValue(key)}"
                    } else {
                        null
                    }
                }.sorted(),
            )
            addAll(
                enItems.keys.intersect(itItems.keys).mapNotNull { key ->
                    val a = placeholders(enItems.getValue(key))
                    val b = placeholders(itItems.getValue(key))
                    if (a != b) "Plural placeholder mismatch $key: en=$a it=$b" else null
                }.sorted(),
            )
        }
        println("Android resource parity: ${en.size} strings + ${enPlurals.size} plurals per locale checked.")
        if (problems.isNotEmpty()) throw GradleException("Android resource parity failures:\n${problems.joinToString("\n")}")
    }
}

tasks.register<AndroidResourceParityCheck>("verifyAndroidResourceParity") {
    group = "verification"
    description = "Asserts EN/IT parity for Android-owned presentation resources (T8.12)."
    resDirectory.set(layout.projectDirectory.dir("androidApp/src/main/res"))
}

/**
 * T8.12: EN/IT parity for the iOS-owned String Catalogs plus a
 * statically-checked reference guard: every production SwiftUI-visible
 * localization key referenced through the established patterns must
 * resolve in both locales. Covered patterns (documented limitation: a
 * future pattern outside this list needs extending here):
 * - Text("key", tableName:) / NSLocalizedString("key", tableName:)
 *   with a literal key;
 * - savedString/settingsString/stationTrainString/alertsString/
 *   monitoringString/journeyString/journeyText/
 *   railwayPresentationString("shortKey") helpers;
 * - String(localized:) default-table keys;
 * - any other dotted lowercase literal must either resolve in some
 *   catalog or be an explicitly listed non-localized literal
 *   (SF Symbols returned as tone/symbol strings, not systemName: sites).
 */
abstract class IosCatalogParityCheck : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iosAppDirectory: DirectoryProperty

    @TaskAction
    fun checkParity() {
        val slurper = groovy.json.JsonSlurper()
        data class Entry(val table: String, val value: String)
        val catalogKeys = mutableMapOf<String, MutableList<Entry>>()
        var catalogCount = 0
        var keyCount = 0
        val problems = mutableListOf<String>()
        iosAppDirectory.get().asFile.walkTopDown()
            .filter { file -> file.isFile && file.extension == "xcstrings" }
            .sortedBy { file -> file.invariantSeparatorsPath }
            .forEach { file ->
                catalogCount++
                val table = file.nameWithoutExtension
                @Suppress("UNCHECKED_CAST")
                val parsed = slurper.parse(file) as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val strings = parsed["strings"] as? Map<String, Any?> ?: emptyMap()
                keyCount += strings.size
                strings.forEach { (key, raw) ->
                    @Suppress("UNCHECKED_CAST")
                    val localizations = ((raw as? Map<String, Any?>)?.get("localizations") as? Map<String, Any?>)
                        ?: emptyMap()
                    listOf("en", "it").forEach { locale ->
                        @Suppress("UNCHECKED_CAST")
                        val unit = ((localizations[locale] as? Map<String, Any?>)?.get("stringUnit") as? Map<String, Any?>)
                        val value = unit?.get("value") as? String
                        if (value.isNullOrEmpty()) problems += "${file.name}: $key missing/empty $locale"
                    }
                    catalogKeys.getOrPut(key) { mutableListOf() } += Entry(table, file.name)
                }
            }
        require(catalogCount > 0) { "No .xcstrings catalogs found" }
        // Reference guard over production Swift (previews excluded; test
        // hosts live outside iosApp/iosApp and are intentionally uncovered).
        val helpers = mapOf(
            "savedString" to "sv.",
            "settingsString" to "se.",
            "stationTrainString" to "st.",
            "alertsString" to "al.",
            "monitoringString" to "mon.",
            "journeyString" to "",
            "journeyText" to "",
            "railwayPresentationString" to "",
        )
        val referenced = mutableSetOf<String>()
        val swiftRoots = iosAppDirectory.dir("iosApp").get().asFile
        swiftRoots.walkTopDown()
            .filter { file -> file.isFile && file.extension == "swift" && "Preview Content" !in file.path }
            .sortedBy { file -> file.invariantSeparatorsPath }
            .forEach { file ->
                val text = file.readText()
                helpers.forEach { (helper, prefix) ->
                    Regex(Regex.escape(helper) + "\\(\\s*\"([^\"]+)\"").findAll(text).forEach { match ->
                        referenced += prefix + match.groupValues[1]
                    }
                }
                Regex("(?:Text|NSLocalizedString)\\(\\s*\"([^\"]+)\",\\s*tableName:").findAll(text).forEach { match ->
                    referenced += match.groupValues[1]
                }
                Regex("String\\(localized:\\s*\"([^\"]+)\"").findAll(text).forEach { match ->
                    referenced += match.groupValues[1]
                }
                val scrubbed = text.replace(Regex("systemName:\\s*\"[^\"]*\""), "")
                Regex("\"([a-z]+\\.[A-Za-z0-9_.]+)\"").findAll(scrubbed).forEach { match ->
                    referenced += match.groupValues[1]
                }
            }
        // Dotted lowercase literals that are platform symbols rather than
        // localization keys (tone/symbol strings, not systemName: sites).
        val nonLocalized = setOf(
            "arrow.right",
            "checkmark.circle.fill",
            "clock.badge.exclamationmark",
            "xmark.octagon.fill",
            "flag.checkered",
            "exclamationmark.triangle.fill",
            "exclamationmark.triangle",
            "questionmark.circle",
        )
        (referenced - nonLocalized).sorted().forEach { key ->
            if (!catalogKeys.containsKey(key)) problems += "Referenced key resolves in no catalog: $key"
        }
        println("iOS catalog parity: $catalogCount catalogs, $keyCount keys, ${referenced.size} referenced keys checked.")
        if (problems.isNotEmpty()) throw GradleException("iOS catalog parity failures:\n${problems.sorted().joinToString("\n")}")
    }
}

tasks.register<IosCatalogParityCheck>("verifyIosCatalogParity") {
    group = "verification"
    description = "Asserts EN/IT String Catalog parity and key resolution for iOS (T8.12)."
    iosAppDirectory.set(layout.projectDirectory.dir("iosApp"))
}

tasks.register<ResourceParityCheck>("verifyResourceParity") {
    group = "verification"
    description = "Asserts EN/IT Compose string-resource parity for shared UI (T7.14-C)."
    resDirectory.set(layout.projectDirectory.dir("shared/core/ui/src/commonMain/composeResources"))
}

tasks.register<SourceFormatCheck>("formatCheck") {
    group = "verification"
    description = "Checks source and configuration files for basic formatting problems."
    rootDirectory.set(layout.projectDirectory)
    checkedFiles.from(
        fileTree(rootDir) {
            include(
                "androidApp/**/*.kt",
                "androidApp/**/*.kts",
                "shared/**/*.kt",
                "shared/**/*.kts",
                "iosApp/**/*.swift",
                "*.gradle.kts",
                "gradle/*.toml",
            )
            exclude("**/build/**")
        },
    )
}

tasks.register<ArchitectureCheck>("architectureCheck") {
    group = "verification"
    description = "Rejects forbidden Gradle and Station/Train presentation dependency directions."
    rootDirectory.set(layout.projectDirectory)
    buildScripts.from(
        fileTree(layout.projectDirectory.dir("shared")) {
            include("**/build.gradle.kts")
            exclude("**/build/**")
        },
    )
    stationTrainPresentationSources.from(
        fileTree(layout.projectDirectory.dir("androidApp/src/main/kotlin/it/danielebufarini/trenify/stationtrain")) {
            include("**/*.kt")
        },
        fileTree(layout.projectDirectory.dir("iosApp/iosApp/Features/StationTrain")) {
            include("**/*.swift")
        },
    )
}

/**
 * No-legacy-rendering guard (T8.15). Executes the path-aware
 * `iosApp/verify-no-legacy-rendering.py` audit, which rejects shared
 * production Compose rendering and iOS Compose hosting while permitting
 * legitimate Android-owned Compose UI. Python logic lives only in the
 * script; Gradle only invokes it.
 */
tasks.register<Exec>("verifyNoLegacyRendering") {
    group = "verification"
    description = "Rejects shared production Compose rendering and iOS Compose hosting (T8.15 guard)."
    workingDir = layout.projectDirectory.asFile
    commandLine("python3", "iosApp/verify-no-legacy-rendering.py")
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs the compile-time and Android lint checks used by local verification."
    dependsOn("architectureCheck", "verifyNoLegacyRendering", ":androidApp:lintDebug")
}

/**
 * Final client-MVP aggregate coverage (T7.15). Module -> aggregate mapping:
 *
 * - verifyRealtimeAndroid/verifyRealtimeIos ([realtimeTestModules]): model,
 *   domain, platform, viaggiatreno provider, mit-strikes provider, data,
 *   home/strikes/station/favorites/train/monitoring/settings features, app
 *   (incl. shared navigation/restoration/UI-integration suites in `:shared:app`).
 * - verifyJourneyAndroid/verifyJourneyIos ([journeyTestModules]): domain,
 *   platform, journey provider, data, journey feature (incl. journey history),
 *   app (incl. booking component/UI suites).
 * - verifyAll directly: EN/IT resource parity (`verifyResourceParity`),
 *   Android-owned EN/IT parity (`verifyAndroidResourceParity`), iOS
 *   catalog parity + key resolution (`verifyIosCatalogParity`),
 *   no-legacy-rendering architecture guard (`verifyNoLegacyRendering`),
 *   core:network + core:database (`allTests`), database migrations
 *   (`verifySqlDelightMigration`), androidApp unit tests, Android build, iOS
 *   framework link, Android instrumentation (incl. booking handoff and
 *   notification-tap suites).
 * - Intentionally uncovered: core:provider-api, core:ui, core:testing
 *   (no test sources), provider:pricing (deferred, no tests).
 *
 * T7 deterministic verification is local. Remote CI/CD is not part of the
 * current Trenify project workflow and is outside T7 client-MVP scope.
 */
tasks.register("verifyAll") {
    group = "verification"
    description = "Runs the complete local verification suite (requires an Android emulator and an iOS simulator)."
    dependsOn(
        "formatCheck",
        "verifyNoLegacyRendering",
        "verifyAggregateCoverage",
        "verifyResourceParity",
        "verifyAndroidResourceParity",
        "verifyIosCatalogParity",
        "staticAnalysis",
        ":shared:core:database:allTests",
        ":shared:core:database:verifySqlDelightMigration",
        ":shared:core:network:allTests",
        ":shared:app:allTests",
        ":androidApp:testDebugUnitTest",
        ":androidApp:assembleDebug",
        ":shared:app:linkDebugFrameworkIosSimulatorArm64",
        "verifyRealtimeAndroid",
        "verifyRealtimeIos",
        "verifyJourneyAndroid",
        "verifyJourneyIos",
        ":androidApp:connectedDebugAndroidTest",
    )
}

val journeyTestModules = listOf(
    ":shared:core:domain", ":shared:core:platform", ":shared:provider:journey", ":shared:data",
    ":shared:feature:journey", ":shared:app",
)

tasks.register("verifyJourneyAndroid") {
    group = "verification"
    description = "Runs deterministic journey and booking contracts, repositories, domain and components on Android/JVM."
    dependsOn(journeyTestModules.map { "$it:testAndroidHostTest" })
    dependsOn("architectureCheck")
}

tasks.register("verifyJourneyIos") {
    group = "verification"
    description = "Runs the same journey suites and shared KMP component/presentation tests on the iOS simulator."
    dependsOn(journeyTestModules.map { "$it:iosSimulatorArm64Test" })
}

val realtimeTestModules = listOf(
    ":shared:core:model",
    ":shared:core:domain",
    ":shared:core:platform",
    ":shared:provider:viaggiatreno",
    ":shared:provider:mit-strikes",
    ":shared:data",
    ":shared:feature:home",
    ":shared:feature:strikes",
    ":shared:feature:station",
    ":shared:feature:favorites",
    ":shared:feature:train",
    ":shared:feature:monitoring",
    ":shared:feature:settings",
    ":shared:app",
)

tasks.register("verifyRealtimeAndroid") {
    group = "verification"
    description = "Runs deterministic realtime and monitoring tests on Android/JVM."
    dependsOn(realtimeTestModules.map { "$it:testAndroidHostTest" })
}

tasks.register("verifyRealtimeIos") {
    group = "verification"
    description = "Runs the same realtime and monitoring suites plus iOS simulator shared tests."
    dependsOn(realtimeTestModules.map { "$it:iosSimulatorArm64Test" })
}

/**
 * Final aggregate-coverage guard (T7.15, corrected pass). Module identity
 * comes from Gradle project metadata (`subprojects`), never from
 * filesystem-depth parsing, so nested (`:shared:feature:train`) and direct
 * (`:shared:data`, `:shared:app`) shapes are all recognized. Test detection
 * scans each project's own `src` tree for Kotlin files under test source
 * sets. All inputs are plain strings computed at configuration time, keeping
 * the task action configuration-cache compatible.
 */
abstract class AggregateCoverageCheck : DefaultTask() {
    @get:Input
    abstract val coveredModules: ListProperty<String>

    @get:Input
    abstract val testBearingModules: ListProperty<String>

    @get:Input
    abstract val requiredTestBearingModules: ListProperty<String>

    @get:Input
    abstract val verifyAllRequiredTasks: ListProperty<String>

    @get:Input
    abstract val verifyAllDeclaredTasks: ListProperty<String>

    @TaskAction
    fun checkCoverage() {
        val covered = coveredModules.get().toSet()
        val testBearing = testBearingModules.get().toSet()
        // Permanent positive control: these modules must be detected as
        // test-bearing. A detection regression (e.g. depth-sensitive path
        // parsing that drops `:shared:data`/`:shared:app`) fails here.
        val undetected = (requiredTestBearingModules.get().toSet() - testBearing).sorted()
        if (undetected.isNotEmpty()) {
            throw GradleException("Aggregate guard no longer detects test-bearing modules: $undetected.")
        }
        val offenders = (testBearing - covered).sorted()
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Test-bearing modules missing from final aggregates: $offenders. " +
                    "Add them to realtimeTestModules/journeyTestModules or wire them into verifyAll.",
            )
        }
        // verifyAll must keep owning every required final-verification task,
        // so resource parity (or any other leg) cannot be silently dropped.
        val missingTasks = (verifyAllRequiredTasks.get().toSet() - verifyAllDeclaredTasks.get().toSet()).sorted()
        if (missingTasks.isNotEmpty()) {
            throw GradleException("verifyAll no longer owns required verification tasks: $missingTasks.")
        }
        println(
            "Aggregate coverage guard: ${covered.size} covered modules, " +
                "${testBearing.size} test-bearing modules, " +
                "${verifyAllDeclaredTasks.get().size} verifyAll task dependencies.",
        )
    }
}

tasks.register<AggregateCoverageCheck>("verifyAggregateCoverage") {
    group = "verification"
    description = "Fails if any test-bearing module is missing from final verification (T7.15 guard)."
    // Same module sets consumed by the aggregates above plus the suites
    // verifyAll wires directly; values (not script references) are stored so
    // the task action stays configuration-cache compatible.
    coveredModules.set(
        (
            realtimeTestModules + journeyTestModules + listOf(
                ":shared:core:network",
                ":shared:core:database",
                ":shared:app",
                ":androidApp",
            )
            ).distinct().sorted(),
    )
    val testSourceSet = Regex("(^|/)(commonTest|androidTest|androidHostTest|iosTest|uiTest|test)(/|$)")
    testBearingModules.set(
        rootProject.subprojects.mapNotNull { sub ->
            val src = sub.projectDir.resolve("src")
            val bearing = src.isDirectory && src.walkTopDown().any { file ->
                file.isFile && file.extension == "kt" &&
                    testSourceSet.containsMatchIn(file.relativeTo(src).invariantSeparatorsPath)
            }
            if (bearing) sub.path else null
        }.sorted(),
    )
    requiredTestBearingModules.set(
        listOf(
            ":shared:data",
            ":shared:app",
            ":shared:provider:mit-strikes",
            ":shared:feature:strikes",
            ":shared:core:network",
            ":shared:core:database",
            ":shared:provider:journey",
            ":shared:feature:journey",
            ":androidApp",
        ).sorted(),
    )
    // Declared verifyAll task dependencies, resolved through the realized
    // task so the check follows the real wiring (not a copy of the list).
    verifyAllDeclaredTasks.set(
        tasks.named("verifyAll").get().dependsOn.map { it.toString() }.sorted(),
    )
    verifyAllRequiredTasks.set(
        listOf(
            "formatCheck",
            "verifyNoLegacyRendering",
            "verifyAggregateCoverage",
            "verifyResourceParity",
            "verifyAndroidResourceParity",
            "verifyIosCatalogParity",
            "staticAnalysis",
            ":shared:core:database:allTests",
            ":shared:core:database:verifySqlDelightMigration",
            ":shared:core:network:allTests",
            ":shared:app:allTests",
            ":androidApp:testDebugUnitTest",
            ":androidApp:assembleDebug",
            ":shared:app:linkDebugFrameworkIosSimulatorArm64",
            "verifyRealtimeAndroid",
            "verifyRealtimeIos",
            "verifyJourneyAndroid",
            "verifyJourneyIos",
            ":androidApp:connectedDebugAndroidTest",
        ).sorted(),
    )
}
