package dev.coyduck.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.coyduck.feature.Features;
import dev.coyduck.modules.ElytraPilot;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

public class PilotCommand extends Command {
    public PilotCommand() {
        super("pilot", "Flies to a destination with elytra-pilot: .pilot <x> <z>, .pilot <x> <y> <z>, .pilot spiral <step> to search outward in a square spiral, .pilot stop");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            info(pilot().status());
            return SINGLE_SUCCESS;
        });

        builder.then(literal("stop").executes(context -> {
            ElytraPilot pilot = pilot();
            if (pilot.isActive()) pilot.toggle();
            info("Stopped.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("spiral").then(argument("step", IntegerArgumentType.integer(16, 4096)).executes(context -> {
            pilot().spiral(context.getArgument("step", Integer.class));
            info("Spiralling out from here, %d blocks between rings.", context.getArgument("step", Integer.class));
            return SINGLE_SUCCESS;
        })));

        builder.then(argument("x", IntegerArgumentType.integer())
            .then(argument("second", IntegerArgumentType.integer())
                .executes(context -> {
                    pilot().travel(context.getArgument("x", Integer.class), null, context.getArgument("second", Integer.class));
                    return SINGLE_SUCCESS;
                })
                .then(argument("z", IntegerArgumentType.integer()).executes(context -> {
                    pilot().travel(context.getArgument("x", Integer.class), context.getArgument("second", Integer.class), context.getArgument("z", Integer.class));
                    return SINGLE_SUCCESS;
                }))));
    }

    private static ElytraPilot pilot() {
        return Features.get(ElytraPilot.class);
    }
}
