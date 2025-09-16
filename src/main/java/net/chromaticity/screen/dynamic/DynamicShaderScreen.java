package net.chromaticity.screen.dynamic;

import net.chromaticity.shader.properties.OptionDefinition;
import net.chromaticity.shader.properties.ScreenDefinition;
import net.chromaticity.config.ShaderPackConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import net.vulkanmod.config.gui.widget.RangeOptionWidget;
import net.vulkanmod.config.option.RangeOption;

import java.util.ArrayList;
import java.util.List;

public class DynamicShaderScreen extends Screen {
    private final Screen parent;
    private final ScreenDefinition screenDefinition;
    private final ShaderPackConfig config;
    // UI Constants
    private static final int TITLE_Y = 20;
    private static final int CONTENT_START_Y = 45;

    // Navigation buttons
    private VButtonWidget backButton;
    private VButtonWidget resetButton;

    // Scrollable content
    private ScrollableShaderOptionsList optionsList;

    public DynamicShaderScreen(Screen parent, ScreenDefinition screenDefinition, ShaderPackConfig config) {
        super(Component.literal(formatScreenTitle(screenDefinition.getName())));
        this.parent = parent;
        this.screenDefinition = screenDefinition;
        this.config = config;
    }

    @Override
    protected void init() {
        super.init();

        // Back button
        this.backButton = new VButtonWidget(
            10, 10, 50, 20,
            Component.literal("Back"),
            button -> this.minecraft.setScreen(this.parent)
        );

        // Reset button
        this.resetButton = new VButtonWidget(
            this.width - 60, 10, 50, 20,
            Component.literal("Reset"),
            button -> this.resetToDefaults()
        );

        // Create scrollable options list
        int listHeight = this.height - CONTENT_START_Y - 30; // Leave space for bottom margin
        this.optionsList = new ScrollableShaderOptionsList(
            this.minecraft,
            this.width - 40, // Width with margins
            listHeight,
            CONTENT_START_Y,
            35, // Item height (row height)
            this,
            this.screenDefinition,
            this.config
        );

        this.addRenderableWidget(this.optionsList);
    }



    private void resetToDefaults() {
        // Reset all options in this screen to their default values
        for (OptionDefinition option : screenDefinition.getOptions().values()) {
            if (option.getDefaultValue() != null) {
                config.setValue(option.getName(), option.getDefaultValue());
            }
        }

        // Refresh the screen
        this.init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render background
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // Render scrollable options list (handled by parent)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render navigation buttons on top
        this.backButton.render(mouseX, mouseY);
        this.resetButton.render(mouseX, mouseY);

        // Render title
        int titleX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, titleX, TITLE_Y, 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle navigation button clicks
        if (this.backButton.mouseClicked(mouseX, mouseY, button)) return true;
        if (this.resetButton.mouseClicked(mouseX, mouseY, button)) return true;

        // Delegate to parent for other mouse clicks (scrollable list)
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        // Delegate to parent for mouse dragging (scrollable list)
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    // Utility methods
    private static String formatScreenTitle(String screenName) {
        // Convert SCREEN_NAME to "Screen Name"
        String[] words = screenName.replace("_", " ").toLowerCase().split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (result.length() > 0) result.append(" ");
            if (word.length() > 0) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1));
                }
            }
        }
        return result.toString();
    }

    private static boolean parseBoolean(String value) {
        if (value == null) return false;
        return value.equalsIgnoreCase("true") || value.equals("1");
    }

    private static float parseFloat(String value) {
        if (value == null) return 0.0f;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }
}