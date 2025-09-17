package net.chromaticity.mixin.screen;

import net.chromaticity.screen.ShaderPackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Mixin that adds a "Shader Packs" button to VulkanMod's options screen.
 * This mixin applies when VulkanMod is available as a dependency.
 */
@Mixin(VOptionScreen.class)
public class VOptionScreenMixin {

    private VButtonWidget chromaticityShaderPacksButton;

    @Inject(method = "init", at = @At("RETURN"))
    private void addShaderPacksButton(CallbackInfo ci) {
        this.addShaderPacksButtonToScreen();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void ensureShaderPacksButtonExists(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // Check if our button is missing from the buttons list and re-add if needed
        try {
            VOptionScreen screen = (VOptionScreen) (Object) this;
            Field buttonsField = VOptionScreen.class.getDeclaredField("buttons");
            buttonsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<VButtonWidget> buttons = (List<VButtonWidget>) buttonsField.get(screen);

            // Check if our button is missing
            boolean buttonExists = false;
            for (VButtonWidget button : buttons) {
                if (button == this.chromaticityShaderPacksButton) {
                    buttonExists = true;
                }
            }

            // Re-add button if it's missing
            if (!buttonExists && this.chromaticityShaderPacksButton != null) {
                this.addShaderPacksButtonToScreen();
            }
        } catch (Exception e) {
            // Silent failure
        }
    }

    private void addShaderPacksButtonToScreen() {
        try {
            VOptionScreen screen = (VOptionScreen) (Object) this;

            // Get support button using reflection
            Field supportButtonField = VOptionScreen.class.getDeclaredField("supportButton");
            supportButtonField.setAccessible(true);
            VButtonWidget supportButton = (VButtonWidget) supportButtonField.get(screen);

            if (supportButton == null) {
                // Use fallback position if support button isn't found
                createAndAddButton(screen, 200, 200);
            }

            // Calculate position to the left of Support Me button
            int buttonWidth = 100;
            int buttonHeight = 20;
            int spacing = 5;
            int shaderPacksX = supportButton.getX() - buttonWidth - spacing;
            int shaderPacksY = supportButton.getY();

            createAndAddButton(screen, shaderPacksX, shaderPacksY);

        } catch (Exception e) {
            // Silent failure - no console spam
        }
    }

    private void createAndAddButton(VOptionScreen screen, int x, int y) {
        try {
            // Create the shader packs button
            this.chromaticityShaderPacksButton = new VButtonWidget(
                x, y, 100, 20,
                Component.literal("Shader Packs"),
                button -> Minecraft.getInstance().setScreen(new ShaderPackScreen(screen))
            );

            // Add to VulkanMod's button list using reflection
            Field buttonsField = VOptionScreen.class.getDeclaredField("buttons");
            buttonsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<VButtonWidget> buttons = (List<VButtonWidget>) buttonsField.get(screen);
            buttons.add(this.chromaticityShaderPacksButton);

        } catch (Exception e) {
            // Silent failure - no console spam
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (this.chromaticityShaderPacksButton != null && button == 0) {
            // Manual click detection since clicked() is protected
            boolean isClicked = this.chromaticityShaderPacksButton.active
                && this.chromaticityShaderPacksButton.visible
                && mouseX >= this.chromaticityShaderPacksButton.getX()
                && mouseY >= this.chromaticityShaderPacksButton.getY()
                && mouseX < this.chromaticityShaderPacksButton.getX() + this.chromaticityShaderPacksButton.getWidth()
                && mouseY < this.chromaticityShaderPacksButton.getY() + this.chromaticityShaderPacksButton.getHeight();

            if (isClicked) {
                this.chromaticityShaderPacksButton.onClick(mouseX, mouseY);
                cir.setReturnValue(true);
            }
        }
    }
}