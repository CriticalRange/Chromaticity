package net.chromaticity.shader.preprocessing;

import net.chromaticity.shader.option.ShaderOptionValues;
import net.chromaticity.shader.preprocessing.GlslVersionTranslator.ShaderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Main service for translating OpenGL shaderpacks to Vulkan-compatible GLSL.
 * Orchestrates version translation, preprocessing, and uniform mapping.
 */
public class ShaderTranslationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderTranslationService.class);

    private final GlslVersionTranslator versionTranslator;
    private final ShaderPreprocessor preprocessor;
    private final UniformMappingService uniformMapper;

    // Cache for translated shaders to avoid reprocessing
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();

    public ShaderTranslationService() {
        this.versionTranslator = new GlslVersionTranslator();
        this.preprocessor = new ShaderPreprocessor();
        this.uniformMapper = new UniformMappingService();
    }

    /**
     * Translates a single shader file from OpenGL to Vulkan GLSL.
     */
    public String translateShaderFile(Path shaderFile, ShaderOptionValues optionValues) throws IOException {
        String fileName = shaderFile.getFileName().toString();

        // Check cache first
        String cacheKey = generateCacheKey(shaderFile, optionValues);
        if (translationCache.containsKey(cacheKey)) {
            LOGGER.debug("Using cached translation for shader: {}", fileName);
            return translationCache.get(cacheKey);
        }

        // Read shader source
        String originalSource = Files.readString(shaderFile);
        if (originalSource.trim().isEmpty()) {
            LOGGER.warn("Shader file is empty: {}", fileName);
            return originalSource;
        }

        LOGGER.info("Translating shader: {}", fileName);

        // Determine shader type from file name
        ShaderType shaderType = ShaderType.fromFileName(fileName);

        // Translation pipeline
        String translatedSource = originalSource;

        try {
            // Step 1: Preprocessor - handle macros and conditional compilation
            translatedSource = preprocessor.preprocessShader(translatedSource, optionValues, fileName);

            // Step 2: Version translation - upgrade to GLSL 450 core
            translatedSource = versionTranslator.translateShader(translatedSource, shaderType);

            // Step 3: Uniform mapping - convert OpenGL uniforms to Vulkan equivalents
            translatedSource = uniformMapper.mapUniforms(translatedSource, shaderType);

            // Cache the result
            translationCache.put(cacheKey, translatedSource);

            LOGGER.info("Successfully translated shader: {} (type: {})", fileName, shaderType);
            return translatedSource;

        } catch (Exception e) {
            LOGGER.error("Failed to translate shader: {}", fileName, e);
            // Return original source as fallback
            return originalSource;
        }
    }

    /**
     * Translates all shader files in a directory.
     */
    public void translateShaderDirectory(Path originalDir, Path compiledDir, ShaderOptionValues optionValues) throws IOException {
        if (!Files.exists(originalDir)) {
            LOGGER.warn("Original shader directory does not exist: {}", originalDir);
            return;
        }

        // Ensure compiled directory exists
        Files.createDirectories(compiledDir);

        LOGGER.debug("Starting shader translation from {} to {}", originalDir, compiledDir);

        // Find all shader files and include files
        List<Path> shaderFiles;
        List<Path> includeFiles;
        try (Stream<Path> paths = Files.walk(originalDir)) {
            List<Path> allFiles = paths.filter(Files::isRegularFile).collect(Collectors.toList());
            shaderFiles = allFiles.stream().filter(this::isShaderFile).collect(Collectors.toList());
            includeFiles = allFiles.stream().filter(this::isIncludeFile).collect(Collectors.toList());
        }

        LOGGER.info("Found {} shader files and {} include files to process in directory: {}",
            shaderFiles.size(), includeFiles.size(), originalDir);

        if (shaderFiles.isEmpty() && includeFiles.isEmpty()) {
            LOGGER.warn("No shader or include files found in directory: {}", originalDir);
            // List all files for debugging
            try (Stream<Path> paths = Files.walk(originalDir)) {
                List<Path> allFiles = paths.filter(Files::isRegularFile).collect(Collectors.toList());
                LOGGER.debug("Directory contains {} total files:", allFiles.size());
                allFiles.forEach(file -> LOGGER.debug("  - {}", file.getFileName()));
            }
            return;
        }

        // Process main shader files first
        int processedCount = 0;
        for (Path shaderFile : shaderFiles) {
            try {
                // Determine relative path
                Path relativePath = originalDir.relativize(shaderFile);
                Path outputFile = compiledDir.resolve(relativePath);

                // Ensure parent directories exist
                Files.createDirectories(outputFile.getParent());

                // Translate and save
                String translatedSource = translateShaderFile(shaderFile, optionValues);
                Files.writeString(outputFile, translatedSource);

                processedCount++;
                LOGGER.debug("Translated shader [{}/{}]: {} -> {}",
                    processedCount, shaderFiles.size(),
                    shaderFile.getFileName(), outputFile.getFileName());

            } catch (IOException e) {
                LOGGER.error("Failed to translate shader file: {}", shaderFile, e);
            }
        }

        // Process include files (minimal processing - mostly copy with basic compatibility fixes)
        for (Path includeFile : includeFiles) {
            try {
                // Determine relative path
                Path relativePath = originalDir.relativize(includeFile);
                Path outputFile = compiledDir.resolve(relativePath);

                // Ensure parent directories exist
                Files.createDirectories(outputFile.getParent());

                // Apply minimal processing to include files
                String processedSource = processIncludeFile(includeFile);
                Files.writeString(outputFile, processedSource);

                processedCount++;
                LOGGER.debug("Processed include file [{}/{}]: {} -> {}",
                    processedCount, shaderFiles.size() + includeFiles.size(),
                    includeFile.getFileName(), outputFile.getFileName());

            } catch (IOException e) {
                LOGGER.error("Failed to process include file: {}", includeFile, e);
            }
        }

        LOGGER.info("Completed translation of shader directory: {} ({}/{} files processed - {} shaders, {} includes)",
            originalDir, processedCount, shaderFiles.size() + includeFiles.size(), shaderFiles.size(), includeFiles.size());
    }

    /**
     * Checks if a file is a main shader file that needs full GLSL 450 transformation.
     * Include files (.glsl) are processed separately.
     */
    private boolean isShaderFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return fileName.endsWith(".vsh") ||
               fileName.endsWith(".fsh") ||
               fileName.endsWith(".gsh") ||
               fileName.endsWith(".tcs") ||
               fileName.endsWith(".tes") ||
               fileName.endsWith(".vert") ||
               fileName.endsWith(".frag") ||
               fileName.endsWith(".geom");
    }

    /**
     * Checks if a file is an include file that needs minimal processing.
     */
    private boolean isIncludeFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        String pathStr = file.toString().replace('\\', '/');

        // .glsl files are typically include files
        if (fileName.endsWith(".glsl")) {
            return true;
        }

        // Files in lib/ or include/ directories are include files
        return pathStr.contains("/lib/") || pathStr.contains("/include/") ||
               pathStr.contains("\\lib\\") || pathStr.contains("\\include\\");
    }

    /**
     * Processes an include file with minimal transformations.
     * Unlike main shader files, include files don't get GLSL 450 headers or output declarations.
     */
    private String processIncludeFile(Path includeFile) throws IOException {
        String originalSource = Files.readString(includeFile);
        String result = originalSource;

        // Basic syntax fixes for include files
        result = fixBasicSyntax(result);

        // Apply minimal compatibility transformations (but not full GLSL 450 transformation)
        result = applyMinimalCompatibilityFixes(result);

        return result;
    }

    /**
     * Fixes basic syntax issues that might prevent compilation.
     */
    private String fixBasicSyntax(String source) {
        // Fix common syntax issues
        String result = source;

        // Ensure comments are properly closed
        result = fixUnclosedComments(result);

        return result;
    }

    /**
     * Fixes unclosed block comments.
     */
    private String fixUnclosedComments(String source) {
        String result = source;

        // Count opening and closing comment markers
        long openComments = result.chars().filter(ch -> ch == '/').count();
        if (openComments > 0 && result.contains("/*") && !result.contains("*/")) {
            // Add missing closing comment marker at the end
            result = result + "\n*/";
        }

        return result;
    }

    /**
     * Applies minimal compatibility fixes for include files.
     */
    private String applyMinimalCompatibilityFixes(String source) {
        String result = source;

        // Only apply basic variable transformations, no GLSL 450 additions
        result = replaceBuiltInVariables(result, null); // No shader type context for includes

        return result;
    }

    /**
     * Replaces built-in variables with modern equivalents, but only basic ones for include files.
     */
    private String replaceBuiltInVariables(String source, ShaderType shaderType) {
        String result = source;

        // For include files, we need to be more careful about gl_Position
        // The discard.glsl file has gl_Position in what's meant to be fragment shader code
        // Replace problematic gl_Position assignments that don't make sense in fragment context
        if (result.contains("gl_Position") && result.contains("discard")) {
            // This looks like fragment shader code with invalid gl_Position usage
            result = result.replaceAll("gl_Position\\s*=\\s*vec4\\(-1\\.0\\);?", "// gl_Position removed - invalid in fragment shader");
        }

        return result;
    }

    /**
     * Generates a cache key for a shader translation.
     */
    private String generateCacheKey(Path shaderFile, ShaderOptionValues optionValues) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(shaderFile.toString());

        if (optionValues != null) {
            // Include option values in cache key
            Map<String, String> options = optionValues.toPropertiesMap();
            options.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> keyBuilder.append("|").append(entry.getKey()).append("=").append(entry.getValue()));
        }

        return keyBuilder.toString();
    }

    /**
     * Clears the translation cache.
     */
    public void clearCache() {
        translationCache.clear();
        LOGGER.debug("Translation cache cleared");
    }

    /**
     * Gets cache statistics.
     */
    public int getCacheSize() {
        return translationCache.size();
    }

    /**
     * Service for mapping OpenGL uniforms to Vulkan equivalents.
     */
    private static class UniformMappingService {
        private static final Logger LOGGER = LoggerFactory.getLogger(UniformMappingService.class);

        // OpenGL to Vulkan uniform mappings
        private static final Map<String, String> UNIFORM_MAPPINGS;
        static {
            Map<String, String> map = new HashMap<>();
            // Matrix uniforms
            map.put("gl_ModelViewMatrix", "uModelViewMatrix");
            map.put("gl_ProjectionMatrix", "uProjectionMatrix");
            map.put("gl_ModelViewProjectionMatrix", "uModelViewProjectionMatrix");
            map.put("gl_NormalMatrix", "uNormalMatrix");

            // Texture uniforms (common OptiFine patterns)
            map.put("texture", "uTexture");
            map.put("lightmap", "uLightmap");
            map.put("normals", "uNormals");
            map.put("specular", "uSpecular");

            // Time and animation
            map.put("frameTimeCounter", "uTime");
            map.put("worldTime", "uWorldTime");

            // Camera and view
            map.put("cameraPosition", "uCameraPosition");
            map.put("previousCameraPosition", "uPreviousCameraPosition");

            UNIFORM_MAPPINGS = Collections.unmodifiableMap(map);
        }

        /**
         * Maps OpenGL uniform names to Vulkan equivalents.
         */
        public String mapUniforms(String source, ShaderType shaderType) {
            String result = source;

            for (Map.Entry<String, String> mapping : UNIFORM_MAPPINGS.entrySet()) {
                String openglUniform = mapping.getKey();
                String vulkanUniform = mapping.getValue();

                // Replace uniform declarations
                String uniformPattern = "\\buniform\\s+\\w+\\s+" + Pattern.quote(openglUniform) + "\\b";
                result = result.replaceAll(uniformPattern, "uniform " + getUniformType(openglUniform) + " " + vulkanUniform);

                // Replace uniform references
                String referencePattern = "\\b" + Pattern.quote(openglUniform) + "\\b";
                result = result.replaceAll(referencePattern, vulkanUniform);
            }

            LOGGER.debug("Applied uniform mappings for {} shader", shaderType);
            return result;
        }

        /**
         * Gets the appropriate uniform type for a given uniform name.
         */
        private String getUniformType(String uniformName) {
            if (uniformName.contains("Matrix")) {
                return "mat4";
            } else if (uniformName.contains("Position") || uniformName.contains("Camera")) {
                return "vec3";
            } else if (uniformName.contains("Time")) {
                return "float";
            } else if (uniformName.toLowerCase().contains("texture") ||
                       uniformName.toLowerCase().contains("map")) {
                return "sampler2D";
            }
            return "vec4"; // Default fallback
        }
    }
}