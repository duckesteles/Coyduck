package dev.coyduck.modules;

import dev.coyduck.events.RenderWorldEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.logic.Holes;
import dev.coyduck.render.Argb;
import dev.coyduck.render.Canvas;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.attribute.EnvironmentAttributes;

public class HoleEsp extends Feature {
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

    private final Setting<Boolean> markTrapped = sgGeneral.add(new BoolSetting.Builder()
        .name("mark-trapped")
        .description("Gives roofed holes their own color.")
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

    private final ColorSetting safeColor = sgRender.add(new ColorSetting.Builder()
        .name("bedrock-color")
        .description("Bedrock-only holes.")
        .defaultValue(new Rgba(255, 255, 255, 60))
        .build()
    );

    private final ColorSetting otherColor = sgRender.add(new ColorSetting.Builder()
        .name("obsidian-color")
        .description("Holes with breakable walls.")
        .defaultValue(new Rgba(190, 170, 255, 60))
        .build()
    );

    private final ColorSetting trappedColor = sgRender.add(new ColorSetting.Builder()
        .name("trapped-color")
        .description("Holes with a solid roof.")
        .defaultValue(new Rgba(255, 180, 70, 60))
        .visible(markTrapped::get)
        .build()
    );

    private static final class Hole {
        final double minX;
        final double minZ;
        final double maxX;
        final double maxZ;
        final double y;
        boolean safe;
        boolean trapped;
        boolean present = true;
        double shown;

        Hole(double minX, double minZ, double maxX, double maxZ, double y, boolean safe, boolean trapped) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.y = y;
            this.safe = safe;
            this.trapped = trapped;
        }
    }

    private final Long2ObjectOpenHashMap<Hole> holes = new Long2ObjectOpenHashMap<>();
    private final LongSet seen = new LongOpenHashSet();
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private final Holes.Terrain terrain = new Holes.Terrain() {
        @Override
        public boolean room(int x, int y, int z) {
            return open(x, y, z);
        }

        @Override
        public int wall(int x, int y, int z) {
            return kind(x, y, z);
        }
    };
    private Holes.Shapes shapes = new Holes.Shapes(true, true, false);
    private int ticks;
    private long last;
    private boolean anchorsWork;

    public HoleEsp() {
        super("hole-esp+", "Shows safe holes.");
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
        anchorsWork = mc.world.getRegistryKey() == World.NETHER || Boolean.TRUE.equals(mc.world.getEnvironmentAttributes().getAttributeValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS_GAMEPLAY, center));
        shapes = new Holes.Shapes(true, doubles.get(), false);
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
        Holes.Hole hole = Holes.at(terrain, x, y, z, shapes);
        if (hole == null) return;

        boolean trapped = true;
        for (int cx = hole.x(); cx < hole.maxX(); cx++) {
            for (int cz = hole.z(); cz < hole.maxZ(); cz++) trapped &= roofed(cx, y, cz);
        }
        add(BlockPos.asLong(x, y, z), x, z, hole.maxX(), hole.maxZ(), y, hole.safe(), trapped);
    }

    private boolean roofed(int x, int y, int z) {
        return markTrapped.get() && kind(x, y + 2, z) != Holes.OPEN;
    }

    private void add(long id, double minX, double minZ, double maxX, double maxZ, double y, boolean safe, boolean trapped) {
        if (hideOwn.get() && mc.player.getBoundingBox().intersects(minX, y, minZ, maxX, y + 2, maxZ)) return;

        seen.add(id);
        Hole hole = holes.get(id);
        if (hole == null || hole.maxX != maxX || hole.maxZ != maxZ) holes.put(id, new Hole(minX, minZ, maxX, maxZ, y, safe, trapped));
        else {
            hole.safe = safe;
            hole.trapped = trapped;
        }
    }

    private boolean open(int x, int y, int z) {
        return mc.world.getBlockState(cursor.set(x, y, z)).isAir() && mc.world.getBlockState(cursor.set(x, y + 1, z)).isAir();
    }

    private int kind(int x, int y, int z) {
        BlockState state = mc.world.getBlockState(cursor.set(x, y, z));
        if (state.getHardness(mc.world, cursor) < 0) return Holes.BEDROCK;
        if (state.isOf(Blocks.RESPAWN_ANCHOR) && !anchorsWork) return Holes.OPEN;
        return state.getBlock().getBlastResistance() >= SAFE_RESISTANCE ? Holes.SOLID : Holes.OPEN;
    }

    @EventHandler
    private void onRender(RenderWorldEvent event) {
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
            draw(event.canvas, hole);
        }
    }

    private void draw(Canvas r, Hole hole) {
        double inverse = 1 - hole.shown;
        double t = 1 - inverse * inverse * inverse;
        int base = Shaders.tint((hole.trapped ? trappedColor : hole.safe ? safeColor : otherColor).argb());
        int fill = Argb.alpha(base, (int) (Argb.a(base) * t));
        int clear = Argb.alpha(base, 0);
        int line = Argb.alpha(base, (int) (Math.min(255, Argb.a(base) * 3.5) * t));

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
        r.line(hole.minX, y, hole.minZ, hole.minX, top, hole.minZ, line, clear);
        r.line(hole.maxX, y, hole.minZ, hole.maxX, top, hole.minZ, line, clear);
        r.line(hole.minX, y, hole.maxZ, hole.minX, top, hole.maxZ, line, clear);
        r.line(hole.maxX, y, hole.maxZ, hole.maxX, top, hole.maxZ, line, clear);
    }
}
