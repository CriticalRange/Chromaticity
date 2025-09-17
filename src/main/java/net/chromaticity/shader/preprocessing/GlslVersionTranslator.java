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

        // Step 1: Fix comment syntax issues that cause INTCONSTANT errors
        fixCommentSyntaxIssues(lines);

        // Step 1b: Fix multiline comment content issues
        String joinedForMultilinefix = String.join("\n", lines);
        joinedForMultilinefix = fixMultilineCommentContent(joinedForMultilinefix);
        lines = new ArrayList<>(Arrays.asList(joinedForMultilinefix.split("\n")));

        // Step 2: Transform version directive
        transformVersionDirective(lines);

        // Step 3: Add required extensions and outputs for fragment shaders
        addVulkanCompatibilityDirectives(lines, shaderType);

        // Step 4: Transform legacy syntax
        transformLegacySyntax(lines, shaderType);

        // Step 5: Add layout qualifiers for vertex attributes
        LayoutQualifierTracker layoutTracker = new LayoutQualifierTracker();
        addLayoutQualifiers(lines, shaderType, layoutTracker);

        // Step 6: Transform built-in variables
        transformBuiltinVariables(lines);

        // Step 7: Add uniform block for matrix uniforms
        addUniformBlock(lines, shaderType);

        // Step 8: Transform texture functions
        transformTextureFunctions(lines);

        // Step 9: Apply VulkanMod-specific binding conventions FIRST
        String intermediateResult = String.join("\n", lines);
        VulkanModIntegration.VulkanBindingAnalysis bindingAnalysis =
            VulkanModIntegration.analyzeShaderBindings(intermediateResult, shaderType);

        String vulkanModResult = VulkanModIntegration.applyVulkanModBindings(intermediateResult, bindingAnalysis);

        // Step 10: Fix Vulkan GLSL compliance issues AFTER VulkanMod adds uniforms
        List<String> finalLines = new ArrayList<>(Arrays.asList(vulkanModResult.split("\n")));
        fixVulkanCompliance(finalLines, shaderType);

        // Step 11: Fix operator spacing issues that cause INTCONSTANT errors
        String finalResult = String.join("\n", finalLines);
        finalResult = fixOperatorSpacing(finalResult);
        finalLines = new ArrayList<>(Arrays.asList(finalResult.split("\n")));

        // Step 12: Fix broken GLSL syntax (types, uniforms, function names)
        fixBrokenGLSLSyntax(finalLines);

        // Step 13: Ensure #version directive is absolutely first (critical for SPIR-V compilation)
        ensureVersionDirectiveFirst(finalLines);

        String result = String.join("\n", finalLines);

        // Ensure proper file ending with newline (critical for shaderc)
        result = ensureProperFileEnding(result);

        LOGGER.debug("Translated {} shader from OpenGL to Vulkan GLSL 450 core", shaderType);

        return result;
    }

    /**
     * Translates an include file with minimal transformations.
     * Does NOT add version directives or output declarations to avoid conflicts when included.
     */
    public String translateShaderMinimal(String source, ShaderType shaderType) {
        if (source == null || source.trim().isEmpty()) {
            return source;
        }

        List<String> lines = new ArrayList<>(List.of(source.split("\n")));

        // Step 1: Fix comment syntax issues that cause INTCONSTANT errors
        fixCommentSyntaxIssues(lines);

        // Step 2: Fix multiline comment content issues
        String joinedForMultilinefix = String.join("\n", lines);
        joinedForMultilinefix = fixMultilineCommentContent(joinedForMultilinefix);
        lines = new ArrayList<>(Arrays.asList(joinedForMultilinefix.split("\n")));

        // Step 3: Apply basic legacy syntax replacements (but skip version/extension directives)
        transformLegacySyntax(lines, shaderType);

        // Step 4: Fix texture function calls
        transformTextureFunctions(lines);

        // Step 5: Add include directive for include files
        // Since this is translateShaderMinimal (for include files), always add the extension
        // Include files might need it for nested includes even if not immediately apparent
        addIncludeDirectiveIfNeeded(lines);
        LOGGER.debug("Processing include file: ensuring GL_GOOGLE_include_directive is present");

        // Step 6: Fix operator spacing issues that cause INTCONSTANT errors
        String finalResult = String.join("\n", lines);
        finalResult = fixOperatorSpacing(finalResult);
        lines = new ArrayList<>(Arrays.asList(finalResult.split("\n")));

        // Step 7: Fix broken GLSL syntax (types, uniforms, function names, include paths)
        fixBrokenGLSLSyntax(lines);

        String result = String.join("\n", lines);

        // Ensure proper file ending with newline (critical for shaderc)
        result = ensureProperFileEnding(result);

        LOGGER.debug("Applied minimal translation to include file");

        return result;
    }

    /**
     * Fixes comment syntax issues that can cause INTCONSTANT parsing errors.
     * Moves problematic comment content to separate lines to avoid parser confusion.
     */
    private void fixCommentSyntaxIssues(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) { // Process backwards to avoid index issues when inserting
            String line = lines.get(i);
            String originalLine = line;

            // Fix comments with array-like syntax that causes INTCONSTANT errors
            // Move the problematic comment part to its own line
            if (line.contains("//") && line.contains("[") && line.contains("]")) {
                int commentStart = line.indexOf("//");
                String beforeComment = line.substring(0, commentStart).trim();
                String commentPart = line.substring(commentStart);

                // Check if this looks like the problematic pattern
                if (commentPart.matches(".*//\\s*\\[\\d+(?:\\s+\\d+)+\\].*")) {
                    // Split into code line and comment line
                    lines.set(i, beforeComment);
                    lines.add(i + 1, "// " + commentPart.substring(2).trim());

                    LOGGER.debug("Moved problematic comment to separate line: '{}'", originalLine.trim());
                }
            }
        }
    }

    /**
     * Converts all multiline comments to single-line comments to avoid parsing issues.
     */
    private String fixMultilineCommentContent(String source) {
        StringBuilder result = new StringBuilder();
        boolean inMultilineComment = false;
        String[] lines = source.split("\n");

        for (String line : lines) {
            String originalLine = line;
            String processedLine = line;

            // Handle start of multiline comment
            if (line.contains("/*")) {
                inMultilineComment = true;
                // Replace /* with //
                processedLine = processedLine.replace("/*", "//");
            }

            // Handle end of multiline comment
            if (line.contains("*/")) {
                inMultilineComment = false;
                // Remove */ entirely
                processedLine = processedLine.replace("*/", "");
            }

            // If we're inside a multiline comment block, convert to single-line comment
            if (inMultilineComment && !line.trim().startsWith("*") && !line.contains("/*")) {
                String trimmed = processedLine.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                    // Get the indentation and convert to single-line comment
                    String indent = getIndentation(processedLine);
                    processedLine = indent + "// " + trimmed;
                }
            }

            // Handle lines that start with * inside multiline comments
            if (inMultilineComment && line.trim().startsWith("*") && !line.trim().startsWith("*/")) {
                String trimmed = processedLine.trim();
                if (trimmed.startsWith("*")) {
                    trimmed = trimmed.substring(1).trim(); // Remove leading *
                }
                if (!trimmed.isEmpty()) {
                    String indent = getIndentation(processedLine);
                    processedLine = indent + "// " + trimmed;
                }
            }

            if (!processedLine.equals(originalLine)) {
                LOGGER.debug("Converted multiline comment to single-line in main shader: '{}' -> '{}'",
                    originalLine.trim(), processedLine.trim());
            }

            result.append(processedLine);
            if (!line.equals(lines[lines.length - 1])) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Fixes excessive spacing around operators that can confuse the GLSL parser.
     */
    private String fixOperatorSpacing(String source) {
        String[] lines = source.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            String originalLine = line;
            String fixedLine = line;

            // Skip comment lines and preprocessor directives
            String trimmed = line.trim();
            if (!trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("#") && !trimmed.isEmpty()) {
                // Fix INTCONSTANT syntax errors specifically
                fixedLine = fixIntConstantSyntax(fixedLine);

                // Fix excessive spaces around equals signs
                fixedLine = fixedLine.replaceAll("\\s{2,}=\\s*", " = ");
                fixedLine = fixedLine.replaceAll("\\s*=\\s{2,}", " = ");

                // Fix excessive spaces around other operators
                fixedLine = fixedLine.replaceAll("\\s{3,}", " ");

                if (!fixedLine.equals(originalLine)) {
                    LOGGER.debug("Fixed operator spacing in main shader: '{}'", originalLine.trim());
                }
            }

            result.append(fixedLine);
            if (!line.equals(lines[lines.length - 1])) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Fixes specific INTCONSTANT syntax errors that occur during compilation.
     * Uses a safer approach that doesn't use placeholders.
     */
    private String fixIntConstantSyntax(String line) {
        String fixed = line;

        // Skip lines that contain GLSL built-in types to avoid breaking them
        String[] protectedPatterns = {
            "\\bvec[234]\\b", "\\bmat[234]\\b", "\\bivec[234]\\b", "\\buvec[234]\\b",
            "\\bsampler2D\\b", "\\bsampler3D\\b", "\\bsamplerCube\\b",
            "\\bstd140\\b", "\\bstd430\\b", "\\bmat[234]x[234]\\b"
        };

        for (String pattern : protectedPatterns) {
            if (fixed.matches(".*" + pattern + ".*")) {
                // This line contains protected GLSL syntax, be very conservative
                return applyMinimalIntConstantFixes(fixed);
            }
        }

        // Safe to apply more aggressive fixes for lines without GLSL types

        // Fix cases where numbers are directly adjacent to identifiers: "123abc" -> "123 abc"
        // But be careful not to break valid patterns
        fixed = fixed.replaceAll("(\\d+)([a-zA-Z_][a-zA-Z_]*(?![0-9]))", "$1 $2");

        // Fix cases where identifiers are directly adjacent to numbers: "abc123" -> "abc 123"
        // But avoid breaking GLSL types like vec3, mat4, etc.
        fixed = fixed.replaceAll("([a-zA-Z_](?!vec|mat|ivec|uvec|sampler))(\\d+)(?![Dx])", "$1 $2");

        // Fix multiple consecutive numbers without operators: "123 456" -> "123, 456"
        fixed = fixed.replaceAll("(\\d+)\\s+(\\d+)(?![eE])", "$1, $2");

        // Fix number followed by decimal point issues: "123." -> "123.0"
        fixed = fixed.replaceAll("(\\d+)\\.$", "$1.0");

        // Fix malformed floating point: ".123" -> "0.123"
        fixed = fixed.replaceAll("\\B\\.(\\d+)", "0.$1");

        // Fix hexadecimal constants that might be malformed: "0x" -> "0x0"
        fixed = fixed.replaceAll("\\b0x\\b", "0x0");

        // Fix exponential notation issues: "1e" -> "1e0", "1E" -> "1E0"
        fixed = fixed.replaceAll("(\\d+)[eE]\\s*$", "$1e0");
        fixed = fixed.replaceAll("(\\d+)[eE]\\s+", "$1e0 ");

        return fixed;
    }

    /**
     * Applies minimal INTCONSTANT fixes for lines containing GLSL built-in types.
     */
    private String applyMinimalIntConstantFixes(String line) {
        String fixed = line;

        // Only fix the most obvious issues without risking GLSL type corruption

        // Fix number followed by decimal point issues: "123." -> "123.0"
        fixed = fixed.replaceAll("(\\d+)\\.$", "$1.0");

        // Fix malformed floating point: ".123" -> "0.123"
        fixed = fixed.replaceAll("\\B\\.(\\d+)", "0.$1");

        // Fix hexadecimal constants that might be malformed: "0x" -> "0x0"
        fixed = fixed.replaceAll("\\b0x\\b", "0x0");

        return fixed;
    }


    /**
     * Fixes broken GLSL syntax that may have been introduced during processing.
     * This includes fixing spaced type names, layout qualifiers, and function names.
     */
    private void fixBrokenGLSLSyntax(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String originalLine = line;

            // Fix common broken GLSL type names
            line = line.replaceAll("\\bmut ([234])\\b", "mat$1");
            line = line.replaceAll("\\bmat ([234])\\b", "mat$1");
            line = line.replaceAll("\\bvec ([234])\\b", "vec$1");
            line = line.replaceAll("\\bivec ([234])\\b", "ivec$1");
            line = line.replaceAll("\\buvec ([234])\\b", "uvec$1");

            // Fix sampler types
            line = line.replaceAll("\\bsampler ([234])\\s*D\\b", "sampler$1D");
            line = line.replaceAll("\\bsampler 2 D\\b", "sampler2D");
            line = line.replaceAll("\\bsampler 3 D\\b", "sampler3D");
            line = line.replaceAll("\\bsampler Cube\\b", "samplerCube");

            // Fix layout qualifiers
            line = line.replaceAll("\\bstd ([14][34]0)\\b", "std$1");

            // Fix common function names that might have been broken
            line = line.replaceAll("\\bclamp ([0-9]+) F\\b", "clamp$1F");
            line = line.replaceAll("\\bpack ([0-9]+) x ([0-9]+)\\b", "pack$1x$2");
            line = line.replaceAll("\\bunpack ([0-9]+) x ([0-9]+)\\b", "unpack$1x$2");
            line = line.replaceAll("\\bencodeRGBE ([0-9]+)\\b", "encodeRGBE$1");
            line = line.replaceAll("\\bdecodeRGBE ([0-9]+)\\b", "decodeRGBE$1");

            // Fix matrix dimension specifiers
            line = line.replaceAll("\\bmat ([0-9]+) x ([0-9]+)\\b", "mat$1x$2");
            line = line.replaceAll("\\bmat([0-9]+)x ([0-9]+)\\b", "mat$1x$2");

            // Fix pack/unpack function names
            line = line.replaceAll("\\bpack([0-9]+)x ([0-9]+)\\b", "pack$1x$2");
            line = line.replaceAll("\\bunpack([0-9]+)x ([0-9]+)\\b", "unpack$1x$2");

            // Fix variable/constant names that got broken (bool values)
            line = line.replaceAll("\\bcolortex ([0-9]+) Clear\\b", "colortex$1Clear");
            line = line.replaceAll("\\bcolortex ([0-9]+)\\b", "colortex$1");
            line = line.replaceAll("\\bdepthtex ([0-9]+)\\b", "depthtex$1");

            // Fix relative include paths by converting them to absolute paths
            // This fixes issues where relative includes like "internal.glsl" cause SPIR-V compilation errors
            if (line.trim().startsWith("#include \"") && !line.contains("/")) {
                // This is a relative include without a path - convert to absolute path
                String includeFile = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'));
                String absoluteInclude = "/" + includeFile;
                line = line.replace("\"" + includeFile + "\"", "\"" + absoluteInclude + "\"");
                LOGGER.info("Normalized relative include: '{}' -> '{}'", originalLine.trim(), line.trim());
            }

            if (!line.equals(originalLine)) {
                lines.set(i, line);
                LOGGER.debug("Fixed broken GLSL syntax: '{}' -> '{}'", originalLine.trim(), line.trim());
            }
        }
    }

    /**
     * Ensures #version directive is absolutely first in the shader.
     * Critical for SPIR-V compilation - #version MUST be the first non-comment line.
     */
    private void ensureVersionDirectiveFirst(List<String> lines) {
        String versionLine = null;
        int versionIndex = -1;

        // Find the version directive
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("#version")) {
                versionLine = lines.get(i);
                versionIndex = i;
                break;
            }
        }

        if (versionLine == null) {
            // No version directive found, add one
            versionLine = "#version 450 core";
            lines.add(0, versionLine);
            LOGGER.debug("Added missing #version directive at start of shader");
            return;
        }

        // If version directive is not first (after skipping comments and empty lines), move it
        int firstNonCommentIndex = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.isEmpty() && !line.startsWith("//") && !line.startsWith("/*")) {
                firstNonCommentIndex = i;
                break;
            }
        }

        if (versionIndex != firstNonCommentIndex) {
            // Remove version from current position
            lines.remove(versionIndex);

            // Insert at correct position (after comments but first among directives)
            lines.add(firstNonCommentIndex, versionLine);

            LOGGER.debug("Moved #version directive to first position in shader");
        }
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

        LOGGER.debug("Ensured proper file ending with newline character in main shader");
        return result;
    }

    /**
     * Adds the GL_GOOGLE_include_directive extension if not already present.
     * This is needed for include files that contain #include statements.
     */
    private void addIncludeDirectiveIfNeeded(List<String> lines) {
        // Check if extension is already present
        boolean hasIncludeDirective = lines.stream()
            .anyMatch(line -> line.contains("GL_GOOGLE_include_directive"));

        LOGGER.debug("addIncludeDirectiveIfNeeded called - hasIncludeDirective: {}, total lines: {}", hasIncludeDirective, lines.size());

        if (!hasIncludeDirective) {
            // Find insertion point for include files
            int insertIndex = findBestInsertionPointForIncludeDirective(lines);
            LOGGER.debug("Insertion point found at index: {}", insertIndex);

            // Add the extension directive
            lines.add(insertIndex, "#extension GL_GOOGLE_include_directive : enable");
            lines.add(insertIndex + 1, ""); // Add blank line for readability
            LOGGER.info("Successfully added GL_GOOGLE_include_directive to include file at index {}", insertIndex);

            // Log the first few lines after modification for verification
            LOGGER.debug("First 5 lines after adding extension:");
            for (int i = 0; i < Math.min(5, lines.size()); i++) {
                LOGGER.debug("  {}: {}", i, lines.get(i));
            }
        } else {
            LOGGER.debug("GL_GOOGLE_include_directive already present, skipping addition");
        }
    }

    /**
     * Finds the best place to insert the GL_GOOGLE_include_directive in an include file.
     */
    private int findBestInsertionPointForIncludeDirective(List<String> lines) {
        // Look for #version directive first
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("#version")) {
                return i + 1; // Insert after version
            }
        }

        // No #version found - insert at the first non-comment, non-empty line
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.isEmpty() && !line.startsWith("//") && !line.startsWith("/*") && !line.startsWith("*")) {
                // Insert before the first actual content
                return i;
            }
        }

        // If everything is comments, insert at the end
        return lines.size();
    }

    /**
     * Transforms the #version directive to GLSL 450 core.
     */
    private void transformVersionDirective(List<String> lines) {
        boolean foundVersion = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = VERSION_PATTERN.matcher(line);

            if (matcher.find()) {
                foundVersion = true;
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

        // If no version directive was found, add one at the beginning
        if (!foundVersion) {
            lines.add(0, "#version 450 core");
            LOGGER.debug("Added missing GLSL version directive: 450 core");
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
    private static int findInsertionPointForUniforms(List<String> lines) {
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

    /**
     * Fixes Vulkan GLSL compliance issues.
     */
    private static void fixVulkanCompliance(List<String> lines, ShaderType shaderType) {
        // Fix 1: Convert loose uniforms to uniform blocks
        convertLooseUniformsToBlocks(lines);

        // Fix 2: Remove duplicate layout location assignments
        fixDuplicateLayoutLocations(lines);

        // Fix 3: Fix binding qualifier syntax for Vulkan
        fixBindingQualifiers(lines);
    }

    /**
     * Converts loose uniforms to proper uniform blocks as required by Vulkan.
     * In Vulkan GLSL, only loose non-opaque uniforms (without layout bindings) need to be in uniform blocks.
     * Uniforms with layout bindings (samplers, bound matrices) should remain individual.
     */
    private static void convertLooseUniformsToBlocks(List<String> lines) {
        List<String> looseUniforms = new ArrayList<>();
        List<Integer> linesToRemove = new ArrayList<>();

        // First pass: identify ONLY loose non-opaque uniforms (no layout bindings)
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Skip comments, empty lines, and preprocessor directives
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
            }

            // Only process simple uniform declarations WITHOUT layout bindings
            // Pattern: "uniform type name;" (no layout qualifier)
            if (line.matches("^\\s*uniform\\s+\\w+\\s+\\w+\\s*;\\s*$")) {
                // Extract uniform type and name
                String uniformDeclaration = extractUniformDeclaration(line);
                String uniformType = extractUniformType(line);

                // Check if it's a non-opaque type that needs to be in a uniform block
                if (uniformDeclaration != null && isNonOpaqueType(uniformType)) {
                    looseUniforms.add(uniformDeclaration);
                    linesToRemove.add(i);
                    LOGGER.debug("Found loose uniform for block conversion: {}", line.trim());
                }
            }
        }

        // Remove loose uniform declarations (in reverse order to maintain indices)
        for (int i = linesToRemove.size() - 1; i >= 0; i--) {
            lines.remove((int) linesToRemove.get(i));
        }

        // Add uniform block if we found loose uniforms
        if (!looseUniforms.isEmpty()) {
            int insertIndex = findInsertionPointForUniforms(lines);

            lines.add(insertIndex++, "// Uniform block for loose non-opaque uniforms (Vulkan requirement)");
            lines.add(insertIndex++, "layout(binding = 1, std140) uniform GlobalUniforms {");

            for (String uniformDecl : looseUniforms) {
                lines.add(insertIndex++, "    " + uniformDecl);
            }

            lines.add(insertIndex++, "} globals;");
            lines.add(insertIndex++, "");


            // Update references to use 'globals.' prefix
            updateUniformReferences(lines, looseUniforms);
        } else {
            LOGGER.debug("No loose uniforms found that require uniform block conversion");
        }
    }

    /**
     * Extracts uniform declaration from a line.
     */
    private static String extractUniformDeclaration(String line) {
        // Remove layout qualifiers and extract just the type and name
        String cleaned = line.replaceAll("layout\\s*\\([^)]+\\)\\s*", "").trim();

        // Pattern: uniform type name; or uniform type name = value;
        Pattern pattern = Pattern.compile("uniform\\s+(\\w+(?:\\[\\d*\\])?)\\s+(\\w+)(?:\\s*=\\s*[^;]+)?\\s*;");
        Matcher matcher = pattern.matcher(cleaned);

        if (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            return type + " " + name + ";";
        }

        return null;
    }

    /**
     * Extracts uniform type from a line.
     */
    private static String extractUniformType(String line) {
        Pattern pattern = Pattern.compile("uniform\\s+(\\w+(?:\\[\\d*\\])?)");
        Matcher matcher = pattern.matcher(line);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return "";
    }

    /**
     * Determines if a uniform type is non-opaque and requires a uniform block in Vulkan.
     * Opaque types (samplers, images) can remain as individual uniforms.
     */
    private static boolean isNonOpaqueType(String type) {
        // Opaque types that can remain as individual uniforms
        String[] opaqueTypes = {
            "sampler1D", "sampler2D", "sampler3D", "samplerCube",
            "sampler1DArray", "sampler2DArray", "samplerCubeArray",
            "sampler2DMS", "sampler2DMSArray",
            "sampler1DShadow", "sampler2DShadow", "samplerCubeShadow",
            "sampler1DArrayShadow", "sampler2DArrayShadow", "samplerCubeArrayShadow",
            "samplerBuffer", "sampler2DRect", "sampler2DRectShadow",
            "isampler1D", "isampler2D", "isampler3D", "isamplerCube",
            "isampler1DArray", "isampler2DArray", "isamplerCubeArray",
            "isampler2DMS", "isampler2DMSArray", "isamplerBuffer", "isampler2DRect",
            "usampler1D", "usampler2D", "usampler3D", "usamplerCube",
            "usampler1DArray", "usampler2DArray", "usamplerCubeArray",
            "usampler2DMS", "usampler2DMSArray", "usamplerBuffer", "usampler2DRect",
            "image1D", "image2D", "image3D", "imageCube",
            "image1DArray", "image2DArray", "imageCubeArray",
            "image2DMS", "image2DMSArray", "imageBuffer", "image2DRect",
            "iimage1D", "iimage2D", "iimage3D", "iimageCube",
            "iimage1DArray", "iimage2DArray", "iimageCubeArray",
            "iimage2DMS", "iimage2DMSArray", "iimageBuffer", "iimage2DRect",
            "uimage1D", "uimage2D", "uimage3D", "uimageCube",
            "uimage1DArray", "uimage2DArray", "uimageCubeArray",
            "uimage2DMS", "uimage2DMSArray", "uimageBuffer", "uimage2DRect",
            "atomic_uint"
        };

        // Remove array notation for comparison
        String baseType = type.replaceAll("\\[\\d*\\]", "");

        for (String opaqueType : opaqueTypes) {
            if (baseType.equals(opaqueType)) {
                return false; // It's opaque, doesn't need uniform block
            }
        }

        // Non-opaque types (float, int, vec*, mat*, etc.) need uniform blocks
        return true;
    }

    /**
     * Updates uniform references to use the 'globals.' prefix after moving to uniform block.
     */
    private static void updateUniformReferences(List<String> lines, List<String> uniforms) {
        // Extract uniform names from declarations
        Set<String> uniformNames = new HashSet<>();
        for (String uniformDecl : uniforms) {
            String[] parts = uniformDecl.split("\\s+");
            if (parts.length >= 2) {
                String name = parts[1].replace(";", "");
                uniformNames.add(name);
            }
        }

        // Track whether we're inside the uniform block declaration
        boolean insideUniformBlock = false;

        // Update references in shader code
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String originalLine = line;

            // Skip lines inside uniform block declaration
            if (line.contains("uniform GlobalUniforms")) {
                insideUniformBlock = true;
            }
            if (insideUniformBlock && line.contains("} globals;")) {
                insideUniformBlock = false;
            }
            if (insideUniformBlock) {
                continue; // Don't modify lines inside the uniform block declaration
            }

            // Skip lines that shouldn't have uniform replacement
            String trimmedLine = line.trim();

            // Skip preprocessor directives, comments, and function declarations
            boolean shouldSkip = trimmedLine.startsWith("#") ||         // Preprocessor directives
                                trimmedLine.startsWith("//") ||        // Single-line comments
                                trimmedLine.startsWith("/*") ||        // Multi-line comments
                                line.contains("*/") ||                 // End of multi-line comments
                                (trimmedLine.matches(".*\\w+\\s*\\([^)]*\\)\\s*\\{?") &&  // Function declarations
                                 !trimmedLine.contains("=") &&
                                 (trimmedLine.contains("vec") || trimmedLine.contains("mat") || trimmedLine.contains("float") || trimmedLine.contains("int")));

            if (!shouldSkip) {
                for (String uniformName : uniformNames) {
                    // Use word boundaries to avoid partial matches
                    String pattern = "\\b" + Pattern.quote(uniformName) + "\\b";
                    line = line.replaceAll(pattern, "globals." + uniformName);
                }
            }

            if (!line.equals(originalLine)) {
                lines.set(i, line);
            }
        }
    }

    /**
     * Fixes duplicate layout location assignments.
     */
    private static void fixDuplicateLayoutLocations(List<String> lines) {
        Set<Integer> usedLocations = new HashSet<>();
        int nextLocation = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Look for layout location assignments
            if (line.matches(".*layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\).*")) {
                String locationStr = line.replaceAll(".*location\\s*=\\s*(\\d+).*", "$1");
                try {
                    int location = Integer.parseInt(locationStr);

                    if (usedLocations.contains(location)) {
                        // Find next available location
                        while (usedLocations.contains(nextLocation)) {
                            nextLocation++;
                        }

                        // Replace with available location
                        String newLine = line.replaceAll("location\\s*=\\s*\\d+", "location = " + nextLocation);
                        lines.set(i, newLine);
                        usedLocations.add(nextLocation);
                        nextLocation++;
                    } else {
                        usedLocations.add(location);
                        nextLocation = Math.max(nextLocation, location + 1);
                    }
                } catch (NumberFormatException e) {
                    // Skip invalid location numbers
                }
            }
        }
    }

    /**
     * Fixes binding qualifier syntax for Vulkan compatibility.
     */
    private static void fixBindingQualifiers(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            // Fix binding qualifiers that aren't on appropriate types
            if (line.contains("layout") && line.contains("binding") &&
                !line.contains("uniform") && !line.contains("sampler") &&
                !line.contains("image") && !line.contains("atomic")) {

                // Remove binding qualifier from inappropriate types
                String fixedLine = line.replaceAll("layout\\s*\\([^)]*binding[^)]*\\)\\s*", "");
                lines.set(i, fixedLine);
            }
        }
    }
}