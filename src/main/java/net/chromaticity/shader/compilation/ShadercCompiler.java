package net.chromaticity.shader.compilation;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Pure Shaderc-based GLSL to SPIR-V compiler with include support.
 * Based on VulkanMod's proven approach with legacy shader pack support.
 */
public class ShadercCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadercCompiler.class);

    private static long compiler;
    private static long debugOptions;
    private static long releaseOptions;
    private static Path currentShaderPackRoot;
    private static final IncludeResolver INCLUDE_RESOLVER = new IncludeResolver();
    private static final IncludeReleaser INCLUDE_RELEASER = new IncludeReleaser();
    private static final Map<String, CompilationResult> compilationCache = new ConcurrentHashMap<>();

    // Compilation modes
    public enum CompilationMode {
        DEBUG("Debug mode with full debug info and no optimization"),
        RELEASE("Release mode with maximum optimization"),
        DEVELOPMENT("Development mode with some optimization but debug info");

        private final String description;

        CompilationMode(String description) {
            this.description = description;
        }

        public String getDescription() { return description; }
    }

    // Output formats
    public enum OutputFormat {
        SPIRV_BINARY,
        SPIRV_ASSEMBLY,
        PREPROCESSED_SOURCE
    }

    // Current compilation mode (can be changed at runtime)
    private static CompilationMode currentMode = CompilationMode.RELEASE;

    public enum ShaderStage {
        VERTEX("vertex", shaderc_glsl_vertex_shader),
        FRAGMENT("fragment", shaderc_glsl_fragment_shader),
        GEOMETRY("geometry", shaderc_glsl_geometry_shader),
        COMPUTE("compute", shaderc_glsl_compute_shader);

        private final String name;
        private final int shadercKind;

        ShaderStage(String name, int shadercKind) {
            this.name = name;
            this.shadercKind = shadercKind;
        }

        public String getName() { return name; }
        public int getShadercKind() { return shadercKind; }

        public static ShaderStage fromFileName(String filename) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".vsh") || lower.contains("vertex")) return VERTEX;
            if (lower.endsWith(".fsh") || lower.contains("fragment")) return FRAGMENT;
            if (lower.endsWith(".gsh") || lower.contains("geometry")) return GEOMETRY;
            if (lower.endsWith(".csh") || lower.contains("compute")) return COMPUTE;
            return FRAGMENT; // Default fallback
        }
    }

    public static class CompilationResult {
        private final String filename;
        private final ShaderStage stage;
        private final byte[] data;
        private final OutputFormat format;
        private final boolean fromCache;
        private final String warnings;
        private final Map<String, String> appliedMacros;

        public CompilationResult(String filename, ShaderStage stage, byte[] data, OutputFormat format,
                               boolean fromCache, String warnings, Map<String, String> appliedMacros) {
            this.filename = filename;
            this.stage = stage;
            this.data = data;
            this.format = format;
            this.fromCache = fromCache;
            this.warnings = warnings;
            this.appliedMacros = new HashMap<>(appliedMacros != null ? appliedMacros : new HashMap<>());
        }

        public String getFilename() { return filename; }
        public ShaderStage getStage() { return stage; }
        public byte[] getData() { return data; }
        public byte[] getSpirvBytecode() { return format == OutputFormat.SPIRV_BINARY ? data : null; }
        public String getAssemblyText() { return format == OutputFormat.SPIRV_ASSEMBLY ? new String(data) : null; }
        public String getPreprocessedSource() { return format == OutputFormat.PREPROCESSED_SOURCE ? new String(data) : null; }
        public OutputFormat getFormat() { return format; }
        public boolean isFromCache() { return fromCache; }
        public String getWarnings() { return warnings; }
        public Map<String, String> getAppliedMacros() { return new HashMap<>(appliedMacros); }

        public boolean hasWarnings() { return warnings != null && !warnings.trim().isEmpty(); }
    }

    /**
     * Initialize the Shaderc compiler with advanced optimizations and multiple configuration modes.
     */
    public static void initialize() {
        if (compiler != NULL) {
            LOGGER.debug("Shaderc compiler already initialized");
            return;
        }

        // Initialize compiler
        compiler = shaderc_compiler_initialize();
        if (compiler == NULL) {
            throw new RuntimeException("Failed to initialize Shaderc compiler");
        }

        // Initialize debug options
        debugOptions = shaderc_compile_options_initialize();
        if (debugOptions == NULL) {
            throw new RuntimeException("Failed to initialize Shaderc debug options");
        }
        configureDebugOptions(debugOptions);

        // Initialize release options
        releaseOptions = shaderc_compile_options_initialize();
        if (releaseOptions == NULL) {
            throw new RuntimeException("Failed to initialize Shaderc release options");
        }
        configureReleaseOptions(releaseOptions);

        LOGGER.info("Shaderc compiler initialized successfully with {} mode", currentMode);
    }

    /**
     * Configure debug compilation options.
     */
    private static void configureDebugOptions(long options) {
        // Debug configuration: no optimization, full debug info
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
        shaderc_compile_options_set_generate_debug_info(options);

        // Target Vulkan 1.2
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);

        // Include callbacks
        shaderc_compile_options_set_include_callbacks(options, INCLUDE_RESOLVER, INCLUDE_RELEASER, NULL);

        // Treat warnings as errors for better debugging
        shaderc_compile_options_set_warnings_as_errors(options);

        // Set resource limits for safety
        setResourceLimits(options);

        LOGGER.debug("Configured debug compilation options");
    }

    /**
     * Configure release compilation options with maximum optimizations.
     */
    private static void configureReleaseOptions(long options) {
        // Release configuration: maximum optimization
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        // Target Vulkan 1.2
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);

        // Include callbacks
        shaderc_compile_options_set_include_callbacks(options, INCLUDE_RESOLVER, INCLUDE_RELEASER, NULL);

        // Advanced optimizations for shader packs
        shaderc_compile_options_set_auto_bind_uniforms(options, true);
        shaderc_compile_options_set_auto_combined_image_sampler(options, true);
        shaderc_compile_options_set_auto_map_locations(options, true);

        // Set resource limits for safety
        setResourceLimits(options);

        LOGGER.debug("Configured release compilation options with advanced optimizations");
    }

    /**
     * Set reasonable resource limits to prevent runaway compilations.
     */
    private static void setResourceLimits(long options) {
        // Set reasonable limits based on Vulkan spec minimums but higher for shader packs
        shaderc_compile_options_set_limit(options, shaderc_limit_max_lights, 32);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_clip_planes, 8);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_texture_units, 32);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_texture_coords, 8);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_vertex_attribs, 16);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_vertex_uniform_components, 4096);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_varying_floats, 128);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_vertex_texture_image_units, 16);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_combined_texture_image_units, 128);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_texture_image_units, 16);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_fragment_uniform_components, 4096);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_draw_buffers, 8);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_vertex_uniform_vectors, 256);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_varying_vectors, 32);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_fragment_uniform_vectors, 256);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_vertex_output_vectors, 32);
        shaderc_compile_options_set_limit(options, shaderc_limit_max_fragment_input_vectors, 32);
    }

    /**
     * Set the shader pack root for include resolution.
     */
    public static void setShaderPackRoot(Path shaderPackRoot) {
        currentShaderPackRoot = shaderPackRoot;
        LOGGER.debug("Shader pack root set to: {}", shaderPackRoot);
    }

    /**
     * Compile GLSL source to SPIR-V using advanced Shaderc features.
     */
    public static CompilationResult compileShader(String filename, String source, ShaderStage stage) {
        return compileShader(filename, source, stage, OutputFormat.SPIRV_BINARY, null);
    }

    /**
     * Compile GLSL source with advanced options including macros and output format.
     */
    public static CompilationResult compileShader(String filename, String source, ShaderStage stage,
                                                 OutputFormat format, Map<String, String> macros) {
        if (compiler == NULL) {
            initialize();
        }

        // Check cache first
        String cacheKey = generateCacheKey(filename, source, stage, format, macros);
        CompilationResult cached = compilationCache.get(cacheKey);
        if (cached != null) {
            LOGGER.debug("Using cached compilation result for: {}", filename);
            return cached;
        }

        try {
            LOGGER.debug("Compiling {} shader: '{}' (source length: {} chars, format: {}, mode: {})",
                stage.getName(), filename, source.length(), format, currentMode);

            // Ensure source ends with newline
            if (!source.endsWith("\n")) {
                source = source + "\n";
                LOGGER.debug("Added missing final newline to {}", filename);
            }

            // Upgrade legacy GLSL versions for SPIR-V compatibility
            source = upgradeGlslVersion(source, filename);

            // Apply legacy GLSL syntax fixes for SPIR-V compatibility
            if (PreprocessingFix.needsLegacyFixes(source)) {
                PreprocessingFix.LegacyStats stats = PreprocessingFix.analyzeLegacySyntax(source);
                LOGGER.debug("Found legacy GLSL syntax in {}: {}", filename, stats);
                source = PreprocessingFix.applyAllFixes(source, filename, stage);
            }

            // Get appropriate options based on current mode
            long options = getCurrentOptions();

            // Clone options if we need to add macros
            if (macros != null && !macros.isEmpty()) {
                options = shaderc_compile_options_clone(options);
                for (Map.Entry<String, String> macro : macros.entrySet()) {
                    shaderc_compile_options_add_macro_definition(options, macro.getKey(), macro.getValue());
                    LOGGER.debug("Added macro: {} = {}", macro.getKey(), macro.getValue());
                }
            }

            // Compile based on requested format
            long result;
            switch (format) {
                case SPIRV_BINARY:
                    result = shaderc_compile_into_spv(compiler, source, stage.getShadercKind(), filename, "main", options);
                    break;
                case SPIRV_ASSEMBLY:
                    result = shaderc_compile_into_spv_assembly(compiler, source, stage.getShadercKind(), filename, "main", options);
                    break;
                case PREPROCESSED_SOURCE:
                    result = shaderc_compile_into_preprocessed_text(compiler, source, stage.getShadercKind(), filename, "main", options);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported output format: " + format);
            }

            // Clean up cloned options if we created them
            if (macros != null && !macros.isEmpty()) {
                shaderc_compile_options_release(options);
            }

            if (result == NULL) {
                throw new RuntimeException("Failed to compile shader " + filename + " - compilation returned null");
            }

            // Check compilation status
            int status = shaderc_result_get_compilation_status(result);
            String warnings = shaderc_result_get_error_message(result);

            if (status != shaderc_compilation_status_success) {
                LOGGER.error("Shaderc compilation failed for {}: {}", filename, warnings);

                // Log first 10 lines for debugging
                String[] lines = source.split("\n");
                LOGGER.error("First 10 lines of problematic shader source:");
                for (int i = 0; i < Math.min(10, lines.length); i++) {
                    LOGGER.error("  {}: {}", i + 1, lines[i]);
                }

                shaderc_result_release(result);
                throw new RuntimeException("Failed to compile shader " + filename + " to " + format + ":\n" + warnings);
            }

            // Extract output data
            byte[] outputData;
            if (format == OutputFormat.SPIRV_BINARY) {
                ByteBuffer dataBuffer = shaderc_result_get_bytes(result);
                outputData = new byte[dataBuffer.remaining()];
                dataBuffer.get(outputData);
            } else {
                // For assembly and preprocessed text, get bytes directly
                ByteBuffer dataBuffer = shaderc_result_get_bytes(result);
                outputData = new byte[dataBuffer.remaining()];
                dataBuffer.get(outputData);

                // Log text output size for debugging
                String textOutput = new String(outputData);
                LOGGER.debug("Generated {} output: {} bytes, {} lines",
                           format, outputData.length, textOutput.split("\n").length);
            }

            shaderc_result_release(result);

            // Create result and cache it
            CompilationResult compilationResult = new CompilationResult(
                filename, stage, outputData, format, false, warnings, macros);
            compilationCache.put(cacheKey, compilationResult);

            if (warnings != null && !warnings.trim().isEmpty()) {
                LOGGER.warn("Compilation warnings for {}: {}", filename, warnings);
            }

            LOGGER.info("Successfully compiled {} to {} ({} bytes)", filename, format, outputData.length);
            return compilationResult;

        } catch (Exception e) {
            String errorMsg = String.format("Failed to compile %s shader '%s' to %s", stage.getName(), filename, format);
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Get current compilation options based on mode.
     */
    private static long getCurrentOptions() {
        switch (currentMode) {
            case DEBUG:
            case DEVELOPMENT:
                return debugOptions;
            case RELEASE:
            default:
                return releaseOptions;
        }
    }

    /**
     * Upgrade legacy GLSL versions to be SPIR-V compatible.
     * Adds #version directive if missing, or upgrades existing versions.
     */
    private static String upgradeGlslVersion(String source, String filename) {
        String[] lines = source.split("\n");
        StringBuilder result = new StringBuilder();
        boolean hasVersion = false;
        boolean upgraded = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#version ")) {
                hasVersion = true;
                try {
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length >= 2) {
                        int version = Integer.parseInt(parts[1]);

                        if (version < 450) {
                            String newVersionLine = "#version 450 core";
                            result.append(newVersionLine).append("\n");
                            upgraded = true;
                            LOGGER.info("Upgraded GLSL version in {}: {} -> {}", filename, trimmed, newVersionLine);
                            continue;
                        }
                    }
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid GLSL version format in {}: {}", filename, trimmed);
                }
            }

            result.append(line).append("\n");
        }

        // If no #version directive was found, add one at the beginning
        if (!hasVersion) {
            String versionLine = "#version 450 core";
            String finalSource = versionLine + "\n" + result.toString();
            upgraded = true;
            LOGGER.info("Added missing GLSL version to {}: {}", filename, versionLine);
            return finalSource;
        }

        if (upgraded) {
            LOGGER.info("Applied GLSL version upgrade for SPIR-V compatibility: {}", filename);
        }

        return result.toString();
    }


    /**
     * Include resolver for shader pack files.
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

                    String errorContent = String.format("// ERROR: Include file '%s' not found\n#error \"Include file not found: %s\"\n", requestedSource, requestedSource);
                    return ShadercIncludeResult.malloc(stack)
                        .source_name(stack.ASCII(requestedSource))
                        .content(stack.bytes(errorContent.getBytes()))
                        .user_data(userData)
                        .address();
                }

                // Read file content
                String content = Files.readString(includeFile);
                byte[] bytes = content.getBytes();

                LOGGER.info("Successfully resolved include: '{}' -> {} ({} bytes)", requestedSource, includeFile, bytes.length);

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
     * Include result releaser.
     */
    private static class IncludeReleaser implements ShadercIncludeResultReleaseI {
        @Override
        public void invoke(long userData, long includeResult) {
            // Memory is managed by MemoryStack
        }
    }

    /**
     * Resolve include file path by checking multiple possible locations.
     */
    private static Path resolveIncludeFile(String requestedSource, String requestingSource) {
        if (currentShaderPackRoot == null) {
            return null;
        }

        List<Path> candidatePaths = new ArrayList<>();

        if (requestedSource.startsWith("/")) {
            // Absolute include: try various root locations
            candidatePaths.add(currentShaderPackRoot.resolve("shaders" + requestedSource));
            candidatePaths.add(currentShaderPackRoot.resolve(requestedSource.substring(1)));
            candidatePaths.add(currentShaderPackRoot.resolve("shaders").resolve(requestedSource.substring(1)));

            if (requestedSource.startsWith("/lib/")) {
                candidatePaths.add(currentShaderPackRoot.resolve("lib").resolve(requestedSource.substring(5)));
            }
        } else {
            // Relative include: resolve from requesting file's directory
            Path requestingFile = currentShaderPackRoot.resolve("shaders").resolve(requestingSource);
            Path requestingDir = requestingFile.getParent();

            if (requestingDir != null) {
                candidatePaths.add(requestingDir.resolve(requestedSource));
                candidatePaths.add(currentShaderPackRoot.resolve("shaders").resolve(requestedSource));
            }
        }

        // Find first existing file
        for (Path candidate : candidatePaths) {
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                LOGGER.debug("Resolved '{}' to: {}", requestedSource, candidate);
                return candidate;
            }
        }

        LOGGER.warn("Could not resolve include: {}", requestedSource);
        return null;
    }

    /**
     * Generate cache key for compiled shader including new parameters.
     */
    private static String generateCacheKey(String filename, String source, ShaderStage stage) {
        return generateCacheKey(filename, source, stage, OutputFormat.SPIRV_BINARY, null);
    }

    /**
     * Generate cache key for compiled shader with all parameters.
     */
    private static String generateCacheKey(String filename, String source, ShaderStage stage,
                                          OutputFormat format, Map<String, String> macros) {
        int contentHash = source.hashCode();
        int macroHash = macros != null ? macros.hashCode() : 0;
        return String.format("%s_%s_%s_%s_%d_%d", filename, stage.name(), format.name(),
                           currentMode.name(), contentHash, macroHash);
    }

    /**
     * Set compilation mode (debug, release, development).
     */
    public static void setCompilationMode(CompilationMode mode) {
        currentMode = mode;
        LOGGER.info("Switched to {} compilation mode: {}", mode, mode.getDescription());
    }

    /**
     * Get current compilation mode.
     */
    public static CompilationMode getCompilationMode() {
        return currentMode;
    }

    /**
     * Compile shader with shader pack options as macros.
     */
    public static CompilationResult compileShaderWithOptions(String filename, String source, ShaderStage stage,
                                                            Map<String, String> shaderPackOptions) {
        LOGGER.debug("Compiling {} with {} shader pack options", filename,
                   shaderPackOptions != null ? shaderPackOptions.size() : 0);
        return compileShader(filename, source, stage, OutputFormat.SPIRV_BINARY, shaderPackOptions);
    }

    /**
     * Preprocess shader source only (useful for debugging includes).
     */
    public static CompilationResult preprocessShader(String filename, String source, ShaderStage stage) {
        return compileShader(filename, source, stage, OutputFormat.PREPROCESSED_SOURCE, null);
    }

    /**
     * Preprocess shader and save to cache directory for debugging.
     */
    public static CompilationResult preprocessAndSaveShader(String filename, String source, ShaderStage stage,
                                                           Path preprocessedDir) {
        CompilationResult result = preprocessShader(filename, source, stage);

        if (result != null && result.getFormat() == OutputFormat.PREPROCESSED_SOURCE) {
            try {
                // Ensure preprocessed directory exists
                Files.createDirectories(preprocessedDir);

                // Save preprocessed source to file
                Path preprocessedFile = preprocessedDir.resolve(filename);
                String preprocessedSource = result.getPreprocessedSource();

                if (preprocessedSource != null) {
                    Files.writeString(preprocessedFile, preprocessedSource);
                    LOGGER.info("Saved preprocessed shader: {} ({} bytes)",
                              preprocessedFile, preprocessedSource.length());
                } else {
                    LOGGER.warn("Preprocessed source was null for: {}", filename);
                }

            } catch (IOException e) {
                LOGGER.error("Failed to save preprocessed shader {}: {}", filename, e.getMessage());
            }
        }

        return result;
    }

    /**
     * Preprocess all shaders in a directory and save to cache.
     */
    public static void preprocessShadersInDirectory(Path shaderDir, Path preprocessedDir) {
        try {
            if (!Files.exists(shaderDir)) {
                LOGGER.warn("Shader directory does not exist: {}", shaderDir);
                return;
            }

            Files.createDirectories(preprocessedDir);

            try (Stream<Path> paths = Files.walk(shaderDir)) {
                paths.filter(Files::isRegularFile)
                     .filter(ShadercCompiler::isShaderFile)
                     .forEach(shaderFile -> {
                         try {
                             String filename = shaderFile.getFileName().toString();
                             String source = Files.readString(shaderFile);
                             ShaderStage stage = ShaderStage.fromFileName(filename);

                             if (stage != null) {
                                 preprocessAndSaveShader(filename, source, stage, preprocessedDir);
                             } else {
                                 LOGGER.debug("Skipping non-shader file: {}", filename);
                             }

                         } catch (IOException e) {
                             LOGGER.error("Failed to preprocess shader file: {}", shaderFile, e);
                         }
                     });
            }

            LOGGER.info("Completed preprocessing shaders from {} to {}", shaderDir, preprocessedDir);

        } catch (IOException e) {
            LOGGER.error("Failed to preprocess shaders in directory: {}", shaderDir, e);
        }
    }

    /**
     * Check if a file is a shader file based on extension.
     */
    private static boolean isShaderFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return fileName.endsWith(".vsh") || fileName.endsWith(".fsh") ||
               fileName.endsWith(".gsh") || fileName.endsWith(".tcs") ||
               fileName.endsWith(".tes") || fileName.endsWith(".glsl") ||
               fileName.endsWith(".vert") || fileName.endsWith(".frag") ||
               fileName.endsWith(".geom") || fileName.endsWith(".comp");
    }

    /**
     * Compile to SPIR-V assembly (useful for debugging and analysis).
     */
    public static CompilationResult compileToAssembly(String filename, String source, ShaderStage stage) {
        return compileShader(filename, source, stage, OutputFormat.SPIRV_ASSEMBLY, null);
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
     * Cleanup resources.
     */
    public static void cleanup() {
        if (debugOptions != NULL) {
            shaderc_compile_options_release(debugOptions);
            debugOptions = NULL;
        }
        if (releaseOptions != NULL) {
            shaderc_compile_options_release(releaseOptions);
            releaseOptions = NULL;
        }
        if (compiler != NULL) {
            shaderc_compiler_release(compiler);
            compiler = NULL;
        }
        clearCache();
        LOGGER.info("Shaderc compiler cleaned up");
    }

    /**
     * Get compilation statistics.
     */
    public static CompilationStats getStats() {
        return new CompilationStats(
            compilationCache.size(),
            currentMode,
            compiler != NULL
        );
    }

    /**
     * Compilation statistics class.
     */
    public static class CompilationStats {
        private final int cachedShaders;
        private final CompilationMode mode;
        private final boolean available;

        public CompilationStats(int cachedShaders, CompilationMode mode, boolean available) {
            this.cachedShaders = cachedShaders;
            this.mode = mode;
            this.available = available;
        }

        public int getCachedShaders() { return cachedShaders; }
        public CompilationMode getMode() { return mode; }
        public boolean isAvailable() { return available; }

        @Override
        public String toString() {
            return String.format("ShadercCompiler Stats: %d cached shaders, mode: %s, available: %s",
                cachedShaders, mode, available);
        }
    }
}