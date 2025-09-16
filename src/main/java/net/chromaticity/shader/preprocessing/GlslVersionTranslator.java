package net.chromaticity.shader.preprocessing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates GLSL shaders from older OpenGL versions to Vulkan-compatible GLSL 450 core.
 * Handles version directive transformation and legacy syntax conversion.
 */
public class GlslVersionTranslator {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlslVersionTranslator.class);

    // GLSL version patterns
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?", Pattern.MULTILINE);

    // Legacy syntax patterns for transformation
    private static final Map<String, String> LEGACY_REPLACEMENTS;
    static {
        Map<String, String> map = new HashMap<>();
        map.put("attribute", "in");
        map.put("varying", "in"); // Will be context-dependent (in/out)
        map.put("gl_FragColor", "fragColor");
        map.put("texture2D", "texture");
        map.put("texture2DLod", "textureLod");
        map.put("texture2DProj", "textureProj");
        map.put("textureCube", "texture");
        map.put("textureCubeLod", "textureLod");
        LEGACY_REPLACEMENTS = Collections.unmodifiableMap(map);
    }

    // Built-in variables that need replacement
    private static final Map<String, String> BUILTIN_REPLACEMENTS;
    static {
        Map<String, String> map = new HashMap<>();
        map.put("gl_ModelViewMatrix", "modelViewMatrix");
        map.put("gl_ProjectionMatrix", "projectionMatrix");
        map.put("gl_ModelViewProjectionMatrix", "modelViewProjectionMatrix");
        map.put("gl_NormalMatrix", "normalMatrix");
        map.put("gl_Vertex", "inPosition");
        map.put("gl_Normal", "inNormal");
        map.put("gl_MultiTexCoord0", "inTexCoord0");
        map.put("gl_MultiTexCoord1", "inTexCoord1");
        map.put("gl_Color", "inColor");
        map.put("gl_SecondaryColor", "inSecondaryColor");
        BUILTIN_REPLACEMENTS = Collections.unmodifiableMap(map);
    }

    // Texture function replacements for GLSL 450 core
    private static final Map<String, String> TEXTURE_REPLACEMENTS;
    static {
        Map<String, String> map = new HashMap<>();
        map.put("texture1D", "texture");
        map.put("texture2D", "texture");
        map.put("texture3D", "texture");
        map.put("textureCube", "texture");
        map.put("texture1DLod", "textureLod");
        map.put("texture2DLod", "textureLod");
        map.put("texture3DLod", "textureLod");
        map.put("textureCubeLod", "textureLod");
        map.put("texture1DProj", "textureProj");
        map.put("texture2DProj", "textureProj");
        map.put("texture3DProj", "textureProj");
        map.put("texture1DGrad", "textureGrad");
        map.put("texture2DGrad", "textureGrad");
        map.put("texture3DGrad", "textureGrad");
        map.put("textureCubeGrad", "textureGrad");
        map.put("shadow1D", "texture");
        map.put("shadow2D", "texture");
        map.put("shadow1DProj", "textureProj");
        map.put("shadow2DProj", "textureProj");
        map.put("shadow1DLod", "textureLod");
        map.put("shadow2DLod", "textureLod");
        map.put("shadow1DProjLod", "textureProjLod");
        map.put("shadow2DProjLod", "textureProjLod");
        TEXTURE_REPLACEMENTS = Collections.unmodifiableMap(map);
    }

    // Layout qualifier tracking for vertex attributes
    private static class LayoutQualifierTracker {
        private int nextLocation = 0;
        private final Map<String, Integer> attributeLocations = new HashMap<>();

        public int getOrAssignLocation(String attributeName) {
            return attributeLocations.computeIfAbsent(attributeName, k -> nextLocation++);
        }

        public void reset() {
            nextLocation = 0;
            attributeLocations.clear();
        }
    }

    /**
     * Translates a GLSL shader source to Vulkan-compatible GLSL 450 core.
     */
    public String translateShader(String source, ShaderType shaderType) {
        if (source == null || source.trim().isEmpty()) {
            return source;
        }

        List<String> lines = new ArrayList<>(List.of(source.split("\n")));

        // Step 1: Transform version directive
        transformVersionDirective(lines);

        // Step 2: Add required extensions and outputs for fragment shaders
        addVulkanCompatibilityDirectives(lines, shaderType);

        // Step 3: Transform legacy syntax
        transformLegacySyntax(lines, shaderType);

        // Step 4: Add layout qualifiers for vertex attributes
        LayoutQualifierTracker layoutTracker = new LayoutQualifierTracker();
        addLayoutQualifiers(lines, shaderType, layoutTracker);

        // Step 5: Transform built-in variables
        transformBuiltinVariables(lines);

        // Step 6: Add uniform block for matrix uniforms
        addUniformBlock(lines, shaderType);

        // Step 7: Transform texture functions
        transformTextureFunctions(lines);

        // Step 8: Apply VulkanMod-specific binding conventions
        String intermediateResult = String.join("\n", lines);
        VulkanModIntegration.VulkanBindingAnalysis bindingAnalysis =
            VulkanModIntegration.analyzeShaderBindings(intermediateResult, shaderType);

        String result = VulkanModIntegration.applyVulkanModBindings(intermediateResult, bindingAnalysis);
        LOGGER.debug("Translated {} shader from OpenGL to Vulkan GLSL 450 core", shaderType);

        return result;
    }

    /**
     * Transforms the #version directive to GLSL 450 core.
     */
    private void transformVersionDirective(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = VERSION_PATTERN.matcher(line);

            if (matcher.find()) {
                int version = Integer.parseInt(matcher.group(1));
                String profile = matcher.group(2);

                // Only upgrade versions that are older than 450
                if (version < 450) {
                    lines.set(i, "#version 450 core");
                    LOGGER.debug("Upgraded GLSL version from {} {} to 450 core", version, profile != null ? profile : "");
                }
                break; // Version directive should be first
            }
        }
    }

    /**
     * Adds Vulkan-specific compatibility directives.
     */
    private void addVulkanCompatibilityDirectives(List<String> lines, ShaderType shaderType) {
        int insertIndex = findVersionDirectiveIndex(lines) + 1;

        // Add GL_GOOGLE_include_directive support
        lines.add(insertIndex++, "#extension GL_GOOGLE_include_directive : enable");
        lines.add(insertIndex++, "");

        // For fragment shaders, add output declaration for gl_FragColor replacement
        if (shaderType == ShaderType.FRAGMENT) {
            boolean hasFragColorOutput = lines.stream()
                .anyMatch(line -> line.contains("out") && line.contains("fragColor"));

            if (!hasFragColorOutput) {
                lines.add(insertIndex++, "layout(location = 0) out vec4 fragColor;");
                lines.add(insertIndex++, "");
            }
        }
    }

    /**
     * Transforms legacy GLSL syntax to modern equivalents.
     */
    private void transformLegacySyntax(List<String> lines, ShaderType shaderType) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String originalLine = line;

            // Handle varying keyword - context dependent
            if (line.contains("varying")) {
                if (shaderType == ShaderType.VERTEX) {
                    line = line.replace("varying", "out");
                } else if (shaderType == ShaderType.FRAGMENT) {
                    line = line.replace("varying", "in");
                }
            }

            // Apply other legacy replacements
            for (Map.Entry<String, String> replacement : LEGACY_REPLACEMENTS.entrySet()) {
                if (!replacement.getKey().equals("varying")) { // Already handled above
                    line = replaceKeyword(line, replacement.getKey(), replacement.getValue());
                }
            }

            if (!line.equals(originalLine)) {
                lines.set(i, line);
                LOGGER.debug("Transformed legacy syntax: '{}' -> '{}'", originalLine.trim(), line.trim());
            }
        }
    }

    /**
     * Adds layout qualifiers for vertex attributes and inter-stage variables.
     */
    private void addLayoutQualifiers(List<String> lines, ShaderType shaderType, LayoutQualifierTracker layoutTracker) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Process attribute and varying declarations
            if ((line.startsWith("attribute ") && shaderType == ShaderType.VERTEX) ||
                (line.startsWith("in ") && !line.contains("layout(location")) ||
                (line.startsWith("out ") && !line.contains("layout(location"))) {

                String updatedLine = addLayoutQualifierToLine(lines.get(i), layoutTracker, shaderType);
                if (!updatedLine.equals(lines.get(i))) {
                    lines.set(i, updatedLine);
                    LOGGER.debug("Added layout qualifier: '{}' -> '{}'", line, updatedLine);
                }
            }
        }
    }

    /**
     * Adds layout qualifier to a specific line.
     */
    private String addLayoutQualifierToLine(String line, LayoutQualifierTracker layoutTracker, ShaderType shaderType) {
        // Extract variable declaration pattern
        String trimmed = line.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 3) {
            String qualifier = parts[0]; // attribute, in, out
            String type = parts[1];
            String variable = parts[2].replace(";", "");

            // Skip if already has layout qualifier
            if (line.contains("layout(")) {
                return line;
            }

            // Assign location
            int location = layoutTracker.getOrAssignLocation(variable);

            // Reconstruct line with layout qualifier
            String indent = getIndentation(line);
            return String.format("%slayout(location = %d) %s %s %s;", indent, location, qualifier, type, variable);
        }

        return line;
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
     * Adds uniform block for matrix uniforms and other common uniforms.
     */
    private void addUniformBlock(List<String> lines, ShaderType shaderType) {
        // Check if any built-in matrices are used
        boolean usesMatrices = lines.stream().anyMatch(line ->
            BUILTIN_REPLACEMENTS.keySet().stream().anyMatch(builtin ->
                builtin.contains("Matrix") && line.contains(builtin)));

        if (usesMatrices) {
            int insertIndex = findInsertionPointForUniforms(lines);

            lines.add(insertIndex++, "// VulkanMod standard uniform block");
            lines.add(insertIndex++, "layout(binding = 0, std140) uniform MatrixUniforms {");
            lines.add(insertIndex++, "    mat4 modelViewMatrix;");
            lines.add(insertIndex++, "    mat4 projectionMatrix;");
            lines.add(insertIndex++, "    mat4 modelViewProjectionMatrix;");
            lines.add(insertIndex++, "    mat3 normalMatrix;");
            lines.add(insertIndex++, "};");
            lines.add(insertIndex++, "");

            LOGGER.debug("Added matrix uniform block for {} shader", shaderType);
        }
    }

    /**
     * Finds the best insertion point for uniform declarations.
     */
    private int findInsertionPointForUniforms(List<String> lines) {
        // Insert after version and extension directives, before any other declarations
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.startsWith("#") && !line.isEmpty() && !line.startsWith("//")) {
                return i;
            }
        }
        return lines.size();
    }

    /**
     * Transforms built-in variables to modern equivalents.
     */
    private void transformBuiltinVariables(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String originalLine = line;

            for (Map.Entry<String, String> replacement : BUILTIN_REPLACEMENTS.entrySet()) {
                line = replaceKeyword(line, replacement.getKey(), replacement.getValue());
            }

            if (!line.equals(originalLine)) {
                lines.set(i, line);
                LOGGER.debug("Transformed built-in variable: '{}' -> '{}'", originalLine.trim(), line.trim());
            }
        }
    }

    /**
     * Transforms legacy texture functions to modern equivalents.
     */
    private void transformTextureFunctions(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String originalLine = line;

            for (Map.Entry<String, String> replacement : TEXTURE_REPLACEMENTS.entrySet()) {
                line = replaceKeyword(line, replacement.getKey(), replacement.getValue());
            }

            if (!line.equals(originalLine)) {
                lines.set(i, line);
                LOGGER.debug("Transformed texture function: '{}' -> '{}'", originalLine.trim(), line.trim());
            }
        }
    }

    /**
     * Replaces a keyword with proper word boundary matching.
     */
    private String replaceKeyword(String line, String keyword, String replacement) {
        // Use word boundaries to avoid partial matches
        String pattern = "\\b" + Pattern.quote(keyword) + "\\b";
        return line.replaceAll(pattern, replacement);
    }

    /**
     * Finds the index of the version directive line.
     */
    private int findVersionDirectiveIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (VERSION_PATTERN.matcher(lines.get(i)).find()) {
                return i;
            }
        }
        return 0; // If no version found, insert at beginning
    }

    /**
     * Shader type enumeration for context-aware processing.
     */
    public enum ShaderType {
        VERTEX("vert", "vsh"),
        FRAGMENT("frag", "fsh"),
        GEOMETRY("geom", "gsh"),
        TESSELLATION_CONTROL("tesc", "tcs"),
        TESSELLATION_EVALUATION("tese", "tes"),
        COMPUTE("comp", "csh");

        private final String[] extensions;

        ShaderType(String... extensions) {
            this.extensions = extensions;
        }

        public String[] getExtensions() {
            return extensions;
        }

        /**
         * Determines shader type from file name or extension.
         */
        public static ShaderType fromFileName(String fileName) {
            String lowerName = fileName.toLowerCase();

            for (ShaderType type : values()) {
                for (String ext : type.extensions) {
                    if (lowerName.endsWith("." + ext)) {
                        return type;
                    }
                }
            }

            // Fallback detection based on common patterns
            if (lowerName.contains("vert")) return VERTEX;
            if (lowerName.contains("frag")) return FRAGMENT;
            if (lowerName.contains("geom")) return GEOMETRY;

            // Default to fragment for unknown types
            return FRAGMENT;
        }
    }
}