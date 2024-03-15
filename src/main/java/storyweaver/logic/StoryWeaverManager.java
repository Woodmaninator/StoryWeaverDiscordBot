package storyweaver.logic;

import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.reaction.ReactionEmoji;

import java.util.Optional;

public class StoryWeaverManager {

    private StoryWeaverInstance currentInstance = null;
    public void interpretMessage(Message message) {
        if(message.getAuthor().get().isBot())
            return;
        if(message.getChannel().block().getType().equals(Channel.Type.DM))
            interpretPrivateMessage(message);
        else
            interpretPublicMessage(message);
    }

    private void interpretPrivateMessage(Message message) {
        if(currentInstance != null) {
            currentInstance.addStoryFromUser(message.getAuthor().get().getId().asLong(), message.getContent());
        }
    }

    private void interpretPublicMessage(Message message) {
        String content = message.getContent();
        content = message.getData().content();
        String[] args = content.split(" ");
        if(args[0].equalsIgnoreCase("!start")) {

            int maxRounds = 7;
            if(args.length > 1) {
                try {
                    maxRounds = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    //Default value
                }
            }

            if(currentInstance == null) {
                currentInstance = new StoryWeaverInstance(message, () -> {
                    currentInstance = null;
                }, maxRounds);
            }
        }

        if(args[0].equalsIgnoreCase("!reset")) {
            if(currentInstance != null) {
                if(currentInstance.getOwnerId().equals(message.getAuthor().get().getId().asLong())) {
                    currentInstance = null;
                    message.getChannel().block().createMessage("Lobby has been reset").block();
                }
            }
        }
    }

    public void interpretReaction(Message message, User user, ReactionEmoji emoji) {
        if(currentInstance != null && message.equals(currentInstance.getLobbyMessage())) {
            //Check if the emoji has been removed or added
            if(currentInstance.getLobbyMessage().getReactors(emoji).any(user1 -> user1.equals(user)).block()) {

                Optional<ReactionEmoji.Unicode> unicodeEmoji = emoji.asUnicodeEmoji();
                if(unicodeEmoji.isPresent()) {
                    String rawEmoji = unicodeEmoji.get().getRaw();
                    Long userId = user.getId().asLong();
                    if (rawEmoji.equals("\uD83D\uDFE9")) {
                        if (!userId.equals(currentInstance.getOwnerId())) {
                            this.currentInstance.addParticipant(userId);
                        }

                    } else if (rawEmoji.equals("\uD83D\uDFE5")) {
                        if (!userId.equals(currentInstance.getOwnerId())) {
                            this.currentInstance.removeParticipant(userId);
                        }
                    } else if (rawEmoji.equals("\u25B6\uFE0F")) {
                        if (userId.equals(currentInstance.getOwnerId()) && currentInstance.getNumberOfParticipants() > 1 && currentInstance.isLobbyOpen()) {
                            this.currentInstance.startGame();
                        }
                    } else if (rawEmoji.equals("\u274C")) {
                        if (userId.equals(currentInstance.getOwnerId())) {
                            //close the lobby
                            this.currentInstance = null;
                        }
                    }

                    //Remove the reaction again
                    try {
                        message.removeReaction(ReactionEmoji.of(unicodeEmoji.get().asEmojiData()), user.getId()).block();
                    } catch (Exception e) {
                        //Ignore, probably not that important
                    }
                }
            }
        }
    }
}
