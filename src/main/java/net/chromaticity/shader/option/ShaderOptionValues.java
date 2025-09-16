package net.chromaticity.shader.option;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores the current values for all shader options.
 * Supports both mutable operations for UI changes and immutable snapshots.
 */
public class ShaderOptionValues {
    private final ShaderOptionSet optionSet;
    private final Map<String, Boolean> booleanValues;
    private final Map<String, String> stringValues;

    public ShaderOptionValues(ShaderOptionSet optionSet) {
        this.optionSet = optionSet;
        this.booleanValues = new HashMap<>();
        this.stringValues = new HashMap<>();
    }

    public ShaderOptionValues(ShaderOptionSet optionSet, Map<String, String> initialValues) {
        this.optionSet = optionSet;
        this.booleanValues = new HashMap<>();
        this.stringValues = new HashMap<>();

        applyValues(initialValues);
    }

    /**
     * Applies a map of string values, parsing them according to option types.
     */
    public void applyValues(Map<String, String> values) {
        // Clear existing values
        booleanValues.clear();
        stringValues.clear();

        // Apply boolean options
        optionSet.getBooleanOptions().forEach((name, option) -> {
            String value = values.get(name);
            if (value != null) {
                boolean boolValue = option.parseValue(value);
                // Only store non-default values
                if (boolValue != option.getDefaultValue()) {
                    booleanValues.put(name, boolValue);
                }
            }
        });

        // Apply string options
        optionSet.getStringOptions().forEach((name, option) -> {
            String value = values.get(name);
            if (value != null && option.isValidValue(value)) {
                // Only store non-default values
                if (!value.equals(option.getDefaultValue())) {
                    stringValues.put(name, value);
                }
            }
        });
    }

    /**
     * Sets a boolean option value.
     */
    public void setBooleanValue(String name, boolean value) {
        BooleanShaderOption option = optionSet.getBooleanOption(name);
        if (option != null) {
            if (value == option.getDefaultValue()) {
                booleanValues.remove(name); // Remove if default
            } else {
                booleanValues.put(name, value);
            }
        }
    }

    /**
     * Sets a string option value.
     */
    public void setStringValue(String name, String value) {
        StringShaderOption option = optionSet.getStringOption(name);
        if (option != null && option.isValidValue(value)) {
            if (value.equals(option.getDefaultValue())) {
                stringValues.remove(name); // Remove if default
            } else {
                stringValues.put(name, value);
            }
        }
    }

    /**
     * Gets the current boolean value, returning default if not set.
     */
    public boolean getBooleanValue(String name) {
        BooleanShaderOption option = optionSet.getBooleanOption(name);
        if (option != null) {
            return booleanValues.getOrDefault(name, option.getDefaultValue());
        }
        return false;
    }

    /**
     * Gets the current string value, returning default if not set.
     */
    public String getStringValue(String name) {
        StringShaderOption option = optionSet.getStringOption(name);
        if (option != null) {
            return stringValues.getOrDefault(name, option.getDefaultValue());
        }
        return "";
    }

    /**
     * Gets the current string value as Optional, returning empty if default.
     */
    public Optional<String> getStringValueOptional(String name) {
        String value = stringValues.get(name);
        return Optional.ofNullable(value);
    }

    /**
     * Resets all options to their default values.
     */
    public void resetToDefaults() {
        booleanValues.clear();
        stringValues.clear();
    }

    /**
     * Resets a specific option to its default value.
     */
    public void resetOption(String name) {
        booleanValues.remove(name);
        stringValues.remove(name);
    }

    /**
     * Returns the number of options that have been changed from their defaults.
     */
    public int getChangedOptionCount() {
        return booleanValues.size() + stringValues.size();
    }

    /**
     * Converts to a Properties-compatible map (all string values).
     */
    public Map<String, String> toPropertiesMap() {
        Map<String, String> properties = new HashMap<>();
        booleanValues.forEach((key, value) -> properties.put(key, value.toString()));
        stringValues.forEach(properties::put);
        return properties;
    }

    /**
     * Creates a mutable copy of these values.
     */
    public ShaderOptionValues mutableCopy() {
        ShaderOptionValues copy = new ShaderOptionValues(optionSet);
        copy.booleanValues.putAll(this.booleanValues);
        copy.stringValues.putAll(this.stringValues);
        return copy;
    }

    public ShaderOptionSet getOptionSet() {
        return optionSet;
    }

    @Override
    public String toString() {
        return "ShaderOptionValues{" +
                "changedOptions=" + getChangedOptionCount() +
                ", booleanValues=" + booleanValues +
                ", stringValues=" + stringValues +
                '}';
    }
}