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
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HideRich extends Module {
    private static final Pattern CHAT = Pattern.compile(
        "^((?:<\\d{1,2}:\\d{2}(?::\\d{2})?> )?(?:\\[[^\\]\\n]{1,40} head\\] )?)(\\[[^\\]\\n]{1,40}\\] )?(\\.?[A-Za-z0-9_]{1,16})( » )(.*)$",
        Pattern.DOTALL);

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

    private record Piece(int start, int end, TextContent content, String string, Style style) {
        boolean literal() {
            return content instanceof PlainTextContent;
        }
    }

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
        List<Piece> pieces = new ArrayList<>();
        flatten(text, Style.EMPTY, pieces, new int[1]);

        StringBuilder plain = new StringBuilder();
        for (Piece piece : pieces) plain.append(piece.string);
        Matcher matcher = CHAT.matcher(plain);
        if (!matcher.matches()) return;

        int rankStart = matcher.group(2) == null ? matcher.start(3) : matcher.start(2);
        for (Piece piece : pieces) {
            if (!piece.literal() && piece.end > rankStart) return;
        }

        if (matcher.group(2) == null) {
            nameStyle = plainStyle(styleAt(pieces, matcher.start(3)));
            separatorStyle = plainStyle(styleAt(pieces, matcher.start(4)));
            if (!matcher.group(5).isEmpty()) bodyStyle = plainStyle(styleAt(pieces, matcher.start(5)));
            return;
        }
        if (nameStyle == null || separatorStyle == null || bodyStyle == null) return;

        Style original = styleAt(pieces, matcher.start(3));
        Style name = nameStyle.withClickEvent(original.getClickEvent()).withHoverEvent(original.getHoverEvent()).withInsertion(original.getInsertion());

        MutableText rebuilt = Text.empty();
        for (Piece piece : pieces) {
            if (piece.start >= rankStart) break;
            if (piece.literal()) {
                String kept = piece.string.substring(0, Math.min(piece.string.length(), rankStart - piece.start));
                if (!kept.isEmpty()) rebuilt.append(Text.literal(kept).setStyle(piece.style));
            } else {
                rebuilt.append(MutableText.of(piece.content).setStyle(piece.style));
            }
        }
        if (!hideRank.get()) rebuilt.append(Text.literal(matcher.group(2)).setStyle(nameStyle));
        rebuilt.append(Text.literal(matcher.group(3)).setStyle(name));
        rebuilt.append(Text.literal(matcher.group(4)).setStyle(separatorStyle));
        rebuilt.append(Text.literal(matcher.group(5)).setStyle(bodyStyle));
        event.setMessage(rebuilt);
    }

    private static void flatten(Text node, Style parent, List<Piece> out, int[] at) {
        Style style = node.getStyle().withParent(parent);
        TextContent content = node.getContent();
        StringBuilder string = new StringBuilder();
        content.visit(part -> {
            string.append(part);
            return Optional.empty();
        });
        if (!string.isEmpty() || !(content instanceof PlainTextContent)) {
            out.add(new Piece(at[0], at[0] + string.length(), content, string.toString(), style));
            at[0] += string.length();
        }
        for (Text child : node.getSiblings()) flatten(child, style, out, at);
    }

    private static Style plainStyle(Style style) {
        return style.withClickEvent(null).withHoverEvent(null).withInsertion(null);
    }

    private static Style styleAt(List<Piece> pieces, int index) {
        for (Piece piece : pieces) {
            if (index >= piece.start && index < piece.end) return piece.style;
        }
        return Style.EMPTY;
    }
}
