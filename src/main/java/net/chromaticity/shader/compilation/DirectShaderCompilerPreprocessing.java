package net.chromaticity.shader.compilation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.util.shaderc.Shaderc.*;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.system.MemoryStack;

/**
 * DirectShaderCompiler using VulkanMod-style shaderc include callbacks.
 * Preserves #include directives and lets shaderc handle includes during compilation.
 */
public class DirectShaderCompilerPreprocessing {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectShaderCompilerPreprocessing.class);

    private static long compiler;
    private static long options;

    // VulkanMod-style include handling
    private static Path currentShaderPackRoot;
    private static final IncludeResolver SHADER_INCLUDER = new IncludeResolver();
    private static final IncludeReleaser SHADER_RELEASER = new IncludeReleaser();

    // Cache for compiled shaders
    private static final Map<String, CompilationResult> compilationCache = new ConcurrentHashMap<>();

    static {
        initializeCompiler();
    }

    /**
     * Initialize the shaderc compiler without include callbacks.
     */
    private static void initializeCompiler() {
        try {
            // Initialize compiler
            compiler = shaderc_compiler_initialize();
            if (compiler == NULL) {
                throw new RuntimeException("Failed to initialize shaderc compiler");
            }

            // Initialize compilation options
            options = shaderc_compile_options_initialize();
            if (options == NULL) {
                throw new RuntimeException("Failed to initialize shaderc options");
            }

            // Configure compiler options
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

            // Set target environment to Vulkan 1.2
            shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_2, 0x00400000);

            // Enable GLSL preprocessing (but no include callbacks needed!)
            shaderc_compile_options_set_source_language(options, shaderc_source_language_glsl);

            // Generate debug information for better error messages
            shaderc_compile_options_set_generate_debug_info(options);

            // VulkanMod approach: Set up include callbacks
            shaderc_compile_options_set_include_callbacks(options,
                SHADER_INCLUDER,
                SHADER_RELEASER,
                NULL);

            LOGGER.info("DirectShaderCompiler initialized successfully (VulkanMod-style include callbacks)");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize DirectShaderCompiler", e);
            throw new RuntimeException("DirectShaderCompiler initialization failed", e);
        }
    }

    /**
     * Initialize shaderc include paths for a shader pack (VulkanMod approach).
     */
    public static void initializeIncludeSystem(Path shaderPackRoot) {
        try {
            LOGGER.info("Initializing VulkanMod-style include paths for shader pack: {}", shaderPackRoot);

            // Store shader pack root for include resolution
            currentShaderPackRoot = shaderPackRoot;

            LOGGER.info("Include paths configured for: {}", shaderPackRoot);

        } catch (Exception e) {
            LOGGER.error("Failed to initialize include paths", e);
            throw new RuntimeException("Include path initialization failed", e);
        }
    }

    /**
     * VulkanMod-style include resolver callback implementation.
     */
    private static class IncludeResolver implements ShadercIncludeResolveI {
        @Override
        public long invoke(long userData, long requestedSourcePtr, int includeType, long requestingSourcePtr, long includeDepth) {
            String requestingSource = memASCII(requestingSourcePtr);
            String requestedSource = memASCII(requestedSourcePtr);

            LOGGER.info("Include resolver called: '{}' requested by '{}' (type: {}, depth: {})",
                requestedSource, requestingSource, includeType, includeDepth);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (currentShaderPackRoot == null) {
                    LOGGER.error("Shader pack root not initialized for include resolution");
                    throw new RuntimeException("Shader pack root not initialized");
                }

                Path includeFile = resolveIncludeFile(requestedSource, requestingSource);

                if (includeFile == null || !Files.exists(includeFile)) {
                    String error = String.format("%s: Unable to find %s in shader pack", requestingSource, requestedSource);
                    LOGGER.error(error);

                    // Return a minimal include result instead of crashing to allow compilation to continue
                    String errorContent = String.format("// ERROR: Include file '%s' not found\n#error \"Include file not found: %s\"\n", requestedSource, requestedSource);
                    return ShadercIncludeResult.malloc(stack)
                        .source_name(stack.ASCII(requestedSource))
                        .content(stack.bytes(errorContent.getBytes()))
                        .user_data(userData)
                        .address();
                }

                // Read file content
                byte[] bytes = Files.readAllBytes(includeFile);

                LOGGER.debug("Resolved include: {} -> {} ({} bytes)", requestedSource, includeFile, bytes.length);

                // Create shaderc include result exactly like VulkanMod
                return ShadercIncludeResult.malloc(stack)
                    .source_name(stack.ASCII(requestedSource))
                    .content(stack.bytes(bytes))
                    .user_data(userData)
                    .address();

            } catch (Exception e) {
                LOGGER.error("Failed to resolve include: {} from {}", requestedSource, requestingSource, e);
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Resolves include file path by checking multiple possible locations.
     * Handles common shader pack directory structures and include patterns.
     */
    private static Path resolveIncludeFile(String requestedSource, String requestingSource) {
        if (currentShaderPackRoot == null) {
            return null;
        }

        // List of possible include paths to check
        List<Path> candidatePaths = new ArrayList<>();

        if (requestedSource.startsWith("/")) {
            // Absolute include: try various root locations

            // Standard OptiFine/Iris location: shaders/lib/
            candidatePaths.add(currentShaderPackRoot.resolve("shaders" + requestedSource));

            // Alternative: lib/ at pack root
            candidatePaths.add(currentShaderPackRoot.resolve(requestedSource.substring(1)));

            // Alternative: inside shaders/ but without extra prefix
            String withoutSlash = requestedSource.substring(1);
            candidatePaths.add(currentShaderPackRoot.resolve("shaders").resolve(withoutSlash));

            // Some packs put lib at root level
            if (requestedSource.startsWith("/lib/")) {
                candidatePaths.add(currentShaderPackRoot.resolve("lib").resolve(requestedSource.substring(5)));
            }

        } else {
            // Relative include: resolve from requesting file's directory
            Path requestingFile = currentShaderPackRoot.resolve("shaders").resolve(requestingSource);
            Path requestingDir = requestingFile.getParent();

            LOGGER.debug("Resolving relative include '{}' from requesting file: {}", requestedSource, requestingSource);
            LOGGER.debug("Requesting file path: {}", requestingFile);
            LOGGER.debug("Requesting dir: {}", requestingDir);

            if (requestingDir != null) {
                candidatePaths.add(requestingDir.resolve(requestedSource));

                // Also try relative to shaders root
                candidatePaths.add(currentShaderPackRoot.resolve("shaders").resolve(requestedSource));

                // For files in lib directory, also try lib-specific paths
                if (requestingSource.contains("lib/")) {
                    // Extract the lib path and resolve relative to it
                    String libPath = requestingSource.substring(0, requestingSource.lastIndexOf('/'));
                    Path libDir = currentShaderPackRoot.resolve("shaders").resolve(libPath);
                    candidatePaths.add(libDir.resolve(requestedSource));
                    LOGGER.debug("Added lib-specific candidate: {}", libDir.resolve(requestedSource));
                }
            }
        }

        // Find the first existing file
        for (Path candidate : candidatePaths) {
            LOGGER.debug("Checking candidate path: {} (exists: {})", candidate, Files.exists(candidate));
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                LOGGER.info("Successfully resolved include: '{}' -> {}", requestedSource, candidate);
                return candidate;
            }
        }

        // Log all attempted paths for debugging
        LOGGER.error("Include file '{}' requested by '{}' not found. Tried paths:", requestedSource, requestingSource);
        for (Path candidate : candidatePaths) {
            LOGGER.error("  - {} (exists: {})", candidate, Files.exists(candidate));
        }

        return null;
    }

    /**
     * VulkanMod-style include releaser callback implementation.
     * Note: VulkanMod doesn't actually need to do anything here since MemoryStack frees automatically.
     */
    private static class IncludeReleaser implements ShadercIncludeResultReleaseI {
        @Override
        public void invoke(long userData, long includeResult) {
            // VulkanMod approach: MemoryStack frees this for us, no need to manually free
        }
    }

    /**
     * Compile shader source to SPIR-V using VulkanMod-style include callbacks.
     */
    public static CompilationResult compileShader(String filename, String source, ShaderStage stage) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("Shader source cannot be null or empty");
        }

        // Validate and clean shader source to prevent compilation errors
        source = validateAndCleanShaderSource(source, filename);

        // Check cache first
        String cacheKey = generateCacheKey(filename, source, stage);
        CompilationResult cached = compilationCache.get(cacheKey);
        if (cached != null) {
            LOGGER.debug("Using cached compilation result for: {}", filename);
            return cached;
        }

        try {
            LOGGER.debug("Compiling {} shader: '{}' (source length: {} chars)", stage.getName(), filename, source.length());

            // Log truncated source content for debugging
            String truncatedSource = source.length() > 500 ? source.substring(0, 500) + "..." : source;
            LOGGER.debug("Source preview: {}", truncatedSource);

            // VulkanMod approach: Source contains #include directives
            // shaderc will resolve includes using our callbacks during compilation

            // Compile shader to SPIR-V
            long result = shaderc_compile_into_spv(
                compiler,
                source,
                stage.getShadercKind(),
                filename,
                "main",
                options
            );

            if (result == NULL) {
                throw new RuntimeException("Failed to compile shader " + filename + " - compilation returned null");
            }

            // Check compilation status
            int status = shaderc_result_get_compilation_status(result);
            if (status != shaderc_compilation_status_success) {
                String errorMessage = shaderc_result_get_error_message(result);
                LOGGER.error("SPIR-V compilation failed for {}: {}", filename, errorMessage);

                // Log first few lines of source for debugging
                String[] sourceLines = source.split("\n");
                LOGGER.error("First 10 lines of problematic shader source:");
                for (int i = 0; i < Math.min(10, sourceLines.length); i++) {
                    LOGGER.error("  {}: {}", i + 1, sourceLines[i]);
                }

                shaderc_result_release(result);
                throw new RuntimeException("Failed to compile shader " + filename + " to SPIR-V:\n" + errorMessage);
            }

            // Get compiled bytecode
            long resultLength = shaderc_result_get_length(result);
            if (resultLength > Integer.MAX_VALUE) {
                throw new RuntimeException("Compiled shader too large: " + resultLength + " bytes");
            }
            byte[] spirvBytecode = new byte[(int) resultLength];
            shaderc_result_get_bytes(result).get(spirvBytecode);

            // Create compilation result
            CompilationResult compilationResult = new CompilationResult(
                spirvBytecode,
                filename,
                stage,
                false // not from cache
            );

            // Cache the result
            compilationCache.put(cacheKey, compilationResult);

            // Release shaderc result
            shaderc_result_release(result);

            LOGGER.debug("Successfully compiled {} shader: {} ({} bytes SPIR-V)",
                stage.getName(), filename, spirvBytecode.length);

            return compilationResult;

        } catch (Exception e) {
            String errorMsg = String.format("Failed to compile %s shader '%s' to SPIR-V", stage.getName(), filename);
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }


    /**
     * Validates and cleans shader source to prevent compilation errors.
     * Fixes common issues like malformed preprocessor directives.
     */
    private static String validateAndCleanShaderSource(String source, String filename) {
        List<String> lines = new ArrayList<>(List.of(source.split("\n")));
        List<String> cleanedLines = new ArrayList<>();
        boolean hasIssues = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmedLine = line.trim();

            // Check for malformed preprocessor directives
            if (trimmedLine.contains("#") && !trimmedLine.startsWith("#")) {
                // Check if # appears after other tokens on the same line
                int hashIndex = line.indexOf('#');
                String beforeHash = line.substring(0, hashIndex).trim();

                if (!beforeHash.isEmpty()) {
                    // This is a malformed preprocessor directive
                    LOGGER.warn("Fixed malformed preprocessor directive in {} at line {}: '{}'",
                        filename, i + 1, line);

                    // Extract the preprocessor directive part
                    String directivePart = line.substring(hashIndex).trim();

                    // Add the content before # as a separate line if it's not whitespace
                    if (!beforeHash.matches("\\s*")) {
                        cleanedLines.add(beforeHash);
                    }

                    // Add the directive on its own line
                    cleanedLines.add(directivePart);
                    hasIssues = true;
                    continue;
                }
            }

            // Add the line as-is if no issues found
            cleanedLines.add(line);
        }

        if (hasIssues) {
            LOGGER.info("Cleaned {} malformed preprocessor directive issues in shader: {}",
                lines.size() - cleanedLines.size(), filename);
        }

        return String.join("\n", cleanedLines);
    }

    /**
     * Generate cache key for compiled shader.
     */
    private static String generateCacheKey(String filename, String source, ShaderStage stage) {
        int contentHash = source.hashCode();
        return String.format("%s_%s_%d", filename, stage.name(), contentHash);
    }

    /**
     * Clear compilation cache.
     */
    public static void clearCache() {
        int cacheSize = compilationCache.size();
        compilationCache.clear();
        LOGGER.debug("Cleared compilation cache ({} entries)", cacheSize);
    }

    /**
     * Get compilation statistics.
     */
    public static CompilationStats getStats() {
        return new CompilationStats(
            compilationCache.size(),
            0, // no file tracking needed with VulkanMod approach
            true // always available since we use direct LWJGL
        );
    }

    /**
     * Cleanup resources.
     */
    public static void cleanup() {
        if (options != NULL) {
            shaderc_compile_options_release(options);
            options = NULL;
        }
        if (compiler != NULL) {
            shaderc_compiler_release(compiler);
            compiler = NULL;
        }
        clearCache();
        currentShaderPackRoot = null;
    }

    // Reuse existing classes from the original implementation
    public static class CompilationResult {
        private final byte[] spirvBytecode;
        private final String shaderName;
        private final ShaderStage stage;
        private final boolean fromCache;

        public CompilationResult(byte[] spirvBytecode, String shaderName, ShaderStage stage, boolean fromCache) {
            this.spirvBytecode = spirvBytecode;
            this.shaderName = shaderName;
            this.stage = stage;
            this.fromCache = fromCache;
        }

        public byte[] getSpirvBytecode() { return spirvBytecode; }
        public String getShaderName() { return shaderName; }
        public ShaderStage getStage() { return stage; }
        public boolean isFromCache() { return fromCache; }
    }

    public enum ShaderStage {
        VERTEX("vertex", ".vsh", shaderc_glsl_vertex_shader),
        FRAGMENT("fragment", ".fsh", shaderc_glsl_fragment_shader),
        GEOMETRY("geometry", ".gsh", shaderc_glsl_geometry_shader),
        COMPUTE("compute", ".csh", shaderc_glsl_compute_shader);

        private final String name;
        private final String extension;
        private final int shadercKind;

        ShaderStage(String name, String extension, int shadercKind) {
            this.name = name;
            this.extension = extension;
            this.shadercKind = shadercKind;
        }

        public String getName() { return name; }
        public String getExtension() { return extension; }
        public int getShadercKind() { return shadercKind; }

        public static ShaderStage fromFileName(String fileName) {
            String lowerName = fileName.toLowerCase();
            if (lowerName.endsWith(".vsh") || lowerName.contains("vertex")) return VERTEX;
            if (lowerName.endsWith(".fsh") || lowerName.contains("fragment")) return FRAGMENT;
            if (lowerName.endsWith(".gsh") || lowerName.contains("geometry")) return GEOMETRY;
            if (lowerName.endsWith(".csh") || lowerName.contains("compute")) return COMPUTE;
            return FRAGMENT; // Default fallback
        }
    }

    public static class CompilationStats {
        private final int cachedShaders;
        private final int totalFiles;
        private final boolean available;

        public CompilationStats(int cachedShaders, int totalFiles, boolean available) {
            this.cachedShaders = cachedShaders;
            this.totalFiles = totalFiles;
            this.available = available;
        }

        public int getCachedShaders() { return cachedShaders; }
        public int getTotalFiles() { return totalFiles; }
        public boolean isAvailable() { return available; }

        @Override
        public String toString() {
            return String.format("DirectShaderCompiler Stats: %d cached shaders, %d total files, available: %s",
                cachedShaders, totalFiles, available);
        }
    }
}