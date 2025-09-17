package net.chromaticity.shader.compilation;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Direct LWJGL shaderc compiler with file system include resolution.
 * Bypasses VulkanMod's resource-only include system to support dynamic shader packs.
 *
 * @deprecated This class is deprecated in favor of {@link DirectShaderCompilerPreprocessing}
 *             which uses Iris-style preprocessing instead of shaderc callbacks for better
 *             compatibility and error handling.
 * @see DirectShaderCompilerPreprocessing
 */
@Deprecated
public class DirectShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectShaderCompiler.class);

    private static long compiler;
    private static long options;
    private static final FileSystemIncludeResolver INCLUDE_RESOLVER = new FileSystemIncludeResolver();
    private static final IncludeResultReleaser RESULT_RELEASER = new IncludeResultReleaser();

    // Include paths for file system resolution
    private static final List<Path> includePaths = new ArrayList<>();

    // Cache for compiled shaders
    private static final Map<String, CompilationResult> compilationCache = new ConcurrentHashMap<>();

    static {
        initializeCompiler();
    }

    /**
     * Initialize the direct shaderc compiler with file system include support.
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

            // Set target environment to Vulkan 1.2 (constant value: 0x00400000)
            shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_2, 0x00400000);

            // Enable GLSL preprocessing to handle includes properly
            // This ensures GL_GOOGLE_include_directive extension is available
            shaderc_compile_options_set_source_language(options, shaderc_source_language_glsl);

            // Generate debug information for better error messages
            shaderc_compile_options_set_generate_debug_info(options);

            // Set up our custom file system include resolver
            shaderc_compile_options_set_include_callbacks(options, INCLUDE_RESOLVER, RESULT_RELEASER, 0);


        } catch (Exception e) {
            LOGGER.error("Failed to initialize DirectShaderCompiler", e);
            throw new RuntimeException("DirectShaderCompiler initialization failed", e);
        }
    }

    /**
     * Add a file system include path for shader compilation.
     */
    public static void addIncludePath(Path includePath) {
        if (includePath != null && Files.exists(includePath) && Files.isDirectory(includePath)) {
            includePaths.add(includePath.toAbsolutePath());
            LOGGER.debug("Added include path: {}", includePath);
        } else {
            LOGGER.warn("Invalid include path (not a directory or doesn't exist): {}", includePath);
        }
    }

    /**
     * Clear all include paths.
     */
    public static void clearIncludePaths() {
        includePaths.clear();
        LOGGER.debug("Cleared all include paths");
    }

    /**
     * Compile shader source to SPIR-V using direct LWJGL shaderc.
     */
    public static CompilationResult compileShader(String filename, String source, ShaderStage stage) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("Shader source cannot be null or empty");
        }

        // Check cache first
        String cacheKey = generateCacheKey(filename, source, stage);
        CompilationResult cached = compilationCache.get(cacheKey);
        if (cached != null) {
            LOGGER.debug("Using cached compilation result for: {}", filename);
            return cached;
        }

        try {
            LOGGER.error("=== SHADER COMPILATION DEBUG ===");
            LOGGER.error("Compiling {} shader: '{}'", stage.getName(), filename);
            LOGGER.error("Source length: {} characters", source.length());
            LOGGER.error("Source preview (first 1000 chars):");
            LOGGER.error("'{}'", source.length() > 1000 ? source.substring(0, 1000) + "..." : source);
            LOGGER.error("Source contains #include directives: {}", source.contains("#include"));
            LOGGER.error("Source contains GL_GOOGLE_include_directive: {}", source.contains("GL_GOOGLE_include_directive"));
            LOGGER.error("=== END COMPILATION DEBUG ===");

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


            return compilationResult;

        } catch (Exception e) {
            String errorMsg = String.format("Failed to compile %s shader '%s' to SPIR-V", stage.getName(), filename);
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Compile shader from file path.
     */
    public static CompilationResult compileShaderFile(Path shaderPath) {
        try {
            String filename = shaderPath.getFileName().toString();
            String source = Files.readString(shaderPath);
            ShaderStage stage = ShaderStage.fromFileName(filename);

            return compileShader(filename, source, stage);

        } catch (IOException e) {
            String errorMsg = "Failed to read shader file: " + shaderPath;
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
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
            includePaths.size(),
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
        clearIncludePaths();
    }

    /**
     * File system include resolver that reads from actual file paths.
     */
    private static class FileSystemIncludeResolver implements ShadercIncludeResolveI {

        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            String requesting = memASCII(requesting_source);
            String requested = memASCII(requested_source);

            LOGGER.error("=== INCLUDE RESOLUTION DEBUG ===");
            LOGGER.error("Requested: '{}' by '{}'", requested, requesting);
            LOGGER.error("Include depth: {}, type: {}", include_depth, type);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Try to resolve the include file
                LOGGER.error("Available include paths: {}", includePaths);

                // For relative includes (type 0), first try relative to requesting file's directory
                if (type == 0 && !requested.startsWith("/")) {
                    Path requestingPath = findRequestingFilePath(requesting);
                    if (requestingPath != null) {
                        Path relativeBasePath = requestingPath.getParent();
                        LOGGER.error("Trying relative to requesting file directory: {}", relativeBasePath);
                        Path resolvedPath = resolveIncludePath(relativeBasePath, requested);
                        LOGGER.error("Resolved to: {}", resolvedPath);

                        if (resolvedPath != null && Files.exists(resolvedPath) && Files.isRegularFile(resolvedPath)) {
                            try {
                                byte[] content = Files.readAllBytes(resolvedPath);
                                String contentStr = new String(content, java.nio.charset.StandardCharsets.UTF_8);

                                LOGGER.error("=== RESOLVED CONTENT for '{}' (relative) ===", requested);
                                LOGGER.error("File size: {} bytes", content.length);
                                LOGGER.error("Content preview (first 500 chars):");
                                LOGGER.error("'{}'", contentStr.length() > 500 ? contentStr.substring(0, 500) + "..." : contentStr);
                                LOGGER.error("=== END CONTENT ===");

                                return ShadercIncludeResult.malloc(stack)
                                    .source_name(stack.ASCII(requested))
                                    .content(stack.bytes(content))
                                    .user_data(user_data)
                                    .address();

                            } catch (IOException e) {
                                LOGGER.error("Failed to read include file: {}", resolvedPath, e);
                            }
                        }
                    }
                }

                // Fall back to global include paths
                for (int i = 0; i < includePaths.size(); i++) {
                    Path includePath = includePaths.get(i);
                    LOGGER.error("Trying include path #{}: {}", i + 1, includePath);

                    Path resolvedPath = resolveIncludePath(includePath, requested);
                    LOGGER.error("Resolved to: {}", resolvedPath);

                    if (resolvedPath != null && Files.exists(resolvedPath) && Files.isRegularFile(resolvedPath)) {
                        try {
                            byte[] content = Files.readAllBytes(resolvedPath);
                            String contentStr = new String(content, java.nio.charset.StandardCharsets.UTF_8);

                            LOGGER.error("=== RESOLVED CONTENT for '{}' ===", requested);
                            LOGGER.error("File size: {} bytes", content.length);
                            LOGGER.error("Content preview (first 500 chars):");
                            LOGGER.error("'{}'", contentStr.length() > 500 ? contentStr.substring(0, 500) + "..." : contentStr);
                            LOGGER.error("Content contains problematic comment patterns: {}",
                                contentStr.matches(".*//\\[\\d+\\s+\\d+.*"));
                            LOGGER.error("=== END CONTENT ===");

                            return ShadercIncludeResult.malloc(stack)
                                .source_name(stack.ASCII(requested))
                                .content(stack.bytes(content))
                                .user_data(user_data)
                                .address();

                        } catch (IOException e) {
                            LOGGER.error("Failed to read include file: {}", resolvedPath, e);
                        }
                    } else {
                        LOGGER.error("Path not valid: {} (exists: {}, isFile: {})",
                            resolvedPath,
                            resolvedPath != null && Files.exists(resolvedPath),
                            resolvedPath != null && Files.exists(resolvedPath) && Files.isRegularFile(resolvedPath));
                    }
                }

                // Include not found
                String errorMsg = String.format("%s: Unable to find '%s' in include paths: %s",
                    requesting, requested, includePaths);
                LOGGER.error("INCLUDE RESOLUTION FAILED: {}", errorMsg);
                throw new RuntimeException(errorMsg);

            } catch (Exception e) {
                LOGGER.error("Include resolution exception for '{}' requested by '{}'", requested, requesting, e);
                throw new RuntimeException("Include resolution failed", e);
            }
        }

        /**
         * Resolve include path, handling both absolute and relative paths.
         * Follows Google Shaderc requirements for include path resolution.
         */
        private Path resolveIncludePath(Path basePath, String requested) {
            try {
                // Validate that the requested path doesn't contain invalid sequences
                if (requested.contains("..") || requested.contains("\\\\") || requested.contains("//")) {
                    LOGGER.debug("Invalid include path contains suspicious sequences: {}", requested);
                    return null;
                }

                // Handle absolute paths (starting with /)
                if (requested.startsWith("/")) {
                    // Convert to relative path by removing leading slash
                    String relativePath = requested.substring(1);
                    // Normalize path separators for current OS
                    relativePath = relativePath.replace('/', java.io.File.separatorChar);
                    return basePath.resolve(relativePath);
                } else {
                    // Handle relative paths - normalize separators
                    String normalizedPath = requested.replace('/', java.io.File.separatorChar);
                    return basePath.resolve(normalizedPath);
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to resolve include path: {} + {}", basePath, requested, e);
                return null;
            }
        }

        /**
         * Extract filename from path string.
         */
        private String getFilename(String path) {
            int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        }

        /**
         * Find the actual filesystem path of the requesting file.
         */
        private Path findRequestingFilePath(String requesting) {
            // The requesting parameter is the filename as passed to shaderc
            // We need to find it in our include paths
            for (Path basePath : includePaths) {
                Path candidate = basePath.resolve(requesting);
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    return candidate;
                }

                // Also try with normalized path separators
                String normalizedRequesting = requesting.replace('/', java.io.File.separatorChar);
                candidate = basePath.resolve(normalizedRequesting);
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * Include result releaser (required by shaderc but handled by MemoryStack).
     */
    private static class IncludeResultReleaser implements ShadercIncludeResultReleaseI {
        @Override
        public void invoke(long user_data, long include_result) {
            // Memory is automatically freed by MemoryStack
            // This is just required by the shaderc API
        }
    }

    /**
     * Shader compilation result.
     */
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

    /**
     * Shader stage enumeration.
     */
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

    /**
     * Compilation statistics.
     */
    public static class CompilationStats {
        private final int cachedShaders;
        private final int includePaths;
        private final boolean available;

        public CompilationStats(int cachedShaders, int includePaths, boolean available) {
            this.cachedShaders = cachedShaders;
            this.includePaths = includePaths;
            this.available = available;
        }

        public int getCachedShaders() { return cachedShaders; }
        public int getIncludePaths() { return includePaths; }
        public boolean isAvailable() { return available; }

        @Override
        public String toString() {
            return String.format("DirectShaderCompiler Stats: %d cached shaders, %d include paths, available: %s",
                cachedShaders, includePaths, available);
        }
    }
}