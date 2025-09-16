package net.chromaticity.shader.option;

/**
 * Represents a boolean shader option that can be enabled or disabled.
 * Examples:
 * - #define SHADOWS           // Default: true (enabled)
 * - //#define REFLECTIONS      // Default: false (disabled)
 * - const bool enableFog = true;
 */
public class BooleanShaderOption extends ShaderOption {
    private final boolean defaultValue;

    public BooleanShaderOption(String name, String comment, OptionType type, boolean defaultValue) {
        super(name, comment, type);
        this.defaultValue = defaultValue;
    }

    public boolean getDefaultValue() {
        return defaultValue;
    }

    @Override
    public String getDefaultValueAsString() {
        return Boolean.toString(defaultValue);
    }

    @Override
    public boolean isValidValue(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }

    /**
     * Parses a string value to boolean, returning the default if invalid.
     */
    public boolean parseValue(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        } else if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return "BooleanShaderOption{" +
                "name='" + name + '\'' +
                ", comment='" + comment + '\'' +
                ", type=" + type +
                ", defaultValue=" + defaultValue +
                '}';
    }
}