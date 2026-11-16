package dev.coyduck.feature;

import net.minecraft.item.Item;

public final class ItemListSetting extends ListSetting<Item> {
    private ItemListSetting(Builder builder) {
        super(builder);
    }

    public static final class Builder extends ListSetting.Builder<Builder, Item, ItemListSetting> {
        @Override
        public ItemListSetting build() {
            return new ItemListSetting(this);
        }
    }
}
