package net.chromaticity.shader.option;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Container for all shader options discovered from a shader pack.
 * Maintains both boolean and string options, with builders for construction.
 */
public class ShaderOptionSet {
    private final Map<String, BooleanShaderOption> booleanOptions;
    private final Map<String, StringShaderOption> stringOptions;

    private ShaderOptionSet(Builder builder) {
        this.booleanOptions = new LinkedHashMap<>(builder.booleanOptions);
        this.stringOptions = new LinkedHashMap<>(builder.stringOptions);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, BooleanShaderOption> getBooleanOptions() {
        return new LinkedHashMap<>(booleanOptions);
    }

    public Map<String, StringShaderOption> getStringOptions() {
        return new LinkedHashMap<>(stringOptions);
    }

    public BooleanShaderOption getBooleanOption(String name) {
        return booleanOptions.get(name);
    }

    public StringShaderOption getStringOption(String name) {
        return stringOptions.get(name);
    }

    public boolean isBooleanOption(String name) {
        return booleanOptions.containsKey(name);
    }

    public boolean isStringOption(String name) {
        return stringOptions.containsKey(name);
    }

    public Set<String> getAllOptionNames() {
        Set<String> allNames = new LinkedHashMap<>(booleanOptions).keySet();
        allNames.addAll(stringOptions.keySet());
        return allNames;
    }

    public int getTotalOptionCount() {
        return booleanOptions.size() + stringOptions.size();
    }

    public boolean isEmpty() {
        return booleanOptions.isEmpty() && stringOptions.isEmpty();
    }

    @Override
    public String toString() {
        return "ShaderOptionSet{" +
                "booleanOptions=" + booleanOptions.size() +
                ", stringOptions=" + stringOptions.size() +
                '}';
    }

    public static class Builder {
        private final Map<String, BooleanShaderOption> booleanOptions = new LinkedHashMap<>();
        private final Map<String, StringShaderOption> stringOptions = new LinkedHashMap<>();

        public Builder addBooleanOption(BooleanShaderOption option) {
            if (option != null) {
                booleanOptions.put(option.getName(), option);
            }
            return this;
        }

        public Builder addStringOption(StringShaderOption option) {
            if (option != null) {
                stringOptions.put(option.getName(), option);
            }
            return this;
        }

        public Builder addAll(ShaderOptionSet other) {
            if (other != null) {
                booleanOptions.putAll(other.booleanOptions);
                stringOptions.putAll(other.stringOptions);
            }
            return this;
        }

        /**
         * Merges options from another builder, handling conflicts by keeping the first occurrence.
         */
        public Builder merge(Builder other) {
            if (other != null) {
                // Only add options that don't already exist (first wins)
                other.booleanOptions.forEach(booleanOptions::putIfAbsent);
                other.stringOptions.forEach(stringOptions::putIfAbsent);
            }
            return this;
        }

        public ShaderOptionSet build() {
            return new ShaderOptionSet(this);
        }

        public int size() {
            return booleanOptions.size() + stringOptions.size();
        }

        public boolean isEmpty() {
            return booleanOptions.isEmpty() && stringOptions.isEmpty();
        }
    }
}