package net.chromaticity.shader.include;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Represents a directed graph of shader files and their include dependencies.
 * Provides cycle detection and dependency resolution.
 * Based on Iris's IncludeGraph implementation.
 */
public class IncludeGraph {
    private static final Logger LOGGER = LoggerFactory.getLogger(IncludeGraph.class);

    private final Path shaderPackRoot;
    private final Map<AbsolutePackPath, FileNode> nodes;

    public IncludeGraph(Path shaderPackRoot) {
        this.shaderPackRoot = shaderPackRoot;
        this.nodes = new HashMap<>();
    }

    /**
     * Discovers and loads all shader files in the shader pack.
     */
    public void discoverFiles() throws IOException {
        LOGGER.info("Discovering shader files in: {}", shaderPackRoot);

        try (Stream<Path> walk = Files.walk(shaderPackRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(this::isShaderFile)
                .forEach(this::loadFile);
        }

        LOGGER.info("Discovered {} shader files", nodes.size());

        // Perform upfront cycle detection like Iris does
        validateIncludeGraph();
    }

    /**
     * Loads a specific shader file into the graph.
     */
    public void loadFile(AbsolutePackPath packPath, Path filesystemPath) {
        try {
            if (nodes.containsKey(packPath)) {
                LOGGER.debug("File already loaded: {}", packPath);
                return;
            }

            FileNode node = new FileNode(packPath, filesystemPath);
            nodes.put(packPath, node);

            LOGGER.debug("Loaded file: {} (includes: {})", packPath, node.getIncludes().size());

            // Recursively load included files
            for (AbsolutePackPath includePath : node.getIncludes()) {
                Path includeFilesystemPath = includePath.toFilesystemPath(shaderPackRoot);
                if (Files.exists(includeFilesystemPath)) {
                    loadFile(includePath, includeFilesystemPath);
                } else {
                    LOGGER.warn("Include file not found: {} (referenced by {})",
                        includePath, packPath);
                }
            }

        } catch (IOException e) {
            LOGGER.error("Failed to load file: {} -> {}", packPath, filesystemPath, e);
        }
    }

    /**
     * Loads a file from a filesystem path, computing the pack path.
     */
    private void loadFile(Path filesystemPath) {
        try {
            Path relativePath = shaderPackRoot.relativize(filesystemPath);
            String packPathStr = "/" + relativePath.toString().replace('\\', '/');
            AbsolutePackPath packPath = AbsolutePackPath.fromAbsolutePath(packPathStr);

            loadFile(packPath, filesystemPath);

        } catch (Exception e) {
            LOGGER.error("Failed to load file: {}", filesystemPath, e);
        }
    }

    /**
     * Checks if a file is a shader file based on its extension.
     */
    private boolean isShaderFile(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        return filename.endsWith(".glsl") ||
               filename.endsWith(".fsh") ||
               filename.endsWith(".vsh") ||
               filename.endsWith(".gsh") ||
               filename.endsWith(".csh") ||
               filename.endsWith(".frag") ||
               filename.endsWith(".vert") ||
               filename.endsWith(".geom") ||
               filename.endsWith(".comp");
    }

    /**
     * Detects cycles in the include graph.
     */
    public Optional<List<AbsolutePackPath>> detectCycle() {
        Set<AbsolutePackPath> visited = new HashSet<>();
        Set<AbsolutePackPath> recursionStack = new HashSet<>();

        for (AbsolutePackPath startNode : nodes.keySet()) {
            if (!visited.contains(startNode)) {
                Optional<List<AbsolutePackPath>> cycle = detectCycleRecursive(
                    startNode, visited, recursionStack, new ArrayList<>());
                if (cycle.isPresent()) {
                    return cycle;
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Recursive cycle detection using DFS.
     */
    private Optional<List<AbsolutePackPath>> detectCycleRecursive(
            AbsolutePackPath current,
            Set<AbsolutePackPath> visited,
            Set<AbsolutePackPath> recursionStack,
            List<AbsolutePackPath> path) {

        visited.add(current);
        recursionStack.add(current);
        path.add(current);

        FileNode currentNode = nodes.get(current);
        if (currentNode != null) {
            for (AbsolutePackPath dependency : currentNode.getIncludes()) {
                if (!nodes.containsKey(dependency)) {
                    // Skip missing files (already logged as warnings)
                    continue;
                }

                if (!visited.contains(dependency)) {
                    Optional<List<AbsolutePackPath>> cycle = detectCycleRecursive(
                        dependency, visited, recursionStack, new ArrayList<>(path));
                    if (cycle.isPresent()) {
                        return cycle;
                    }
                } else if (recursionStack.contains(dependency)) {
                    // Found a cycle!
                    List<AbsolutePackPath> cycle = new ArrayList<>(path);
                    cycle.add(dependency);
                    return Optional.of(cycle);
                }
            }
        }

        recursionStack.remove(current);
        return Optional.empty();
    }

    /**
     * Validates the include graph for cycles and missing files.
     */
    private void validateIncludeGraph() throws IllegalStateException {
        // Check for cycles
        Optional<List<AbsolutePackPath>> cycle = detectCycle();
        if (cycle.isPresent()) {
            List<AbsolutePackPath> cyclePath = cycle.get();
            StringBuilder cycleStr = new StringBuilder();
            for (int i = 0; i < cyclePath.size(); i++) {
                if (i > 0) cycleStr.append(" -> ");
                cycleStr.append(cyclePath.get(i));
            }
            throw new IllegalStateException("Circular include dependency detected: " + cycleStr);
        }

        // Check for missing files
        Set<AbsolutePackPath> missingFiles = new HashSet<>();
        for (FileNode node : nodes.values()) {
            for (AbsolutePackPath includePath : node.getIncludes()) {
                if (!nodes.containsKey(includePath)) {
                    missingFiles.add(includePath);
                }
            }
        }

        if (!missingFiles.isEmpty()) {
            LOGGER.warn("Found {} missing include files: {}", missingFiles.size(), missingFiles);
        }

        LOGGER.info("Include graph validation completed. {} files, {} missing includes",
            nodes.size(), missingFiles.size());
    }

    /**
     * Gets a file node by its pack path.
     */
    public FileNode getFileNode(AbsolutePackPath packPath) {
        return nodes.get(packPath);
    }

    /**
     * Gets all file nodes in the graph.
     */
    public Collection<FileNode> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /**
     * Checks if the graph contains a file.
     */
    public boolean contains(AbsolutePackPath packPath) {
        return nodes.containsKey(packPath);
    }

    /**
     * Gets the number of files in the graph.
     */
    public int size() {
        return nodes.size();
    }
}