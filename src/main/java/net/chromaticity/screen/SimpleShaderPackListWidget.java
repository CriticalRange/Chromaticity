package net.chromaticity.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import net.vulkanmod.vulkan.util.ColorUtil;

import java.util.ArrayList;
import java.util.List;

public class SimpleShaderPackListWidget extends AbstractWidget {
    private final List<ShaderPackScreen.ShaderPack> shaderPacks = new ArrayList<>();
    private final ShaderPackScreen parent;
    private ShaderPackScreen.ShaderPack selectedPack;
    private int scrollOffset = 0;
    private final int itemHeight = 22; // Slightly taller for better readability
    private final int padding = 2;

    // VulkanMod-style hover animation state
    private int hoveredItemIndex = -1;
    private long hoverStartTime;
    private int hoverTime;
    private long hoverStopTime;

    public SimpleShaderPackListWidget(int x, int y, int width, int height, ShaderPackScreen parent) {
        super(x, y, width, height, Component.empty());
        this.parent = parent;
    }

    public void updateShaderPacks(List<ShaderPackScreen.ShaderPack> packs) {
        this.shaderPacks.clear();
        this.shaderPacks.addAll(packs);
        this.selectedPack = null;
        this.scrollOffset = 0;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw background
        graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, 0x66000000);

        // Calculate visible items
        int visibleItems = this.height / this.itemHeight;
        int maxScroll = Math.max(0, this.shaderPacks.size() - visibleItems);
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, maxScroll));

        // Update hover state
        this.updateHoverState(mouseX, mouseY, visibleItems);

        // Render items
        for (int i = 0; i < visibleItems && i + this.scrollOffset < this.shaderPacks.size(); i++) {
            int index = i + this.scrollOffset;
            ShaderPackScreen.ShaderPack pack = this.shaderPacks.get(index);

            int itemY = this.getY() + i * this.itemHeight;
            boolean isHovered = mouseX >= this.getX() && mouseX < this.getX() + this.width &&
                               mouseY >= itemY && mouseY < itemY + this.itemHeight;
            boolean isSelected = pack == this.selectedPack;
            boolean isApplied = pack == this.parent.getAppliedShaderPack();

            // Background with VulkanMod page selection style
            if (isSelected) {
                // VulkanMod selected style: red left border + red overlay
                int redColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 0.2f);
                graphics.fill(this.getX(), itemY, this.getX() + this.width, itemY + this.itemHeight, redColor);

                // Red left border (1.5px wide like VulkanMod)
                int borderColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 1.0f);
                graphics.fill(this.getX(), itemY, this.getX() + 2, itemY + this.itemHeight, borderColor);
            } else if (isApplied) {
                // Applied but not selected: more opaque version of VulkanMod style
                int lightRedColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 0.1f);
                graphics.fill(this.getX(), itemY, this.getX() + this.width, itemY + this.itemHeight, lightRedColor);

                // More visible red left border for applied state
                int lightBorderColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 0.7f);
                graphics.fill(this.getX(), itemY, this.getX() + 1, itemY + this.itemHeight, lightBorderColor);
            }

            // VulkanMod-style hover animation (render on top of other states)
            if (isHovered || (index == this.hoveredItemIndex && this.getHoverMultiplier(200) > 0.0f)) {
                this.renderItemHover(graphics, itemY);
            }

            // Text
            String name = pack.getName();
            int textY = itemY + (this.itemHeight - Minecraft.getInstance().font.lineHeight) / 2;
            graphics.drawString(Minecraft.getInstance().font, name, this.getX() + 5, textY, 0xFFFFFF);

            // Type info
            if (!pack.isNone()) {
                String info = pack.getFile().isDirectory() ? "Folder" : "Archive";
                int infoWidth = Minecraft.getInstance().font.width(info);
                graphics.drawString(Minecraft.getInstance().font, info,
                    this.getX() + this.width - infoWidth - 5, textY, 0xCCCCCC);
            }
        }

        // Scrollbar if needed
        if (this.shaderPacks.size() > visibleItems) {
            int scrollbarHeight = Math.max(10, (visibleItems * this.height) / this.shaderPacks.size());
            int scrollbarY = this.getY() + (this.scrollOffset * (this.height - scrollbarHeight)) / maxScroll;
            graphics.fill(this.getX() + this.width - 6, scrollbarY, this.getX() + this.width - 2,
                scrollbarY + scrollbarHeight, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.isMouseOver(mouseX, mouseY)) {
            int relativeY = (int) (mouseY - this.getY());
            int clickedIndex = relativeY / this.itemHeight + this.scrollOffset;

            if (clickedIndex >= 0 && clickedIndex < this.shaderPacks.size()) {
                this.selectedPack = this.shaderPacks.get(clickedIndex);
                this.parent.setSelectedShaderPack(this.selectedPack);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.isMouseOver(mouseX, mouseY)) {
            this.scrollOffset -= (int) verticalAmount * 3;
            int visibleItems = this.height / this.itemHeight;
            int maxScroll = Math.max(0, this.shaderPacks.size() - visibleItems);
            this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, maxScroll));
            return true;
        }
        return false;
    }

    private void updateHoverState(int mouseX, int mouseY, int visibleItems) {
        int newHoveredIndex = -1;

        // Check which item is being hovered
        for (int i = 0; i < visibleItems && i + this.scrollOffset < this.shaderPacks.size(); i++) {
            int itemY = this.getY() + i * this.itemHeight;
            boolean isHovered = mouseX >= this.getX() && mouseX < this.getX() + this.width &&
                               mouseY >= itemY && mouseY < itemY + this.itemHeight;

            if (isHovered) {
                newHoveredIndex = i + this.scrollOffset;
                break;
            }
        }

        // Update hover animation state (VulkanMod style)
        if (newHoveredIndex != -1) {
            if (this.hoveredItemIndex != newHoveredIndex) {
                this.hoverStartTime = Util.getMillis();
                this.hoveredItemIndex = newHoveredIndex;
            }
            this.hoverTime = (int) (Util.getMillis() - this.hoverStartTime);
        } else {
            if (this.hoveredItemIndex != -1) {
                this.hoverStopTime = Util.getMillis();
            }
            this.hoveredItemIndex = -1;
            this.hoverTime = 0;
        }
    }

    private float getHoverMultiplier(float duration) {
        if (this.hoveredItemIndex != -1) {
            return Math.min(((this.hoverTime) / duration), 1.0f);
        } else {
            int delta = (int) (Util.getMillis() - this.hoverStopTime);
            return Math.max(1.0f - (delta / duration), 0.0f);
        }
    }

    private void renderItemHover(GuiGraphics graphics, int itemY) {
        float hoverMultiplier = this.getHoverMultiplier(200);

        if (hoverMultiplier > 0.0f) {
            // VulkanMod hover overlay: red with animation
            int overlayColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, hoverMultiplier * 0.2f);
            graphics.fill(this.getX(), itemY, this.getX() + this.width, itemY + this.itemHeight, overlayColor);

            // VulkanMod hover border: red with animation
            int borderColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, hoverMultiplier * 0.8f);

            // Draw border around the item
            int x0 = this.getX();
            int x1 = this.getX() + this.width;
            int y0 = itemY;
            int y1 = itemY + this.itemHeight;
            int border = 1;

            // Top border
            graphics.fill(x0, y0, x1, y0 + border, borderColor);
            // Bottom border
            graphics.fill(x0, y1 - border, x1, y1, borderColor);
            // Left border
            graphics.fill(x0, y0, x0 + border, y1, borderColor);
            // Right border
            graphics.fill(x1 - border, y0, x1, y1, borderColor);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Simple narration for accessibility
    }
}