package net.chromaticity.handler;

import net.chromaticity.service.VulkanModIntegrationService;

/**
 * Simple handler that initializes the VulkanMod integration service.
 * The actual button injection will be handled by the service using reflection.
 */
public class ScreenHandler {

    /**
     * Initializes the screen handler system.
     * For now, this just ensures the service is ready for button injection.
     */
    public static void register() {
        // The VulkanModIntegrationService already handles the button injection
        // This handler is here for future expansion when we need more complex
        // screen interaction logic
        System.out.println("[Chromaticity] Screen handler registered");
    }
}