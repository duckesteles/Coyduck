package dev.coyduck.events;

public final class TickEvent {
    private TickEvent() {}

    public static final class Pre {
        private static final Pre INSTANCE = new Pre();

        private Pre() {}

        public static Pre get() {
            return INSTANCE;
        }
    }

    public static final class Post {
        private static final Post INSTANCE = new Post();

        private Post() {}

        public static Post get() {
            return INSTANCE;
        }
    }
}
