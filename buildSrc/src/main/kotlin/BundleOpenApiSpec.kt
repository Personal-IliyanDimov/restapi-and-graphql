import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Bundles the multi-file OpenAPI spec (openapi.yaml + the schemas directory's fragment files)
 * into a single JSON document where every reference to a shared fragment - whether declared as
 * a top-level
 * named component (components/schemas/<Name>) or reached indirectly through another fragment
 * (e.g. ProductPage.yaml's `content.items`) - is canonicalized to the exact same
 * "#/components/schemas/<Name>" pointer.
 *
 * Why this exists: OpenAPI Generator's dereferencer does not reliably recognize that two
 * *different* reference paths reaching the *same* fragment file describe the *same* model. On
 * OpenAPI 3.1 input it silently duplicates the class ("Product1"); on 3.0 input it gives up
 * entirely and types the field as Object (both tried directly against the multi-file spec - see
 * the history in openapi.yaml and ProductPage.yaml). Feeding the generator a single, already-
 * bundled file sidesteps this: same-file, same-string "#/components/schemas/X" reuse is the
 * well-tested, primary code path in the tool.
 *
 * Deliberately fails the build (rather than falling back to something silently wrong) if a
 * relative $ref is found that doesn't resolve to one of the fragments declared under
 * components/schemas - same fail-fast philosophy as the rest of this contract.
 */
abstract class BundleOpenApiSpec : DefaultTask() {

    @get:InputFile
    abstract val inputSpec: RegularFileProperty

    @get:OutputFile
    abstract val outputSpec: RegularFileProperty

    @TaskAction
    fun bundle() {
        val yamlMapper = YAMLMapper()
        val jsonMapper = ObjectMapper()
        val mapType = object : TypeReference<LinkedHashMap<String, Any?>>() {}

        val rootFile = inputSpec.get().asFile
        val rootDir = rootFile.parentFile
        val root: LinkedHashMap<String, Any?> = yamlMapper.readValue(rootFile, mapType)

        @Suppress("UNCHECKED_CAST")
        val components = root["components"] as? LinkedHashMap<String, Any?>
            ?: throw GradleException("$rootFile has no components section to bundle")
        @Suppress("UNCHECKED_CAST")
        val schemas = components["schemas"] as? LinkedHashMap<String, Any?>
            ?: throw GradleException("$rootFile has no components/schemas section to bundle")

        // Pass 1: every top-level components/schemas/<Name> entry is expected to be a bare
        // {$ref: <relative file>} stub. Resolve which file each one points at and remember the
        // mapping canonical-file-path -> component name *before* inlining anything, so later
        // passes can recognize "this is the same fragment" regardless of how it's reached.
        val canonicalPathToName = LinkedHashMap<String, String>()
        val fragmentRawContent = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        val fragmentDirs = LinkedHashMap<String, File>()
        for ((name, value) in schemas) {
            val refPath = singleRefTarget(value)
                ?: throw GradleException(
                    "components/schemas/$name in $rootFile must be a bare {\$ref: <file>} stub " +
                        "pointing at a fragment file - found something else instead."
                )
            val fragmentFile = File(rootDir, refPath).canonicalFile
            canonicalPathToName[fragmentFile.path] = name
            fragmentRawContent[name] = yamlMapper.readValue(fragmentFile, mapType)
            fragmentDirs[name] = fragmentFile.parentFile
        }

        // Pass 2: bundle each fragment's own content - rewriting any nested relative $refs it
        // contains (e.g. ProductPage.yaml's `content.items`), relative to that fragment's own
        // directory - and inline the result directly into components/schemas/<Name>, replacing
        // the {$ref: ...} stub with the real schema body.
        for ((name, rawContent) in fragmentRawContent) {
            schemas[name] = rewriteRefs(rawContent, fragmentDirs.getValue(name), canonicalPathToName)
        }

        // Pass 3: safety net - rewrite any remaining relative $refs anywhere else in the
        // document (paths, parameters, responses, ...). None exist today (operations already
        // use "#/components/schemas/<Name>" directly), but this keeps the bundler correct - and
        // loud, not silent - if that ever changes.
        @Suppress("UNCHECKED_CAST")
        val bundledRoot = rewriteRefs(root, rootDir, canonicalPathToName) as LinkedHashMap<String, Any?>

        val outFile = outputSpec.get().asFile
        outFile.parentFile.mkdirs()
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(outFile, bundledRoot)
    }

    private fun singleRefTarget(node: Any?): String? {
        val map = node as? Map<*, *> ?: return null
        if (map.size != 1) return null
        return map["\$ref"] as? String
    }

    private fun rewriteRefs(
        node: Any?,
        baseDir: File,
        canonicalPathToName: Map<String, String>
    ): Any? = when (node) {
        is Map<*, *> -> {
            val refValue = node["\$ref"] as? String
            if (node.size == 1 && refValue != null && !refValue.startsWith("#")) {
                val canonicalPath = File(baseDir, refValue).canonicalFile.path
                val name = canonicalPathToName[canonicalPath]
                    ?: throw GradleException(
                        "Found \$ref \"$refValue\" (resolved to $canonicalPath) that doesn't " +
                            "match any fragment declared under components/schemas. Every " +
                            "fragment used anywhere in the spec must also be declared there so " +
                            "the bundler can dedupe it - add it instead of letting the build " +
                            "silently regenerate a duplicate or untyped model."
                    )
                linkedMapOf<String, Any?>("\$ref" to "#/components/schemas/$name")
            } else {
                val result = LinkedHashMap<String, Any?>()
                for ((key, value) in node) {
                    result[key as String] = rewriteRefs(value, baseDir, canonicalPathToName)
                }
                result
            }
        }
        is List<*> -> node.map { rewriteRefs(it, baseDir, canonicalPathToName) }
        else -> node
    }
}
