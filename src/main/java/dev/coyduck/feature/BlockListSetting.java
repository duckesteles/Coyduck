package dev.coyduck.feature;

import net.minecraft.block.Block;

public final class BlockListSetting extends ListSetting<Block> {
    private BlockListSetting(Builder builder) {
        super(builder);
    }

    public static final class Builder extends ListSetting.Builder<Builder, Block, BlockListSetting> {
        @Override
        public BlockListSetting build() {
            return new BlockListSetting(this);
        }
    }
}
