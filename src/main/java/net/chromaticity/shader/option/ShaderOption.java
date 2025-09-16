package net.chromaticity.shader.option;

/**
 * Base class for all shader options that can be configured by users.
 * Shader options are discovered by parsing GLSL source files for #define and const declarations.
 */
public abstract class ShaderOption {
    protected final String name;
    protected final String comment;
    protected final OptionType type;

    public ShaderOption(String name, String comment, OptionType type) {
        this.name = name;
        this.comment = comment;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public String getComment() {
        return comment != null ? comment : "";
    }

    public OptionType getType() {
        return type;
    }

    public abstract String getDefaultValueAsString();

    public abstract boolean isValidValue(String value);

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "name='" + name + '\'' +
                ", comment='" + comment + '\'' +
                ", type=" + type +
                '}';
    }

    public enum OptionType {
        DEFINE,  // #define OPTION_NAME value
        CONST    // const type OPTION_NAME = value;
    }
}