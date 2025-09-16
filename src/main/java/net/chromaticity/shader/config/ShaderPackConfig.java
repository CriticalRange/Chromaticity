package net.chromaticity.shader.config;

import net.chromaticity.shader.option.ShaderOptionValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Handles persistence of shader pack settings using Java Properties format.
 * Compatible with Iris shader pack settings files ({packName}.txt).
 */
public class ShaderPackConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderPackConfig.class);

    /**
     * Loads shader pack settings from a .txt file in the shaderpacks directory.
     * Returns empty map if file doesn't exist or fails to load.
     */
    public static Map<String, String> loadShaderPackSettings(Path shaderpacksDir, String packName) {
        Path configFile = shaderpacksDir.resolve(packName + ".txt");

        if (!Files.exists(configFile)) {
            LOGGER.debug("No settings file found for pack: {}", packName);
            return new HashMap<>();
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configFile)) {
            properties.load(input);

            Map<String, String> settings = new HashMap<>();
            properties.forEach((key, value) -> settings.put(key.toString(), value.toString()));

            LOGGER.info("Loaded {} settings for pack: {}", settings.size(), packName);
            return settings;

        } catch (IOException e) {
            LOGGER.error("Failed to load settings for pack: {}", packName, e);
            return new HashMap<>();
        }
    }

    /**
     * Saves shader pack settings to a .txt file in the shaderpacks directory.
     * Only saves non-default values. Deletes file if no settings to save.
     */
    public static void saveShaderPackSettings(Path shaderpacksDir, String packName, ShaderOptionValues values) {
        Path configFile = shaderpacksDir.resolve(packName + ".txt");
        Map<String, String> settingsToSave = values.toPropertiesMap();

        if (settingsToSave.isEmpty()) {
            // Delete the file if there are no changed settings
            try {
                if (Files.exists(configFile)) {
                    Files.delete(configFile);
                    LOGGER.debug("Deleted empty settings file for pack: {}", packName);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to delete empty settings file for pack: {}", packName, e);
            }
            return;
        }

        // Save settings to file
        Properties properties = new Properties();
        settingsToSave.forEach(properties::setProperty);

        try (OutputStream output = Files.newOutputStream(configFile)) {
            properties.store(output, "Chromaticity Shader Pack Settings for " + packName);
            LOGGER.info("Saved {} settings for pack: {}", settingsToSave.size(), packName);

        } catch (IOException e) {
            LOGGER.error("Failed to save settings for pack: {}", packName, e);
        }
    }

    /**
     * Imports settings from a properties file.
     */
    public static Map<String, String> importSettings(Path importFile) {
        if (!Files.exists(importFile)) {
            LOGGER.warn("Import file does not exist: {}", importFile);
            return new HashMap<>();
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(importFile)) {
            properties.load(input);

            Map<String, String> settings = new HashMap<>();
            properties.forEach((key, value) -> settings.put(key.toString(), value.toString()));

            LOGGER.info("Imported {} settings from: {}", settings.size(), importFile);
            return settings;

        } catch (IOException e) {
            LOGGER.error("Failed to import settings from: {}", importFile, e);
            return new HashMap<>();
        }
    }

    /**
     * Exports current settings to a properties file.
     */
    public static void exportSettings(Path exportFile, ShaderOptionValues values, String packName) {
        Map<String, String> settingsToExport = values.toPropertiesMap();

        // Include all current values (not just non-defaults) for export
        Map<String, String> allSettings = new HashMap<>();

        // Add boolean values
        values.getOptionSet().getBooleanOptions().forEach((name, option) -> {
            boolean currentValue = values.getBooleanValue(name);
            allSettings.put(name, Boolean.toString(currentValue));
        });

        // Add string values
        values.getOptionSet().getStringOptions().forEach((name, option) -> {
            String currentValue = values.getStringValue(name);
            allSettings.put(name, currentValue);
        });

        Properties properties = new Properties();
        allSettings.forEach(properties::setProperty);

        try {
            // Ensure parent directory exists
            Files.createDirectories(exportFile.getParent());

            try (OutputStream output = Files.newOutputStream(exportFile)) {
                properties.store(output, "Exported Chromaticity Shader Pack Settings for " + packName);
                LOGGER.info("Exported {} settings to: {}", allSettings.size(), exportFile);
            }

        } catch (IOException e) {
            LOGGER.error("Failed to export settings to: {}", exportFile, e);
        }
    }

    /**
     * Checks if a shader pack has saved settings.
     */
    public static boolean hasSettings(Path shaderpacksDir, String packName) {
        Path configFile = shaderpacksDir.resolve(packName + ".txt");
        return Files.exists(configFile);
    }

    /**
     * Gets the settings file path for a shader pack.
     */
    public static Path getSettingsFile(Path shaderpacksDir, String packName) {
        return shaderpacksDir.resolve(packName + ".txt");
    }

    /**
     * Resets shader pack settings by deleting the settings file.
     */
    public static void resetSettings(Path shaderpacksDir, String packName) {
        Path configFile = shaderpacksDir.resolve(packName + ".txt");

        try {
            if (Files.exists(configFile)) {
                Files.delete(configFile);
                LOGGER.info("Reset settings for pack: {}", packName);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to reset settings for pack: {}", packName, e);
        }
    }
}