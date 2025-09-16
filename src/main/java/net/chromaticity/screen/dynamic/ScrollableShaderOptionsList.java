package net.chromaticity.screen.dynamic;

import net.chromaticity.config.ShaderPackConfig;
import net.chromaticity.shader.properties.OptionDefinition;
import net.chromaticity.shader.properties.ScreenDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.RangeOptionWidget;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import net.vulkanmod.config.option.RangeOption;

import java.util.ArrayList;
import java.util.List;

public class ScrollableShaderOptionsList extends ObjectSelectionList<ScrollableShaderOptionsList.OptionEntry> {
    private final DynamicShaderScreen parentScreen;
    private final ScreenDefinition screenDefinition;
    private final ShaderPackConfig config;

    // UI Constants
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SLIDER_WIDTH = 150;
    private static final int SPACING_X = 15;

    public ScrollableShaderOptionsList(Minecraft minecraft, int width, int height, int y, int itemHeight,
                                     DynamicShaderScreen parentScreen, ScreenDefinition screenDefinition,
                                     ShaderPackConfig config) {
        super(minecraft, width, height, y, itemHeight);
        this.parentScreen = parentScreen;
        this.screenDefinition = screenDefinition;
        this.config = config;

        generateEntries();
    }

    private void generateEntries() {
        List<List<String>> layout = screenDefinition.getLayout();

        for (List<String> row : layout) {
            // Create a row entry
            RowEntry rowEntry = new RowEntry(row);
            this.addEntry(rowEntry);
        }
    }

    @Override
    protected int getScrollbarPosition() {
        return this.getRight() - 6;
    }

    @Override
    public int getRowWidth() {
        return this.width - 20;
    }

    public abstract static class OptionEntry extends Entry<OptionEntry> {
    }

    public class RowEntry extends OptionEntry {
        private final List<String> rowItems;
        private final List<VButtonWidget> buttons = new ArrayList<>();
        private final List<RangeOptionWidget> sliders = new ArrayList<>();

        public RowEntry(List<String> rowItems) {
            this.rowItems = rowItems;
            createWidgets();
        }

        private void createWidgets() {
            int startX = calculateStartX(rowItems.size());
            int currentX = startX;

            for (String itemName : rowItems) {
                if (itemName == null || itemName.isEmpty()) {
                    // Empty slot - just advance X position
                    currentX += BUTTON_WIDTH + SPACING_X;
                    continue;
                }

                // Handle button navigation (child screens)
                if (itemName.startsWith("[") && itemName.endsWith("]")) {
                    String childScreenName = itemName.substring(1, itemName.length() - 1);
                    createNavigationButton(childScreenName, currentX, 0);
                } else if (screenDefinition.hasOption(itemName)) {
                    // Handle regular options
                    OptionDefinition option = screenDefinition.getOption(itemName);
                    createOptionWidget(option, currentX, 0);
                }

                currentX += BUTTON_WIDTH + SPACING_X;
            }
        }

        private void createNavigationButton(String childScreenName, int x, int y) {
            if (!screenDefinition.hasChildScreen(childScreenName)) {
                return; // Skip if child screen doesn't exist
            }

            VButtonWidget navButton = new VButtonWidget(
                x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal(formatScreenTitle(childScreenName)),
                button -> {
                    ScreenDefinition childScreen = screenDefinition.getChildScreen(childScreenName);
                    DynamicShaderScreen childScreenUI = new DynamicShaderScreen(parentScreen, childScreen, config);
                    Minecraft.getInstance().setScreen(childScreenUI);
                }
            );

            buttons.add(navButton);
        }

        private void createOptionWidget(OptionDefinition option, int x, int y) {
            switch (option.getType()) {
                case TOGGLE:
                    createToggleButton(option, x, y);
                    break;
                case SLIDER:
                    createSliderWidget(option, x, y);
                    break;
                case DROPDOWN:
                    createDropdownButton(option, x, y);
                    break;
                case PROFILE:
                    createProfileButton(option, x, y);
                    break;
                default:
                    createGenericButton(option, x, y);
                    break;
            }
        }

        private void createToggleButton(OptionDefinition option, int x, int y) {
            boolean currentValue = config.getBooleanValue(option.getName(), parseBoolean(option.getDefaultValue()));

            VButtonWidget toggleButton = new VButtonWidget(
                x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal(option.getName() + ": " + (currentValue ? "ON" : "OFF")),
                button -> {
                    boolean newValue = !config.getBooleanValue(option.getName(), parseBoolean(option.getDefaultValue()));
                    config.setValue(option.getName(), String.valueOf(newValue));
                }
            );

            buttons.add(toggleButton);
        }

        private void createSliderWidget(OptionDefinition option, int x, int y) {
            int currentValue = (int) config.getFloatValue(option.getName(), parseFloat(option.getDefaultValue()));

            RangeOption rangeOption = new RangeOption(
                Component.literal(option.getName()),
                (int) option.getMin(),
                (int) option.getMax(),
                (int) option.getStep(),
                (value) -> config.setValue(option.getName(), String.valueOf(value)),
                () -> (int) config.getFloatValue(option.getName(), parseFloat(option.getDefaultValue()))
            );

            RangeOptionWidget slider = new RangeOptionWidget(
                rangeOption,
                x, y, SLIDER_WIDTH, BUTTON_HEIGHT,
                Component.literal(option.getName())
            );

            sliders.add(slider);
        }

        private void createDropdownButton(OptionDefinition option, int x, int y) {
            String currentValue = config.getStringValue(option.getName(), option.getDefaultValue());

            VButtonWidget dropdownButton = new VButtonWidget(
                x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal(option.getName() + ": " + currentValue),
                button -> {
                    if (option.getValues() != null && !option.getValues().isEmpty()) {
                        String current = config.getStringValue(option.getName(), option.getDefaultValue());
                        int currentIndex = option.getValues().indexOf(current);
                        int nextIndex = (currentIndex + 1) % option.getValues().size();
                        String nextValue = option.getValues().get(nextIndex);
                        config.setValue(option.getName(), nextValue);
                    }
                }
            );

            buttons.add(dropdownButton);
        }

        private void createProfileButton(OptionDefinition option, int x, int y) {
            String currentValue = config.getStringValue(option.getName(), option.getDefaultValue());

            VButtonWidget profileButton = new VButtonWidget(
                x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal("Profile: " + currentValue),
                button -> {
                    // TODO: Implement profile cycling
                }
            );

            buttons.add(profileButton);
        }

        private void createGenericButton(OptionDefinition option, int x, int y) {
            String currentValue = config.getStringValue(option.getName(), option.getDefaultValue());

            VButtonWidget genericButton = new VButtonWidget(
                x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal(option.getName() + ": " + currentValue),
                button -> {
                    boolean boolValue = parseBoolean(currentValue);
                    config.setValue(option.getName(), String.valueOf(!boolValue));
                }
            );

            buttons.add(genericButton);
        }

        private int calculateStartX(int itemsInRow) {
            int totalWidth = (itemsInRow * BUTTON_WIDTH) + ((itemsInRow - 1) * SPACING_X);
            return Math.max(50, (ScrollableShaderOptionsList.this.getRowWidth() - totalWidth) / 2);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight,
                          int mouseX, int mouseY, boolean hovered, float tickDelta) {
            // Update button positions to match current Y
            updateButtonPositions(y);

            // Render buttons
            for (VButtonWidget button : buttons) {
                button.render(mouseX, mouseY);
            }

            // Render sliders
            for (RangeOptionWidget slider : sliders) {
                slider.render(mouseX, mouseY);
            }
        }

        private void updateButtonPositions(int y) {
            // Update Y position for all widgets
            for (VButtonWidget button : buttons) {
                updateWidgetPosition(button, y);
            }
            for (RangeOptionWidget slider : sliders) {
                updateWidgetPosition(slider, y);
            }
        }

        private void updateWidgetPosition(Object widget, int y) {
            try {
                // Use reflection to update Y position
                // Try to find the 'y' field in this class or parent classes
                Class<?> clazz = widget.getClass();
                java.lang.reflect.Field yField = null;

                while (clazz != null && yField == null) {
                    try {
                        yField = clazz.getDeclaredField("y");
                    } catch (NoSuchFieldException e) {
                        clazz = clazz.getSuperclass();
                    }
                }

                if (yField != null) {
                    yField.setAccessible(true);
                    yField.setInt(widget, y + 2); // Small offset from row top
                }
            } catch (Exception e) {
                // Ignore reflection errors silently
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            // Handle button clicks
            for (VButtonWidget optionButton : buttons) {
                if (optionButton.mouseClicked(mouseX, mouseY, button)) return true;
            }

            // Handle slider clicks
            for (RangeOptionWidget slider : sliders) {
                if (slider.mouseClicked(mouseX, mouseY, button)) return true;
            }

            return false;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            // Handle slider dragging
            for (RangeOptionWidget slider : sliders) {
                if (slider.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
            }

            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal("Shader options row with " + rowItems.size() + " items");
        }
    }

    // Utility methods
    private static String formatScreenTitle(String screenName) {
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