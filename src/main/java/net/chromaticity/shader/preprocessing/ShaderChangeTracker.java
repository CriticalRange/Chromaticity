package net.chromaticity.shader.preprocessing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tracks and analyzes changes made during shader preprocessing.
 * Creates detailed reports of what was modified during OpenGL to Vulkan translation.
 */
public class ShaderChangeTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderChangeTracker.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Analyzes changes between original and updated shader files and creates a change report.
     */
    public static ChangeReport analyzeChanges(Path originalDir, Path updatedDir, String packName) {
        ChangeReport report = new ChangeReport(packName);

        try {
            // Find all shader files in original directory
            Map<String, Path> originalShaders = findShaderFiles(originalDir);
            Map<String, Path> updatedShaders = findShaderFiles(updatedDir);

            // Analyze each shader file
            for (Map.Entry<String, Path> entry : originalShaders.entrySet()) {
                String relativePath = entry.getKey();
                Path originalFile = entry.getValue();
                Path updatedFile = updatedShaders.get(relativePath);

                if (updatedFile != null && Files.exists(updatedFile)) {
                    ShaderFileChange change = analyzeShaderFile(originalFile, updatedFile, relativePath);
                    report.addShaderChange(change);
                } else {
                    LOGGER.warn("Updated file not found for: {}", relativePath);
                }
            }

            // Check for any new files in updated directory
            for (Map.Entry<String, Path> entry : updatedShaders.entrySet()) {
                String relativePath = entry.getKey();
                if (!originalShaders.containsKey(relativePath)) {
                    report.addNewFile(relativePath);
                }
            }

            LOGGER.info("Change analysis completed for pack '{}': {} files analyzed, {} modified",
                packName, report.getTotalFiles(), report.getModifiedFiles());

        } catch (IOException e) {
            LOGGER.error("Failed to analyze changes for pack: {}", packName, e);
        }

        return report;
    }

    /**
     * Saves a change report to the cache directory.
     */
    public static void saveChangeReport(ChangeReport report, Path cacheDir) {
        try {
            Path reportFile = cacheDir.resolve("preprocessing_report.json");
            String json = GSON.toJson(report);
            Files.writeString(reportFile, json);

            LOGGER.info("Saved preprocessing report for pack '{}' to: {}",
                report.getPackName(), reportFile);

        } catch (IOException e) {
            LOGGER.error("Failed to save change report for pack: {}", report.getPackName(), e);
        }
    }

    /**
     * Loads a change report from the cache directory.
     */
    public static ChangeReport loadChangeReport(Path cacheDir) {
        try {
            Path reportFile = cacheDir.resolve("preprocessing_report.json");
            if (Files.exists(reportFile)) {
                String json = Files.readString(reportFile);
                return GSON.fromJson(json, ChangeReport.class);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load change report from: {}", cacheDir, e);
        }
        return null;
    }

    /**
     * Finds all shader files in a directory and returns them mapped by relative path.
     */
    private static Map<String, Path> findShaderFiles(Path directory) throws IOException {
        Map<String, Path> shaderFiles = new HashMap<>();

        if (!Files.exists(directory)) {
            return shaderFiles;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                 .filter(ShaderChangeTracker::isShaderFile)
                 .forEach(path -> {
                     String relativePath = directory.relativize(path).toString();
                     shaderFiles.put(relativePath, path);
                 });
        }

        return shaderFiles;
    }

    /**
     * Analyzes changes in a single shader file.
     */
    private static ShaderFileChange analyzeShaderFile(Path originalFile, Path updatedFile, String relativePath) {
        ShaderFileChange change = new ShaderFileChange(relativePath);

        try {
            List<String> originalLines = Files.readAllLines(originalFile);
            List<String> updatedLines = Files.readAllLines(updatedFile);

            change.setOriginalLineCount(originalLines.size());
            change.setUpdatedLineCount(updatedLines.size());

            // Analyze specific types of changes
            analyzeVersionChanges(originalLines, updatedLines, change);
            analyzeExtensionChanges(originalLines, updatedLines, change);
            analyzeSyntaxChanges(originalLines, updatedLines, change);
            analyzeUniformChanges(originalLines, updatedLines, change);

            // Calculate overall modification status
            boolean hasChanges = change.hasVersionChange() ||
                               !change.getExtensionsAdded().isEmpty() ||
                               !change.getSyntaxChanges().isEmpty() ||
                               !change.getUniformChanges().isEmpty();

            change.setModified(hasChanges);

            if (hasChanges) {
                LOGGER.debug("Detected changes in shader: {}", relativePath);
            }

        } catch (IOException e) {
            LOGGER.error("Failed to analyze shader file: {}", relativePath, e);
            change.setAnalysisError(e.getMessage());
        }

        return change;
    }

    /**
     * Analyzes GLSL version changes.
     */
    private static void analyzeVersionChanges(List<String> original, List<String> updated, ShaderFileChange change) {
        String originalVersion = extractVersion(original);
        String updatedVersion = extractVersion(updated);

        if (originalVersion != null && updatedVersion != null && !originalVersion.equals(updatedVersion)) {
            change.setVersionChange(originalVersion, updatedVersion);
        }
    }

    /**
     * Analyzes extension additions.
     */
    private static void analyzeExtensionChanges(List<String> original, List<String> updated, ShaderFileChange change) {
        List<String> originalExtensions = extractExtensions(original);
        List<String> updatedExtensions = extractExtensions(updated);

        for (String extension : updatedExtensions) {
            if (!originalExtensions.contains(extension)) {
                change.addExtension(extension);
            }
        }
    }

    /**
     * Analyzes syntax transformations.
     */
    private static void analyzeSyntaxChanges(List<String> original, List<String> updated, ShaderFileChange change) {
        // Look for common syntax transformations
        Map<String, Integer> originalKeywords = countKeywords(original);
        Map<String, Integer> updatedKeywords = countKeywords(updated);

        // Check for attribute -> in transformations
        int originalAttributes = originalKeywords.getOrDefault("attribute", 0);
        int updatedAttributes = updatedKeywords.getOrDefault("attribute", 0);
        if (originalAttributes > updatedAttributes) {
            change.addSyntaxChange("attribute -> in", originalAttributes - updatedAttributes);
        }

        // Check for varying transformations
        int originalVarying = originalKeywords.getOrDefault("varying", 0);
        int updatedVarying = updatedKeywords.getOrDefault("varying", 0);
        if (originalVarying > updatedVarying) {
            change.addSyntaxChange("varying -> in/out", originalVarying - updatedVarying);
        }

        // Check for texture function transformations
        int originalTexture2D = countFunctionCalls(original, "texture2D");
        int updatedTexture2D = countFunctionCalls(updated, "texture2D");
        if (originalTexture2D > updatedTexture2D) {
            change.addSyntaxChange("texture2D -> texture", originalTexture2D - updatedTexture2D);
        }
    }

    /**
     * Analyzes uniform name changes.
     */
    private static void analyzeUniformChanges(List<String> original, List<String> updated, ShaderFileChange change) {
        List<String> originalUniforms = extractUniforms(original);
        List<String> updatedUniforms = extractUniforms(updated);

        // Look for uniform transformations (this is a simplified check)
        for (String originalUniform : originalUniforms) {
            if (!updatedUniforms.contains(originalUniform)) {
                // Check if it was likely transformed
                if (originalUniform.startsWith("gl_")) {
                    change.addUniformChange(originalUniform + " -> (transformed)");
                }
            }
        }
    }

    /**
     * Helper methods for analysis
     */
    private static String extractVersion(List<String> lines) {
        return lines.stream()
            .filter(line -> line.trim().startsWith("#version"))
            .map(line -> line.trim())
            .findFirst()
            .orElse(null);
    }

    private static List<String> extractExtensions(List<String> lines) {
        return lines.stream()
            .filter(line -> line.trim().startsWith("#extension"))
            .map(line -> line.trim())
            .collect(Collectors.toList());
    }

    private static List<String> extractUniforms(List<String> lines) {
        return lines.stream()
            .filter(line -> line.trim().startsWith("uniform"))
            .map(line -> line.trim())
            .collect(Collectors.toList());
    }

    private static Map<String, Integer> countKeywords(List<String> lines) {
        Map<String, Integer> counts = new HashMap<>();
        String[] keywords = {"attribute", "varying", "uniform"};

        for (String line : lines) {
            for (String keyword : keywords) {
                if (line.contains(keyword)) {
                    counts.merge(keyword, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static int countFunctionCalls(List<String> lines, String functionName) {
        return lines.stream()
            .mapToInt(line -> {
                int count = 0;
                int index = 0;
                while ((index = line.indexOf(functionName + "(", index)) != -1) {
                    count++;
                    index += functionName.length();
                }
                return count;
            })
            .sum();
    }

    private static boolean isShaderFile(Path file) {
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
     * Represents a complete change report for a shader pack.
     */
    public static class ChangeReport {
        private final String packName;
        private final String timestamp;
        private final List<ShaderFileChange> shaderChanges = new ArrayList<>();
        private final List<String> newFiles = new ArrayList<>();

        public ChangeReport(String packName) {
            this.packName = packName;
            this.timestamp = Instant.now().toString();
        }

        public void addShaderChange(ShaderFileChange change) {
            shaderChanges.add(change);
        }

        public void addNewFile(String filePath) {
            newFiles.add(filePath);
        }

        public String getPackName() { return packName; }
        public String getTimestamp() { return timestamp; }
        public List<ShaderFileChange> getShaderChanges() { return shaderChanges; }
        public List<String> getNewFiles() { return newFiles; }

        public int getTotalFiles() { return shaderChanges.size(); }
        public long getModifiedFiles() {
            return shaderChanges.stream().mapToLong(change -> change.isModified() ? 1 : 0).sum();
        }
    }

    /**
     * Represents changes in a single shader file.
     */
    public static class ShaderFileChange {
        private final String filePath;
        private boolean modified = false;
        private int originalLineCount = 0;
        private int updatedLineCount = 0;
        private String originalVersion;
        private String updatedVersion;
        private final List<String> extensionsAdded = new ArrayList<>();
        private final Map<String, Integer> syntaxChanges = new HashMap<>();
        private final List<String> uniformChanges = new ArrayList<>();
        private String analysisError;

        public ShaderFileChange(String filePath) {
            this.filePath = filePath;
        }

        // Getters and setters
        public String getFilePath() { return filePath; }
        public boolean isModified() { return modified; }
        public void setModified(boolean modified) { this.modified = modified; }
        public int getOriginalLineCount() { return originalLineCount; }
        public void setOriginalLineCount(int count) { this.originalLineCount = count; }
        public int getUpdatedLineCount() { return updatedLineCount; }
        public void setUpdatedLineCount(int count) { this.updatedLineCount = count; }

        public void setVersionChange(String original, String updated) {
            this.originalVersion = original;
            this.updatedVersion = updated;
        }

        public boolean hasVersionChange() {
            return originalVersion != null && updatedVersion != null;
        }

        public String getOriginalVersion() { return originalVersion; }
        public String getUpdatedVersion() { return updatedVersion; }

        public void addExtension(String extension) { extensionsAdded.add(extension); }
        public List<String> getExtensionsAdded() { return extensionsAdded; }

        public void addSyntaxChange(String change, int count) { syntaxChanges.put(change, count); }
        public Map<String, Integer> getSyntaxChanges() { return syntaxChanges; }

        public void addUniformChange(String change) { uniformChanges.add(change); }
        public List<String> getUniformChanges() { return uniformChanges; }

        public void setAnalysisError(String error) { this.analysisError = error; }
        public String getAnalysisError() { return analysisError; }
    }
}