package net.chromaticity.config.option;

import net.chromaticity.config.ShaderPackConfig;
import net.chromaticity.shader.properties.PropertiesParser;
import net.chromaticity.screen.ShaderPackScreen;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChromaticityOptions {

    private static final Map<String, ShaderPackConfig> configCache = new HashMap<>();
    private static final Map<String, PropertiesParser.ShaderPackDefinition> definitionCache = new HashMap<>();

    public static ShaderPackConfig getShaderPackConfig(String shaderPackName) {
        return configCache.computeIfAbsent(shaderPackName, ShaderPackConfig::new);
    }

    public static PropertiesParser.ShaderPackDefinition getShaderPackDefinition(String shaderPackName) {
        if (definitionCache.containsKey(shaderPackName)) {
            return definitionCache.get(shaderPackName);
        }

        try {
            ShaderPackScreen.ShaderPack shaderPack = findShaderPack(shaderPackName);
            if (shaderPack != null && shaderPack.hasProperties()) {
                PropertiesParser.ShaderPackDefinition definition = shaderPack.loadProperties();
                definitionCache.put(shaderPackName, definition);
                return definition;
            }
        } catch (IOException e) {
            System.err.println("Failed to load shader pack definition for: " + shaderPackName);
            e.printStackTrace();
        }

        return null;
    }

    public static List<String> getAvailableShaderPacks() {
        List<String> shaderPacks = new ArrayList<>();
        Path shaderPacksPath = getShaderPacksFolder();

        if (!Files.exists(shaderPacksPath)) {
            return shaderPacks;
        }

        try {
            Files.list(shaderPacksPath)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase();
                    if (name.startsWith(".") || name.equals("loading_cache")) {
                        return false;
                    }
                    return Files.isDirectory(path) || name.endsWith(".zip") || name.endsWith(".rar");
                })
                .forEach(path -> {
                    String name = path.getFileName().toString();
                    shaderPacks.add(name);
                });
        } catch (IOException e) {
            System.err.println("Failed to list shader packs: " + e.getMessage());
        }

        return shaderPacks;
    }

    public static boolean hasShaderPackSettings(String shaderPackName) {
        try {
            ShaderPackScreen.ShaderPack shaderPack = findShaderPack(shaderPackName);
            return shaderPack != null && shaderPack.hasProperties();
        } catch (Exception e) {
            return false;
        }
    }

    private static ShaderPackScreen.ShaderPack findShaderPack(String name) {
        Path shaderPacksPath = getShaderPacksFolder();
        Path packPath = shaderPacksPath.resolve(name);

        if (Files.exists(packPath)) {
            return new ShaderPackScreen.ShaderPack(name, packPath.toFile());
        }

        return null;
    }

    private static Path getShaderPacksFolder() {
        // Get Minecraft game directory
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".minecraft", "shaderpacks");
    }

    public static void clearCache() {
        configCache.clear();
        definitionCache.clear();
    }

    public static void clearCacheForPack(String shaderPackName) {
        configCache.remove(shaderPackName);
        definitionCache.remove(shaderPackName);
    }

    public static void reloadShaderPacks() {
        clearCache();
        System.out.println("Chromaticity: Shader pack cache cleared and configurations reloaded");
    }

}