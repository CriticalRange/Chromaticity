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
import java.util.regex.Matcher;
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
            return translationCache.get(cacheKey);
        }

        // Read shader source
        String originalSource = Files.readString(shaderFile);
        if (originalSource.trim().isEmpty()) {
            LOGGER.warn("Shader file is empty: {}", fileName);
            return originalSource;
        }

        // Determine shader type from file name
        ShaderType shaderType = ShaderType.fromFileName(fileName);

        // Translation pipeline
        String translatedSource = originalSource;

        try {
            // Step 1: Preprocessor - handle macros and conditional compilation
            translatedSource = preprocessor.preprocessShader(translatedSource, optionValues, fileName);

            // Step 2: Version translation - upgrade to GLSL 450 core
            // For include files, apply minimal translation without version directives or output declarations
            boolean isIncludeFile = isIncludeFile(shaderFile);
            LOGGER.debug("Processing shader file: {} - isIncludeFile: {}", fileName, isIncludeFile);
            if (isIncludeFile) {
                LOGGER.debug("Using translateShaderMinimal for include file: {}", fileName);
                translatedSource = versionTranslator.translateShaderMinimal(translatedSource, shaderType);
            } else {
                LOGGER.debug("Using translateShader for main shader: {}", fileName);
                translatedSource = versionTranslator.translateShader(translatedSource, shaderType);
            }

            // Step 3: Uniform mapping - convert OpenGL uniforms to Vulkan equivalents
            translatedSource = uniformMapper.mapUniforms(translatedSource, shaderType);

            // Cache the result
            translationCache.put(cacheKey, translatedSource);

            return translatedSource;

        } catch (Exception e) {
            LOGGER.error("Failed to translate shader: {}", fileName, e);
            // Return original source as fallback
            return originalSource;
        }
    }

    /**
     * Translates shader source code directly (used for preprocessed sources).
     */
    private String translateShaderSource(String source, Path shaderFile, ShaderOptionValues optionValues) throws IOException {
        String fileName = shaderFile.getFileName().toString();

        // Check cache first
        String cacheKey = generateCacheKey(source, fileName, optionValues);
        if (translationCache.containsKey(cacheKey)) {
            return translationCache.get(cacheKey);
        }

        try {
            // Determine shader type from filename
            ShaderType shaderType = ShaderType.fromFileName(fileName);

            // Apply complete translation pipeline to the preprocessed source
            String translatedSource = source;

            // Step 1: Already preprocessed (includes flattened), skip this step

            // Step 2: Version translation - upgrade to GLSL 450 core
            translatedSource = versionTranslator.translateShader(translatedSource, shaderType);

            // Step 3: Uniform mapping
            translatedSource = uniformMapper.mapUniforms(translatedSource, shaderType);

            // Cache the result
            translationCache.put(cacheKey, translatedSource);

            return translatedSource;

        } catch (Exception e) {
            LOGGER.error("Failed to translate preprocessed shader source: {}", fileName, e);
            // Return preprocessed source as fallback
            return source;
        }
    }

    /**
     * Translates all shader files in a directory.
     */
    public void translateShaderDirectory(Path originalDir, Path compiledDir, ShaderOptionValues optionValues) throws IOException {
        if (!Files.exists(originalDir)) {
            LOGGER.warn("Original shader directory does not exist: {}", originalDir);
        }

        // Ensure compiled directory exists
        Files.createDirectories(compiledDir);

        // VulkanMod approach: No include preprocessing needed
        // ShaderC handles includes via callbacks during compilation

        // Find all shader files and utility files that need processing
        List<Path> shaderFiles;
        List<Path> utilityFiles;
        try (Stream<Path> paths = Files.walk(originalDir)) {
            List<Path> allShaderFiles = paths.filter(Files::isRegularFile)
                                            .filter(this::isAnyShaderFile)
                                            .collect(Collectors.toList());

            // Separate .glsl files (includes) from main shader files (.vsh/.fsh/etc)
            List<Path> glslFiles = allShaderFiles.stream()
                                                .filter(path -> path.toString().toLowerCase().endsWith(".glsl"))
                                                .collect(Collectors.toList());

            shaderFiles = allShaderFiles.stream()
                                       .filter(path -> !path.toString().toLowerCase().endsWith(".glsl"))
                                       .collect(Collectors.toList());

            utilityFiles = glslFiles; // Process all .glsl files as utilities first
        }

        if (shaderFiles.isEmpty() && utilityFiles.isEmpty()) {
            LOGGER.warn("No GLSL files found in directory: {}", originalDir);
        }

        // First, process all .glsl files (includes) with appropriate translation
        for (Path glslFile : utilityFiles) {
            try {
                // Determine relative path
                Path relativePath = originalDir.relativize(glslFile);
                Path outputFile = compiledDir.resolve(relativePath);

                // Ensure parent directories exist
                Files.createDirectories(outputFile.getParent());

                // Use appropriate translation based on file type
                String translatedSource;
                if (isIncludeFile(glslFile)) {
                    // Include files need proper translation with include directive support
                    LOGGER.debug("Processing include file: {}", glslFile.getFileName());
                    translatedSource = translateShaderFile(glslFile, optionValues);
                } else {
                    // Small utility files get basic syntax fixes only
                    String originalSource = Files.readString(glslFile);
                    translatedSource = fixBasicSyntax(originalSource);
                }

                Files.writeString(outputFile, translatedSource);

            } catch (IOException e) {
                LOGGER.error("Failed to process .glsl file: {}", glslFile, e);
            }
        }

        // Process all shader files with full translation
        int processedCount = 0;
        for (Path shaderFile : shaderFiles) {
            try {
                // Determine relative path
                Path relativePath = originalDir.relativize(shaderFile);
                Path outputFile = compiledDir.resolve(relativePath);

                // Ensure parent directories exist
                Files.createDirectories(outputFile.getParent());

                // VulkanMod approach: Direct translation, preserve #include directives
                String translatedSource = translateShaderFile(shaderFile, optionValues);
                Files.writeString(outputFile, translatedSource);

                processedCount++;

            } catch (IOException e) {
                LOGGER.error("Failed to translate shader file: {}", shaderFile, e);
            }
        }

    }

    /**
     * Checks if a file is any kind of shader-related file (for initial filtering).
     */
    private boolean isAnyShaderFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();

        return fileName.endsWith(".vsh") ||
               fileName.endsWith(".fsh") ||
               fileName.endsWith(".gsh") ||
               fileName.endsWith(".tcs") ||
               fileName.endsWith(".tes") ||
               fileName.endsWith(".vert") ||
               fileName.endsWith(".frag") ||
               fileName.endsWith(".geom") ||
               fileName.endsWith(".glsl");
    }

    /**
     * Checks if a file is a shader file that needs full GLSL 450 transformation.
     * Processes all shader files including world-specific and program shaders.
     */
    private boolean isShaderFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        String pathStr = file.toString().replace('\\', '/');

        // All shader file extensions get full processing
        if (fileName.endsWith(".vsh") ||
            fileName.endsWith(".fsh") ||
            fileName.endsWith(".gsh") ||
            fileName.endsWith(".tcs") ||
            fileName.endsWith(".tes") ||
            fileName.endsWith(".vert") ||
            fileName.endsWith(".frag") ||
            fileName.endsWith(".geom")) {
            return true;
        }

        // .glsl files get full processing except small utility files
        if (fileName.endsWith(".glsl")) {
            // Only exclude small utility files that are truly just includes
            return !(fileName.equals("const.glsl") ||
                     fileName.equals("macros.glsl") ||
                     fileName.equals("functions.glsl") ||
                     fileName.equals("encoders.glsl") ||
                     fileName.equals("transforms.glsl") ||
                     fileName.equals("bicubic.glsl") ||
                     fileName.equals("colorspace.glsl") ||
                     fileName.equals("poisson.glsl"));
        }

        return false;
    }

    /**
     * Checks if a file is an include file that should not get version directives or output declarations.
     */
    private boolean isIncludeFile(Path file) {
        String pathStr = file.toString().replace('\\', '/');

        // Files in lib/ directories are include files
        if (pathStr.contains("/lib/") || pathStr.contains("\\lib\\")) {
            return true;
        }

        // Files in include/ directories are include files
        if (pathStr.contains("/include/") || pathStr.contains("\\include\\")) {
            return true;
        }

        // Common include file names
        String fileName = file.getFileName().toString().toLowerCase();
        if (fileName.equals("settings.glsl") ||
            fileName.equals("internal.glsl") ||
            fileName.equals("head.glsl") ||
            fileName.equals("const.glsl") ||
            fileName.equals("macros.glsl") ||
            fileName.equals("functions.glsl") ||
            fileName.equals("encoders.glsl") ||
            fileName.equals("transforms.glsl")) {
            return true;
        }

        return false;
    }


    /**
     * Fixes basic syntax issues that might prevent compilation.
     */
    private String fixBasicSyntax(String source) {
        // Single-pass processor for all syntax fixes
        String result = applySyntaxFixes(source);

        // Ensure file ends with proper newline (critical for shaderc)
        result = ensureProperFileEnding(result);

        return result;
    }

    /**
     * Ensures the shader file ends with a proper newline character.
     * This is critical for shaderc to properly parse comments and preprocessor directives.
     */
    private String ensureProperFileEnding(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }

        // Ensure file ends with exactly one newline
        String result = source.replaceAll("\\s+$", ""); // Remove trailing whitespace
        result = result + "\n"; // Add single newline

        return result;
    }

    /**
     * Consolidated single-pass processor for all syntax fixes.
     * Combines comment pattern fixes, include directive fixes, and operator spacing fixes.
     */
    private String applySyntaxFixes(String source) {
        String[] lines = source.split("\n");
        StringBuilder result = new StringBuilder();

        // Pre-compile patterns for efficiency
        // Pattern matches: //[numbers with spaces] including decimals and negative numbers
        Pattern commentBracketPattern = Pattern.compile("(//.*?\\[)([\\d\\-\\.\\s]+)(\\])");

        for (String line : lines) {
            String originalLine = line;
            String processedLine = line;

            // 1. Fix problematic comment patterns: //[0 1 2 3 4 5] -> //[0_1_2_3_4_5]
            if (line.contains("//") && line.contains("[") && line.contains("]")) {
                Matcher matcher = commentBracketPattern.matcher(line);
                if (matcher.find()) {
                    String prefix = matcher.group(1);
                    String numbers = matcher.group(2);
                    String suffix = matcher.group(3);
                    if (numbers.contains(" ")) {
                        String fixedNumbers = numbers.replaceAll("\\s+", "_");
                        processedLine = line.substring(0, matcher.start()) + prefix + fixedNumbers + suffix + line.substring(matcher.end());
                        // Only log critical comment pattern fixes
                    }
                }
            }

            // 1b. Fix dash-separated patterns in comments: 4-albedo -> 4_albedo (prevents INTCONSTANT errors)
            if (processedLine.contains("//")) {
                processedLine = processedLine.replaceAll("(\\d+)-(\\w+)", "$1_$2");
            }

            // 2. Fix include directives: #include <path> -> #include "path"
            if (processedLine.trim().matches("^#include\\s+<.*>.*")) {
                processedLine = processedLine.replaceAll("#include\\s+<([^>]+)>", "#include \"$1\"");
            }

            // 3. Fix operator spacing and excessive whitespace (skip only comments)
            String trimmed = processedLine.trim();
            if (!trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.isEmpty()) {
                // Fix operator spacing
                processedLine = processedLine.replaceAll("\\s{2,}=\\s*", " = ");
                processedLine = processedLine.replaceAll("\\s*=\\s{2,}", " = ");

                // Fix excessive whitespace (including in preprocessor directives)
                processedLine = processedLine.replaceAll("\\s{3,}", " ");
            }

            result.append(processedLine);
            if (!line.equals(lines[lines.length - 1])) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Gets the indentation of a line.
     */
    private String getIndentation(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
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
     * Generate cache key for source string.
     */
    private String generateCacheKey(String source, String fileName, ShaderOptionValues optionValues) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(fileName).append("_").append(source.hashCode());

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