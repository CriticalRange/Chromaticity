package net.chromaticity.shader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.chromaticity.shader.config.ShaderPackConfig;
import net.chromaticity.shader.option.ShaderOptionSet;
import net.chromaticity.shader.option.ShaderOptionValues;
import net.chromaticity.shader.parser.ShaderOptionParser;
// Note: All preprocessing removed - using pure LWJGL Shaderc
import net.chromaticity.shader.compilation.ShadercCompiler;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Manages shader pack discovery, extraction, and caching.
 * Handles the cache structure with original/, spir-v/, and shaderpack_settings.json.
 */
public class ShaderPackManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderPackManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path shaderpacksDirectory;
    private final Path cacheDirectory;
    private final Map<String, ShaderPackInfo> loadedPacks = new ConcurrentHashMap<>();
    private final ShaderOptionParser optionParser = new ShaderOptionParser();
    // Note: No translation service needed - using pure LWJGL Shaderc

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
            // Try to find the actual shader pack file/directory
            Path packPath = findShaderPackPath(packName);
            if (packPath == null) {
                LOGGER.error("Shader pack '{}' not found in shaderpacks directory", packName);

                // List available files for debugging
                try (Stream<Path> paths = Files.list(shaderpacksDirectory)) {
                    List<String> availableFiles = paths
                        .map(path -> path.getFileName().toString())
                        .collect(Collectors.toList());
                    LOGGER.error("Available files in shaderpacks directory: {}", availableFiles);
                } catch (IOException e) {
                    LOGGER.error("Failed to list shaderpacks directory: {}", e.getMessage());
                }

                return null;
            }

            Path packCacheDir = cacheDirectory.resolve(packName);
            LOGGER.debug("Preparing shader pack '{}' from path: {}", packName, packPath);

            // Ensure cache directories exist
            Files.createDirectories(packCacheDir);
            Files.createDirectories(packCacheDir.resolve("original"));
            // Note: spir-v/ folder will be created later during apply/processing

            LOGGER.debug("Created cache directories for pack: {}", packName);

            // Extract shader pack if needed
            if (needsExtraction(packPath, packCacheDir)) {
                LOGGER.debug("Extracting shader pack: {} -> {}", packPath, packCacheDir.resolve("original"));
                extractShaderPack(packPath, packCacheDir.resolve("original"));
            } else {
                LOGGER.debug("Shader pack extraction not needed (cache up to date): {}", packName);
            }

            // Parse shader options from extracted files
            Path originalDir = packCacheDir.resolve("original");
            Path shadersDir = originalDir.resolve("shaders");

            // If shaders subdirectory exists, parse that; otherwise parse the original dir directly
            Path parseDir = Files.exists(shadersDir) ? shadersDir : originalDir;
            LOGGER.debug("Parsing shader options from: {}", parseDir);

            // Log directory contents for debugging
            logDirectoryContents(originalDir, "Extracted shader pack contents");
            if (Files.exists(shadersDir)) {
                logDirectoryContents(shadersDir, "Shaders subdirectory contents");
            }

            ShaderOptionSet optionSet = optionParser.parseShaderDirectory(parseDir);

            // Load user settings
            Map<String, String> userSettings = ShaderPackConfig.loadShaderPackSettings(shaderpacksDirectory, packName);
            ShaderOptionValues settings = new ShaderOptionValues(optionSet, userSettings);

            // Save initial settings to shaderpack_settings.json
            saveSettingsToCache(packName, settings);

            ShaderPackInfo info = new ShaderPackInfo(packName, packCacheDir, optionSet, settings);
            loadedPacks.put(packName, info);

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
     * Finds the actual path for a shader pack, trying both directory and ZIP variations.
     */
    private Path findShaderPackPath(String packName) {
        // Try exact name first (directory)
        Path directPath = shaderpacksDirectory.resolve(packName);
        if (Files.exists(directPath)) {
            return directPath;
        }

        // Try with .zip extension
        Path zipPath = shaderpacksDirectory.resolve(packName + ".zip");
        if (Files.exists(zipPath)) {
            return zipPath;
        }

        // Not found
        return null;
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
                Path entryPath = targetDir.resolve(entry.getName());

                // Check if this is a directory entry (either marked as directory or name ends with /)
                if (entry.isDirectory() || entry.getName().endsWith("/")) {
                    // Create directory
                    Files.createDirectories(entryPath);
                } else {
                    // Create parent directories if needed
                    Path parentDir = entryPath.getParent();
                    if (parentDir != null && !Files.exists(parentDir)) {
                        Files.createDirectories(parentDir);
                    }

                    // Extract file (skip if it's size 0 and looks like a directory)
                    if (entry.getSize() == 0 && !entry.getName().contains(".")) {
                        // This might be a directory masquerading as a file, create as directory
                        Files.createDirectories(entryPath);
                    } else {
                        // Extract as regular file
                        try (InputStream in = zipFile.getInputStream(entry);
                             OutputStream out = Files.newOutputStream(entryPath)) {
                            in.transferTo(out);
                        }
                    }
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
        }

        // Update settings in memory
        packInfo.getSettings().applyValues(newSettings);

        // Save to Properties file (Iris compatibility)
        ShaderPackConfig.saveShaderPackSettings(shaderpacksDirectory, packName, packInfo.getSettings());

        // Save to cache
        saveSettingsToCache(packName, packInfo.getSettings());

    }

    /**
     * Processes/applies a shader pack (when user clicks Apply button).
     * Creates spir-v/ folder and triggers shader compilation directly from original files.
     */
    public void processShaderPack(String packName) {
        try {
            ShaderPackInfo packInfo = loadedPacks.get(packName);
            if (packInfo == null) {
                LOGGER.warn("Shader pack '{}' not loaded, attempting to prepare it first", packName);

                // Try to prepare the shader pack first
                packInfo = prepareShaderPackForSettings(packName);
                if (packInfo == null) {
                    LOGGER.error("Cannot process shader pack '{}': preparation failed or pack not found", packName);

                    // Debug: List available shader packs
                    List<String> availablePacks = discoverShaderPacks();
                    LOGGER.debug("Available shader packs: {}", availablePacks);

                }

            }

            Path packCacheDir = cacheDirectory.resolve(packName);
            Path originalDir = packCacheDir.resolve("original");

            // Note: No updated/ directory needed - working directly with original files


            // Phase 1: Shader preprocessing and translation
            ShaderOptionValues currentSettings = packInfo.getSettings();

            // Find shaders directory within original
            Path shadersDir = originalDir.resolve("shaders");
            if (!Files.exists(shadersDir)) {
                shadersDir = originalDir; // Some packs have shaders directly in root
                LOGGER.debug("No 'shaders' subdirectory found, using root directory: {}", originalDir);
            } else {
                LOGGER.debug("Found shaders directory: {}", shadersDir);
            }

            // Debug: Check what files exist in the shaders directory
            logDirectoryContents(shadersDir, "Shaders directory contents");

            // No preprocessing needed - DirectShaderCompiler handles everything directly from original files

            // Note: No file copying needed - working directly with original files

            // Phase 2: Generate change tracking report (skipped - no updated files)
            // generateChangeReport(packName, originalDir, updatedDir, packCacheDir);

            // Phase 3: Preprocessing for debugging (GLSL with includes resolved)
            Path preprocessedDir = packCacheDir.resolve("preprocessed");
            Files.createDirectories(preprocessedDir);
            preprocessShaders(packName, shadersDir, preprocessedDir);

            // Phase 4: SPIR-V Compilation (GLSL 450 core → SPIR-V bytecode)
            Path spirvDir = packCacheDir.resolve("spir-v");
            Files.createDirectories(spirvDir);
            compileToSpirv(packName, shadersDir, spirvDir);

            // Phase 5: Add VulkanMod include paths for runtime integration
            addVulkanModIncludePaths(packName, originalDir);


        } catch (IOException e) {
            LOGGER.error("Failed to process shader pack: {}", packName, e);
        }
    }

    /**
     * Preprocess shaders to debug includes and see final GLSL before compilation.
     */
    private void preprocessShaders(String packName, Path originalShadersDir, Path preprocessedDir) {
        try {
            LOGGER.info("Preprocessing shaders for debugging: {}", packName);

            // Initialize Shaderc compiler
            ShadercCompiler.initialize();
            ShadercCompiler.setShaderPackRoot(originalShadersDir);

            // Set compilation mode to debug for better error messages
            ShadercCompiler.setCompilationMode(ShadercCompiler.CompilationMode.DEBUG);

            // Preprocess all shaders
            ShadercCompiler.preprocessShadersInDirectory(originalShadersDir, preprocessedDir);

            LOGGER.info("Completed shader preprocessing for: {}", packName);

        } catch (Exception e) {
            LOGGER.error("Failed to preprocess shaders for pack: {}", packName, e);
        }
    }

    /**
     * Compiles shaders to SPIR-V bytecode using ShadercCompiler.
     */
    private void compileToSpirv(String packName, Path originalShadersDir, Path spirvDir) {
        try {
            // Initialize Shaderc compiler
            ShadercCompiler.initialize();
            ShadercCompiler.setShaderPackRoot(originalShadersDir);

            // Find all shader files
            List<Path> shaderFiles = findShaderFiles(originalShadersDir);
            LOGGER.info("Found {} shader files to compile for pack: {}", shaderFiles.size(), packName);

            int compiledCount = 0;
            for (Path shaderFile : shaderFiles) {
                try {
                    // Read shader source
                    String shaderSource = Files.readString(shaderFile);
                    String filename = shaderFile.getFileName().toString();
                    ShadercCompiler.ShaderStage stage = ShadercCompiler.ShaderStage.fromFileName(filename);

                    // Compile to SPIR-V
                    ShadercCompiler.CompilationResult result = ShadercCompiler.compileShader(filename, shaderSource, stage);

                    // Calculate output path
                    Path relativePath = originalShadersDir.relativize(shaderFile);
                    Path spirvOutputPath = spirvDir.resolve(relativePath.toString() + ".spv");

                    // Create output directory
                    Files.createDirectories(spirvOutputPath.getParent());

                    // Save SPIR-V bytecode
                    Files.write(spirvOutputPath, result.getSpirvBytecode());

                    compiledCount++;
                    LOGGER.debug("Compiled shader [{}/{}]: {} -> {}",
                        compiledCount, shaderFiles.size(),
                        shaderFile.getFileName(), spirvOutputPath.getFileName());

                } catch (Exception e) {
                    LOGGER.error("Failed to compile shader: {}", shaderFile, e);
                }
            }

            LOGGER.info("Successfully compiled {} shaders for pack: {}", compiledCount, packName);

        } catch (Exception e) {
            LOGGER.error("Failed to compile shader pack '{}' to SPIR-V", packName, e);
            throw new RuntimeException("SPIR-V compilation failed for: " + packName, e);
        }
    }

    /**
     * Finds all shader files recursively in a directory.
     */
    private List<Path> findShaderFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return Collections.emptyList();
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(this::isShaderFile)
                .collect(Collectors.toList());
        }
    }

    /**
     * Adds VulkanMod include paths for the processed shader pack.
     */
    private void addVulkanModIncludePaths(String packName, Path compiledDir) {
        try {
            // Add shaderpack-specific include paths to VulkanMod's system
            // Note: Include paths handled by DirectShaderCompiler include callbacks

            LOGGER.debug("Configured include paths for shader pack: {} - Integration status: Direct Shaderc", packName);

        } catch (Exception e) {
            LOGGER.warn("Failed to configure include paths for pack: {}", packName, e);
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
     * Copies non-shader files from original to updated directory maintaining structure.
     * This includes textures, properties files, documentation, etc.
     */
    private void copyNonShaderFiles(Path originalDir, Path updatedDir) throws IOException {
        try (Stream<Path> paths = Files.walk(originalDir)) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> !isShaderFile(path))
                 .forEach(sourcePath -> {
                     try {
                         Path relativePath = originalDir.relativize(sourcePath);
                         Path targetPath = updatedDir.resolve(relativePath);

                         // Ensure parent directories exist
                         Files.createDirectories(targetPath.getParent());

                         // Copy the file
                         Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

                         LOGGER.trace("Copied non-shader file: {} -> {}",
                             relativePath, targetPath.getFileName());

                     } catch (IOException e) {
                         LOGGER.warn("Failed to copy file: {}", sourcePath, e);
                     }
                 });
        }

        LOGGER.debug("Copied non-shader files from original to updated directory");
    }

    /**
     * Checks if a file is a shader file based on its extension.
     */
    private boolean isShaderFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return fileName.endsWith(".vsh") ||
               fileName.endsWith(".fsh") ||
               fileName.endsWith(".gsh") ||
               fileName.endsWith(".tcs") ||
               fileName.endsWith(".tes") ||
               fileName.endsWith(".glsl") ||
               fileName.endsWith(".vert") ||
               fileName.endsWith(".frag") ||
               fileName.endsWith(".geom");
    }

    /**
     * Logs the contents of a directory for debugging purposes.
     */
    private void logDirectoryContents(Path directory, String description) {
        try {
            if (!Files.exists(directory)) {
                LOGGER.debug("{}: Directory does not exist: {}", description, directory);
            }

            if (!Files.isDirectory(directory)) {
                LOGGER.debug("{}: Path is not a directory: {}", description, directory);
            }

            try (Stream<Path> paths = Files.list(directory)) {
                List<Path> files = paths.collect(Collectors.toList());
                LOGGER.debug("{}: Found {} items in {}", description, files.size(), directory);

                for (Path file : files) {
                    String type = Files.isDirectory(file) ? "DIR " : "FILE";
                    String name = file.getFileName().toString();
                    LOGGER.debug("  {} {}", type, name);

                    // If it's a shader file, log it specifically
                    if (Files.isRegularFile(file) && isShaderFile(file)) {
                        LOGGER.debug("    ↳ Detected as shader file");
                    }
                }
            }

        } catch (IOException e) {
            LOGGER.error("Failed to list directory contents for {}: {}", description, directory, e);
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

        public Path getPreprocessedDirectory() {
            return cacheDirectory.resolve("preprocessed");
        }

        // Note: Updated directory no longer used - working directly with original files
        // public Path getUpdatedDirectory() {
        //     return cacheDirectory.resolve("updated");
        // }

        public Path getSpirvDirectory() {
            return cacheDirectory.resolve("spir-v");
        }

        public ShaderOptionSet getOptionSet() {
            return optionSet;
        }

        public ShaderOptionValues getSettings() {
            return settings;
        }
    }

    /**
     * Force preprocessing of a specific shader pack for debugging.
     */
    public void forcePreprocessShaderPack(String packName) {
        try {
            ShaderPackInfo packInfo = loadedPacks.get(packName);
            if (packInfo == null) {
                LOGGER.warn("Shader pack '{}' not loaded for preprocessing", packName);
                return;
            }

            Path packCacheDir = cacheDirectory.resolve(packName);
            Path originalDir = packCacheDir.resolve("original");
            Path preprocessedDir = packCacheDir.resolve("preprocessed");

            // Find shaders directory
            Path shadersDir = originalDir.resolve("shaders");
            if (!Files.exists(shadersDir)) {
                shadersDir = originalDir;
            }

            // Clean and recreate preprocessed directory
            if (Files.exists(preprocessedDir)) {
                Files.walk(preprocessedDir)
                     .sorted(java.util.Comparator.reverseOrder())
                     .map(Path::toFile)
                     .forEach(File::delete);
            }
            Files.createDirectories(preprocessedDir);

            // Preprocess shaders
            preprocessShaders(packName, shadersDir, preprocessedDir);

            LOGGER.info("Force preprocessing completed for: {}", packName);

        } catch (IOException e) {
            LOGGER.error("Failed to force preprocess shader pack: {}", packName, e);
        }
    }
}