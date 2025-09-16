package net.chromaticity.shader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.chromaticity.shader.config.ShaderPackConfig;
import net.chromaticity.shader.option.ShaderOptionSet;
import net.chromaticity.shader.option.ShaderOptionValues;
import net.chromaticity.shader.parser.ShaderOptionParser;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Manages shader pack discovery, extraction, and caching.
 * Handles the simplified cache structure with original/, compiled/, and current_settings.json.
 */
public class ShaderPackManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderPackManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path shaderpacksDirectory;
    private final Path cacheDirectory;
    private final Map<String, ShaderPackInfo> loadedPacks = new ConcurrentHashMap<>();
    private final ShaderOptionParser optionParser = new ShaderOptionParser();

    public ShaderPackManager() {
        this.shaderpacksDirectory = getShaderpacksDirectory();
        this.cacheDirectory = getCacheDirectory();

        try {
            Files.createDirectories(shaderpacksDirectory);
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            LOGGER.error("Failed to create shader pack directories", e);
        }
    }

    /**
     * Gets the shaderpacks directory, creating it if it doesn't exist.
     */
    public static Path getShaderpacksDirectory() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve("shaderpacks");
    }

    /**
     * Gets the cache directory for processed shader packs.
     */
    public static Path getCacheDirectory() {
        Path shaderpacksDir = getShaderpacksDirectory();
        return shaderpacksDir.resolve("chromaticity_cache");
    }

    /**
     * Discovers all available shader packs in the shaderpacks directory.
     */
    public List<String> discoverShaderPacks() {
        List<String> packs = new ArrayList<>();

        try {
            if (!Files.exists(shaderpacksDirectory)) {
                return packs;
            }

            try (Stream<Path> paths = Files.list(shaderpacksDirectory)) {
                paths.filter(this::isValidShaderPack)
                     .map(path -> getShaderPackName(path))
                     .forEach(packs::add);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to discover shader packs", e);
        }

        return packs;
    }

    /**
     * Prepares a shader pack for settings view by extracting it and parsing options.
     * Creates the cache structure: original/ folder + shaderpack_settings.json
     */
    public ShaderPackInfo prepareShaderPackForSettings(String packName) {
        // Don't create cache for "None" option
        if ("None".equals(packName)) {
            LOGGER.debug("Skipping cache creation for 'None' shader pack");
            return null;
        }

        if (loadedPacks.containsKey(packName)) {
            return loadedPacks.get(packName);
        }

        try {
            Path packPath = shaderpacksDirectory.resolve(packName);
            Path packCacheDir = cacheDirectory.resolve(packName);

            // Ensure cache directories exist
            Files.createDirectories(packCacheDir);
            Files.createDirectories(packCacheDir.resolve("original"));
            // Note: compiled/ folder will be created later during apply/processing

            // Extract shader pack if needed
            if (needsExtraction(packPath, packCacheDir)) {
                extractShaderPack(packPath, packCacheDir.resolve("original"));
            }

            // Parse shader options from extracted files
            Path originalDir = packCacheDir.resolve("original");
            Path shadersDir = originalDir.resolve("shaders");

            // If shaders subdirectory exists, parse that; otherwise parse the original dir directly
            Path parseDir = Files.exists(shadersDir) ? shadersDir : originalDir;
            LOGGER.debug("Parsing shader options from: {}", parseDir);
            ShaderOptionSet optionSet = optionParser.parseShaderDirectory(parseDir);

            // Load user settings
            Map<String, String> userSettings = ShaderPackConfig.loadShaderPackSettings(shaderpacksDirectory, packName);
            ShaderOptionValues settings = new ShaderOptionValues(optionSet, userSettings);

            // Save initial settings to shaderpack_settings.json
            saveSettingsToCache(packName, settings);

            ShaderPackInfo info = new ShaderPackInfo(packName, packCacheDir, optionSet, settings);
            loadedPacks.put(packName, info);

            LOGGER.info("Prepared shader pack for settings: {}", packName);
            return info;

        } catch (IOException e) {
            LOGGER.error("Failed to prepare shader pack: {}", packName, e);
            return null;
        }
    }

    /**
     * Checks if a path represents a valid shader pack.
     */
    private boolean isValidShaderPack(Path path) {
        if (Files.isDirectory(path)) {
            return hasShadersDirectory(path);
        } else if (path.toString().endsWith(".zip")) {
            return isValidZipShaderPack(path);
        }
        return false;
    }

    /**
     * Checks if a directory contains a shaders subdirectory.
     */
    private boolean hasShadersDirectory(Path directory) {
        try (Stream<Path> paths = Files.walk(directory, 2)) {
            return paths.filter(Files::isDirectory)
                       .anyMatch(path -> path.getFileName().toString().equals("shaders"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Checks if a ZIP file is a valid shader pack.
     */
    private boolean isValidZipShaderPack(Path zipPath) {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            return zipFile.stream()
                         .anyMatch(entry -> entry.getName().contains("shaders/"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Extracts the shader pack name from a path.
     */
    private String getShaderPackName(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.endsWith(".zip")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    /**
     * Checks if extraction is needed (cache is empty or source is newer).
     */
    private boolean needsExtraction(Path sourcePath, Path cacheDir) throws IOException {
        Path originalDir = cacheDir.resolve("original");

        if (!Files.exists(originalDir)) {
            return true;
        }

        // Check if cache is empty
        try (Stream<Path> files = Files.list(originalDir)) {
            if (files.findAny().isEmpty()) {
                return true;
            }
        }

        // Check if source is newer than cache
        long sourceTime = Files.getLastModifiedTime(sourcePath).toMillis();
        long cacheTime = Files.getLastModifiedTime(originalDir).toMillis();
        return sourceTime > cacheTime;
    }

    /**
     * Extracts a shader pack to the original directory.
     */
    private void extractShaderPack(Path sourcePath, Path targetDir) throws IOException {
        // Clear target directory
        if (Files.exists(targetDir)) {
            try (Stream<Path> paths = Files.walk(targetDir)) {
                paths.sorted(Comparator.reverseOrder())
                     .forEach(path -> {
                         try {
                             Files.deleteIfExists(path);
                         } catch (IOException e) {
                             LOGGER.warn("Failed to delete: {}", path, e);
                         }
                     });
            }
        }

        Files.createDirectories(targetDir);

        if (Files.isDirectory(sourcePath)) {
            extractDirectoryPack(sourcePath, targetDir);
        } else if (sourcePath.toString().endsWith(".zip")) {
            extractZipPack(sourcePath, targetDir);
        }

        LOGGER.info("Extracted shader pack to: {}", targetDir);
    }

    /**
     * Extracts a directory-based shader pack.
     */
    private void extractDirectoryPack(Path sourceDir, Path targetDir) throws IOException {
        // Find the shaders directory within the source
        Path shadersDir = findShadersDirectory(sourceDir);
        if (shadersDir != null) {
            // Copy the shader pack root (parent of shaders directory) to target
            Path shaderPackRoot = shadersDir.getParent();
            copyDirectory(shaderPackRoot, targetDir);
        } else {
            throw new IOException("No shaders directory found in: " + sourceDir);
        }
    }

    /**
     * Extracts a ZIP-based shader pack.
     */
    private void extractZipPack(Path zipPath, Path targetDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                Path entryPath = targetDir.resolve(entry.getName());
                Files.createDirectories(entryPath.getParent());

                try (InputStream in = zipFile.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(entryPath)) {
                    in.transferTo(out);
                }
            }
        }
    }

    /**
     * Finds the shaders directory within a shader pack.
     */
    private Path findShadersDirectory(Path packDir) throws IOException {
        try (Stream<Path> paths = Files.walk(packDir, 3)) {
            return paths.filter(Files::isDirectory)
                       .filter(path -> path.getFileName().toString().equals("shaders"))
                       .findFirst()
                       .orElse(null);
        }
    }

    /**
     * Recursively copies a directory.
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    LOGGER.error("Failed to copy: {} to {}", sourcePath, target, e);
                }
            });
        }
    }

    /**
     * Applies settings changes and saves them to both cache and Properties file.
     */
    public void applySettings(String packName, Map<String, String> newSettings) {
        ShaderPackInfo packInfo = loadedPacks.get(packName);
        if (packInfo == null) {
            LOGGER.warn("Cannot apply settings to unknown pack: {}", packName);
            return;
        }

        // Update settings in memory
        packInfo.getSettings().applyValues(newSettings);

        // Save to Properties file (Iris compatibility)
        ShaderPackConfig.saveShaderPackSettings(shaderpacksDirectory, packName, packInfo.getSettings());

        // Save to cache
        saveSettingsToCache(packName, packInfo.getSettings());

        LOGGER.info("Applied settings changes to pack: {}", packName);
    }

    /**
     * Processes/applies a shader pack (when user clicks Apply button).
     * Creates compiled/ folder and triggers shader compilation.
     */
    public void processShaderPack(String packName) {
        try {
            Path packCacheDir = cacheDirectory.resolve(packName);
            Path compiledDir = packCacheDir.resolve("compiled");

            // Create compiled directory if it doesn't exist
            Files.createDirectories(compiledDir);

            LOGGER.info("Processing shader pack: {} (compilation will be implemented later)", packName);

            // TODO: Implement GLSL preprocessing and SPIR-V compilation
            // This will be done in the next phase:
            // 1. Load settings from current_settings.json
            // 2. Apply settings to GLSL source files (modify #define statements)
            // 3. Compile modified GLSL to SPIR-V using VulkanMod's shaderc
            // 4. Save compiled .spv files to compiled/ directory

        } catch (IOException e) {
            LOGGER.error("Failed to process shader pack: {}", packName, e);
        }
    }

    /**
     * Saves settings to cache (shaderpack_settings.json).
     */
    private void saveSettingsToCache(String packName, ShaderOptionValues settings) {
        try {
            Path cacheDir = cacheDirectory.resolve(packName);
            Path settingsFile = cacheDir.resolve("shaderpack_settings.json");

            Map<String, String> settingsMap = settings.toPropertiesMap();
            String json = GSON.toJson(settingsMap);

            Files.writeString(settingsFile, json);
            LOGGER.debug("Saved settings to cache for pack: {}", packName);

        } catch (IOException e) {
            LOGGER.error("Failed to save settings to cache for pack: {}", packName, e);
        }
    }

    /**
     * Information about a loaded shader pack.
     */
    public static class ShaderPackInfo {
        private final String name;
        private final Path cacheDirectory;
        private final ShaderOptionSet optionSet;
        private final ShaderOptionValues settings;

        public ShaderPackInfo(String name, Path cacheDirectory, ShaderOptionSet optionSet, ShaderOptionValues settings) {
            this.name = name;
            this.cacheDirectory = cacheDirectory;
            this.optionSet = optionSet;
            this.settings = settings;
        }

        public String getName() {
            return name;
        }

        public Path getCacheDirectory() {
            return cacheDirectory;
        }

        public Path getOriginalDirectory() {
            return cacheDirectory.resolve("original");
        }

        public Path getCompiledDirectory() {
            return cacheDirectory.resolve("compiled");
        }

        public ShaderOptionSet getOptionSet() {
            return optionSet;
        }

        public ShaderOptionValues getSettings() {
            return settings;
        }
    }
}