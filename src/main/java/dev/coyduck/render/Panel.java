package dev.coyduck.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

public interface Panel {
    double DEFAULT_SCALE = -1;

    DrawContext context();

    double text(String text, double x, double y, int argb, boolean shadow, double scale);

    double textWidth(String text, boolean shadow, double scale);

    double textHeight(boolean shadow, double scale);

    void quad(double x, double y, double width, double height, int argb);

    void texture(Identifier id, double x, double y, double width, double height, int argb);

    void item(ItemStack stack, int x, int y, float scale, boolean overlay, String count);

    void post(Runnable task);
}
