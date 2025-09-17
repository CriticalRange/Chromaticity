package net.chromaticity.shader.include;

import net.chromaticity.shader.compilation.DirectShaderCompilerPreprocessing;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test for the new Iris-inspired include system.
 */
public class IncludeSystemTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(IncludeSystemTest.class);

    @Test
    public void testIncludeSystem() throws IOException {
        // Test with actual VanillaPlus shader pack if available
        Path testPackPath = Paths.get("C:\\Users\\Ahmet\\AppData\\Roaming\\PrismLauncher\\instances\\1.21.1(1)\\minecraft\\shaderpacks\\chromaticity_cache\\VanillaPlus_v3.3\\updated");

        if (!Files.exists(testPackPath)) {
            LOGGER.warn("Test shader pack not found: {}", testPackPath);
            return;
        }

        // Initialize the include system
        DirectShaderCompilerPreprocessing.initializeIncludeSystem(testPackPath);

        // Test preprocessing of head.glsl (the problematic file)
        AbsolutePackPath headPath = AbsolutePackPath.fromAbsolutePath("/shaders/lib/head.glsl");

        // Create a temporary include processor to test
        IncludeGraph graph = new IncludeGraph(testPackPath);
        graph.discoverFiles();
        // Validation now happens automatically during discoverFiles()

        IncludeProcessor processor = new IncludeProcessor(graph);

        // Get preprocessed content
        String preprocessed = processor.getProcessedFileAsString(headPath);

        LOGGER.info("Preprocessed head.glsl ({} characters):", preprocessed.length());
        LOGGER.info("Preview:\n{}", preprocessed.length() > 1000 ?
            preprocessed.substring(0, 1000) + "..." : preprocessed);

        // Verify that includes were processed (either successfully or with error markers)
        boolean hasSuccessfulIncludes = preprocessed.contains("BEGIN INCLUDE");
        boolean hasErrorMarkers = preprocessed.contains("ERROR: Include file not found");
        assert hasSuccessfulIncludes || hasErrorMarkers : "Should contain either include markers or error markers";
        assert !preprocessed.contains("#include") : "Should not contain unprocessed includes";

        LOGGER.info("Include system test passed!");
    }
}