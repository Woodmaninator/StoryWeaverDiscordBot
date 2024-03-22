package storyweaver.logic;

import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.reaction.ReactionEmoji;
import reactor.core.publisher.Flux;

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
            //Check if the emoji has been added. if it was removed -> do nothing

            //Cancel if the lobby message does not exist anymore for some reason (this should never happen but whatever)
            if(currentInstance.getLobbyMessage() == null)
                return;

            //Check if the reaction still exists for that message, if not, return
            if(!currentInstance.getLobbyMessage().getReactions().stream().anyMatch(reaction -> reaction.getEmoji().equals(emoji)))
                return;

            try {

                //Get the reactors of that emoji
                Flux<User> reactors = currentInstance.getLobbyMessage().getReactors(emoji);

                //Check if the user is in the list of reactors
                if (Boolean.TRUE.equals(reactors.any(u -> u.getId().asLong() == user.getId().asLong()).block())) {

                    Optional<ReactionEmoji.Unicode> unicodeEmoji = emoji.asUnicodeEmoji();
                    if (unicodeEmoji.isPresent()) {
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
                        } catch (Throwable e) {
                            //Ignore, probably not that important, just means that the bot can't remove the reaction, but who cares
                        }
                    }
                }
            } catch (Throwable e) {
                //Ignore, probably not that important, just means that the reaction wasn't handled properly, but at
                //least the lobby will no longer break
            }
        }
    }
}
