package net.chromaticity.mixin.screen;

import net.chromaticity.service.VulkanModIntegrationService;
import net.vulkanmod.config.gui.VOptionScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that adds a "Shader Packs" button to VulkanMod's options screen.
 * This mixin applies when VulkanMod is available as a dependency.
 */
@Mixin(VOptionScreen.class)
public class VOptionScreenMixin {

    @Inject(method = "init", at = @At("RETURN"))
    private void addShaderPacksButton(CallbackInfo ci) {
        System.out.println("[Chromaticity] VOptionScreenMixin.init() called");
        // Use the service to handle VulkanMod integration
        VulkanModIntegrationService.addShaderPacksButton(this);
    }
}