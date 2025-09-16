package net.chromaticity.service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Service that handles VulkanMod integration dynamically.
 * This allows the mod to work without VulkanMod being present,
 * but enable features when it is available.
 */
public class VulkanModIntegrationService {

    private static boolean vulkanModAvailable = false;
    private static boolean initialized = false;

    /**
     * Initialize the service and check if VulkanMod is available.
     */
    public static void initialize() {
        if (initialized) return;

        try {
            Class.forName("net.vulkanmod.config.gui.VOptionScreen");
            vulkanModAvailable = true;
            System.out.println("[Chromaticity] VulkanMod detected, enabling integration features");
        } catch (ClassNotFoundException e) {
            vulkanModAvailable = false;
            System.out.println("[Chromaticity] VulkanMod not detected, integration features disabled");
        }

        initialized = true;
    }

    /**
     * Check if VulkanMod is available.
     */
    public static boolean isVulkanModAvailable() {
        if (!initialized) {
            initialize();
        }
        return vulkanModAvailable;
    }

    /**
     * Add shader packs button to VulkanMod options screen.
     * This method uses reflection to avoid compile-time dependencies.
     */
    public static void addShaderPacksButton(Object screen) {
        if (!isVulkanModAvailable()) {
            return;
        }

        try {
            System.out.println("[Chromaticity] Starting button addition process...");
            // Access the supportButton field to determine position
            Field supportButtonField = screen.getClass().getDeclaredField("supportButton");
            supportButtonField.setAccessible(true);
            Object supportButton = supportButtonField.get(screen);

            // Get button dimensions and position from support button (search inheritance hierarchy)
            Field xField = null, yField = null, widthField = null, heightField = null;
            Class<?> currentClass = supportButton.getClass();

            // Search up the inheritance hierarchy for the fields
            while (currentClass != null && (xField == null || yField == null || widthField == null || heightField == null)) {
                try {
                    if (xField == null) {
                        xField = currentClass.getDeclaredField("x");
                        xField.setAccessible(true);
                    }
                    if (yField == null) {
                        yField = currentClass.getDeclaredField("y");
                        yField.setAccessible(true);
                    }
                    if (widthField == null) {
                        widthField = currentClass.getDeclaredField("width");
                        widthField.setAccessible(true);
                    }
                    if (heightField == null) {
                        heightField = currentClass.getDeclaredField("height");
                        heightField.setAccessible(true);
                    }
                } catch (NoSuchFieldException e) {
                    // Continue to parent class
                }
                currentClass = currentClass.getSuperclass();
            }

            if (xField == null || yField == null || widthField == null || heightField == null) {
                throw new NoSuchFieldException("Could not find required fields in button class hierarchy");
            }

            int supportX = (Integer) xField.get(supportButton);
            int supportY = (Integer) yField.get(supportButton);
            int supportWidth = (Integer) widthField.get(supportButton);
            int supportHeight = (Integer) heightField.get(supportButton);

            // Get the Component class and create a translatable component
            // Try different class names for Component (obfuscated vs deobfuscated)
            Class<?> componentClass = null;
            String[] componentClassNames = {
                "net.minecraft.network.chat.Component",  // Deobfuscated (dev environment)
                "net.minecraft.class_2561",              // Obfuscated class name
                "net.minecraft.text.Text"                // Alternative mapping name
            };

            for (String className : componentClassNames) {
                try {
                    componentClass = Class.forName(className);
                    break;
                } catch (ClassNotFoundException e) {
                    // Continue trying other names
                }
            }

            if (componentClass == null) {
                throw new ClassNotFoundException("Could not find Component class with any known mapping");
            }

            Method translatableMethod = componentClass.getMethod("translatable", String.class);
            Object component = translatableMethod.invoke(null, "chromaticity.options.shaderpacks.button");

            // Get VButtonWidget class
            Class<?> vButtonWidgetClass = Class.forName("net.vulkanmod.config.gui.widget.VButtonWidget");

            // Create shader packs button to the left of support button
            int shaderPacksButtonX = supportX - supportWidth - 5; // 5 pixel gap

            // Create a lambda for the button action with correct VButtonWidget type
            Object buttonAction = (java.util.function.Consumer<Object>) (widget) -> {
                System.out.println("[Chromaticity] Shader Packs button clicked!");
                // TODO: Open shader packs screen
            };

            // Create VButtonWidget instance
            java.lang.reflect.Constructor<?> buttonConstructor = vButtonWidgetClass.getConstructor(
                int.class, int.class, int.class, int.class, componentClass, java.util.function.Consumer.class
            );

            Object shaderPacksButton = buttonConstructor.newInstance(
                shaderPacksButtonX, supportY, supportWidth, supportHeight, component, buttonAction
            );

            // Add the button to the screen using addWidget method
            // Search for addWidget method with GuiEventListener parameter
            Method addWidgetMethod = null;
            Class<?> screenClass = screen.getClass();
            while (screenClass != null && addWidgetMethod == null) {
                try {
                    // Try different parameter types for addWidget
                    Class<?> guiEventListenerClass = null;
                    String[] listenerClassNames = {
                        "net.minecraft.client.gui.components.events.GuiEventListener",  // Deobfuscated
                        "net.minecraft.class_364",                                      // Obfuscated
                        "net.minecraft.client.gui.Element"                              // Alternative name
                    };

                    // Try to find GuiEventListener class
                    for (String className : listenerClassNames) {
                        try {
                            guiEventListenerClass = Class.forName(className);
                            break;
                        } catch (ClassNotFoundException e) {
                            // Continue trying
                        }
                    }

                    if (guiEventListenerClass != null) {
                        try {
                            addWidgetMethod = screenClass.getMethod("addWidget", guiEventListenerClass);
                        } catch (NoSuchMethodException e) {
                            // Fall back to Object parameter
                            addWidgetMethod = screenClass.getMethod("addWidget", Object.class);
                        }
                    } else {
                        // Try with Object parameter
                        addWidgetMethod = screenClass.getMethod("addWidget", Object.class);
                    }
                } catch (NoSuchMethodException e) {
                    screenClass = screenClass.getSuperclass();
                }
            }

            if (addWidgetMethod == null) {
                throw new NoSuchMethodException("Could not find addWidget method");
            }

            addWidgetMethod.invoke(screen, shaderPacksButton);

            System.out.println("[Chromaticity] Successfully added Shader Packs button to VulkanMod options");

        } catch (Exception e) {
            System.out.println("[Chromaticity] Error adding Shader Packs button: " + e.getMessage());
            e.printStackTrace();
        }
    }
}