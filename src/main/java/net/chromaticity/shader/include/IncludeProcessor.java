package net.chromaticity.shader.include;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Processes and flattens include directives in shaders.
 * Recursively resolves all includes and produces a single flattened source.
 * Based on Iris's IncludeProcessor implementation.
 */
public class IncludeProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(IncludeProcessor.class);

    private final IncludeGraph includeGraph;
    private final Map<AbsolutePackPath, List<String>> processedCache;

    public IncludeProcessor(IncludeGraph includeGraph) {
        this.includeGraph = includeGraph;
        this.processedCache = new HashMap<>();
    }

    /**
     * Gets the fully processed (flattened) content of a file.
     * All includes are recursively resolved and replaced with their content.
     */
    public List<String> getProcessedFile(AbsolutePackPath packPath) {
        // Check cache first
        if (processedCache.containsKey(packPath)) {
            return processedCache.get(packPath);
        }

        // Get the file node
        FileNode fileNode = includeGraph.getFileNode(packPath);
        if (fileNode == null) {
            LOGGER.warn("File not found in include graph: {}", packPath);
            return Collections.emptyList();
        }

        // Process the file
        List<String> processedLines = processFileRecursive(fileNode);

        // Cache the result
        processedCache.put(packPath, processedLines);

        LOGGER.debug("Processed file: {} ({} lines -> {} lines)",
            packPath, fileNode.getLines().size(), processedLines.size());

        return processedLines;
    }

    /**
     * Recursively processes a file, resolving all includes.
     * Simplified Iris-style approach without runtime cycle detection.
     */
    private List<String> processFileRecursive(FileNode fileNode) {
        List<String> result = new ArrayList<>();
        List<String> originalLines = fileNode.getLines();
        Map<Integer, AbsolutePackPath> includes = fileNode.getIncludeMap();

        for (int i = 0; i < originalLines.size(); i++) {
            AbsolutePackPath includePath = includes.get(i);

            if (includePath != null) {
                // This line is an include directive
                FileNode includeNode = includeGraph.getFileNode(includePath);

                if (includeNode != null) {
                    // Recursively process the included file (Iris style)
                    // Check cache first, then process if needed
                    List<String> includedContent = processedCache.get(includePath);
                    if (includedContent == null) {
                        includedContent = processFileRecursive(includeNode);
                        processedCache.put(includePath, includedContent);
                    }
                    result.addAll(includedContent);

                    LOGGER.trace("Included {} lines from: {}", includedContent.size(), includePath);
                } else {
                    // Include file not found - replace with error comment
                    result.add(String.format("// ERROR: Include file not found: %s", includePath));
                    LOGGER.warn("Include file not found: {}", includePath);
                }
            } else {
                // Regular line - copy as-is
                result.add(originalLines.get(i));
            }
        }

        return result;
    }

    /**
     * Gets the flattened source as a single string.
     */
    public String getProcessedFileAsString(AbsolutePackPath packPath) {
        List<String> lines = getProcessedFile(packPath);
        StringBuilder builder = new StringBuilder();

        for (String line : lines) {
            builder.append(line);
            builder.append('\n');
        }

        return builder.toString();
    }

    /**
     * Pre-processes all files in the include graph.
     * This populates the cache for faster subsequent access.
     */
    public void preprocessAllFiles() {
        LOGGER.info("Pre-processing all files in include graph...");

        int processedCount = 0;
        for (FileNode node : includeGraph.getAllNodes()) {
            AbsolutePackPath packPath = node.getPackPath();
            if (!processedCache.containsKey(packPath)) {
                getProcessedFile(packPath);
                processedCount++;
            }
        }

        LOGGER.info("Pre-processed {} files", processedCount);
    }

    /**
     * Clears the processed file cache.
     */
    public void clearCache() {
        processedCache.clear();
        LOGGER.debug("Cleared include processor cache");
    }

    /**
     * Gets cache statistics.
     */
    public ProcessorStats getStats() {
        int totalFiles = includeGraph.size();
        int cachedFiles = processedCache.size();

        return new ProcessorStats(totalFiles, cachedFiles);
    }

    /**
     * Statistics about the include processor.
     */
    public static class ProcessorStats {
        public final int totalFiles;
        public final int cachedFiles;

        public ProcessorStats(int totalFiles, int cachedFiles) {
            this.totalFiles = totalFiles;
            this.cachedFiles = cachedFiles;
        }

        @Override
        public String toString() {
            return String.format("ProcessorStats{total=%d, cached=%d, hit_rate=%.1f%%}",
                totalFiles, cachedFiles,
                totalFiles > 0 ? (cachedFiles * 100.0 / totalFiles) : 0.0);
        }
    }
}