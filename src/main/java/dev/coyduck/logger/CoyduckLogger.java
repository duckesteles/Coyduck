package dev.coyduck.logger;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoyduckLogger implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Coyduck Logger");

    @Override
    public void onInitializeClient() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Recorder.get().close("game closed"), "Coyduck Logger shutdown"));
        LOG.info("Coyduck Logger ready, fights are recorded to coyduck-logger without coordinates");
    }
}
