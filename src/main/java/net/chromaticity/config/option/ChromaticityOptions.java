package net.chromaticity.config.option;

import java.util.List;
import java.util.ArrayList;

public class ChromaticityOptions {

    public static List<Object> getShaderPacksOpts() {
        List<Object> options = new ArrayList<>();

        // Simple placeholder options for now
        // We'll replace these with proper VulkanMod Option instances later
        options.add("Shader Packs: Enabled");
        options.add("Current Pack: None");
        options.add("Reload Shaders: Click to reload");

        return options;
    }

    public static void reloadShaderPacks() {
        // TODO: Implement shader pack reloading logic
        System.out.println("Reloading shader packs...");
    }
}