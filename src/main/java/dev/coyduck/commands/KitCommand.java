package dev.coyduck.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.coyduck.modules.Regear;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

public class KitCommand extends Command {
    public KitCommand() {
        super("kit", "Saves inventory layouts for regear: .kit save <name>, .kit delete <name>, .kit list");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("save").then(argument("name", StringArgumentType.word()).executes(context -> {
            String name = context.getArgument("name", String.class);
            if (regear().save(name)) info("Saved your inventory as kit %s.", name);
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("delete").then(argument("name", StringArgumentType.word()).executes(context -> {
            String name = context.getArgument("name", String.class);
            if (regear().delete(name)) info("Deleted kit %s.", name);
            else error("No kit named %s.", name);
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("list").executes(context -> {
            info("Kits: %s", regear().names().isEmpty() ? "none" : String.join(", ", regear().names()));
            return SINGLE_SUCCESS;
        }));
    }

    private static Regear regear() {
        return Modules.get().get(Regear.class);
    }
}
