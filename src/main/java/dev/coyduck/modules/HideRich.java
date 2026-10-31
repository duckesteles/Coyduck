package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HideRich extends Module {
    private static final Pattern CHAT = Pattern.compile("^(\\[[^\\]\\n]{1,40}\\] )?(\\.?[A-Za-z0-9_]{1,16})( » )(.*)$", Pattern.DOTALL);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hideRank = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-rank")
        .description("Removes the [Rank] tag in front of the name.")
        .defaultValue(true)
        .build()
    );

    private Style nameStyle;
    private Style separatorStyle;
    private Style bodyStyle;

    private record Part(int start, int end, Style style) {}

    public HideRich() {
        super(Coyduck.CATEGORY, "hide-rich", "Shows players with a paid rank like everyone else in chat. Only player messages are touched.");
    }

    @Override
    public String getInfoString() {
        return nameStyle == null ? "learning" : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMessage(ReceiveMessageEvent event) {
        Text text = event.getMessage();
        String plain = text.getString();
        Matcher matcher = CHAT.matcher(plain);
        if (!matcher.matches()) return;

        List<Part> parts = parts(text);
        if (matcher.group(1) == null) {
            nameStyle = plainStyle(styleAt(parts, matcher.start(2)));
            separatorStyle = plainStyle(styleAt(parts, matcher.start(3)));
            if (!matcher.group(4).isEmpty()) bodyStyle = plainStyle(styleAt(parts, matcher.start(4)));
            return;
        }
        if (nameStyle == null || separatorStyle == null || bodyStyle == null) return;

        Style original = styleAt(parts, matcher.start(2));
        Style name = nameStyle.withClickEvent(original.getClickEvent()).withHoverEvent(original.getHoverEvent()).withInsertion(original.getInsertion());

        MutableText rebuilt = Text.empty();
        if (!hideRank.get()) rebuilt.append(Text.literal(matcher.group(1)).setStyle(nameStyle));
        rebuilt.append(Text.literal(matcher.group(2)).setStyle(name));
        rebuilt.append(Text.literal(matcher.group(3)).setStyle(separatorStyle));
        rebuilt.append(Text.literal(matcher.group(4)).setStyle(bodyStyle));
        event.setMessage(rebuilt);
    }

    private static Style plainStyle(Style style) {
        return style.withClickEvent(null).withHoverEvent(null).withInsertion(null);
    }

    private static List<Part> parts(Text text) {
        List<Part> parts = new ArrayList<>();
        int[] at = {0};
        text.visit((style, string) -> {
            parts.add(new Part(at[0], at[0] + string.length(), style));
            at[0] += string.length();
            return Optional.empty();
        }, Style.EMPTY);
        return parts;
    }

    private static Style styleAt(List<Part> parts, int index) {
        for (Part part : parts) {
            if (index >= part.start && index < part.end) return part.style;
        }
        return Style.EMPTY;
    }
}
