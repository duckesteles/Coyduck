package dev.coyduck.events;

import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class MovementTest {
    @Test
    void untouchedMovementKeepsTheSameVector() {
        Vec3d movement = new Vec3d(1, 2, 3);
        assertSame(movement, PlayerMoveEvent.get(MovementType.SELF, movement).result());
    }

    @Test
    void editsBuildANewVectorAndKeepTheRest() {
        Vec3d movement = new Vec3d(1, 2, 3);
        PlayerMoveEvent event = PlayerMoveEvent.get(MovementType.SELF, movement);
        event.setY(5);
        event.setXZ(-1, -3);
        assertEquals(5, event.y());
        Vec3d result = event.result();
        assertNotSame(movement, result);
        assertEquals(new Vec3d(-1, 5, -3), result);
        assertEquals(new Vec3d(1, 2, 3), movement);
    }

    @Test
    void eachPostStartsClean() {
        PlayerMoveEvent first = PlayerMoveEvent.get(MovementType.SELF, new Vec3d(1, 1, 1));
        first.set(9, 9, 9);
        first.result();
        Vec3d next = new Vec3d(2, 2, 2);
        assertSame(next, PlayerMoveEvent.get(MovementType.PLAYER, next).result());
    }
}
