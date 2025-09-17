package net.chromaticity.shader.compilation;

import net.chromaticity.shader.ShaderPackManager;
import net.chromaticity.shader.preprocessing.ShaderTranslationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Service that orchestrates the complete shader compilation pipeline:
 * GLSL Source → Preprocessing → SPIR-V Compilation → Caching
 */
public class ShaderCompilationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderCompilationService.class);

    private final ShaderTranslationService translationService;
    private final Map<String, CompilationSession> activeSessions = new ConcurrentHashMap<>();

    public ShaderCompilationService(ShaderTranslationService translationService) {
        this.translationService = translationService;
    }

    /**
     * Compilation session tracking for a specific shaderpack.
     */
    public static class CompilationSession {
        private final String shaderpackName;
        private final Path updatedDir;
        private final Path compiledDir;
        private final Map<String, ChromaticityShaderCompiler.CompilationResult> compiledShaders;
        private final long startTime;

        public CompilationSession(String shaderpackName, Path updatedDir, Path compiledDir) {
            this.shaderpackName = shaderpackName;
            this.updatedDir = updatedDir;
            this.compiledDir = compiledDir;
            this.compiledShaders = new ConcurrentHashMap<>();
            this.startTime = System.currentTimeMillis();
        }

        public String getShaderpackName() { return shaderpackName; }
        public Path getUpdatedDir() { return updatedDir; }
        public Path getCompiledDir() { return compiledDir; }
        public Map<String, ChromaticityShaderCompiler.CompilationResult> getCompiledShaders() { return compiledShaders; }
        public long getStartTime() { return startTime; }
        public long getDuration() { return System.currentTimeMillis() - startTime; }
        public int getShaderCount() { return compiledShaders.size(); }
    }

    /**
     * Compiles all preprocessed shaders in a shaderpack to SPIR-V bytecode.
     *
     * @param shaderpackName Name of the shaderpack
     * @param updatedDir Directory containing preprocessed GLSL 450 core shaders
     * @param compiledDir Directory to save compiled SPIR-V bytecode
     * @return CompilationSession containing compilation results and statistics
     */
    public CompilationSession compileShaderpackToSpirv(String shaderpackName, Path updatedDir, Path compiledDir) {
        if (!ChromaticityShaderCompiler.isAvailable()) {
            throw new UnsupportedOperationException("VulkanMod SPIR-V compilation not available");
        }

        LOGGER.info("Starting SPIR-V compilation for shaderpack: {}", shaderpackName);

        CompilationSession session = new CompilationSession(shaderpackName, updatedDir, compiledDir);
        activeSessions.put(shaderpackName, session);

        try {
            // Create compiled directory
            Files.createDirectories(compiledDir);

            // Configure include paths for this shaderpack
            ChromaticityShaderCompiler.configureShaderpackIncludes(shaderpackName, updatedDir.getParent());

            // Find all preprocessed shader files
            List<Path> shaderFiles = findShaderFiles(updatedDir);
            LOGGER.info("Found {} preprocessed shader files to compile to SPIR-V", shaderFiles.size());

            // Compile each shader file with safety measures
            int compiledCount = 0;
            int failedCount = 0;
            int skippedCount = 0;
            final int MAX_BATCH_SIZE = 10; // Process in smaller batches to prevent crashes

            LOGGER.info("Processing {} shaders in batches of {} to prevent memory issues",
                shaderFiles.size(), MAX_BATCH_SIZE);

            for (int i = 0; i < shaderFiles.size(); i++) {
                Path shaderFile = shaderFiles.get(i);

                // Add a small delay every batch to prevent overwhelming the compiler
                if (i > 0 && i % MAX_BATCH_SIZE == 0) {
                    try {
                        Thread.sleep(100); // 100ms pause between batches
                        LOGGER.debug("Processed batch {}/{}, continuing...",
                            i / MAX_BATCH_SIZE, (shaderFiles.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                try {
                    // Validate shader file before compilation
                    if (!isValidForCompilation(shaderFile)) {
                        skippedCount++;
                        LOGGER.debug("Skipping invalid shader file: {}", shaderFile.getFileName());
                        continue;
                    }

                    // Determine relative path for output
                    Path relativePath = updatedDir.relativize(shaderFile);
                    Path spirvOutputPath = compiledDir.resolve(relativePath.toString() + ".spv");

                    // Create output directory
                    Files.createDirectories(spirvOutputPath.getParent());

                    // Compile to SPIR-V with additional safety checks
                    ChromaticityShaderCompiler.CompilationResult result =
                        ChromaticityShaderCompiler.compileShaderFile(shaderFile);

                    // Store compilation result
                    session.getCompiledShaders().put(relativePath.toString(), result);

                    // Save SPIR-V bytecode to file (this would require additional reflection to extract bytes)
                    // For now, we track the successful compilation
                    saveSpirvPlaceholder(spirvOutputPath, result);

                    compiledCount++;
                    LOGGER.debug("Compiled shader [{}/{}]: {} -> {}",
                        compiledCount, shaderFiles.size(),
                        shaderFile.getFileName(), spirvOutputPath.getFileName());

                } catch (Exception e) {
                    failedCount++;
                    String errorMsg = e.getMessage();

                    // Check for common library file issues
                    if (errorMsg != null && errorMsg.contains("undeclared identifier")) {
                        LOGGER.warn("Shader compilation failed (likely library file or missing includes): {} - {}",
                            shaderFile.getFileName(), e.getMessage());
                    } else {
                        LOGGER.error("Failed to compile shader: {}", shaderFile, e);
                    }

                    // Emergency stop if too many failures (potential system issue)
                    double failureRate = (double) failedCount / (compiledCount + failedCount + skippedCount + 1);
                    if (failedCount > 20 && failureRate > 0.5) {
                        LOGGER.error("Too many compilation failures ({} failed, {:.1f}% failure rate). Stopping to prevent system issues.",
                            failedCount, failureRate * 100);
                        break;
                    }
                }
            }

            LOGGER.info("SPIR-V compilation completed for '{}': {} succeeded, {} failed, {} skipped, {} total",
                shaderpackName, compiledCount, failedCount, skippedCount, shaderFiles.size());

            // Generate compilation report
            generateCompilationReport(session);

            return session;

        } catch (IOException e) {
            String errorMsg = "Failed to compile shaderpack to SPIR-V: " + shaderpackName;
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);

        } finally {
            activeSessions.remove(shaderpackName);
        }
    }

    /**
     * Compiles a single shader from updated directory to SPIR-V.
     *
     * @param shaderpackName Name of the shaderpack (for context)
     * @param shaderPath Path to the preprocessed shader file
     * @return CompilationResult containing SPIR-V bytecode and metadata
     */
    public ChromaticityShaderCompiler.CompilationResult compileSingleShader(String shaderpackName, Path shaderPath) {
        if (!ChromaticityShaderCompiler.isAvailable()) {
            throw new UnsupportedOperationException("VulkanMod SPIR-V compilation not available");
        }

        LOGGER.debug("Compiling single shader to SPIR-V: {}", shaderPath);

        try {
            return ChromaticityShaderCompiler.compileShaderFile(shaderPath);

        } catch (Exception e) {
            String errorMsg = String.format("Failed to compile shader '%s' for pack '%s'",
                shaderPath.getFileName(), shaderpackName);
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Performs a full pipeline compilation: Translation → SPIR-V Compilation.
     *
     * @param shaderpackName Name of the shaderpack
     * @param originalDir Directory containing original OpenGL shaders
     * @param updatedDir Directory for preprocessed GLSL 450 core shaders
     * @param compiledDir Directory for compiled SPIR-V bytecode
     * @return CompilationSession containing complete pipeline results
     */
    public CompilationSession compileFullPipeline(String shaderpackName, Path originalDir,
                                                 Path updatedDir, Path compiledDir) {
        LOGGER.info("Starting full shader compilation pipeline for: {}", shaderpackName);

        try {
            // Phase 1: Preprocessing (OpenGL → GLSL 450 core)
            LOGGER.info("Phase 1: Preprocessing OpenGL shaders to GLSL 450 core");
            translationService.translateShaderDirectory(originalDir, updatedDir, null);

            // Phase 2: SPIR-V Compilation (GLSL 450 core → SPIR-V bytecode)
            LOGGER.info("Phase 2: Compiling GLSL 450 core shaders to SPIR-V");
            CompilationSession session = compileShaderpackToSpirv(shaderpackName, updatedDir, compiledDir);

            LOGGER.info("Full compilation pipeline completed for '{}' in {}ms",
                shaderpackName, session.getDuration());

            return session;

        } catch (Exception e) {
            String errorMsg = "Full compilation pipeline failed for: " + shaderpackName;
            LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Gets compilation statistics for all active and recent sessions.
     */
    public CompilationStatistics getCompilationStatistics() {
        return new CompilationStatistics(
            activeSessions.size(),
            activeSessions.values().stream().mapToInt(CompilationSession::getShaderCount).sum(),
            ChromaticityShaderCompiler.getStats()
        );
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
                .toList();
        }
    }

    /**
     * Checks if a file is a compilable shader file (not a library include).
     * Library files are typically in /lib/ directories and are meant for inclusion, not compilation.
     */
    private boolean isShaderFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        String pathStr = file.toString().replace('\\', '/');

        // Check if it's a shader extension
        boolean isShaderExtension = fileName.endsWith(".vsh") ||
                                  fileName.endsWith(".fsh") ||
                                  fileName.endsWith(".gsh") ||
                                  fileName.endsWith(".csh");

        // .glsl files in lib/ directories are library includes, not standalone shaders
        if (fileName.endsWith(".glsl")) {
            return !pathStr.contains("/lib/") && !pathStr.contains("\\lib\\");
        }

        return isShaderExtension;
    }

    /**
     * Validates if a shader file is safe to compile to SPIR-V.
     */
    private boolean isValidForCompilation(Path shaderFile) {
        try {
            // Check file size (skip extremely large files that might cause issues)
            long fileSize = Files.size(shaderFile);
            if (fileSize > 1024 * 1024) { // Skip files larger than 1MB
                LOGGER.warn("Skipping large shader file ({}KB): {}", fileSize / 1024, shaderFile.getFileName());
                return false;
            }

            // Check if file is readable
            if (!Files.isReadable(shaderFile)) {
                LOGGER.warn("Shader file not readable: {}", shaderFile.getFileName());
                return false;
            }

            // Quick content validation - check for basic GLSL structure
            String content = Files.readString(shaderFile);

            // Skip empty files
            if (content.trim().isEmpty()) {
                LOGGER.debug("Skipping empty shader file: {}", shaderFile.getFileName());
                return false;
            }

            // Must have #version directive for SPIR-V compilation
            if (!content.contains("#version")) {
                LOGGER.debug("Skipping shader without #version directive: {}", shaderFile.getFileName());
                return false;
            }

            return true;

        } catch (IOException e) {
            LOGGER.warn("Failed to validate shader file: {}", shaderFile.getFileName(), e);
            return false;
        }
    }

    /**
     * Saves a placeholder file indicating successful SPIR-V compilation.
     * In a full implementation, this would extract and save the actual SPIR-V bytecode.
     */
    private void saveSpirvPlaceholder(Path outputPath, ChromaticityShaderCompiler.CompilationResult result) throws IOException {
        String placeholder = String.format(
            "# SPIR-V Bytecode Placeholder\n" +
            "# Shader: %s\n" +
            "# Stage: %s\n" +
            "# Compiled: %s\n" +
            "# From Cache: %s\n" +
            "# Compilation successful - SPIR-V bytecode available in memory\n",
            result.getShaderName(),
            result.getStage(),
            new Date(),
            result.isFromCache()
        );

        Files.writeString(outputPath, placeholder);
    }

    /**
     * Generates a detailed compilation report.
     */
    private void generateCompilationReport(CompilationSession session) {
        try {
            Path reportPath = session.getCompiledDir().resolve("spir-v_compilation_report.json");

            Map<String, Object> report = new HashMap<>();
            report.put("shaderpackName", session.getShaderpackName());
            report.put("timestamp", new Date().toInstant().toString());
            report.put("duration", session.getDuration());
            report.put("shaderCount", session.getShaderCount());
            report.put("vulkanModAvailable", ChromaticityShaderCompiler.isAvailable());

            // Compilation results
            Map<String, Map<String, Object>> shaderResults = new HashMap<>();
            session.getCompiledShaders().forEach((shaderPath, result) -> {
                Map<String, Object> shaderInfo = new HashMap<>();
                shaderInfo.put("stage", result.getStage().getName());
                shaderInfo.put("fromCache", result.isFromCache());
                shaderResults.put(shaderPath, shaderInfo);
            });
            report.put("compiledShaders", shaderResults);

            // Save report (simplified JSON-like format)
            String reportJson = report.toString().replace("=", ": ").replace("{", "{\n  ").replace("}", "\n}");
            Files.writeString(reportPath, reportJson);

            LOGGER.debug("Generated SPIR-V compilation report: {}", reportPath);

        } catch (IOException e) {
            LOGGER.warn("Failed to generate compilation report for: {}", session.getShaderpackName(), e);
        }
    }

    /**
     * Compilation statistics summary.
     */
    public static class CompilationStatistics {
        private final int activeSessions;
        private final int totalCompiledShaders;
        private final ChromaticityShaderCompiler.CompilationStats compilerStats;

        public CompilationStatistics(int activeSessions, int totalCompiledShaders,
                                   ChromaticityShaderCompiler.CompilationStats compilerStats) {
            this.activeSessions = activeSessions;
            this.totalCompiledShaders = totalCompiledShaders;
            this.compilerStats = compilerStats;
        }

        public int getActiveSessions() { return activeSessions; }
        public int getTotalCompiledShaders() { return totalCompiledShaders; }
        public ChromaticityShaderCompiler.CompilationStats getCompilerStats() { return compilerStats; }

        @Override
        public String toString() {
            return String.format("Compilation Statistics: %d active sessions, %d total compiled shaders, %s",
                activeSessions, totalCompiledShaders, compilerStats);
        }
    }
}