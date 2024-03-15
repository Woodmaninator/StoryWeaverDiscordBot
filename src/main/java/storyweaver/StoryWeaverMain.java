package storyweaver;

import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.event.domain.message.ReactionRemoveEvent;
import storyweaver.logic.StoryWeaverManager;

public class StoryWeaverMain {
    public static void main(String[] args) {
        String token = System.getenv("TOKEN");

        if(token == null || token.trim().isEmpty())
            throw new IllegalArgumentException("TOKEN not found in environment variables");

        startBot(token);
    }

    public static void startBot(String token) {
        final DiscordClient client = DiscordClient.create(token);
        final GatewayDiscordClient gateway = client.login().block();

        StoryWeaverManager manager = new StoryWeaverManager();

        // event listeners for created messages (both public and private)
        gateway.on(MessageCreateEvent.class).subscribe(event -> {
            manager.interpretMessage(event.getMessage());
        });

        // event listeners for reactions added to messages
        gateway.on(ReactionAddEvent.class).subscribe(event -> {
            manager.interpretReaction(event.getMessage().block(), event.getUser().block(), event.getEmoji());
        });

        gateway.onDisconnect().block();
    }
}
