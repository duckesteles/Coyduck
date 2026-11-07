package dev.coyduck.modules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker extends Module {
    private static final URI LATEST = URI.create("https://api.github.com/repos/duckesteles/Coyduck/releases/latest");
    private static final String RELEASES = "https://github.com/duckesteles/Coyduck/releases";
    private static final Identifier BACKGROUND = Identifier.ofVanilla("toast/advancement");
    private static final Identifier ICON = Identifier.of("coyduck", "icon.png");

    private boolean checked;
    private boolean saved;
    private Text pending;

    public UpdateChecker() {
        super(Coyduck.CATEGORY, "update-checker", "Asks GitHub for a newer Coyduck release when the game starts and shows it like an advancement, with a link in chat.");
        runInMainMenu = true;
        MeteorClient.EVENT_BUS.subscribe(new FirstRun());
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        saved = true;
        return super.fromTag(tag);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (checked) return;
        checked = true;
        CompletableFuture.runAsync(this::check);
    }

    private void check() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()) {
            HttpRequest request = HttpRequest.newBuilder(LATEST)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Coyduck-update-check")
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;

            JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
            if (release.has("draft") && release.get("draft").getAsBoolean()) return;
            String tag = release.get("tag_name").getAsString();
            String url = release.has("html_url") ? release.get("html_url").getAsString() : RELEASES;
            String current = FabricLoader.getInstance().getModContainer("coyduck").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("0");
            if (!newer(tag, current)) return;

            mc.execute(() -> announce(tag, current, url));
        } catch (Exception ignored) {
        }
    }

    private void announce(String tag, String current, String url) {
        String version = tag.startsWith("v") ? tag : "v" + tag;
        mc.getToastManager().add(new UpdateToast(Text.literal("Coyduck update!"), Text.literal(version + " is available")));

        Style link = Style.EMPTY.withColor(Formatting.AQUA).withUnderline(true)
            .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
            .withHoverEvent(new HoverEvent.ShowText(Text.literal(url)));
        pending = Text.literal("[Coyduck] ").formatted(Formatting.LIGHT_PURPLE)
            .append(Text.literal(version + " is out (you have v" + current + "). ").formatted(Formatting.WHITE))
            .append(Text.literal("Open on GitHub").setStyle(link));
        if (mc.player != null) flush();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (pending != null) mc.execute(this::flush);
    }

    private void flush() {
        if (pending == null || mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(pending);
        pending = null;
    }

    private final class FirstRun {
        private boolean done;

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (done) return;
            done = true;
            if (!saved && !isActive()) toggle();
        }
    }

    private static boolean newer(String latest, String current) {
        int[] a = parts(latest);
        int[] b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String version) {
        String clean = version.replaceFirst("^[vV]", "").split("[-+ ]")[0];
        String[] pieces = clean.split("\\.");
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                out[i] = Integer.parseInt(pieces[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static final class UpdateToast implements Toast {
        private static final long DURATION = 8_000;

        private final Text title;
        private final Text description;
        private Visibility visibility = Visibility.SHOW;

        UpdateToast(Text title, Text description) {
            this.title = title;
            this.description = description;
        }

        @Override
        public Visibility getVisibility() {
            return visibility;
        }

        @Override
        public void update(ToastManager manager, long time) {
            double scale = manager.getClient().options.getNotificationDisplayTime().getValue();
            visibility = time >= DURATION * scale ? Visibility.HIDE : Visibility.SHOW;
        }

        @Override
        public void draw(DrawContext context, TextRenderer textRenderer, long startTime) {
            context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, getWidth(), getHeight());
            context.drawTexture(RenderPipelines.GUI_TEXTURED, ICON, 8, 8, 0, 0, 16, 16, 256, 256, 256, 256);
            context.drawText(textRenderer, title, 30, 7, 0xFFFFFF00, false);
            context.drawText(textRenderer, description, 30, 18, 0xFFFFFFFF, false);
        }
    }
}
