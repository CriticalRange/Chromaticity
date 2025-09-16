package net.chromaticity.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ShaderPackListWidget extends ObjectSelectionList<ShaderPackListWidget.ShaderPackEntry> {
    private final ShaderPackScreen parent;
    private final int itemHeight;

    public ShaderPackListWidget(Minecraft minecraft, int width, int height, int y0, int y1, int itemHeight, ShaderPackScreen parent) {
        super(minecraft, width, height, y0, y1);
        this.parent = parent;
        this.itemHeight = itemHeight;
    }

    public void updateShaderPacks(List<ShaderPackScreen.ShaderPack> shaderPacks) {
        this.clearEntries();
        for (ShaderPackScreen.ShaderPack shaderPack : shaderPacks) {
            this.addEntry(new ShaderPackEntry(shaderPack));
        }
    }

    @Override
    public void setSelected(ShaderPackEntry entry) {
        super.setSelected(entry);
        if (entry != null) {
            this.parent.setSelectedShaderPack(entry.shaderPack);
        }
    }

    @Override
    protected int getScrollbarPosition() {
        return super.getScrollbarPosition() + 20;
    }

    @Override
    public int getRowWidth() {
        return this.width - 50;
    }




    public class ShaderPackEntry extends ObjectSelectionList.Entry<ShaderPackEntry> {
        private final ShaderPackScreen.ShaderPack shaderPack;

        public ShaderPackEntry(ShaderPackScreen.ShaderPack shaderPack) {
            this.shaderPack = shaderPack;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
            // Create a compact 16px high area at the top of the 56px entry
            int compactHeight = 16;

            // Background highlighting - only for the compact area
            if (this.isSelected()) {
                graphics.fill(x, y, x + entryWidth, y + compactHeight, 0x80FFFFFF);
            } else if (isMouseOver) {
                graphics.fill(x, y, x + entryWidth, y + compactHeight, 0x40FFFFFF);
            }

            // Add a very subtle separator line (much thinner)
            graphics.fill(x + 5, y + compactHeight - 1, x + entryWidth - 5, y + compactHeight, 0x22FFFFFF);

            // Shader pack name - positioned in the compact area
            String name = this.shaderPack.getName();
            int nameY = y + 3; // Position text nicely in the 16px area
            graphics.drawString(ShaderPackListWidget.this.minecraft.font, name, x + 5, nameY, 0xFFFFFF);

            // Type info on the right - also in the compact area
            if (!this.shaderPack.isNone()) {
                String info = this.shaderPack.getFile().isDirectory() ? "Folder" : "Archive";
                int infoWidth = ShaderPackListWidget.this.minecraft.font.width(info);
                graphics.drawString(ShaderPackListWidget.this.minecraft.font, info, x + entryWidth - infoWidth - 5, nameY, 0xCCCCCC);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                ShaderPackListWidget.this.setSelected(this);
                return true;
            }
            return false;
        }

        public boolean isSelected() {
            return ShaderPackListWidget.this.getSelected() == this;
        }

        @Override
        public Component getNarration() {
            return Component.literal("Shader pack: " + this.shaderPack.getName());
        }
    }
}