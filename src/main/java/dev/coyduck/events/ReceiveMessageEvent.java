package dev.coyduck.events;

import net.minecraft.text.Text;

public final class ReceiveMessageEvent extends Cancellable {
    private static final ReceiveMessageEvent INSTANCE = new ReceiveMessageEvent();

    private Text message;
    private boolean modified;

    private ReceiveMessageEvent() {}

    public static ReceiveMessageEvent get(Text message) {
        INSTANCE.setCancelled(false);
        INSTANCE.message = message;
        INSTANCE.modified = false;
        return INSTANCE;
    }

    public Text getMessage() {
        return message;
    }

    public void setMessage(Text message) {
        this.message = message;
        modified = true;
    }

    public boolean isModified() {
        return modified;
    }
}
