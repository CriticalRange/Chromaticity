package net.chromaticity.shader.preprocessing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Integration layer with VulkanMod's shader compilation system.
 * Provides access to VulkanMod's SPIRVUtils for include path management and compilation.
 */
public class VulkanModIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanModIntegration.class);

    private static Class<?> spirvUtilsClass;
    private static Method addIncludePathMethod;
    private static Method compileShaderMethod;
    private static boolean vulkanModAvailable = false;

    static {
        initializeVulkanModIntegration();
    }

    /**
     * Initializes VulkanMod integration using reflection.
     */
    private static void initializeVulkanModIntegration() {
        try {
            // Try to load VulkanMod's SPIRVUtils class
            spirvUtilsClass = Class.forName("net.vulkanmod.vulkan.shader.SPIRVUtils");

            // Get the addIncludePath method
            addIncludePathMethod = spirvUtilsClass.getDeclaredMethod("addIncludePath", String.class);

            // Get the compileShader method (exact signature may vary)
            // This is a placeholder - actual method signature needs to be confirmed
            try {
                compileShaderMethod = spirvUtilsClass.getDeclaredMethod("compileShader",
                    String.class, String.class, int.class);
            } catch (NoSuchMethodException e) {
                // Try alternative signature if the above doesn't work
                LOGGER.debug("Primary compileShader method not found, VulkanMod integration limited");
            }

            vulkanModAvailable = true;

        } catch (ClassNotFoundException | NoSuchMethodException e) {
            vulkanModAvailable = false;
            LOGGER.warn("VulkanMod not available - shader preprocessing will work but SPIR-V compilation disabled: {}",
                e.getMessage());
        }
    }

    /**
     * Adds an include path to VulkanMod's shader compilation system.
     */
    public static boolean addIncludePath(String includePath) {
        if (!vulkanModAvailable) {
            LOGGER.debug("VulkanMod not available, cannot add include path: {}", includePath);
            return false;
        }

        try {
            addIncludePathMethod.invoke(null, includePath);
            LOGGER.debug("Added include path to VulkanMod: {}", includePath);
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to add include path to VulkanMod: {}", includePath, e);
            return false;
        }
    }

    /**
     * Adds multiple include paths for a shader pack.
     */
    public static void addShaderPackIncludePaths(String packName) {
        if (!vulkanModAvailable) {
        }

        // Add standard shaderpack include paths
        String baseResourcePath = "/assets/chromaticity/shaderpacks/" + packName;

        addIncludePath(baseResourcePath + "/shaders/");
        addIncludePath(baseResourcePath + "/shaders/lib/");
        addIncludePath(baseResourcePath + "/shaders/include/");
        addIncludePath(baseResourcePath + "/");

    }

    /**
     * Compiles a shader using VulkanMod's SPIR-V compilation system.
     * This is a placeholder method - actual implementation depends on VulkanMod's API.
     */
    public static byte[] compileShaderToSpirV(String shaderSource, String shaderName, ShaderStage stage) {
        if (!vulkanModAvailable || compileShaderMethod == null) {
            LOGGER.debug("VulkanMod compilation not available for shader: {}", shaderName);
            return null;
        }

        try {
            // This is a placeholder implementation
            // The actual method signature and parameters need to be confirmed with VulkanMod's API
            Object result = compileShaderMethod.invoke(null, shaderSource, shaderName, stage.getVulkanStage());

            if (result instanceof byte[]) {
                LOGGER.debug("Successfully compiled shader to SPIR-V: {}", shaderName);
                return (byte[]) result;
            }

        } catch (Exception e) {
            LOGGER.error("Failed to compile shader with VulkanMod: {}", shaderName, e);
        }

        return null;
    }

    /**
     * Checks if VulkanMod integration is available.
     */
    public static boolean isVulkanModAvailable() {
        return vulkanModAvailable;
    }

    /**
     * Analyzes shader source for VulkanMod-specific binding requirements.
     */
    public static VulkanBindingAnalysis analyzeShaderBindings(String shaderSource, GlslVersionTranslator.ShaderType shaderType) {
        VulkanBindingAnalysis analysis = new VulkanBindingAnalysis();

        String[] lines = shaderSource.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();

            // Detect sampler declarations that need binding assignments
            if (trimmed.startsWith("uniform ") && trimmed.contains("sampler") && !trimmed.contains("layout(binding")) {
                analysis.addSampler(extractUniformName(trimmed));
            }

            // Skip matrix uniforms (handled by UBO) and other built-in uniforms
            if (trimmed.startsWith("uniform ") && !trimmed.contains("layout(binding") &&
                !trimmed.contains("sampler") && !isBuiltinUniform(trimmed)) {
                String uniformName = extractUniformName(trimmed);
                if (!uniformName.equals("unknown")) {
                    analysis.addUnboundUniform(uniformName);
                }
            }

            // Detect UBO usage patterns
            if (trimmed.contains("gl_ModelViewMatrix") || trimmed.contains("gl_ProjectionMatrix") ||
                trimmed.contains("gl_ModelViewProjectionMatrix") || trimmed.contains("gl_NormalMatrix")) {
                analysis.setNeedsMatrixUBO(true);
            }
        }

        return analysis;
    }

    /**
     * Checks if a uniform is a built-in that should be handled specially.
     */
    private static boolean isBuiltinUniform(String uniformLine) {
        return uniformLine.contains("gl_ModelViewMatrix") ||
               uniformLine.contains("gl_ProjectionMatrix") ||
               uniformLine.contains("gl_ModelViewProjectionMatrix") ||
               uniformLine.contains("gl_NormalMatrix") ||
               uniformLine.contains("gl_TextureMatrix");
    }

    /**
     * Applies VulkanMod-specific binding conventions to shader source.
     */
    public static String applyVulkanModBindings(String shaderSource, VulkanBindingAnalysis analysis) {
        List<String> lines = new ArrayList<>(Arrays.asList(shaderSource.split("\n")));

        // Remove existing malformed uniform declarations that need binding
        lines.removeIf(line -> {
            String trimmed = line.trim();
            return trimmed.startsWith("uniform ") && !trimmed.contains("layout(binding") &&
                   (analysis.getSamplers().stream().anyMatch(sampler -> trimmed.contains(sampler)) ||
                    analysis.getUnboundUniforms().stream().anyMatch(uniform -> trimmed.contains(uniform)));
        });

        // Find insertion point for binding declarations
        int insertIndex = findBindingInsertionPoint(lines);

        // Add sampler bindings (start at binding 1, after UBO at binding 0)
        int bindingIndex = 1;
        for (String sampler : analysis.getSamplers()) {
            String bindingDeclaration = String.format("layout(binding = %d) uniform %s %s;",
                bindingIndex++, getSamplerType(sampler), sampler);
            lines.add(insertIndex++, bindingDeclaration);
        }

        // Add bindings ONLY for opaque uniforms (samplers, images)
        // Non-opaque uniforms (floats, vectors, matrices) will be handled by uniform blocks later
        for (String uniform : analysis.getUnboundUniforms()) {
            if (!analysis.getSamplers().contains(uniform)) {
                String uniformType = getUniformType(uniform);

                // Only add individual bindings for opaque types in Vulkan
                if (isOpaqueUniformType(uniformType)) {
                    String bindingDeclaration = String.format("layout(binding = %d) uniform %s %s;",
                        bindingIndex++, uniformType, uniform);
                    lines.add(insertIndex++, bindingDeclaration);
                    LOGGER.debug("Added binding for opaque uniform: {} {}", uniformType, uniform);
                } else {
                    // Non-opaque uniforms stay as loose uniforms to be processed by uniform block conversion
                    String uniformDeclaration = String.format("uniform %s %s;", uniformType, uniform);
                    lines.add(insertIndex++, uniformDeclaration);
                    LOGGER.debug("Added loose uniform for block conversion: {} {}", uniformType, uniform);
                }
            }
        }

        if (!analysis.getUnboundUniforms().isEmpty() || !analysis.getSamplers().isEmpty()) {
            lines.add(insertIndex, "");
            LOGGER.debug("Added {} VulkanMod binding declarations",
                analysis.getUnboundUniforms().size() + analysis.getSamplers().size());
        }

        return String.join("\n", lines);
    }

    /**
     * Determines the type of a uniform based on its name.
     */
    private static String getUniformType(String uniformName) {
        String lower = uniformName.toLowerCase();

        // Common patterns
        if (lower.contains("matrix") || lower.contains("mvp") || lower.contains("projection") || lower.contains("modelview")) {
            return "mat4";
        }
        if (lower.contains("normal") && lower.contains("matrix")) {
            return "mat3";
        }
        if (lower.contains("position") || lower.contains("direction") || lower.contains("color") || lower.contains("light")) {
            return "vec3";
        }
        if (lower.contains("offset") || lower.contains("coord") || lower.contains("uv")) {
            return "vec2";
        }
        if (lower.contains("time") || lower.contains("angle") || lower.contains("far") || lower.contains("near") ||
            lower.contains("flip") || lower.contains("bias")) {
            return "float";
        }

        // Default fallback
        return "float";
    }

    /**
     * Extracts uniform name from a uniform declaration line.
     */
    private static String extractUniformName(String uniformLine) {
        // Pattern: uniform <type> <name>; or uniform <type> <name1>, <name2>;
        String cleaned = uniformLine.replaceAll(";", "").trim();
        String[] parts = cleaned.split("\\s+");

        if (parts.length >= 3) {
            String namesPart = parts[2];
            // Handle multiple declarations like "mat4 matrix1, matrix2"
            if (namesPart.contains(",")) {
                return namesPart.split(",")[0].trim();
            }
            return namesPart;
        }
        return "unknown";
    }

    /**
     * Determines sampler type from variable name.
     */
    private static String getSamplerType(String samplerName) {
        if (samplerName.toLowerCase().contains("cube")) {
            return "samplerCube";
        } else if (samplerName.toLowerCase().contains("3d")) {
            return "sampler3D";
        } else if (samplerName.toLowerCase().contains("shadow")) {
            return "sampler2DShadow";
        } else {
            return "sampler2D";
        }
    }

    /**
     * Finds the best insertion point for binding declarations.
     */
    private static int findBindingInsertionPoint(List<String> lines) {
        // Insert after version, extensions, and existing layout declarations
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.startsWith("#") && !line.startsWith("layout(") &&
                !line.isEmpty() && !line.startsWith("//")) {
                return i;
            }
        }
        return lines.size();
    }

    /**
     * Determines if a uniform type is opaque and can have individual layout bindings in Vulkan.
     * Only opaque types (samplers, images, atomic counters) can have individual bindings.
     * Non-opaque types (floats, vectors, matrices) must be in uniform blocks.
     */
    private static boolean isOpaqueUniformType(String uniformType) {
        if (uniformType == null) return false;

        // Remove array notation for comparison
        String baseType = uniformType.replaceAll("\\[\\d*\\]", "");

        // Opaque types that can have individual layout bindings in Vulkan
        return baseType.contains("sampler") ||
               baseType.contains("image") ||
               baseType.contains("texture") ||
               baseType.equals("atomic_uint");
    }

    /**
     * Gets VulkanMod integration status information.
     */
    public static String getIntegrationStatus() {
        if (vulkanModAvailable) {
            boolean hasCompiler = compileShaderMethod != null;
            return String.format("VulkanMod integration active (includes: ✓, compilation: %s)",
                hasCompiler ? "✓" : "✗");
        } else {
            return "VulkanMod integration unavailable";
        }
    }

    /**
     * Analysis result for VulkanMod binding requirements.
     */
    public static class VulkanBindingAnalysis {
        private final Set<String> unboundUniforms = new HashSet<>();
        private final Set<String> samplers = new HashSet<>();
        private boolean needsMatrixUBO = false;

        public void addUnboundUniform(String uniformName) {
            unboundUniforms.add(uniformName);
        }

        public void addSampler(String samplerName) {
            samplers.add(samplerName);
        }

        public void setNeedsMatrixUBO(boolean needs) {
            needsMatrixUBO = needs;
        }

        public Set<String> getUnboundUniforms() {
            return unboundUniforms;
        }

        public Set<String> getSamplers() {
            return samplers;
        }

        public boolean needsMatrixUBO() {
            return needsMatrixUBO;
        }
    }

    /**
     * Shader stage enumeration for SPIR-V compilation.
     */
    public enum ShaderStage {
        VERTEX(0),
        TESSELLATION_CONTROL(1),
        TESSELLATION_EVALUATION(2),
        GEOMETRY(3),
        FRAGMENT(4),
        COMPUTE(5);

        private final int vulkanStage;

        ShaderStage(int vulkanStage) {
            this.vulkanStage = vulkanStage;
        }

        public int getVulkanStage() {
            return vulkanStage;
        }

        /**
         * Converts from GlslVersionTranslator.ShaderType to VulkanMod ShaderStage.
         */
        public static ShaderStage fromShaderType(GlslVersionTranslator.ShaderType shaderType) {
            switch (shaderType) {
                case VERTEX: return VERTEX;
                case FRAGMENT: return FRAGMENT;
                case GEOMETRY: return GEOMETRY;
                case TESSELLATION_CONTROL: return TESSELLATION_CONTROL;
                case TESSELLATION_EVALUATION: return TESSELLATION_EVALUATION;
                case COMPUTE: return COMPUTE;
                default: return FRAGMENT;
            }
        }
    }
}