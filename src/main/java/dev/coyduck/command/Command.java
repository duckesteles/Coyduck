package dev.coyduck.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.coyduck.feature.Shell;
import net.minecraft.command.CommandSource;

public abstract class Command {
    protected static final int SINGLE_SUCCESS = com.mojang.brigadier.Command.SINGLE_SUCCESS;

    public final String name;
    public final String description;
    private Shell shell = Shell.DETACHED;

    protected Command(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public abstract void build(LiteralArgumentBuilder<CommandSource> builder);

    public final void bind(Shell target) {
        shell = target;
    }

    protected static LiteralArgumentBuilder<CommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    protected static <T> RequiredArgumentBuilder<CommandSource, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    protected final void info(String message, Object... args) {
        shell.info(message, args);
    }

    protected final void error(String message, Object... args) {
        shell.error(message, args);
    }
}
