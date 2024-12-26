package storyweaver.logic;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.reaction.ReactionEmoji;

import java.util.*;

public class StoryWeaverInstance {
    private Message lobbyMessage;
    private List<Long> participants;
    private boolean lobbyOpen;
    private boolean gameOver;
    private Message startMessage;
    private Long ownerId;


    private final Object locker = new Object();


    //Dictionary is used for the stories. The first message is the title, all the others are part of the story
    //The key of the Dictionary is the user id of the person that wrote all the messages. Note that the list
    //of strings is not actually the proper story, but just all the messages that the user wrote
    private Map<Long, List<String>> stories;

    //Dictionary is used for the private channels. The key is the user id of the person that started the story
    private Map<Long, MessageChannel> userChannels;

    private Map<Long, Boolean> userCanWrite;

    private final int maxRounds;

    private Runnable onDelete = null;

    public StoryWeaverInstance(Message startMessage, Runnable onDelete, int maxRounds){
        this.startMessage = startMessage;
        this.ownerId = startMessage.getAuthor().get().getId().asLong();
        this.lobbyOpen = true;
        this.gameOver = false;
        stories = new HashMap<>();
        userChannels = new HashMap<>();
        userCanWrite = new HashMap<>();
        participants = new ArrayList<>();
        participants.add(this.ownerId);
        this.onDelete = onDelete;
        this.maxRounds = maxRounds;
        startLobby(this.startMessage.getChannel().block());
    }

    public StoryWeaverInstance(Message startMessage, Runnable onDelete, int maxRounds, List<Long> userIds) {
        this.startMessage = startMessage;
        this.ownerId = startMessage.getAuthor().get().getId().asLong();
        this.lobbyOpen = false;
        this.gameOver = false;
        stories = new HashMap<>();
        userChannels = new HashMap<>();
        userCanWrite = new HashMap<>();
        participants = userIds;
        this.onDelete = onDelete;
        this.maxRounds = maxRounds;

        startReplayGame();
    }

    public Message getLobbyMessage() {
        return this.lobbyMessage;
    }

    public Long getOwnerId() {
        return this.ownerId;
    }
    public boolean isLobbyOpen() {
        return this.lobbyOpen;
    }

    public int getNumberOfParticipants() {
        return this.participants.size();
    }

    public void addParticipant(Long userId){
        synchronized(locker) {
            if (this.lobbyOpen && !this.participants.contains(userId)) {
                this.participants.add(userId);
                updateLobbyMessage();
            }
        }
    }

    public void removeParticipant(Long userId){
        synchronized(locker) {
            if (this.lobbyOpen && this.participants.contains(userId)) {
                this.participants.remove(userId);
                updateLobbyMessage();
            }
        }
    }

    private void updateLobbyMessage() {
        //Update the embed
        this.lobbyMessage.edit(spec -> {
            spec.setEmbed(embed -> {
                embed.setTitle("Story Weaver");

                StringBuilder userListBuilder = new StringBuilder();
                for(Long userId : this.participants){
                    userListBuilder.append("<@").append(userId).append(">\n");
                }

                embed.addField("User List", userListBuilder.toString(), false);

                StringBuilder controlsBuilder = new StringBuilder();
                controlsBuilder.append(":green_square: Join the game.\n");
                controlsBuilder.append(":red_square: Leave the game.\n");
                controlsBuilder.append(":arrow_forward: Start the game. Only the creator can do this.\n");
                controlsBuilder.append(":x: Close the lobby and abort the game. Only the creator can do this.\n");

                embed.addField("Controls:", controlsBuilder.toString(), false);

                if(this.lobbyOpen)
                    embed.addField("Status:", "Lobby is open.", false);
                else if(! this.gameOver)
                    embed.addField("Status:", "Game is running", false);
                else
                    embed.addField("Status:", "Game is over", false);
            });
        }).block();
    }

    private void startLobby(MessageChannel channel){
        //Build an embed
        this.lobbyMessage =  channel.createMessage(spec -> {
            spec.addEmbed(embed -> {
                embed.setTitle("Story Weaver");

                StringBuilder userListBuilder = new StringBuilder();
                for(Long userId : this.participants){
                    userListBuilder.append("<@").append(userId).append(">\n");
                }

                embed.addField("User List", userListBuilder.toString(), false);

                StringBuilder controlsBuilder = new StringBuilder();
                controlsBuilder.append(":green_square: Join the game.\n");
                controlsBuilder.append(":red_square: Leave the game.\n");
                controlsBuilder.append(":arrow_forward: Start the game. Only the creator can do this.\n");
                controlsBuilder.append(":x: Close the lobby and abort the game. Only the creator can do this.\n");

                embed.addField("Controls:", controlsBuilder.toString(), false);

                embed.addField("Status:", "Lobby is open.", false);
            });
        }).block();

        //Add the reactions
        this.lobbyMessage.addReaction(ReactionEmoji.unicode("\uD83D\uDFE9")).block(); //Green-Square
        this.lobbyMessage.addReaction(ReactionEmoji.unicode("\uD83D\uDFE5")).block(); //Red-Square
        this.lobbyMessage.addReaction(ReactionEmoji.unicode("\u25B6\uFE0F")).block(); //Forward arrow
        this.lobbyMessage.addReaction(ReactionEmoji.unicode("\u274C")).block(); //X-Button
    }

    public void startGame() {
        synchronized(locker) {
            //Shuffle the participants list
            Random random = new Random();
            for (int i = 0; i < this.participants.size(); i++) {
                int randomIndex = random.nextInt(this.participants.size());
                Long temp = this.participants.get(i);
                this.participants.set(i, this.participants.get(randomIndex));
                this.participants.set(randomIndex, temp);
            }

            //Initialize the stories
            for (Long userId : this.participants) {
                stories.put(userId, new ArrayList<>());
            }

            //Initialize the userCanWrite
            for (Long userId : this.participants) {
                userCanWrite.put(userId, false);
            }

            //Close the lobby
            this.lobbyOpen = false;
            updateLobbyMessage();

            //Create the private channels
            for (Long userId : this.participants) {
                MessageChannel privateChannel = this.startMessage.getClient().getUserById(Snowflake.of(userId)).block().getPrivateChannel().ofType(MessageChannel.class).block();
                userChannels.put(userId, privateChannel);
                privateChannel.createMessage("Story Weaver game has started. Please reply with a title for a story. You will get updates of other stories as soon as possible").block();
                userCanWrite.put(userId, true);
            }
        }

    }

    private void startReplayGame(){
        synchronized (locker) {
            this.startMessage.getChannel().block().createMessage("Starting the game with the given participants. This is a replay of the previous round, so please paste the same titles/messages as before.").block();

            //Initialize the stories
            for (Long userId : this.participants) {
                stories.put(userId, new ArrayList<>());
            }

            //Initialize the userCanWrite
            for (Long userId : this.participants) {
                userCanWrite.put(userId, false);
            }

            //Create the private channels
            for (Long userId : this.participants) {
                MessageChannel privateChannel = this.startMessage.getClient().getUserById(Snowflake.of(userId)).block().getPrivateChannel().ofType(MessageChannel.class).block();
                userChannels.put(userId, privateChannel);
                privateChannel.createMessage("Story Weaver game has started. Please reply with a title for a story. You will get updates of other stories as soon as possible").block();
                privateChannel.createMessage(":exclamation::exclamation::exclamation: THIS IS A REPLAY ROUND :exclamation::exclamation::exclamation:\nPlease paste the same titles/messages as before until you are up to date again").block();
                userCanWrite.put(userId, true);
            }
        }
    }

    public void addStoryFromUser(Long userId, String story) {
        synchronized (locker) {
            if (participants.contains(userId)) {

                //Check if the user can write
                if (userCanWrite.get(userId)) {
                    stories.get(userId).add(story);
                    userCanWrite.put(userId, false);

                    System.out.println("User " + userId + " wrote: " + story);

                    //Check if the next person needs the story immediately
                    Long nextUserId = getNextUserId(userId);
                    if (stories.get(nextUserId).size() == stories.get(userId).size()) {
                        if (stories.get(nextUserId).size() < maxRounds) {
                            userChannels.get(nextUserId).createMessage(
                                    "You have a new story to continue. Please send the next part of the story\n\n" +
                                    "You are writing part " + (stories.get(userId).size() + 1) + "/" + maxRounds + " of the story").block();
                            userChannels.get(nextUserId).createMessage(stories.get(userId).get(stories.get(userId).size() - 1)).block();

                            userCanWrite.put(nextUserId, true);

                            System.out.println("The next user got the currently submitted story immediately");
                        }
                    }

                    //Check if the previous person has the story already meaning i could already send it to the current person
                    Long previousUserId = getPreviousUserId(userId);
                    if (stories.get(previousUserId).size() >= stories.get(userId).size()) {
                        if (stories.get(userId).size() < maxRounds) {
                            userChannels.get(userId).createMessage(
                                    "You have a new story to continue. Please send the next part of the story\n\n" +
                                    "You are writing part " + (stories.get(userId).size() + 1) + "/" + maxRounds + " of the story").block();
                            userChannels.get(userId).createMessage(stories.get(previousUserId).get(stories.get(userId).size() - 1)).block();

                            userCanWrite.put(userId, true);

                            System.out.println("The current user got the story from the previous user immediately");
                        }
                    }

                    debugPrintStoriesSoFar();

                    System.out.println("\n========================================================================\n\n");

                    //Check if the game is over
                    if (areStoriesComplete()) {
                        this.gameOver = true;
                        sendFinalStories();
                    }
                }
            }
        }
    }

    private Long getPreviousUserId(Long userId) {
        int index = participants.indexOf(userId);
        int newIndex = index - 1;
        if(newIndex == -1)
            newIndex = participants.size() - 1;

        return participants.get(newIndex);
    }

    private Long getNextUserId(Long userId) {
        int index = participants.indexOf(userId);
        int newIndex = index + 1;
        if(index == participants.size() - 1)
            newIndex = 0;

        return participants.get(newIndex);
    }

    private boolean areStoriesComplete(){
        for(Long userId : participants){
            if(stories.get(userId).size() < maxRounds)
                return false;
        }
        return true;
    }

    private void sendFinalStories() {
        MessageChannel channel = this.startMessage.getChannel().block();

        GatewayDiscordClient gatewayDiscordClient = this.startMessage.getClient();

        for(Long userId : participants) {
            Long currentUser = userId;
            List<Pair<Long, String>> currentStory = new ArrayList<>();
            for(int i = 0; i < maxRounds; i++) {
                currentStory.add(new Pair<>(currentUser, stories.get(currentUser).get(i)));
                currentUser = getNextUserId(currentUser);
            }

            StringBuilder storyBuilder = new StringBuilder();
            boolean isFirstIteration = true;
            for(Pair<Long, String> storyPart : currentStory) {
                User user = gatewayDiscordClient.getUserById(Snowflake.of(storyPart.getKey())).block();
                if (isFirstIteration) {
                    storyBuilder.append("# ").append(storyPart.getValue()).append("\n");
                    storyBuilder.append("## ").append(" *by: ").append(user.getGlobalName().get()).append("*\n\n");
                    isFirstIteration = false;
                } else {
                    storyBuilder.append("## ").append(user.getGlobalName().get()).append(" *wrote:* ").append("\n");
                    storyBuilder.append(storyPart.getValue()).append("\n");
                }
            }

            String text = storyBuilder.toString();
            for(int i = 0; i < text.length(); i += 1800) {
                channel.createMessage(text.substring(i, Math.min(i + 1800, text.length()))).block();
            }
        }

        this.updateLobbyMessage();

        this.onDelete.run();
    }

    private void debugPrintStoriesSoFar() {
        System.out.println("Stories so far:");
        for(Long userId : participants) {
            System.out.println("User " + userId + " has written the following:");
            for(String storyPart : stories.get(userId)) {
                System.out.println(storyPart);
            }
        }
        System.out.println("\n\n");
    }

    public void printStatus(Message message) {
        message.getChannel().block().createMessage(spec -> {
            spec.addEmbed(embed -> {
                embed.setTitle("Story Weaver Status");

                StringBuilder userListBuilder = new StringBuilder();
                for(Long userId : this.participants){
                    userListBuilder.append("<@").append(userId).append(">\n");
                    for(int i = 0; i < maxRounds; i++){
                        if(i < stories.get(userId).size()) {
                            userListBuilder.append("\uD83D\uDFE9");
                        } else if(i >= stories.get(userId).size() && i < stories.get(getPreviousUserId(userId)).size() + 1) {
                            userListBuilder.append("\uD83D\uDFE8");
                        } else {
                            userListBuilder.append("\u2B1B");
                        }
                    }
                    userListBuilder.append("\n");
                }

                embed.addField("Status", userListBuilder.toString(), false);

                StringBuilder controlsBuilder = new StringBuilder();
                controlsBuilder.append("\uD83D\uDFE9 Story written.\n");
                controlsBuilder.append("\uD83D\uDFE8 Story waiting to be written.\n");
                controlsBuilder.append("\u2B1B Story not ready to be written yet.\n");

                embed.addField("Legend:", controlsBuilder.toString(), false);
            });
        }).block();
    }
}
