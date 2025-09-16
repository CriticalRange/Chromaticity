package net.chromaticity.shader.properties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScreenDefinition {
    private final String name;
    private final List<List<String>> layout; // 2D grid layout of options
    private final Map<String, OptionDefinition> options;
    private final Map<String, ScreenDefinition> childScreens;
    private ScreenDefinition parentScreen;

    public ScreenDefinition(String name) {
        this.name = name;
        this.layout = new ArrayList<>();
        this.options = new HashMap<>();
        this.childScreens = new HashMap<>();
    }

    public void addLayoutRow(List<String> row) {
        this.layout.add(new ArrayList<>(row));
    }

    public void addOption(OptionDefinition option) {
        this.options.put(option.getName(), option);
    }

    public void addChildScreen(ScreenDefinition screen) {
        screen.setParentScreen(this);
        this.childScreens.put(screen.getName(), screen);
    }

    public boolean hasOption(String optionName) {
        return options.containsKey(optionName);
    }

    public boolean hasChildScreen(String screenName) {
        return childScreens.containsKey(screenName);
    }

    public OptionDefinition getOption(String optionName) {
        return options.get(optionName);
    }

    public ScreenDefinition getChildScreen(String screenName) {
        return childScreens.get(screenName);
    }

    // Getters and setters
    public String getName() {
        return name;
    }

    public List<List<String>> getLayout() {
        return layout;
    }

    public Map<String, OptionDefinition> getOptions() {
        return options;
    }

    public Map<String, ScreenDefinition> getChildScreens() {
        return childScreens;
    }

    public ScreenDefinition getParentScreen() {
        return parentScreen;
    }

    public void setParentScreen(ScreenDefinition parentScreen) {
        this.parentScreen = parentScreen;
    }

    public boolean isRootScreen() {
        return parentScreen == null;
    }

    @Override
    public String toString() {
        return "ScreenDefinition{" +
                "name='" + name + '\'' +
                ", optionCount=" + options.size() +
                ", childScreenCount=" + childScreens.size() +
                '}';
    }
}