package net.chromaticity.shader.compilation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixes legacy GLSL syntax to make it compatible with GLSL 450 core and SPIR-V compilation.
 * Handles varying/attribute keywords, gl_FragData/gl_FragColor, and other legacy constructs.
 */
public class PreprocessingFix {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreprocessingFix.class);

    // Pre-compiled patterns for performance
    private static final Pattern VARYING_PATTERN = Pattern.compile("^\\s*varying\\s+(.+);\\s*$", Pattern.MULTILINE);
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("^\\s*attribute\\s+(.+);\\s*$", Pattern.MULTILINE);
    private static final Pattern GL_FRAG_DATA_PATTERN = Pattern.compile("gl_FragData\\[(\\d+)\\]");
    private static final Pattern GL_FRAG_COLOR_PATTERN = Pattern.compile("\\bgl_FragColor\\b");
    private static final Pattern TEXTURE2D_PATTERN = Pattern.compile("\\btexture2D\\s*\\(");
    private static final Pattern TEXTURE2DLOD_PATTERN = Pattern.compile("\\btexture2DLod\\s*\\(");
    private static final Pattern TEXTURE2DPROJ_PATTERN = Pattern.compile("\\btexture2DProj\\s*\\(");
    private static final Pattern TEXTURECUBE_PATTERN = Pattern.compile("\\btextureCube\\s*\\(");
    private static final Pattern TEXTURECUBELOD_PATTERN = Pattern.compile("\\btextureCubeLod\\s*\\(");
    private static final Pattern TEXTURE3D_PATTERN = Pattern.compile("\\btexture3D\\s*\\(");
    private static final Pattern TEXTURE3DLOD_PATTERN = Pattern.compile("\\btexture3DLod\\s*\\(");

    /**
     * Applies all legacy GLSL fixes to make source compatible with GLSL 450 core.
     */
    public static String applyAllFixes(String source, String filename, ShadercCompiler.ShaderStage stage) {
        LOGGER.debug("Applying legacy GLSL fixes to: {}", filename);

        String result = source;
        boolean modified = false;

        // Fix varying/attribute keywords
        String afterVaryingFix = fixVaryingAndAttributeKeywords(result, filename, stage);
        if (!afterVaryingFix.equals(result)) {
            result = afterVaryingFix;
            modified = true;
        }

        // Fix fragment output built-ins
        if (stage == ShadercCompiler.ShaderStage.FRAGMENT) {
            String afterFragmentFix = fixFragmentOutputs(result, filename);
            if (!afterFragmentFix.equals(result)) {
                result = afterFragmentFix;
                modified = true;
            }
        }

        // Fix legacy texture functions
        String afterTextureFix = fixLegacyTextureFunctions(result, filename);
        if (!afterTextureFix.equals(result)) {
            result = afterTextureFix;
            modified = true;
        }

        if (modified) {
            LOGGER.info("Applied legacy GLSL fixes to: {}", filename);
        }

        return result;
    }

    /**
     * Fixes varying/attribute keywords for GLSL 450 core compatibility.
     * varying → in/out with layout qualifiers
     * attribute → in with layout qualifiers
     */
    public static String fixVaryingAndAttributeKeywords(String source, String filename, ShadercCompiler.ShaderStage stage) {
        String[] lines = source.split("\n");
        StringBuilder result = new StringBuilder();
        int locationCounter = 0;
        boolean modified = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Handle varying declarations
            Matcher varyingMatcher = VARYING_PATTERN.matcher(line);
            if (varyingMatcher.matches()) {
                String declaration = varyingMatcher.group(1);

                if (stage == ShadercCompiler.ShaderStage.VERTEX) {
                    // In vertex shaders: varying → layout(location = N) out
                    result.append(String.format("layout(location = %d) out %s;", locationCounter, declaration));
                    LOGGER.debug("Fixed varying → out with location {} in vertex shader: {}", locationCounter, filename);
                } else if (stage == ShadercCompiler.ShaderStage.FRAGMENT) {
                    // In fragment shaders: varying → layout(location = N) in
                    result.append(String.format("layout(location = %d) in %s;", locationCounter, declaration));
                    LOGGER.debug("Fixed varying → in with location {} in fragment shader: {}", locationCounter, filename);
                }
                locationCounter++;
                modified = true;
            }
            // Handle attribute declarations (vertex shaders only)
            else if (stage == ShadercCompiler.ShaderStage.VERTEX) {
                Matcher attributeMatcher = ATTRIBUTE_PATTERN.matcher(line);
                if (attributeMatcher.matches()) {
                    String declaration = attributeMatcher.group(1);
                    result.append(String.format("layout(location = %d) in %s;", locationCounter, declaration));
                    LOGGER.debug("Fixed attribute → in with location {} in vertex shader: {}", locationCounter, filename);
                    locationCounter++;
                    modified = true;
                } else {
                    result.append(line);
                }
            } else {
                result.append(line);
            }

            result.append("\n");
        }

        if (modified) {
            LOGGER.info("Fixed {} varying/attribute declarations in: {}", locationCounter, filename);
        }

        return result.toString();
    }

    /**
     * Fixes deprecated fragment output built-ins (gl_FragData, gl_FragColor) for GLSL 450+.
     * Replaces with explicit output declarations and layout qualifiers.
     */
    public static String fixFragmentOutputs(String source, String filename) {
        boolean modified = false;
        Set<Integer> usedFragDataIndices = new HashSet<>();

        // Scan for gl_FragData usage to determine how many outputs we need
        Matcher fragDataMatcher = GL_FRAG_DATA_PATTERN.matcher(source);
        while (fragDataMatcher.find()) {
            int index = Integer.parseInt(fragDataMatcher.group(1));
            usedFragDataIndices.add(index);
        }

        // Check for gl_FragColor usage
        boolean usesFragColor = GL_FRAG_COLOR_PATTERN.matcher(source).find();

        // If we found usage of deprecated outputs, we need to add explicit outputs and replace them
        if (!usedFragDataIndices.isEmpty() || usesFragColor) {
            String[] lines = source.split("\n");
            StringBuilder newSource = new StringBuilder();
            boolean outputsAdded = false;

            for (String line : lines) {
                // Add output declarations after version but before other content
                if (!outputsAdded && !line.trim().startsWith("#") &&
                    !line.trim().isEmpty() && !line.trim().startsWith("//") &&
                    !line.trim().startsWith("layout") && !line.trim().startsWith("const")) {

                    // Add gl_FragColor replacement if used
                    if (usesFragColor) {
                        newSource.append("layout(location = 0) out vec4 fragColor;\n");
                        LOGGER.debug("Added fragColor output declaration for gl_FragColor replacement: {}", filename);
                    }

                    // Add gl_FragData replacements
                    for (int index : usedFragDataIndices) {
                        newSource.append(String.format("layout(location = %d) out vec4 fragData%d;\n", index, index));
                        LOGGER.debug("Added fragData{} output declaration for gl_FragData[{}] replacement: {}", index, index, filename);
                    }

                    if (!usedFragDataIndices.isEmpty() || usesFragColor) {
                        newSource.append("\n");
                    }
                    outputsAdded = true;
                }

                // Replace gl_FragData[N] with fragDataN
                String processedLine = line;
                for (int index : usedFragDataIndices) {
                    processedLine = processedLine.replace("gl_FragData[" + index + "]", "fragData" + index);
                }

                // Replace gl_FragColor with fragColor
                if (processedLine.contains("gl_FragColor")) {
                    processedLine = GL_FRAG_COLOR_PATTERN.matcher(processedLine).replaceAll("fragColor");
                }

                newSource.append(processedLine).append("\n");
            }

            modified = true;
            LOGGER.info("Modernized fragment outputs (gl_FragData/gl_FragColor) for GLSL 450 compatibility: {}", filename);
            return newSource.toString();
        }

        return source;
    }

    /**
     * Fixes legacy texture function names for GLSL 450 compatibility.
     * texture2D → texture, texture2DLod → textureLod, etc.
     */
    public static String fixLegacyTextureFunctions(String source, String filename) {
        String result = source;
        boolean modified = false;

        // Array of texture function replacements
        Pattern[] patterns = {
            TEXTURE2D_PATTERN,
            TEXTURE2DLOD_PATTERN,
            TEXTURE2DPROJ_PATTERN,
            TEXTURECUBE_PATTERN,
            TEXTURECUBELOD_PATTERN,
            TEXTURE3D_PATTERN,
            TEXTURE3DLOD_PATTERN
        };

        String[] replacements = {
            "texture(",
            "textureLod(",
            "textureProj(",
            "texture(",
            "textureLod(",
            "texture(",
            "textureLod("
        };

        String[] functionNames = {
            "texture2D",
            "texture2DLod",
            "texture2DProj",
            "textureCube",
            "textureCubeLod",
            "texture3D",
            "texture3DLod"
        };

        for (int i = 0; i < patterns.length; i++) {
            if (patterns[i].matcher(result).find()) {
                result = patterns[i].matcher(result).replaceAll(replacements[i]);
                modified = true;
                LOGGER.debug("Modernized texture function {} → {} in: {}",
                           functionNames[i], replacements[i].replace("(", ""), filename);
            }
        }

        if (modified) {
            LOGGER.info("Modernized legacy texture functions in: {}", filename);
        }

        return result;
    }

    /**
     * Wrap standalone uniform declarations in uniform blocks for Vulkan compliance.
     * Vulkan SPIR-V requires non-opaque uniforms to be in uniform blocks.
     */
    public static String wrapUniformsInBlocks(String source, String filename) {
        String[] lines = source.split("\n");
        StringBuilder result = new StringBuilder();
        java.util.List<String> uniformDeclarations = new java.util.ArrayList<>();
        boolean inUniformBlock = false;
        int uniformBlockDepth = 0;
        boolean hasUniforms = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Track uniform block nesting
            if (trimmed.contains("uniform") && trimmed.contains("{")) {
                inUniformBlock = true;
                uniformBlockDepth = 1;
                result.append(line).append("\n");
                continue;
            }

            if (inUniformBlock) {
                // Count braces to track nesting
                for (char c : line.toCharArray()) {
                    if (c == '{') uniformBlockDepth++;
                    if (c == '}') uniformBlockDepth--;
                }

                if (uniformBlockDepth <= 0) {
                    inUniformBlock = false;
                }
                result.append(line).append("\n");
                continue;
            }

            // Detect standalone uniform declarations (not in blocks)
            if (trimmed.startsWith("uniform ") && !trimmed.contains("{")) {
                // Skip sampler/texture uniforms - they don't need blocks
                if (trimmed.contains("sampler") || trimmed.contains("texture") ||
                    trimmed.contains("image") || trimmed.contains("samplerCube")) {
                    result.append(line).append("\n");
                    continue;
                }

                // Collect non-opaque uniform for wrapping
                uniformDeclarations.add(line);
                hasUniforms = true;
                continue;
            }

            result.append(line).append("\n");
        }

        // If we found standalone uniforms, wrap them in a block
        if (hasUniforms) {
            StringBuilder finalResult = new StringBuilder();
            String[] resultLines = result.toString().split("\n");
            boolean uniformBlockAdded = false;

            for (String line : resultLines) {
                // Insert uniform block after #version and includes but before main content
                if (!uniformBlockAdded && !line.trim().startsWith("#") &&
                    !line.trim().isEmpty() && !line.trim().startsWith("//") &&
                    !line.trim().startsWith("layout") && !line.trim().startsWith("const")) {

                    finalResult.append("// Auto-generated uniform block for Vulkan compatibility\n");
                    finalResult.append("layout(binding = 0) uniform UniformBlock {\n");
                    for (String uniformDecl : uniformDeclarations) {
                        // Remove 'uniform' keyword and add to block
                        String cleanDecl = uniformDecl.trim().replaceFirst("^uniform\\s+", "    ");
                        finalResult.append(cleanDecl).append("\n");
                    }
                    finalResult.append("};\n\n");
                    uniformBlockAdded = true;

                    LOGGER.info("Wrapped {} standalone uniforms in block for Vulkan compliance: {}",
                        uniformDeclarations.size(), filename);
                }

                finalResult.append(line).append("\n");
            }

            return finalResult.toString();
        }

        return source; // No changes needed
    }

    /**
     * Quick check if source contains legacy GLSL syntax that needs fixing.
     */
    public static boolean needsLegacyFixes(String source) {
        return source.contains("varying ") ||
               source.contains("attribute ") ||
               source.contains("gl_FragData") ||
               source.contains("gl_FragColor") ||
               source.contains("texture2D(") ||
               source.contains("texture2DLod(") ||
               source.contains("textureCube(");
    }

    /**
     * Get statistics about what legacy syntax was found in the source.
     */
    public static LegacyStats analyzeLegacySyntax(String source) {
        int varyingCount = countMatches(source, VARYING_PATTERN);
        int attributeCount = countMatches(source, ATTRIBUTE_PATTERN);
        int fragDataCount = countMatches(source, GL_FRAG_DATA_PATTERN);
        boolean hasFragColor = GL_FRAG_COLOR_PATTERN.matcher(source).find();
        int texture2DCount = countMatches(source, TEXTURE2D_PATTERN);

        return new LegacyStats(varyingCount, attributeCount, fragDataCount, hasFragColor, texture2DCount);
    }

    private static int countMatches(String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Statistics about legacy GLSL syntax found in a shader.
     */
    public static class LegacyStats {
        private final int varyingCount;
        private final int attributeCount;
        private final int fragDataCount;
        private final boolean hasFragColor;
        private final int texture2DCount;

        public LegacyStats(int varyingCount, int attributeCount, int fragDataCount,
                          boolean hasFragColor, int texture2DCount) {
            this.varyingCount = varyingCount;
            this.attributeCount = attributeCount;
            this.fragDataCount = fragDataCount;
            this.hasFragColor = hasFragColor;
            this.texture2DCount = texture2DCount;
        }

        public int getVaryingCount() { return varyingCount; }
        public int getAttributeCount() { return attributeCount; }
        public int getFragDataCount() { return fragDataCount; }
        public boolean hasFragColor() { return hasFragColor; }
        public int getTexture2DCount() { return texture2DCount; }

        public boolean hasLegacySyntax() {
            return varyingCount > 0 || attributeCount > 0 || fragDataCount > 0 ||
                   hasFragColor || texture2DCount > 0;
        }

        @Override
        public String toString() {
            return String.format("LegacyStats{varying=%d, attribute=%d, fragData=%d, fragColor=%s, texture2D=%d}",
                varyingCount, attributeCount, fragDataCount, hasFragColor, texture2DCount);
        }
    }
}