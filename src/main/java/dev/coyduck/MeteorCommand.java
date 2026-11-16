package dev.coyduck;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.coyduck.feature.Shell;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

public final class MeteorCommand extends Command implements Shell {
    private final dev.coyduck.command.Command command;

    public MeteorCommand(dev.coyduck.command.Command command) {
        super(command.name, command.description);
        this.command = command;
        command.bind(this);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        command.build(builder);
    }
}
