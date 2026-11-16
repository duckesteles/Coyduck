package dev.coyduck.events;

public final class TickEvent {
    private TickEvent() {}

    public static final class Pre {
        public static final Pre INSTANCE = new Pre();

        private Pre() {}
    }

    public static final class Post {
        public static final Post INSTANCE = new Post();

        private Post() {}
    }
}
