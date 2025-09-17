package net.chromaticity.shader.compilation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chromaticity SPIR-V shader compiler that integrates with VulkanMod's shaderc system.
 * Compiles preprocessed GLSL 450 core shaders to SPIR-V bytecode using VulkanMod's SPIRVUtils.
 */
public class ChromaticityShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChromaticityShaderCompiler.class);

    // VulkanMod integration via reflection
    private static Class<?> spirvUtilsClass;
    private static Class<?> spirvClass;
    private static Class<?> shaderKindEnum;
    private static Method compileShaderMethod;
    private static Method addIncludePathMethod;
    private static Object vertexShaderKind;
    private static Object fragmentShaderKind;
    private static boolean vulkanModAvailable = false;

    // SPIR-V bytecode cache for performance
    private static final Map<String, Object> compiledShaderCache = new ConcurrentHashMap<>();

    static {
        initializeVulkanModIntegration();
    }

    /**
     * Shader compilation result containing SPIR-V bytecode and metadata.
     */
    public static class CompilationResult {
        private final Object spirvObject;
        private final String shaderName;
        private final ShaderStage stage;
        private final boolean fromCache;

        public CompilationResult(Object spirvObject, String shaderName, ShaderStage stage, boolean fromCache) {
            this.spirvObject = spirvObject;
            this.shaderName = shaderName;
            this.stage = stage;
            this.fromCache = fromCache;
        }

        public Object getSpirvObject() { return spirvObject; }
        public String getShaderName() { return shaderName; }
        public ShaderStage getStage() { return stage; }
        public boolean isFromCache() { return fromCache; }
    }

    /**
     * Shader stage enumeration for compilation.
     */
    public enum ShaderStage {
        VERTEX("vertex", ".vsh"),
        FRAGMENT("fragment", ".fsh"),
        GEOMETRY("geometry", ".gsh"),
        COMPUTE("compute", ".csh");

        private final String name;
        private final String extension;

        ShaderStage(String name, String extension) {
            this.name = name;
            this.extension = extension;
        }

        public String getName() { return name; }
        public String getExtension() { return extension; }

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
     * Initializes reflection-based integration with VulkanMod's SPIRVUtils.
     */
    private static void initializeVulkanModIntegration() {
        try {
            // Load VulkanMod classes
            spirvUtilsClass = Class.forName("net.vulkanmod.vulkan.shader.SPIRVUtils");
            spirvClass = Class.forName("net.vulkanmod.vulkan.shader.SPIRVUtils$SPIRV");
            shaderKindEnum = Class.forName("net.vulkanmod.vulkan.shader.SPIRVUtils$ShaderKind");

            // Get compilation method
            compileShaderMethod = spirvUtilsClass.getDeclaredMethod("compileShader",
                String.class, String.class, shaderKindEnum);

            // Get include path method
            addIncludePathMethod = spirvUtilsClass.getDeclaredMethod("addIncludePath", String.class);

            // Get shader kind constants
            Object[] shaderKindConstants = shaderKindEnum.getEnumConstants();
            for (Object shaderKind : shaderKindConstants) {
                String name = shaderKind.toString();
                if ("VERTEX_SHADER".equals(name)) {
                    vertexShaderKind = shaderKind;
                } else if ("FRAGMENT_SHADER".equals(name)) {
                    fragmentShaderKind = shaderKind;
                }
            }

            vulkanModAvailable = true;

        } catch (ClassNotFoundException | NoSuchMethodException e) {
            vulkanModAvailable = false;
            LOGGER.warn("VulkanMod SPIR-V integration unavailable - compilation will be disabled: {}", e.getMessage());
        }
    }

    /**
     * Compiles a preprocessed GLSL 450 core shader to SPIR-V bytecode.
     *
     * @param shaderName The name of the shader (for error reporting and caching)
     * @param glslSource The preprocessed GLSL 450 core source code
     * @param stage The shader stage (vertex, fragment, etc.)
     * @return CompilationResult containing SPIR-V bytecode and metadata
     */
    public static CompilationResult compileShader(String shaderName, String glslSource, ShaderStage stage) {
        if (!vulkanModAvailable) {
            throw new UnsupportedOperationException("VulkanMod SPIR-V compilation not available");
        }

        // Check cache first
        String cacheKey = generateCacheKey(shaderName, glslSource, stage);
        Object cachedSpirv = compiledShaderCache.get(cacheKey);
        if (cachedSpirv != null) {
            LOGGER.debug("Using cached SPIR-V for shader: {}", shaderName);
            return new CompilationResult(cachedSpirv, shaderName, stage, true);
        }

        try {
            // Get the appropriate VulkanMod shader kind
            Object shaderKind = getVulkanModShaderKind(stage);
            if (shaderKind == null) {
                throw new IllegalArgumentException("Unsupported shader stage: " + stage);
            }

            // Compile using VulkanMod's SPIRVUtils
            LOGGER.debug("Compiling {} shader '{}' to SPIR-V using VulkanMod's shaderc", stage.getName(), shaderName);
            Object spirvResult = compileShaderMethod.invoke(null, shaderName, glslSource, shaderKind);

            // Cache the result
            compiledShaderCache.put(cacheKey, spirvResult);


            return new CompilationResult(spirvResult, shaderName, stage, false);

        } catch (Exception e) {
            String errorMsg = String.format("Failed to compile %s shader '%s' to SPIR-V", stage.getName(), shaderName);
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Compiles a shader file from disk to SPIR-V.
     *
     * @param shaderPath Path to the preprocessed GLSL 450 core shader file
     * @return CompilationResult containing SPIR-V bytecode and metadata
     */
    public static CompilationResult compileShaderFile(Path shaderPath) {
        try {
            String shaderName = shaderPath.getFileName().toString();
            String glslSource = Files.readString(shaderPath);
            ShaderStage stage = ShaderStage.fromFileName(shaderName);

            return compileShader(shaderName, glslSource, stage);

        } catch (IOException e) {
            String errorMsg = "Failed to read shader file: " + shaderPath;
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Adds an include path to VulkanMod's shader compilation system.
     *
     * @param includePath The include path to add (e.g., "/assets/chromaticity/shaderpacks/includes/")
     * @return true if the include path was added successfully
     */
    public static boolean addIncludePath(String includePath) {
        if (!vulkanModAvailable) {
            LOGGER.debug("VulkanMod not available, cannot add include path: {}", includePath);
            return false;
        }

        try {
            addIncludePathMethod.invoke(null, includePath);
            LOGGER.debug("Added include path to VulkanMod's SPIR-V compiler: {}", includePath);
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to add include path to VulkanMod: {}", includePath, e);
            return false;
        }
    }

    /**
     * Configures include paths for a specific shaderpack.
     *
     * @param shaderpackName The name of the shaderpack
     * @param shaderpackBasePath The base path to the shaderpack directory
     */
    public static void configureShaderpackIncludes(String shaderpackName, Path shaderpackBasePath) {
        if (!vulkanModAvailable) {
        }

        // Add standard shaderpack include paths
        String basePathStr = shaderpackBasePath.toString().replace('\\', '/');

        addIncludePath(basePathStr + "/shaders/");
        addIncludePath(basePathStr + "/shaders/lib/");
        addIncludePath(basePathStr + "/shaders/include/");
        addIncludePath(basePathStr + "/");

        // Add Chromaticity-specific includes
        addIncludePath("/assets/chromaticity/includes/");
        addIncludePath("/assets/chromaticity/shaderpacks/" + shaderpackName + "/");

    }

    /**
     * Clears the compiled shader cache.
     */
    public static void clearCache() {
        int cacheSize = compiledShaderCache.size();
        compiledShaderCache.clear();
        LOGGER.debug("Cleared SPIR-V shader cache ({} entries)", cacheSize);
    }

    /**
     * Gets compilation statistics.
     */
    public static CompilationStats getStats() {
        return new CompilationStats(
            compiledShaderCache.size(),
            vulkanModAvailable
        );
    }

    /**
     * Generates a cache key for a compiled shader.
     */
    private static String generateCacheKey(String shaderName, String glslSource, ShaderStage stage) {
        // Use hash of content for cache key to detect changes
        int contentHash = glslSource.hashCode();
        return String.format("%s_%s_%d", shaderName, stage.name(), contentHash);
    }

    /**
     * Converts Chromaticity ShaderStage to VulkanMod ShaderKind.
     */
    private static Object getVulkanModShaderKind(ShaderStage stage) {
        switch (stage) {
            case VERTEX:
                return vertexShaderKind;
            case FRAGMENT:
                return fragmentShaderKind;
            default:
                return null; // Geometry and compute shaders not yet supported
        }
    }

    /**
     * Checks if VulkanMod SPIR-V compilation is available.
     */
    public static boolean isAvailable() {
        return vulkanModAvailable;
    }

    /**
     * Compilation statistics.
     */
    public static class CompilationStats {
        private final int cachedShaders;
        private final boolean vulkanModAvailable;

        public CompilationStats(int cachedShaders, boolean vulkanModAvailable) {
            this.cachedShaders = cachedShaders;
            this.vulkanModAvailable = vulkanModAvailable;
        }

        public int getCachedShaders() { return cachedShaders; }
        public boolean isVulkanModAvailable() { return vulkanModAvailable; }

        @Override
        public String toString() {
            return String.format("SPIR-V Compilation Stats: %d cached shaders, VulkanMod: %s",
                cachedShaders, vulkanModAvailable ? "available" : "unavailable");
        }
    }
}