package net.chromaticity.shader.properties;

import java.util.List;
import java.util.Map;

public class OptionDefinition {
    private final String name;
    private final OptionType type;
    private String defaultValue;
    private List<String> values; // For dropdowns/enums
    private float min = 0.0f;
    private float max = 1.0f;
    private float step = 0.1f;
    private String description;
    private Map<String, String> conditionals; // For #ifdef conditions

    public OptionDefinition(String name, OptionType type) {
        this.name = name;
        this.type = type;
    }

    public enum OptionType {
        TOGGLE,      // Boolean options
        SLIDER,      // Float/int ranges
        DROPDOWN,    // Enumeration with specific values
        PROFILE,     // Profile selection
        BUTTON       // Navigation buttons [SCREEN_NAME]
    }

    // Getters and setters
    public String getName() {
        return name;
    }

    public OptionType getType() {
        return type;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public List<String> getValues() {
        return values;
    }

    public void setValues(List<String> values) {
        this.values = values;
    }

    public float getMin() {
        return min;
    }

    public void setMin(float min) {
        this.min = min;
    }

    public float getMax() {
        return max;
    }

    public void setMax(float max) {
        this.max = max;
    }

    public float getStep() {
        return step;
    }

    public void setStep(float step) {
        this.step = step;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getConditionals() {
        return conditionals;
    }

    public void setConditionals(Map<String, String> conditionals) {
        this.conditionals = conditionals;
    }

    @Override
    public String toString() {
        return "OptionDefinition{" +
                "name='" + name + '\'' +
                ", type=" + type +
                ", defaultValue='" + defaultValue + '\'' +
                '}';
    }
}