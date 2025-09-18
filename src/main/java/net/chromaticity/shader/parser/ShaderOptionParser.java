package net.chromaticity.shader.parser;

import net.chromaticity.shader.option.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses GLSL shader files to discover shader options.
 * Supports both #define and const option declarations.
 */
public class ShaderOptionParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderOptionParser.class);

    // Cache for parsed shader option sets
    private static final Map<String, CachedShaderOptions> CACHE = new ConcurrentHashMap<>();

    // Pre-compiled patterns for better performance
    private static final Pattern OPTION_LINE_PATTERN = Pattern.compile(
        "^\\s*(?://\\s*)?(?:#define|const)\\s+.*");

    private static final Pattern DEFINE_PATTERN = Pattern.compile(
        "#define\\s+(\\w+)(?:\\s+(\\S+))?(?:\\s*//\\s*(.*))?");

    private static final Pattern CONST_PATTERN = Pattern.compile(
        "const\\s+(int|float|bool)\\s+(\\w+)\\s*=\\s*(\\S+)\\s*;(?:\\s*//\\s*(.*))?");

    private static class CachedShaderOptions {
        final ShaderOptionSet options;
        final long lastModified;
        final int fileCount;

        CachedShaderOptions(ShaderOptionSet options, long lastModified, int fileCount) {
            this.options = options;
            this.lastModified = lastModified;
            this.fileCount = fileCount;
        }
    }

    // Shader file extensions to scan
    private static final Set<String> SHADER_EXTENSIONS = Set.of(
        ".vsh", ".fsh", ".gsh", ".tcs", ".tes", ".glsl", ".vert", ".frag", ".geom"
    );

    // Known const option names (from Iris compatibility)
    private static final Set<String> VALID_CONST_OPTION_NAMES = Set.of(
        "shadowMapResolution", "shadowDistance", "voxelDistance",
        "shadowDistanceRenderMul", "entityShadowDistanceMul", "shadowIntervalSize",
        "generateShadowMipmap", "generateShadowColorMipmap", "shadowHardwareFiltering",
        "shadowtex0Mipmap", "shadowtexMipmap", "shadowtex1Mipmap",
        "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest",
        "shadowtex1Nearest", "shadow1MinMagNearest",
        "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife",
        "centerDepthHalflife", "sunPathRotation", "ambientOcclusionLevel",
        "superSamplingLevel", "noiseTextureResolution"
    );

    /**
     * Parses all shader files in a directory to discover options.
     * Uses caching based on directory modification time for performance.
     */
    public ShaderOptionSet parseShaderDirectory(Path shaderDir) {
        try {
            if (!Files.exists(shaderDir)) {
                LOGGER.warn("Shader directory does not exist: {}", shaderDir);
                return ShaderOptionSet.builder().build();
            }

            String cacheKey = shaderDir.toAbsolutePath().toString();

            // Check cache first
            CachedShaderOptions cached = CACHE.get(cacheKey);
            if (cached != null) {
                // Check if directory or any shader files have been modified
                try (Stream<Path> paths = Files.walk(shaderDir)) {
                    long maxModTime = paths
                        .filter(Files::isRegularFile)
                        .filter(this::isShaderFile)
                        .mapToLong(this::getLastModified)
                        .max()
                        .orElse(0L);

                    if (maxModTime <= cached.lastModified) {
                        LOGGER.debug("Using cached shader options for: {}", shaderDir);
                        return cached.options;
                    }
                }
            }

            // Parse directory
            ShaderOptionSet.Builder builder = ShaderOptionSet.builder();
            AtomicInteger fileCount = new AtomicInteger(0);
            long startTime = System.currentTimeMillis();

            try (Stream<Path> paths = Files.walk(shaderDir)) {
                long maxModTime = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isShaderFile)
                    .peek(file -> fileCount.incrementAndGet())
                    .mapToLong(shaderFile -> {
                        try {
                            parseShaderFileOptimized(shaderFile, builder);
                            return getLastModified(shaderFile);
                        } catch (IOException e) {
                            LOGGER.warn("Failed to parse shader file: {}", shaderFile, e);
                            return 0L;
                        }
                    })
                    .max()
                    .orElse(System.currentTimeMillis());

                ShaderOptionSet result = builder.build();

                // Cache the result
                CACHE.put(cacheKey, new CachedShaderOptions(result, maxModTime, fileCount.get()));

                long parseTime = System.currentTimeMillis() - startTime;
                LOGGER.info("Parsed {} shader files in {}ms, found {} options in: {}",
                    fileCount.get(), parseTime, result.getTotalOptionCount(), shaderDir.getFileName());

                return result;
            }

        } catch (IOException e) {
            LOGGER.error("Failed to scan shader directory: {}", shaderDir, e);
            return ShaderOptionSet.builder().build();
        }
    }

    /**
     * Checks if a file is a shader file based on its extension.
     */
    private boolean isShaderFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return SHADER_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    /**
     * Optimized shader file parsing using streaming and early filtering.
     */
    private void parseShaderFileOptimized(Path file, ShaderOptionSet.Builder builder) throws IOException {
        try (Stream<String> lines = Files.lines(file)) {
            lines
                .filter(this::isOptionCandidate)
                .forEach(line -> {
                    try {
                        parseLineOptimized(line, file, builder);
                    } catch (Exception e) {
                        LOGGER.debug("Failed to parse line in {}: {}", file, line, e);
                    }
                });
        }
    }

    /**
     * Quick filter to identify lines that might contain options.
     * Avoids expensive string operations on irrelevant lines.
     */
    private boolean isOptionCandidate(String line) {
        // Quick length and character checks before expensive operations
        if (line.length() < 5) return false;

        // Check for option keywords efficiently
        return line.contains("#define") || line.contains("const");
    }

    /**
     * Optimized line parsing using pre-compiled patterns.
     */
    private void parseLineOptimized(String line, Path file, ShaderOptionSet.Builder builder) {
        String trimmed = line.trim();

        if (trimmed.isEmpty() || !OPTION_LINE_PATTERN.matcher(trimmed).matches()) {
            return;
        }

        if (trimmed.contains("#define")) {
            parseDefineOptionOptimized(trimmed, file, builder);
        } else if (trimmed.startsWith("const")) {
            parseConstOptionOptimized(trimmed, file, builder);
        }
    }

    /**
     * Optimized #define option parsing using pre-compiled patterns.
     */
    private void parseDefineOptionOptimized(String line, Path file, ShaderOptionSet.Builder builder) {
        boolean isCommented = line.startsWith("//");
        String workingLine = isCommented ? line.substring(2).trim() : line;

        Matcher matcher = DEFINE_PATTERN.matcher(workingLine);
        if (!matcher.matches()) {
            return;
        }

        String optionName = matcher.group(1);
        String value = matcher.group(2);
        String comment = matcher.group(3);

        if (value == null) {
            // Boolean option
            boolean defaultValue = !isCommented;
            BooleanShaderOption option = new BooleanShaderOption(
                optionName, comment, ShaderOption.OptionType.DEFINE, defaultValue);
            builder.addBooleanOption(option);

            LOGGER.debug("Found boolean define option: {} = {} in {}", optionName, defaultValue, file.getFileName());

        } else {
            // String option
            StringShaderOption option = StringShaderOption.create(
                optionName, comment, ShaderOption.OptionType.DEFINE, value);

            if (option != null) {
                builder.addStringOption(option);
                LOGGER.debug("Found string define option: {} = {} in {}", optionName, value, file.getFileName());
            }
        }
    }

    /**
     * Optimized const option parsing using pre-compiled patterns.
     */
    private void parseConstOptionOptimized(String line, Path file, ShaderOptionSet.Builder builder) {
        Matcher matcher = CONST_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return;
        }

        String type = matcher.group(1);
        String optionName = matcher.group(2);
        String value = matcher.group(3);
        String comment = matcher.group(4);

        // Only process known const option names for compatibility
        if (!VALID_CONST_OPTION_NAMES.contains(optionName)) {
            return;
        }

        if ("bool".equals(type)) {
            // Boolean const option
            boolean defaultValue = "true".equalsIgnoreCase(value);
            BooleanShaderOption option = new BooleanShaderOption(
                optionName, comment, ShaderOption.OptionType.CONST, defaultValue);
            builder.addBooleanOption(option);

            LOGGER.debug("Found boolean const option: {} = {} in {}", optionName, defaultValue, file.getFileName());

        } else {
            // Numeric const option
            StringShaderOption option = StringShaderOption.create(
                optionName, comment, ShaderOption.OptionType.CONST, value);

            if (option != null) {
                builder.addStringOption(option);
                LOGGER.debug("Found numeric const option: {} = {} in {}", optionName, value, file.getFileName());
            }
        }
    }

    /**
     * Gets the last modified time of a file safely.
     */
    private long getLastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Clears the parsing cache. Useful for development/testing.
     */
    public static void clearCache() {
        CACHE.clear();
        LOGGER.debug("Cleared shader option parser cache");
    }
}