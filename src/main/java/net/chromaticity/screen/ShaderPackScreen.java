package net.chromaticity.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.VButtonWidget;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ShaderPackScreen extends Screen {
    private final Screen parent;
    private final List<ShaderPack> shaderPacks = new ArrayList<>();
    private SimpleShaderPackListWidget shaderPackList;
    private ShaderPack selectedShaderPack;
    private ShaderPack appliedShaderPack; // Track which pack is currently applied
    private boolean hasAppliedCurrentSelection = false;

    // VulkanMod Buttons
    private VButtonWidget backButton;
    private VButtonWidget refreshButton;
    private VButtonWidget openFolderButton;
    private VButtonWidget settingsButton;
    private VButtonWidget applyButton;

    public ShaderPackScreen(Screen parent) {
        super(Component.literal("Shader Packs"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Title positioning
        int titleY = 20;

        // Back button at top left - VulkanMod style
        this.backButton = new VButtonWidget(
            10, 10, 50, 20,
            Component.literal("Back"),
            button -> this.minecraft.setScreen(this.parent)
        );

        // Initialize custom shader pack list with full control over spacing
        int listTop = 35;
        int listHeight = this.height - 60 - listTop; // Available height for list
        this.shaderPackList = new SimpleShaderPackListWidget(20, listTop, this.width - 40, listHeight, this);
        this.addRenderableWidget(this.shaderPackList);

        // Bottom buttons
        int buttonY = this.height - 30;
        int buttonSpacing = 5;
        int buttonWidth = 80;
        int totalButtonsWidth = (buttonWidth * 4) + (buttonSpacing * 3);
        int startX = (this.width - totalButtonsWidth) / 2;

        this.refreshButton = new VButtonWidget(
            startX, buttonY, buttonWidth, 20,
            Component.literal("Refresh"),
            button -> this.refreshShaderPacks()
        );

        this.openFolderButton = new VButtonWidget(
            startX + buttonWidth + buttonSpacing, buttonY, buttonWidth, 20,
            Component.literal("Open Folder"),
            button -> this.openShaderPacksFolder()
        );

        this.settingsButton = new VButtonWidget(
            startX + (buttonWidth + buttonSpacing) * 2, buttonY, buttonWidth, 20,
            Component.literal("Settings"),
            button -> this.openShaderPackSettings()
        );

        this.applyButton = new VButtonWidget(
            startX + (buttonWidth + buttonSpacing) * 3, buttonY, buttonWidth, 20,
            Component.literal("Apply"),
            button -> this.applySelectedShaderPack()
        );

        // Load shader packs
        this.refreshShaderPacks();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render background first
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // Render VulkanMod buttons manually
        this.backButton.render(mouseX, mouseY);
        this.refreshButton.render(mouseX, mouseY);
        this.openFolderButton.render(mouseX, mouseY);
        this.settingsButton.render(mouseX, mouseY);
        this.applyButton.render(mouseX, mouseY);

        // Render shader pack list
        this.shaderPackList.render(graphics, mouseX, mouseY, partialTick);

        // Render title centered at same level as back button (no background)
        int titleX = this.width / 2;
        int titleY = 10; // Same Y coordinate as back button
        graphics.drawCenteredString(this.font, this.title, titleX, titleY, 0xFFFFFF);

        // Update settings and apply button state
        boolean hasSelection = this.selectedShaderPack != null;
        this.settingsButton.active = hasSelection;
        this.applyButton.active = hasSelection && !this.hasAppliedCurrentSelection;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle VulkanMod button clicks
        if (this.backButton.mouseClicked(mouseX, mouseY, button)) return true;
        if (this.refreshButton.mouseClicked(mouseX, mouseY, button)) return true;
        if (this.openFolderButton.mouseClicked(mouseX, mouseY, button)) return true;
        if (this.settingsButton.mouseClicked(mouseX, mouseY, button)) return true;
        if (this.applyButton.mouseClicked(mouseX, mouseY, button)) return true;

        // Delegate to parent for other UI elements
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void refreshShaderPacks() {
        this.shaderPacks.clear();

        // Get shader packs folder
        Path shaderPacksPath = getShaderPacksFolder();

        if (!Files.exists(shaderPacksPath)) {
            try {
                Files.createDirectories(shaderPacksPath);
            } catch (Exception e) {
                // Failed to create directory
                return;
            }
        }

        try {
            Files.list(shaderPacksPath)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase();
                    // Filter out system folders and only include valid shader packs
                    if (name.startsWith(".") || name.equals("loading_cache")) {
                        return false;
                    }
                    return Files.isDirectory(path) || name.endsWith(".zip") || name.endsWith(".rar");
                })
                .forEach(path -> {
                    String name = path.getFileName().toString();
                    this.shaderPacks.add(new ShaderPack(name, path.toFile()));
                });
        } catch (Exception e) {
            // Failed to list shader packs
        }

        // Add "None" option at the beginning
        this.shaderPacks.add(0, new ShaderPack("None", null));

        if (this.shaderPackList != null) {
            this.shaderPackList.updateShaderPacks(this.shaderPacks);
        }
    }

    private Path getShaderPacksFolder() {
        return Paths.get(this.minecraft.gameDirectory.getAbsolutePath(), "shaderpacks");
    }

    private void openShaderPacksFolder() {
        try {
            Path shaderPacksPath = getShaderPacksFolder();
            if (!Files.exists(shaderPacksPath)) {
                Files.createDirectories(shaderPacksPath);
            }

            // Open folder using system default
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec("explorer " + shaderPacksPath.toString());
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec("open " + shaderPacksPath.toString());
            } else if (os.contains("nix") || os.contains("nux")) {
                Runtime.getRuntime().exec("xdg-open " + shaderPacksPath.toString());
            }
        } catch (Exception e) {
            // Failed to open folder
        }
    }

    private void openShaderPackSettings() {
        if (this.selectedShaderPack != null && !this.selectedShaderPack.isNone()) {
            // TODO: Open shader pack settings screen
        }
    }

    private void applySelectedShaderPack() {
        if (this.selectedShaderPack != null) {
            // TODO: Apply the selected shader pack
            System.out.println("Chromaticity: Applying shader pack: " + this.selectedShaderPack.getName());

            // Update the applied shader pack
            this.appliedShaderPack = this.selectedShaderPack;

            // Mark that we've applied the current selection
            this.hasAppliedCurrentSelection = true;

            // Keep the GUI open - don't close it
        }
    }

    public void setSelectedShaderPack(ShaderPack shaderPack) {
        this.selectedShaderPack = shaderPack;

        // Reset the applied flag when a new selection is made
        this.hasAppliedCurrentSelection = false;
    }

    public ShaderPack getSelectedShaderPack() {
        return this.selectedShaderPack;
    }

    public ShaderPack getAppliedShaderPack() {
        return this.appliedShaderPack;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    public static class ShaderPack {
        private final String name;
        private final File file;

        public ShaderPack(String name, File file) {
            this.name = name;
            this.file = file;
        }

        public String getName() {
            return this.name;
        }

        public File getFile() {
            return this.file;
        }

        public boolean isNone() {
            return this.file == null;
        }
    }
}