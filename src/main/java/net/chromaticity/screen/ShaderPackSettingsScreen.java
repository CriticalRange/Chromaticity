package net.chromaticity.screen;

import net.chromaticity.shader.ShaderPackManager;
import net.chromaticity.shader.option.BooleanShaderOption;
import net.chromaticity.shader.option.ShaderOptionValues;
import net.chromaticity.shader.option.StringShaderOption;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings screen for shader packs using the new option system.
 * Dynamically generates UI controls based on discovered shader options.
 */
public class ShaderPackSettingsScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderPackSettingsScreen.class);

    private final Screen parent;
    private final ShaderPackManager.ShaderPackInfo packInfo;
    private final ShaderPackManager shaderPackManager;
    private final Map<String, Object> currentValues = new HashMap<>();
    private final List<AbstractWidget> optionWidgets = new ArrayList<>();

    // VulkanMod style buttons
    private VButtonWidget backButton;
    private VButtonWidget resetButton;
    private VButtonWidget applyButton;

    public ShaderPackSettingsScreen(Screen parent, ShaderPackManager.ShaderPackInfo packInfo, ShaderPackManager shaderPackManager) {
        super(Component.literal(packInfo.getName() + " Settings"));
        this.parent = parent;
        this.packInfo = packInfo;
        this.shaderPackManager = shaderPackManager;

        // Initialize current values from pack settings
        ShaderOptionValues settings = packInfo.getSettings();
        packInfo.getOptionSet().getBooleanOptions().forEach((name, option) ->
            currentValues.put(name, settings.getBooleanValue(name)));
        packInfo.getOptionSet().getStringOptions().forEach((name, option) ->
            currentValues.put(name, settings.getStringValue(name)));
    }

    @Override
    protected void init() {
        super.init();

        // Clear previous widgets
        optionWidgets.clear();

        // Header area
        int headerHeight = 50;
        int footerHeight = 40;
        int contentHeight = this.height - headerHeight - footerHeight;

        // Back button
        this.backButton = new VButtonWidget(
            10, 10, 50, 20,
            Component.literal("Back"),
            button -> this.onClose()
        );

        // Reset button
        this.resetButton = new VButtonWidget(
            this.width - 140, 10, 60, 20,
            Component.literal("Reset"),
            button -> this.resetToDefaults()
        );

        // Apply button
        this.applyButton = new VButtonWidget(
            this.width - 70, 10, 60, 20,
            Component.literal("Apply"),
            button -> this.applySettings()
        );

        // Generate option widgets
        generateOptionWidgets(headerHeight, contentHeight);

        LOGGER.info("Initialized settings screen for pack: {} with {} options",
                   packInfo.getName(), packInfo.getOptionSet().getTotalOptionCount());
    }

    private void generateOptionWidgets(int startY, int availableHeight) {
        int currentY = startY + 10;
        int leftColumnX = 20;
        int rightColumnX = this.width / 2 + 10;
        int widgetWidth = (this.width / 2) - 30;
        int widgetHeight = 20;
        int verticalSpacing = 25;

        boolean useLeftColumn = true;

        // Add boolean options
        for (BooleanShaderOption option : packInfo.getOptionSet().getBooleanOptions().values()) {
            if (currentY > startY + availableHeight - widgetHeight) {
                break; // Out of space
            }

            int x = useLeftColumn ? leftColumnX : rightColumnX;
            boolean currentValue = (Boolean) currentValues.get(option.getName());

            CycleButton<Boolean> toggleButton = CycleButton.onOffBuilder(currentValue)
                .withTooltip(value -> Tooltip.create(Component.literal(option.getComment())))
                .create(x, currentY, widgetWidth, widgetHeight, Component.literal(option.getName()),
                       (button, value) -> currentValues.put(option.getName(), value));

            this.addRenderableWidget(toggleButton);
            optionWidgets.add(toggleButton);

            if (!useLeftColumn) {
                currentY += verticalSpacing;
            }
            useLeftColumn = !useLeftColumn;
        }

        // Add string options
        for (StringShaderOption option : packInfo.getOptionSet().getStringOptions().values()) {
            if (currentY > startY + availableHeight - widgetHeight) {
                break; // Out of space
            }

            int x = useLeftColumn ? leftColumnX : rightColumnX;
            String currentValue = (String) currentValues.get(option.getName());
            List<String> allowedValues = option.getAllowedValues();

            if (!allowedValues.isEmpty()) {
                // Create cycle button for predefined values
                CycleButton<String> cycleButton = CycleButton.<String>builder(value -> {
                    String label = option.getLabelForValue(value);
                    return Component.literal(option.getName() + ": " + label);
                })
                .withValues(allowedValues)
                .withInitialValue(currentValue)
                .withTooltip(value -> Tooltip.create(Component.literal(option.getComment())))
                .create(x, currentY, widgetWidth, widgetHeight, Component.literal(option.getName()),
                       (button, value) -> currentValues.put(option.getName(), value));

                this.addRenderableWidget(cycleButton);
                optionWidgets.add(cycleButton);
            } else {
                // For options without predefined values, show as text (read-only for now)
                Button infoButton = Button.builder(
                    Component.literal(option.getName() + ": " + currentValue),
                    button -> { /* Read-only */ })
                    .bounds(x, currentY, widgetWidth, widgetHeight)
                    .tooltip(Tooltip.create(Component.literal(option.getComment())))
                    .build();

                infoButton.active = false;
                this.addRenderableWidget(infoButton);
                optionWidgets.add(infoButton);
            }

            if (!useLeftColumn) {
                currentY += verticalSpacing;
            }
            useLeftColumn = !useLeftColumn;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render background
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // Render VulkanMod buttons
        this.backButton.render(mouseX, mouseY);
        this.resetButton.render(mouseX, mouseY);
        this.applyButton.render(mouseX, mouseY);

        // Render option widgets (handled by parent)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        // Render option count info
        String optionInfo = String.format("Options: %d", packInfo.getOptionSet().getTotalOptionCount());
        graphics.drawString(this.font, optionInfo, 20, this.height - 30, 0xAAAAAA);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle VulkanMod button clicks
        if (this.backButton.mouseClicked(mouseX, mouseY, button)) return true;
        if (this.resetButton.mouseClicked(mouseX, mouseY, button)) return true;
        if (this.applyButton.mouseClicked(mouseX, mouseY, button)) return true;

        // Delegate to parent for other widgets
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void resetToDefaults() {
        // Reset all values to defaults
        packInfo.getOptionSet().getBooleanOptions().forEach((name, option) ->
            currentValues.put(name, option.getDefaultValue()));
        packInfo.getOptionSet().getStringOptions().forEach((name, option) ->
            currentValues.put(name, option.getDefaultValue()));

        // Reinitialize the screen to update widgets
        this.init();

        LOGGER.info("Reset all options to defaults for pack: {}", packInfo.getName());
    }

    private void applySettings() {
        try {
            // Convert current values to string map for the manager
            Map<String, String> settingsMap = new HashMap<>();
            currentValues.forEach((key, value) -> settingsMap.put(key, value.toString()));

            // Apply settings through the manager
            shaderPackManager.applySettings(packInfo.getName(), settingsMap);

            LOGGER.info("Applied settings for pack: {} ({} changed options)",
                       packInfo.getName(), packInfo.getSettings().getChangedOptionCount());

            // Trigger shader pack processing (compilation)
            shaderPackManager.processShaderPack(packInfo.getName());
            System.out.println("Settings applied and processing started for: " + packInfo.getName());

        } catch (Exception e) {
            LOGGER.error("Failed to apply settings for pack: {}", packInfo.getName(), e);
            System.err.println("Failed to apply settings: " + e.getMessage());
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}