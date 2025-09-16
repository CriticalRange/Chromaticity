package net.chromaticity.shader.preprocessing;

import net.chromaticity.shader.option.ShaderOptionValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles GLSL shader preprocessing including macro expansion, conditional compilation,
 * and OptiFine compatibility features.
 */
public class ShaderPreprocessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderPreprocessor.class);

    // Preprocessor directive patterns
    private static final Pattern DEFINE_PATTERN = Pattern.compile("^\\s*#define\\s+(\\w+)(?:\\s+(.*))?$");
    private static final Pattern UNDEF_PATTERN = Pattern.compile("^\\s*#undef\\s+(\\w+)$");
    private static final Pattern IFDEF_PATTERN = Pattern.compile("^\\s*#ifdef\\s+(\\w+)$");
    private static final Pattern IFNDEF_PATTERN = Pattern.compile("^\\s*#ifndef\\s+(\\w+)$");
    private static final Pattern IF_DEFINED_PATTERN = Pattern.compile("^\\s*#if\\s+defined\\s*\\(\\s*(\\w+)\\s*\\)$");
    private static final Pattern IF_NOT_DEFINED_PATTERN = Pattern.compile("^\\s*#if\\s+!defined\\s*\\(\\s*(\\w+)\\s*\\)$");
    private static final Pattern ELIF_PATTERN = Pattern.compile("^\\s*#elif\\s+(.+)$");
    private static final Pattern ELSE_PATTERN = Pattern.compile("^\\s*#else\\s*$");
    private static final Pattern ENDIF_PATTERN = Pattern.compile("^\\s*#endif\\s*$");

    // OptiFine standard macros
    private static final Map<String, String> OPTIFINE_STANDARD_MACROS;
    static {
        Map<String, String> map = new HashMap<>();
        map.put("MC_VERSION", "121"); // Minecraft 1.21
        map.put("MC_GL_VERSION", "460");
        map.put("MC_GLSL_VERSION", "450");
        OPTIFINE_STANDARD_MACROS = Collections.unmodifiableMap(map);
    }

    /**
     * Preprocesses a shader source with macro expansion and conditional compilation.
     */
    public String preprocessShader(String source, ShaderOptionValues optionValues, String shaderName) {
        if (source == null || source.trim().isEmpty()) {
            return source;
        }

        List<String> lines = new ArrayList<>(List.of(source.split("\n")));
        Map<String, String> defines = new HashMap<>(OPTIFINE_STANDARD_MACROS);

        // Add shader options as defines
        if (optionValues != null) {
            Map<String, String> optionMap = optionValues.toPropertiesMap();
            for (Map.Entry<String, String> option : optionMap.entrySet()) {
                String key = option.getKey();
                String value = option.getValue();

                // Convert boolean true/false to define/undef
                if ("true".equalsIgnoreCase(value)) {
                    defines.put(key, "");
                } else if (!"false".equalsIgnoreCase(value)) {
                    defines.put(key, value);
                }
            }
        }

        LOGGER.debug("Preprocessing shader '{}' with {} defines", shaderName, defines.size());

        // Process conditional compilation and macro expansion
        List<String> processedLines = processConditionalCompilation(lines, defines);
        processedLines = expandMacros(processedLines, defines);

        String result = String.join("\n", processedLines);
        LOGGER.debug("Preprocessing completed for shader '{}'", shaderName);

        return result;
    }

    /**
     * Processes conditional compilation directives (#ifdef, #ifndef, #if, etc.).
     */
    private List<String> processConditionalCompilation(List<String> lines, Map<String, String> defines) {
        List<String> result = new ArrayList<>();
        Stack<ConditionalBlock> blockStack = new Stack<>();
        boolean currentCondition = true;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmedLine = line.trim();

            // Process define/undef directives
            Matcher defineMatcher = DEFINE_PATTERN.matcher(trimmedLine);
            Matcher undefMatcher = UNDEF_PATTERN.matcher(trimmedLine);

            if (defineMatcher.matches()) {
                if (currentCondition && isBlockActive(blockStack)) {
                    String macro = defineMatcher.group(1);
                    String value = defineMatcher.group(2);
                    defines.put(macro, value != null ? value : "");
                    LOGGER.trace("Defined macro: {} = {}", macro, value != null ? value : "");
                }
                continue; // Don't include define directives in output
            }

            if (undefMatcher.matches()) {
                if (currentCondition && isBlockActive(blockStack)) {
                    String macro = undefMatcher.group(1);
                    defines.remove(macro);
                    LOGGER.trace("Undefined macro: {}", macro);
                }
                continue; // Don't include undef directives in output
            }

            // Process conditional directives
            if (isConditionalDirective(trimmedLine)) {
                processConditionalDirective(trimmedLine, blockStack, defines);
                currentCondition = isBlockActive(blockStack);
                continue; // Don't include conditional directives in output
            }

            // Include line if we're in an active block
            if (currentCondition && isBlockActive(blockStack)) {
                result.add(line);
            }
        }

        if (!blockStack.isEmpty()) {
            LOGGER.warn("Unmatched conditional compilation blocks: {} unclosed blocks", blockStack.size());
        }

        return result;
    }

    /**
     * Expands macro references in the preprocessed source.
     */
    private List<String> expandMacros(List<String> lines, Map<String, String> defines) {
        List<String> result = new ArrayList<>();

        for (String line : lines) {
            String expandedLine = line;

            // Expand each defined macro
            for (Map.Entry<String, String> define : defines.entrySet()) {
                String macro = define.getKey();
                String value = define.getValue();

                // Use word boundaries to avoid partial replacements
                String pattern = "\\b" + Pattern.quote(macro) + "\\b";
                expandedLine = expandedLine.replaceAll(pattern, value);
            }

            result.add(expandedLine);
        }

        return result;
    }

    /**
     * Checks if a line contains a conditional compilation directive.
     */
    private boolean isConditionalDirective(String line) {
        return IFDEF_PATTERN.matcher(line).matches() ||
               IFNDEF_PATTERN.matcher(line).matches() ||
               IF_DEFINED_PATTERN.matcher(line).matches() ||
               IF_NOT_DEFINED_PATTERN.matcher(line).matches() ||
               ELIF_PATTERN.matcher(line).matches() ||
               ELSE_PATTERN.matcher(line).matches() ||
               ENDIF_PATTERN.matcher(line).matches();
    }

    /**
     * Processes a conditional compilation directive.
     */
    private void processConditionalDirective(String line, Stack<ConditionalBlock> blockStack, Map<String, String> defines) {
        Matcher ifdefMatcher = IFDEF_PATTERN.matcher(line);
        Matcher ifndefMatcher = IFNDEF_PATTERN.matcher(line);
        Matcher ifDefinedMatcher = IF_DEFINED_PATTERN.matcher(line);
        Matcher ifNotDefinedMatcher = IF_NOT_DEFINED_PATTERN.matcher(line);

        if (ifdefMatcher.matches()) {
            String macro = ifdefMatcher.group(1);
            boolean condition = defines.containsKey(macro);
            blockStack.push(new ConditionalBlock(condition, false));

        } else if (ifndefMatcher.matches()) {
            String macro = ifndefMatcher.group(1);
            boolean condition = !defines.containsKey(macro);
            blockStack.push(new ConditionalBlock(condition, false));

        } else if (ifDefinedMatcher.matches()) {
            String macro = ifDefinedMatcher.group(1);
            boolean condition = defines.containsKey(macro);
            blockStack.push(new ConditionalBlock(condition, false));

        } else if (ifNotDefinedMatcher.matches()) {
            String macro = ifNotDefinedMatcher.group(1);
            boolean condition = !defines.containsKey(macro);
            blockStack.push(new ConditionalBlock(condition, false));

        } else if (ELSE_PATTERN.matcher(line).matches()) {
            if (!blockStack.isEmpty()) {
                ConditionalBlock current = blockStack.pop();
                blockStack.push(new ConditionalBlock(!current.condition && !current.hasElse, true));
            }

        } else if (ENDIF_PATTERN.matcher(line).matches()) {
            if (!blockStack.isEmpty()) {
                blockStack.pop();
            }
        }
    }

    /**
     * Checks if all conditional blocks in the stack are active.
     */
    private boolean isBlockActive(Stack<ConditionalBlock> blockStack) {
        return blockStack.stream().allMatch(block -> block.condition);
    }

    /**
     * Represents a conditional compilation block state.
     */
    private static class ConditionalBlock {
        final boolean condition;
        final boolean hasElse;

        ConditionalBlock(boolean condition, boolean hasElse) {
            this.condition = condition;
            this.hasElse = hasElse;
        }
    }
}