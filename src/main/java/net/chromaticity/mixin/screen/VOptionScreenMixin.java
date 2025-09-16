package net.chromaticity.mixin.screen;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Mixin that adds a "Shader Packs" button to VulkanMod's options screen.
 * This mixin applies when VulkanMod is available as a dependency.
 */
@Mixin(VOptionScreen.class)
public class VOptionScreenMixin {

    @Inject(method = "init", at = @At("RETURN"))
    private void addShaderPacksButton(CallbackInfo ci) {
        try {
            VOptionScreen screen = (VOptionScreen) (Object) this;

            // Get the Support Me button position using reflection
            Field supportButtonField = VOptionScreen.class.getDeclaredField("supportButton");
            supportButtonField.setAccessible(true);
            VButtonWidget supportButton = (VButtonWidget) supportButtonField.get(screen);

            // Calculate position to the left of Support Me button
            int buttonWidth = 100;
            int buttonHeight = 20;
            int spacing = 5; // Space between buttons
            int shaderPacksX = supportButton.getX() - buttonWidth - spacing;
            int shaderPacksY = supportButton.getY();

            // Create the shader packs button
            VButtonWidget shaderPacksButton = new VButtonWidget(
                shaderPacksX, shaderPacksY, buttonWidth, buttonHeight,
                Component.literal("Shader Packs"),
                button -> {
                    // TODO: Open shader packs screen
                }
            );

            // Use reflection to access the private buttons field
            Field buttonsField = VOptionScreen.class.getDeclaredField("buttons");
            buttonsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<VButtonWidget> buttons = (List<VButtonWidget>) buttonsField.get(screen);
            buttons.add(shaderPacksButton);

        } catch (Exception e) {
            // Silently fail if VulkanMod integration is not available
        }
    }
}