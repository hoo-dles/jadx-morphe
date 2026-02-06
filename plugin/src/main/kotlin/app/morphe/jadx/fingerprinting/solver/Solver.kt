package app.morphe.jadx.fingerprinting.solver

import app.morphe.jadx.fingerprinting.MorpheFingerprintPlugin
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.ArrayDeque

data class SolverSettings(
    val useReturnType: Boolean = true,
    val useParameters: Boolean = true,
    val useStrings: Boolean = true,
    val useAccessFlags: Boolean = true,
    val useOpcodes: Boolean = false, // Keep this, might be used elsewhere or later
)

object Solver {
    private val LOG = KotlinLogging.logger("${MorpheFingerprintPlugin.ID}/solver")
    private lateinit var methods: List<Method>
    private var solverSettings: SolverSettings = SolverSettings()
    private var methodFeatureList = mutableMapOf<String, List<String>>()

    fun setSettings(settings: SolverSettings = SolverSettings()) {
        this.solverSettings = settings
        LOG.info { "Solver settings updated: $settings" }
    }

    fun setMethods(methods: List<Method>) {
        this.methods = methods
        this.methodFeatureList.clear() // Clear previous features if methods are reset
        this.methods.forEach { method ->
            val features = extractFeatures(method)
            methodFeatureList[method.getUniqueId()] = features
        }
        LOG.info { "Extracted features for ${this.methods.size} methods." }
    }

    fun extractFeatures(method: Method): List<String> {
        val fingerprint = method.toMethodFingerprint()
        val features = mutableListOf<String>()
        if (solverSettings.useReturnType) {
            features.add("returnType|${fingerprint.returnType}")
        }
        if (solverSettings.useParameters) {
            features.addAll(fingerprint.parameters.mapIndexed { index, param ->
                "parameter_$index|$param"
            })
        }
        if (solverSettings.useStrings) {
            features.addAll(fingerprint.strings.map { "strings|$it" })
        }
        if (solverSettings.useAccessFlags) {
            features.add("accessFlags|${fingerprint.accessFlags}")
        }
        // TODO: useOpcodes
        return features
    }

    fun getMethodFeatures(methodId: String): List<String> {
        return methodFeatureList[methodId] ?: emptyList()
    }

    /**
     * Computes all minimal sets of features (fingerprints) that uniquely identify the target method.
     * A fingerprint is minimal if no proper subset of it also uniquely identifies the target method.
     * Uses a Breadth-First Search (BFS) approach based on feature rarity.
     *
     * @param targetMethodId The unique identifier of the target method.
     * @param allMethodFeatures A map where keys are method IDs and values are lists of features for that method.
     * @return A list of minimal distinguishing feature sets (each set represented as a list of strings).
     * @throws IllegalArgumentException if the targetMethodId is not found in allMethodFeatures.
     */
    private fun computeMinimalFingerprintsInternal(
        targetMethodId: String,
        allMethodFeatures: Map<String, List<String>>
    ): List<Set<String>> {
        LOG.info { "Computing minimal fingerprints for method ID: $targetMethodId" }

        val methodFeatureSets = allMethodFeatures.mapValues { it.value.toSet() }

        // Build inverted index: feature -> set of method IDs having this feature
        val featureToMethods = mutableMapOf<String, MutableSet<String>>()
        methodFeatureSets.forEach { (methodId, features) ->
            features.forEach { feature ->
                featureToMethods.computeIfAbsent(feature) { mutableSetOf() }.add(methodId)
            }
        }
        LOG.info { "Built inverted index with ${featureToMethods.size} unique features." }

        // Get target method's features and check existence
        val targetFeatures = methodFeatureSets[targetMethodId]
            ?: throw IllegalArgumentException("Target method ID not found: $targetMethodId")
        if (targetFeatures.isEmpty()) {
            LOG.warn { "Target method '$targetMethodId' has no features based on current settings. Cannot find fingerprints." }
            return emptyList()
        }
        LOG.info { "Target method has ${targetFeatures.size} features." }

        // Sort target features by rarity (ascending order of methods containing the feature)
        val sortedTargetFeatures = targetFeatures.toList().sortedBy { feature ->
            featureToMethods[feature]?.size ?: 0
        }
        LOG.debug { "Sorted target features by rarity: ${sortedTargetFeatures.joinToString()}" }

        // BFS Initialization
        val potentialFingerprints = mutableListOf<Set<String>>()
        val seenCombinations = mutableSetOf<Set<String>>()
        // Queue stores pairs of (current_feature_combination_set, candidate_methods_set)
        val queue: ArrayDeque<Pair<Set<String>, Set<String>>> = ArrayDeque()

        // Initialize queue with single features from the target method
        sortedTargetFeatures.forEachIndexed { index, feature ->
            val candidateMethods = featureToMethods[feature] ?: emptySet()

            // Ensure the feature is actually associated with the target method (sanity check)
            if (targetMethodId !in candidateMethods) {
                LOG.warn { "Feature '$feature' from target method's list is not mapped back to it in featureToMethods. Skipping." }
                return@forEachIndexed // Continue to next feature
            }

            val currentCombo = setOf(feature)

            if (candidateMethods.size == 1) {
                // This single feature is already a minimal fingerprint
                LOG.info { "Found minimal fingerprint (size 1): {$feature}" }
                potentialFingerprints.add(currentCombo)
                seenCombinations.add(currentCombo) // Mark as seen
            } else if (currentCombo !in seenCombinations) {
                queue.add(Pair(currentCombo, candidateMethods.toSet())) // Add copy of candidate set
                seenCombinations.add(currentCombo)
            }
        }
        LOG.info { "Initialized BFS queue with ${queue.size} single-feature combinations." }


        // BFS Exploration
        while (queue.isNotEmpty()) {
            val (currentCombo, currentCandidateMethods) = queue.pollFirst() // Dequeue

            // Determine the starting index for the next feature to add to avoid permutations
            // Find the highest index in sortedTargetFeatures among features in currentCombo
            var lastFeatureIndex = -1
            currentCombo.forEach { featureInCombo ->
                val idx = sortedTargetFeatures.indexOf(featureInCombo)
                if (idx > lastFeatureIndex) {
                    lastFeatureIndex = idx
                }
            }

            // Iterate through features that come *after* the last added feature in the sorted list
            for (i in (lastFeatureIndex + 1) until sortedTargetFeatures.size) {
                val nextFeature = sortedTargetFeatures[i]

                // Create the new combination
                val newCombo = currentCombo + nextFeature // Creates a new set

                // Skip if this combination has already been processed
                if (newCombo in seenCombinations) {
                    continue
                }

                // Calculate the intersection of candidate methods for the new combination
                val nextFeatureMethods = featureToMethods[nextFeature] ?: emptySet()
                val newCandidateMethods = currentCandidateMethods.intersect(nextFeatureMethods)

                // Process only if the target method is still a candidate and there are candidates left
                if (targetMethodId in newCandidateMethods && newCandidateMethods.isNotEmpty()) {
                    // Mark this combination as seen *before* adding to queue or fingerprints
                    seenCombinations.add(newCombo)

                    // If this combination uniquely identifies the target method
                    if (newCandidateMethods.size == 1) {
                        LOG.info { "Found potential fingerprint (size ${newCombo.size}): {${newCombo.joinToString()}}" }
                        potentialFingerprints.add(newCombo)
                        // This is a potential fingerprint, don't add to queue for further extension
                    } else {
                        // Otherwise, add the new combination and its candidates to the queue
                        queue.add(Pair(newCombo, newCandidateMethods))
                    }
                }
            }
        }
        LOG.info { "BFS finished. Found ${potentialFingerprints.size} potential fingerprints." }


        // Filter out non-minimal fingerprints (supersets)
        if (potentialFingerprints.isEmpty()) {
            LOG.warn { "No potential fingerprints found for target method '$targetMethodId'." }
            return emptyList()
        }

        // Sort by size first - helps readability and slightly optimizes the minimality check
        potentialFingerprints.sortBy { it.size }

        val minimalFingerprints = mutableListOf<Set<String>>()
        for (i in potentialFingerprints.indices) {
            val fp1 = potentialFingerprints[i]
            var isMinimal = true
            // Check if fp1 is a superset of any *other* potential fingerprint fp2
            // We only need to check against fingerprints already confirmed minimal or those smaller/equal size coming earlier
            for (j in minimalFingerprints.indices) {
                val fp2 = minimalFingerprints[j]
                // If fp2 is a proper subset of fp1, then fp1 is not minimal
                // (fp1.containsAll(fp2) implies fp2.issubset(fp1))
                if (fp1.size > fp2.size && fp1.containsAll(fp2)) {
                    isMinimal = false
                    LOG.debug { "Fingerprint {${fp1.joinToString()}} is non-minimal (superset of {${fp2.joinToString()}})." }
                    break // No need to check further for fp1
                }
            }
            if (isMinimal) {
                minimalFingerprints.add(fp1)
            }
        }

        LOG.info { "Found ${minimalFingerprints.size} minimal fingerprints for '$targetMethodId'." }
        minimalFingerprints.forEachIndexed { index, fp ->
            LOG.info { "  Minimal Set ${index + 1} (size ${fp.size}): {${fp.joinToString()}}" }
        }

        return minimalFingerprints
    }


    /**
     * Finds all minimal sets of features that uniquely distinguish the target method
     * from all other methods using the current settings.
     *
     * @param methodId The unique identifier of the target method.
     * @return A mutable list containing all minimal distinguishing feature sets found.
     *         Each set is represented as a list of feature strings, sorted alphabetically.
     * @throws IllegalArgumentException if the methodId is not found.
     * @throws IllegalStateException if the target method cannot be distinguished (no fingerprints found).
     */
    fun getMinimalDistinguishingFeatures(
        methodId: String
    ): MutableList<List<String>> {
        LOG.info { "Getting all minimal distinguishing features for method ID: $methodId" }
        LOG.info { "Current settings: $solverSettings" }

        // Check if methodId exists before calling the internal function
        if (!methodFeatureList.containsKey(methodId)) {
            throw IllegalArgumentException("Method ID not found: $methodId")
        }

        val minimalFeatureSets: List<Set<String>> = try {
            computeMinimalFingerprintsInternal(methodId, methodFeatureList)
        } catch (e: IllegalArgumentException) {
            LOG.error(e) { "Error during fingerprint computation for $methodId" }
            throw e // Re-throw
        }

        if (minimalFeatureSets.isEmpty()) {
            LOG.error { "Error: Could not find any set of features to uniquely distinguish target method '$methodId' from all others using the current settings." }
            throw IllegalStateException(
                "Could not distinguish target method '$methodId' from all other methods with the current feature settings. Try enabling more feature types or check if the method is truly unique."
            )
        }

        // Convert sets to sorted lists for consistent output
        val result = minimalFeatureSets
            .map { it.toList().sorted() } // Sort features within each set alphabetically
            .sortedWith(compareBy<List<String>> { it.size }.thenBy { it.joinToString() }) // Sort the list of sets by size, then content
            .toMutableList()

        LOG.info { "Returning ${result.size} minimal distinguishing feature sets for '$methodId'." }
        return result
    }


    fun featuresToFingerprintString(featureList: List<String>): String {
        LOG.info { "Converting features to fingerprint string: $featureList" }

        val fingerprintString = StringBuilder("fingerprint {")
        val fingerprintParts = mutableListOf<String>()
        val strings = mutableListOf<String>()
        val parametersMap = mutableMapOf<Int, String>()
        featureList.forEach { feature ->
            val parts = feature.split("|", limit = 2)
            if (parts.size != 2) {
                LOG.warn { "Invalid feature format, skipping: $feature" } // Changed to warn
                return@forEach
            }
            val featureName = parts[0]
            val featureValue = parts[1]
            when (featureName) {
                "returnType" -> fingerprintParts.add("returns(\"$featureValue\")")
                "strings" -> strings.add(featureValue)
                "accessFlags" -> {
                    try { // Add try-catch for parsing int
                        val flagsInt = featureValue.toInt()
                        val accessFlags = AccessFlags.getAccessFlagsForMethod(flagsInt)
                        val strBuilder = StringBuilder()
                        strBuilder.append("accessFlags(")
                        accessFlags.joinToString(", ") { flag ->
                            "AccessFlags.${flag.name}"
                        }.let { flags ->
                            strBuilder.append(flags)
                        }
                        strBuilder.append(")")
                        fingerprintParts.add(strBuilder.toString())
                    } catch (e: NumberFormatException) {
                        LOG.error { "Invalid access flags value (not an int): $featureValue in feature: $feature" }
                    }
                }

                else -> {
                    if (featureName.startsWith("parameter_")) {
                        val indexStr = featureName.substringAfter("parameter_")
                        val index = indexStr.toIntOrNull()
                        if (index != null) {
                            parametersMap[index] = featureValue
                        } else {
                            LOG.error { "Invalid parameter index in feature: $featureName" }
                        }
                    } else {
                        LOG.warn { "Unknown feature type, skipping: $featureName in feature: $feature" } // Changed to warn
                    }
                }
            }
        }
        parametersMap.takeIf { it.isNotEmpty() }?.let { map ->
            val maxIndex = map.keys.maxOrNull() ?: -1
            // Handle case where parameters might be sparse (e.g., index 0 and 2 but not 1)
            val parametersList = MutableList(maxIndex + 1) { "" } // Initialize with empty strings
            map.forEach { (index, value) ->
                if (index >= 0 && index < parametersList.size) { // Bounds check
                    parametersList[index] = value // Fill in the values from the map
                } else {
                    LOG.error { "Parameter index $index out of bounds (max: $maxIndex) for map $map" }
                }
            }

            StringBuilder().let { builder ->
                builder.append("parameters(")
                parametersList.joinToString(", ") { param ->
                    "\"$param\"" // Quote each parameter, including empty ones
                }.let { paramList ->
                    builder.append(paramList)
                }
                builder.append(")")
                fingerprintParts.add(builder.toString())
            }
        }

        strings.takeIf { it.isNotEmpty() }?.let {
            StringBuilder().let { builder ->
                builder.append("strings(")
                val separator = if (it.size > 2) ",\n\t\t" else ", "
                it.joinToString(separator) { str ->
                    "\"$str\""
                }.let { strList ->
                    builder.append(strList)
                }
                builder.append(")")
                fingerprintParts.add(builder.toString())
            }
        }

        fingerprintParts.joinToString("\n\t", prefix = "\n\t", postfix = "\n") { it }.let { parts ->
            fingerprintString.append(parts)
        }
        fingerprintString.append("}")

        return fingerprintString.toString()

    }
}