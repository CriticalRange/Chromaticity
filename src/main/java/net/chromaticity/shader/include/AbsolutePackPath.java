package net.chromaticity.shader.include;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Represents an absolute path within a shader pack.
 * Based on Iris's AbsolutePackPath implementation.
 */
public class AbsolutePackPath {
    private final String path;

    private AbsolutePackPath(String path) {
        this.path = path;
    }

    /**
     * Creates an AbsolutePackPath from an absolute path string.
     */
    public static AbsolutePackPath fromAbsolutePath(String absolutePath) {
        if (!absolutePath.startsWith("/")) {
            throw new IllegalArgumentException("Path must be absolute (start with /): " + absolutePath);
        }
        return new AbsolutePackPath(absolutePath);
    }

    /**
     * Resolves a target path relative to this directory.
     */
    public AbsolutePackPath resolve(String target) {
        if (target.startsWith("/")) {
            // Target is absolute
            return fromAbsolutePath(target);
        }

        // Target is relative to this directory
        String currentDir = this.toString();
        if (!currentDir.endsWith("/")) {
            currentDir += "/";
        }

        // Normalize path separators and resolve relative components
        String resolved = normalizePath(currentDir + target);
        return fromAbsolutePath(resolved);
    }

    /**
     * Gets the parent directory of this path.
     */
    public AbsolutePackPath getParent() {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return fromAbsolutePath("/");
        }
        return new AbsolutePackPath(path.substring(0, lastSlash));
    }

    /**
     * Gets the filename component of this path.
     */
    public String getFilename() {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            return path;
        }
        return path.substring(lastSlash + 1);
    }

    /**
     * Converts this path to a filesystem Path relative to the given base.
     */
    public Path toFilesystemPath(Path basePath) {
        // Remove leading slash for relative resolution
        String relativePath = path.startsWith("/") ? path.substring(1) : path;

        // Normalize path separators for current OS
        relativePath = relativePath.replace('/', java.io.File.separatorChar);

        return basePath.resolve(relativePath);
    }

    /**
     * Normalizes a path by resolving . and .. components.
     */
    private String normalizePath(String path) {
        // Use java.nio.file.Path for normalization, then convert back
        Path normalized = Paths.get(path).normalize();
        String result = normalized.toString().replace('\\', '/');

        // Ensure absolute path starts with /
        if (!result.startsWith("/")) {
            result = "/" + result;
        }

        return result;
    }

    @Override
    public String toString() {
        return path;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AbsolutePackPath)) return false;
        return path.equals(((AbsolutePackPath) obj).path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }
}