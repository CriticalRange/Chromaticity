package net.chromaticity.shader.include;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Represents a single shader file in the include graph.
 * Parses include directives and tracks dependencies.
 * Based on Iris's FileNode implementation.
 */
public class FileNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileNode.class);

    private final AbsolutePackPath packPath;
    private final Path filesystemPath;
    private final List<String> lines;
    private final Map<Integer, AbsolutePackPath> foundIncludes;

    public FileNode(AbsolutePackPath packPath, Path filesystemPath) throws IOException {
        this.packPath = packPath;
        this.filesystemPath = filesystemPath;
        this.lines = Files.readAllLines(filesystemPath);
        this.foundIncludes = new HashMap<>();

        parseIncludes();
    }

    /**
     * Parses #include directives from the file content.
     */
    private void parseIncludes() {
        AbsolutePackPath currentDirectory = packPath.getParent();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (!line.startsWith("#include")) {
                continue;
            }

            // Extract the include target
            String target = line.substring("#include ".length()).trim();

            // Remove quotes (both " and < > style includes)
            if ((target.startsWith("\"") && target.endsWith("\"")) ||
                (target.startsWith("<") && target.endsWith(">"))) {
                target = target.substring(1, target.length() - 1);
            }

            try {
                // Resolve the include path
                AbsolutePackPath includePath;
                if (target.startsWith("/")) {
                    // Absolute include
                    includePath = AbsolutePackPath.fromAbsolutePath(target);
                } else {
                    // Relative include - resolve relative to current file's directory
                    includePath = currentDirectory.resolve(target);
                }

                foundIncludes.put(i, includePath);
                LOGGER.debug("Found include at line {}: '{}' -> '{}'", i + 1, target, includePath);

            } catch (Exception e) {
                LOGGER.warn("Failed to parse include at line {}: '{}' in file '{}'",
                    i + 1, target, packPath, e);
            }
        }

        LOGGER.debug("Parsed {} includes from file '{}'", foundIncludes.size(), packPath);
    }

    /**
     * Gets all include dependencies of this file.
     */
    public Set<AbsolutePackPath> getIncludes() {
        return new HashSet<>(foundIncludes.values());
    }

    /**
     * Gets the include map (line number -> include path) like Iris does.
     */
    public Map<Integer, AbsolutePackPath> getIncludeMap() {
        return Collections.unmodifiableMap(foundIncludes);
    }

    /**
     * Gets the include at a specific line number (0-based).
     */
    public AbsolutePackPath getIncludeAtLine(int lineNumber) {
        return foundIncludes.get(lineNumber);
    }

    /**
     * Gets all include line numbers.
     */
    public Set<Integer> getIncludeLines() {
        return foundIncludes.keySet();
    }

    /**
     * Gets the raw source lines of this file.
     */
    public List<String> getLines() {
        return Collections.unmodifiableList(lines);
    }

    /**
     * Gets the pack path of this file.
     */
    public AbsolutePackPath getPackPath() {
        return packPath;
    }

    /**
     * Gets the filesystem path of this file.
     */
    public Path getFilesystemPath() {
        return filesystemPath;
    }

    /**
     * Checks if this file contains any include directives.
     */
    public boolean hasIncludes() {
        return !foundIncludes.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("FileNode{path=%s, includes=%d}", packPath, foundIncludes.size());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FileNode)) return false;
        return packPath.equals(((FileNode) obj).packPath);
    }

    @Override
    public int hashCode() {
        return packPath.hashCode();
    }
}