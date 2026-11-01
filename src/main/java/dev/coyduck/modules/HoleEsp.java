package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

public class HoleEsp extends Module {
    private static final double SAFE_RESISTANCE = 600;
    private static final int SCAN_TICKS = 3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("How far around you to look for holes.")
        .defaultValue(10)
        .range(2, 24)
        .sliderRange(4, 16)
        .build()
    );

    private final Setting<Integer> vertical = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-range")
        .description("How far up and down to look for holes.")
        .defaultValue(5)
        .range(1, 16)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> doubles = sgGeneral.add(new BoolSetting.Builder()
        .name("doubles")
        .description("Also shows two block wide holes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideOwn = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-own")
        .description("Hides the hole you are standing in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> height = sgRender.add(new DoubleSetting.Builder()
        .name("height")
        .description("Height of the boxes.")
        .defaultValue(1)
        .range(0.1, 2)
        .sliderRange(0.2, 1.5)
        .build()
    );

    private final Setting<Double> speed = sgRender.add(new DoubleSetting.Builder()
        .name("animation-speed")
        .description("How quickly boxes rise in and fade out.")
        .defaultValue(9)
        .range(1, 30)
        .sliderRange(3, 20)
        .build()
    );

    private final Setting<SettingColor> safeColor = sgRender.add(new ColorSetting.Builder()
        .name("bedrock-color")
        .description("Holes made only of bedrock. The alpha sets how strong the glass looks.")
        .defaultValue(new SettingColor(255, 255, 255, 60))
        .build()
    );

    private final Setting<SettingColor> otherColor = sgRender.add(new ColorSetting.Builder()
        .name("obsidian-color")
        .description("Holes with breakable walls. The alpha sets how strong the glass looks.")
        .defaultValue(new SettingColor(190, 170, 255, 60))
        .build()
    );

    private static final class Hole {
        final double minX;
        final double minZ;
        final double maxX;
        final double maxZ;
        final double y;
        boolean safe;
        boolean present = true;
        double shown;

        Hole(double minX, double minZ, double maxX, double maxZ, double y, boolean safe) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.y = y;
            this.safe = safe;
        }
    }

    private final Long2ObjectOpenHashMap<Hole> holes = new Long2ObjectOpenHashMap<>();
    private final LongSet seen = new LongOpenHashSet();
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private final Color fill = new Color();
    private final Color clear = new Color();
    private final Color line = new Color();
    private final Color lineClear = new Color();
    private int ticks;
    private long last;

    public HoleEsp() {
        super(Coyduck.CATEGORY, "hole-esp+", "Shows safe holes as glass boxes that rise in and fade out smoothly.");
    }

    @Override
    public void onActivate() {
        holes.clear();
        ticks = 0;
        last = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || ticks++ % SCAN_TICKS != 0) return;

        seen.clear();
        BlockPos center = mc.player.getBlockPos();
        int r = range.get();
        int v = vertical.get();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -v; dy <= v; dy++) scan(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
            }
        }
        for (var entry : holes.long2ObjectEntrySet()) entry.getValue().present = seen.contains(entry.getLongKey());
    }

    private void scan(int x, int y, int z) {
        cursor.set(x, y, z);
        if (!open(cursor)) return;

        BlockPos pos = cursor.toImmutable();
        int walls = 0;
        boolean safe = true;
        Direction gap = null;
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos next = pos.offset(side);
            if (solid(next)) {
                safe &= bedrock(next);
                walls++;
            } else if (gap == null) {
                gap = side;
            } else {
                return;
            }
        }
        if (!solid(pos.down())) return;
        safe &= bedrock(pos.down());

        if (walls == 4) {
            add(pos, x, z, x + 1, z + 1, y, safe);
            return;
        }
        if (!doubles.get() || gap == null || (gap != Direction.EAST && gap != Direction.SOUTH)) return;

        BlockPos other = pos.offset(gap);
        if (!open(other) || !solid(other.down())) return;
        safe &= bedrock(other.down());
        for (Direction side : Direction.Type.HORIZONTAL) {
            if (side == gap.getOpposite()) continue;
            BlockPos next = other.offset(side);
            if (!solid(next)) return;
            safe &= bedrock(next);
        }
        add(pos, x, z, x + 1 + gap.getOffsetX(), z + 1 + gap.getOffsetZ(), y, safe);
    }

    private void add(BlockPos key, double minX, double minZ, double maxX, double maxZ, double y, boolean safe) {
        if (hideOwn.get() && mc.player.getBoundingBox().intersects(new Box(minX, y, minZ, maxX, y + 2, maxZ))) return;

        long id = key.asLong();
        seen.add(id);
        Hole hole = holes.get(id);
        if (hole == null || hole.maxX != maxX || hole.maxZ != maxZ) holes.put(id, new Hole(minX, minZ, maxX, maxZ, y, safe));
        else hole.safe = safe;
    }

    private boolean open(BlockPos pos) {
        return mc.world.getBlockState(pos).isAir() && mc.world.getBlockState(pos.up()).isAir();
    }

    private boolean solid(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.getHardness(mc.world, pos) < 0 || state.getBlock().getBlastResistance() >= SAFE_RESISTANCE;
    }

    private boolean bedrock(BlockPos pos) {
        return mc.world.getBlockState(pos).getHardness(mc.world, pos) < 0;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        long now = Util.getMeasuringTimeMs();
        double delta = last == 0 ? 0 : Math.min((now - last) / 1000.0, 0.1);
        last = now;

        var iterator = holes.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Hole hole = iterator.next().getValue();
            hole.shown += ((hole.present ? 1 : 0) - hole.shown) * Math.min(1, delta * speed.get());
            if (!hole.present && hole.shown < 0.01) {
                iterator.remove();
                continue;
            }
            draw(event.renderer, hole);
        }
    }

    private void draw(Renderer3D r, Hole hole) {
        double inverse = 1 - hole.shown;
        double t = 1 - inverse * inverse * inverse;
        Color base = hole.safe ? safeColor.get() : otherColor.get();
        fill.set(base.r, base.g, base.b, (int) (base.a * t));
        clear.set(base.r, base.g, base.b, 0);
        line.set(base.r, base.g, base.b, (int) (Math.min(255, base.a * 3.5) * t));
        lineClear.set(base.r, base.g, base.b, 0);

        double y = hole.y;
        double top = y + height.get() * t;
        r.quadHorizontal(hole.minX, y, hole.minZ, hole.maxX, hole.maxZ, fill);
        r.gradientQuadVertical(hole.minX, y, hole.minZ, hole.maxX, top, hole.minZ, clear, fill);
        r.gradientQuadVertical(hole.minX, y, hole.maxZ, hole.maxX, top, hole.maxZ, clear, fill);
        r.gradientQuadVertical(hole.minX, y, hole.minZ, hole.minX, top, hole.maxZ, clear, fill);
        r.gradientQuadVertical(hole.maxX, y, hole.minZ, hole.maxX, top, hole.maxZ, clear, fill);

        r.line(hole.minX, y, hole.minZ, hole.maxX, y, hole.minZ, line);
        r.line(hole.minX, y, hole.maxZ, hole.maxX, y, hole.maxZ, line);
        r.line(hole.minX, y, hole.minZ, hole.minX, y, hole.maxZ, line);
        r.line(hole.maxX, y, hole.minZ, hole.maxX, y, hole.maxZ, line);
        r.line(hole.minX, y, hole.minZ, hole.minX, top, hole.minZ, line, lineClear);
        r.line(hole.maxX, y, hole.minZ, hole.maxX, top, hole.minZ, line, lineClear);
        r.line(hole.minX, y, hole.maxZ, hole.minX, top, hole.maxZ, line, lineClear);
        r.line(hole.maxX, y, hole.maxZ, hole.maxX, top, hole.maxZ, line, lineClear);
    }
}
