package net.chromaticity.shader.properties;

import net.chromaticity.shader.properties.OptionDefinition.OptionType;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PropertiesParser {

    private static final Pattern SCREEN_PATTERN = Pattern.compile("screen(?:\\.([A-Z_]+))\\s*=\\s*(.+)");
    private static final Pattern PROFILE_PATTERN = Pattern.compile("profile\\.([A-Z_]+)\\s*=\\s*(.+)");
    private static final Pattern SLIDERS_PATTERN = Pattern.compile("sliders\\s*=\\s*(.+)");
    private static final Pattern BUTTON_PATTERN = Pattern.compile("\\[([A-Z_]+)\\]");

    private final Map<String, String> profiles = new HashMap<>();
    private final Set<String> sliderOptions = new HashSet<>();
    private final Map<String, OptionDefinition> globalOptions = new HashMap<>();

    public ShaderPackDefinition parseProperties(Path propertiesFile) throws IOException {
        if (!Files.exists(propertiesFile)) {
            throw new FileNotFoundException("Properties file not found: " + propertiesFile);
        }

        // Clear previous state
        profiles.clear();
        sliderOptions.clear();
        globalOptions.clear();

        Map<String, String> rawScreenData = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(propertiesFile)) {
            String line;
            StringBuilder currentValue = new StringBuilder();
            String currentKey = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("##")) {
                }

                // Handle line continuations
                if (line.endsWith("\\")) {
                    if (currentKey == null) {
                        // Start of a multi-line value
                        int equalsIndex = line.indexOf('=');
                        if (equalsIndex > 0) {
                            currentKey = line.substring(0, equalsIndex).trim();
                            currentValue.append(line.substring(equalsIndex + 1).trim().replace("\\", ""));
                        }
                    } else {
                        // Continuation of multi-line value
                        currentValue.append(" ").append(line.replace("\\", "").trim());
                    }
                }

                // End of multi-line value or single line
                if (currentKey != null) {
                    currentValue.append(" ").append(line.trim());
                    processLine(currentKey + " = " + currentValue.toString().trim(), rawScreenData);
                    currentKey = null;
                    currentValue.setLength(0);
                } else {
                    processLine(line, rawScreenData);
                }
            }
        }

        // Build screen hierarchy
        ScreenDefinition rootScreen = buildScreenHierarchy(rawScreenData);

        return new ShaderPackDefinition(rootScreen, profiles, globalOptions);
    }

    private void processLine(String line, Map<String, String> rawScreenData) {
        // Parse profiles
        Matcher profileMatcher = PROFILE_PATTERN.matcher(line);
        if (profileMatcher.matches()) {
            String profileName = profileMatcher.group(1);
            String profileValue = profileMatcher.group(2);
            profiles.put(profileName, profileValue);
        }

        // Parse sliders list
        Matcher slidersMatcher = SLIDERS_PATTERN.matcher(line);
        if (slidersMatcher.matches()) {
            String slidersValue = slidersMatcher.group(1);
            String[] sliders = slidersValue.split("\\s+");
            Collections.addAll(sliderOptions, sliders);
        }

        // Parse screen definitions
        Matcher screenMatcher = SCREEN_PATTERN.matcher(line);
        if (screenMatcher.matches()) {
            String screenName = screenMatcher.group(1);
            if (screenName == null) {
                screenName = "MAIN"; // Root screen
            }
            String screenLayout = screenMatcher.group(2);
            rawScreenData.put(screenName, screenLayout);
        }

        // Parse other settings (vignette, separateAo, etc.)
        if (line.contains("=")) {
            int equalsIndex = line.indexOf('=');
            String key = line.substring(0, equalsIndex).trim();
            String value = line.substring(equalsIndex + 1).trim();

            // Create global option definitions for simple settings
            OptionType type = determineOptionType(key, value);
            OptionDefinition option = new OptionDefinition(key, type);
            option.setDefaultValue(value);
            globalOptions.put(key, option);
        }
    }

    private ScreenDefinition buildScreenHierarchy(Map<String, String> rawScreenData) {
        Map<String, ScreenDefinition> screens = new HashMap<>();

        // Create all screen definitions first
        for (Map.Entry<String, String> entry : rawScreenData.entrySet()) {
            String screenName = entry.getKey();
            screens.put(screenName, new ScreenDefinition(screenName));
        }

        // Parse layouts and build relationships
        for (Map.Entry<String, String> entry : rawScreenData.entrySet()) {
            String screenName = entry.getKey();
            String layout = entry.getValue();
            ScreenDefinition screen = screens.get(screenName);

            parseScreenLayout(screen, layout, screens);
        }

        // Return root screen (MAIN) or create default if not found
        ScreenDefinition rootScreen = screens.get("MAIN");
        if (rootScreen == null) {
            // Try to find a screen that looks like the main screen (has the most navigation buttons)
            ScreenDefinition bestCandidate = null;
            int maxNavButtons = 0;

            for (ScreenDefinition screen : screens.values()) {
                int navButtonCount = 0;
                for (OptionDefinition option : screen.getOptions().values()) {
                    if (option.getType() == OptionDefinition.OptionType.BUTTON) {
                        navButtonCount++;
                    }
                }
                if (navButtonCount > maxNavButtons) {
                    maxNavButtons = navButtonCount;
                    bestCandidate = screen;
                }
            }

            if (bestCandidate != null) {
                rootScreen = bestCandidate;
            } else {
                rootScreen = screens.values().iterator().next(); // Final fallback
            }
        } else {
        }

        return rootScreen;
    }

    private void parseScreenLayout(ScreenDefinition screen, String layout, Map<String, ScreenDefinition> allScreens) {
        // Split layout into individual items, handling spaces
        String[] items = layout.split("\\s+");

        List<String> currentRow = new ArrayList<>();
        final int COLUMNS_PER_ROW = 2; // Most shader pack screens use 2 columns

        for (String item : items) {
            item = item.trim();
            if (item.isEmpty() || item.equals("\\")) continue; // Skip empty items and line continuation backslashes

            // Add item to current row
            if (item.equals("<empty>")) {
                currentRow.add(""); // Empty slot
            } else if (item.startsWith("[") && item.endsWith("]")) {
                // Button to child screen
                String childScreenName = item.substring(1, item.length() - 1);
                currentRow.add(item);

                // Create navigation option
                OptionDefinition navOption = new OptionDefinition(childScreenName, OptionType.BUTTON);
                navOption.setDescription("Navigate to " + childScreenName + " settings");
                screen.addOption(navOption);

                // Link child screen if it exists
                ScreenDefinition childScreen = allScreens.get(childScreenName);
                if (childScreen != null) {
                    screen.addChildScreen(childScreen);
                }
            } else if (item.startsWith("<") && item.endsWith(">")) {
                // Profile or special item
                currentRow.add(item);

                // Create special option
                OptionDefinition option = new OptionDefinition(item, OptionType.PROFILE);
                screen.addOption(option);
            } else {
                // Regular option
                currentRow.add(item);

                // Create option definition
                OptionType type = sliderOptions.contains(item) ? OptionType.SLIDER : OptionType.TOGGLE;
                OptionDefinition option = new OptionDefinition(item, type);

                // Set up slider properties if applicable
                if (type == OptionType.SLIDER) {
                    setupSliderOption(option, item);
                }

                screen.addOption(option);
            }

            // Check if we should start a new row
            if (currentRow.size() >= COLUMNS_PER_ROW) {
                screen.addLayoutRow(currentRow);
                currentRow = new ArrayList<>();
            }
        }

        // Add final row if not empty
        if (!currentRow.isEmpty()) {
            screen.addLayoutRow(currentRow);
        }
    }

    private void setupSliderOption(OptionDefinition option, String optionName) {
        // Set default ranges - these could be customized based on option name patterns
        option.setMin(0.0f);
        option.setMax(1.0f);
        option.setStep(0.01f);

        // Customize ranges based on common option patterns
        if (optionName.contains("RESOLUTION")) {
            option.setMin(256);
            option.setMax(4096);
            option.setStep(256);
        } else if (optionName.contains("DISTANCE")) {
            option.setMin(16);
            option.setMax(512);
            option.setStep(16);
        } else if (optionName.contains("STEPS")) {
            option.setMin(1);
            option.setMax(64);
            option.setStep(1);
        } else if (optionName.contains("STRENGTH") || optionName.contains("INTENSITY")) {
            option.setMin(0.0f);
            option.setMax(2.0f);
            option.setStep(0.1f);
        } else if (optionName.contains("SPEED")) {
            option.setMin(0.0f);
            option.setMax(10.0f);
            option.setStep(0.1f);
        }
    }

    private OptionType determineOptionType(String key, String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return OptionType.TOGGLE;
        } else if (sliderOptions.contains(key)) {
            return OptionType.SLIDER;
        } else {
            return OptionType.DROPDOWN; // Default for unknown types
        }
    }

    public static class ShaderPackDefinition {
        private final ScreenDefinition rootScreen;
        private final Map<String, String> profiles;
        private final Map<String, OptionDefinition> globalOptions;

        public ShaderPackDefinition(ScreenDefinition rootScreen, Map<String, String> profiles, Map<String, OptionDefinition> globalOptions) {
            this.rootScreen = rootScreen;
            this.profiles = new HashMap<>(profiles);
            this.globalOptions = new HashMap<>(globalOptions);
        }

        public ScreenDefinition getRootScreen() {
            return rootScreen;
        }

        public Map<String, String> getProfiles() {
            return profiles;
        }

        public Map<String, OptionDefinition> getGlobalOptions() {
            return globalOptions;
        }
    }
}