package net.chromaticity.config;

import net.chromaticity.Chromaticity;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ShaderPackConfig {
    private final String shaderPackName;
    private final Map<String, String> values = new HashMap<>();
    private final Path configFile;

    public ShaderPackConfig(String shaderPackName) {
        this.shaderPackName = shaderPackName;
        this.configFile = getConfigPath(shaderPackName);
        loadConfig();
    }

    private Path getConfigPath(String packName) {
        Minecraft minecraft = Minecraft.getInstance();
        Path configDir = Paths.get(minecraft.gameDirectory.getAbsolutePath(), "config", "chromaticity", "shaderpacks");

        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            Chromaticity.LOGGER.error("Failed to create config directory", e);
        }

        return configDir.resolve(packName + ".properties");
    }

    private void loadConfig() {
        if (!Files.exists(configFile)) {
            return; // No config file yet, use defaults
        }

        Properties props = new Properties();
        try (InputStream input = Files.newInputStream(configFile)) {
            props.load(input);

            // Convert properties to our values map
            for (String key : props.stringPropertyNames()) {
                values.put(key, props.getProperty(key));
            }

            Chromaticity.LOGGER.info("Loaded config for shader pack: {}", shaderPackName);
        } catch (IOException e) {
            Chromaticity.LOGGER.error("Failed to load config for shader pack: {}", shaderPackName, e);
        }
    }

    public void saveConfig() {
        Properties props = new Properties();

        // Convert our values map to properties
        for (Map.Entry<String, String> entry : values.entrySet()) {
            props.setProperty(entry.getKey(), entry.getValue());
        }

        try (OutputStream output = Files.newOutputStream(configFile)) {
            props.store(output, "Chromaticity shader pack configuration for: " + shaderPackName);
            Chromaticity.LOGGER.info("Saved config for shader pack: {}", shaderPackName);
        } catch (IOException e) {
            Chromaticity.LOGGER.error("Failed to save config for shader pack: {}", shaderPackName, e);
        }
    }

    // Generic value access
    public String getStringValue(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public boolean getBooleanValue(String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value) || value.equals("1");
    }

    public int getIntValue(String key, int defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public float getFloatValue(String key, float defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDoubleValue(String key, double defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // Value setting
    public void setValue(String key, String value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        saveConfig(); // Auto-save on change
    }

    public void setValue(String key, boolean value) {
        setValue(key, String.valueOf(value));
    }

    public void setValue(String key, int value) {
        setValue(key, String.valueOf(value));
    }

    public void setValue(String key, float value) {
        setValue(key, String.valueOf(value));
    }

    public void setValue(String key, double value) {
        setValue(key, String.valueOf(value));
    }

    // Bulk operations
    public void setValues(Map<String, String> newValues) {
        values.putAll(newValues);
        saveConfig();
    }

    public void resetToDefaults() {
        values.clear();
        saveConfig();
    }

    public Map<String, String> getAllValues() {
        return new HashMap<>(values);
    }

    public boolean hasValue(String key) {
        return values.containsKey(key);
    }

    public void removeValue(String key) {
        values.remove(key);
        saveConfig();
    }

    // Utility methods
    public String getShaderPackName() {
        return shaderPackName;
    }

    public Path getConfigFile() {
        return configFile;
    }

    public boolean configExists() {
        return Files.exists(configFile);
    }

    @Override
    public String toString() {
        return "ShaderPackConfig{" +
                "shaderPackName='" + shaderPackName + '\'' +
                ", valueCount=" + values.size() +
                '}';
    }
}