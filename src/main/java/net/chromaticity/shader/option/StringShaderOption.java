package net.chromaticity.shader.option;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a string/numeric shader option with predefined allowed values.
 * Examples:
 * - #define SHADOW_QUALITY 1 // [0 1 2] Off Medium High
 * - const int shadowMapResolution = 1024; // [512 1024 2048 4096]
 */
public class StringShaderOption extends ShaderOption {
    private static final Pattern ALLOWED_VALUES_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");

    private final String defaultValue;
    private final List<String> allowedValues;
    private final List<String> valueLabels;

    public StringShaderOption(String name, String comment, OptionType type, String defaultValue) {
        super(name, comment, type);
        this.defaultValue = defaultValue;

        // Parse allowed values from comment like: [0 1 2] Off Medium High
        ParsedValues parsed = parseAllowedValues(comment);
        this.allowedValues = parsed.values;
        this.valueLabels = parsed.labels;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    @Override
    public String getDefaultValueAsString() {
        return defaultValue;
    }

    public List<String> getAllowedValues() {
        return new ArrayList<>(allowedValues);
    }

    public List<String> getValueLabels() {
        return new ArrayList<>(valueLabels);
    }

    /**
     * Gets the display label for a given value.
     */
    public String getLabelForValue(String value) {
        int index = allowedValues.indexOf(value);
        if (index >= 0 && index < valueLabels.size()) {
            return valueLabels.get(index);
        }
        return value; // Fallback to the value itself
    }

    @Override
    public boolean isValidValue(String value) {
        return allowedValues.isEmpty() || allowedValues.contains(value);
    }

    /**
     * Creates a StringShaderOption, returning null if no allowed values are found in the comment.
     */
    public static StringShaderOption create(String name, String comment, OptionType type, String defaultValue) {
        ParsedValues parsed = parseAllowedValues(comment);
        if (parsed.values.isEmpty()) {
            return null; // No allowed values found, not a valid string option
        }
        return new StringShaderOption(name, comment, type, defaultValue);
    }

    private static ParsedValues parseAllowedValues(String comment) {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        if (comment == null || comment.trim().isEmpty()) {
            return new ParsedValues(values, labels);
        }

        // Look for pattern like: [0 1 2] Off Medium High
        Matcher matcher = ALLOWED_VALUES_PATTERN.matcher(comment);
        if (matcher.find()) {
            String valuesStr = matcher.group(1).trim();
            String[] valueArray = valuesStr.split("\\s+");
            values.addAll(Arrays.asList(valueArray));

            // Try to find labels after the bracket
            String afterBracket = comment.substring(matcher.end()).trim();
            if (!afterBracket.isEmpty()) {
                String[] labelArray = afterBracket.split("\\s+");
                labels.addAll(Arrays.asList(labelArray));
            }

            // If we don't have enough labels, use the values as labels
            while (labels.size() < values.size()) {
                labels.add(values.get(labels.size()));
            }
        }

        return new ParsedValues(values, labels);
    }

    private static class ParsedValues {
        final List<String> values;
        final List<String> labels;

        ParsedValues(List<String> values, List<String> labels) {
            this.values = values;
            this.labels = labels;
        }
    }

    @Override
    public String toString() {
        return "StringShaderOption{" +
                "name='" + name + '\'' +
                ", comment='" + comment + '\'' +
                ", type=" + type +
                ", defaultValue='" + defaultValue + '\'' +
                ", allowedValues=" + allowedValues +
                ", valueLabels=" + valueLabels +
                '}';
    }
}