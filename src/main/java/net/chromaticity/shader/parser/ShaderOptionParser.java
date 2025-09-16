package net.chromaticity.shader.parser;

import net.chromaticity.shader.option.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses GLSL shader files to discover shader options.
 * Supports both #define and const option declarations.
 */
public class ShaderOptionParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderOptionParser.class);

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
     */
    public ShaderOptionSet parseShaderDirectory(Path shaderDir) {
        ShaderOptionSet.Builder builder = ShaderOptionSet.builder();

        try {
            if (!Files.exists(shaderDir)) {
                LOGGER.warn("Shader directory does not exist: {}", shaderDir);
                return builder.build();
            }

            try (Stream<Path> paths = Files.walk(shaderDir)) {
                paths.filter(Files::isRegularFile)
                     .filter(this::isShaderFile)
                     .forEach(shaderFile -> {
                         try {
                             parseShaderFile(shaderFile, builder);
                         } catch (IOException e) {
                             LOGGER.warn("Failed to parse shader file: {}", shaderFile, e);
                         }
                     });
            }

        } catch (IOException e) {
            LOGGER.error("Failed to scan shader directory: {}", shaderDir, e);
        }

        ShaderOptionSet result = builder.build();
        LOGGER.info("Discovered {} shader options in {}", result.getTotalOptionCount(), shaderDir);
        return result;
    }

    /**
     * Checks if a file is a shader file based on its extension.
     */
    private boolean isShaderFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return SHADER_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    /**
     * Parses a single shader file for options.
     */
    private void parseShaderFile(Path file, ShaderOptionSet.Builder builder) throws IOException {
        List<String> lines = Files.readAllLines(file);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            parseLine(line, i, file, builder);
        }
    }

    /**
     * Parses a single line for shader options.
     */
    private void parseLine(String line, int lineNumber, Path file, ShaderOptionSet.Builder builder) {
        String trimmed = line.trim();

        // Skip empty lines and non-relevant lines
        if (trimmed.isEmpty() ||
            (!trimmed.contains("#define") && !trimmed.contains("const"))) {
            return;
        }

        try {
            if (trimmed.contains("#define")) {
                parseDefineOption(trimmed, lineNumber, file, builder);
            } else if (trimmed.startsWith("const")) {
                parseConstOption(trimmed, lineNumber, file, builder);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to parse line {} in {}: {}", lineNumber + 1, file, line, e);
        }
    }

    /**
     * Parses #define option lines.
     * Supports:
     * - #define OPTION_NAME           (boolean, default true)
     * - //#define OPTION_NAME          (boolean, default false)
     * - #define OPTION_NAME value // [values] labels
     */
    private void parseDefineOption(String line, int lineNumber, Path file, ShaderOptionSet.Builder builder) {
        boolean isCommented = line.trim().startsWith("//");
        String workingLine = isCommented ? line.trim().substring(2).trim() : line.trim();

        // Pattern: #define OPTION_NAME [value] [// comment]
        Pattern definePattern = Pattern.compile("#define\\s+(\\w+)(?:\\s+(\\S+))?(?:\\s*//\\s*(.*))?");
        Matcher matcher = definePattern.matcher(workingLine);

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
            // String option - check for allowed values in comment
            StringShaderOption option = StringShaderOption.create(
                optionName, comment, ShaderOption.OptionType.DEFINE, value);

            if (option != null) {
                builder.addStringOption(option);
                LOGGER.debug("Found string define option: {} = {} in {}", optionName, value, file.getFileName());
            }
        }
    }

    /**
     * Parses const option lines.
     * Supports:
     * - const int optionName = value;
     * - const float optionName = value;
     * - const bool optionName = value;
     */
    private void parseConstOption(String line, int lineNumber, Path file, ShaderOptionSet.Builder builder) {
        // Pattern: const type name = value; [// comment]
        Pattern constPattern = Pattern.compile("const\\s+(int|float|bool)\\s+(\\w+)\\s*=\\s*(\\S+)\\s*;(?:\\s*//\\s*(.*))?");
        Matcher matcher = constPattern.matcher(line);

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
}