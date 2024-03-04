package storyweaver.logic;

import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Message;
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



    //Dictionary is used for the stories. The first message is the title, all the others are part of the story
    //The key of the Dictionary is the user id of the person that wrote all the messages. Note that the list
    //of strings is not actually the proper story, but just all the messages that the user wrote
    private Map<Long, List<String>> stories;

    //Dictionary is used for the private channels. The key is the user id of the person that started the story
    private Map<Long, MessageChannel> userChannels;

    private Map<Long, Boolean> userCanWrite;

    private int maxRounds = 7;

    private Runnable onDelete = null;

    public StoryWeaverInstance(Message startMessage, Runnable onDelete){
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
        startLobby(this.startMessage.getChannel().block());
    }

    public Message getLobbyMessage() {
        return this.lobbyMessage;
    }

    public Long getOwnerId() {
        return this.ownerId;
    }

    public void addParticipant(Long userId){
        if(this.lobbyOpen) {
            this.participants.add(userId);
            updateLobbyMessage();
        }
    }

    public void removeParticipant(Long userId){
        if(this.lobbyOpen) {
            this.participants.remove(userId);
            updateLobbyMessage();
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
        //Shuffle the participants list
        Random random = new Random();
        for(int i = 0; i < this.participants.size(); i++) {
            int randomIndex = random.nextInt(this.participants.size());
            Long temp = this.participants.get(i);
            this.participants.set(i, this.participants.get(randomIndex));
            this.participants.set(randomIndex, temp);
        }

        //Initialize the stories
        for(Long userId : this.participants) {
            stories.put(userId, new ArrayList<>());
        }

        //Initialize the userCanWrite
        for(Long userId : this.participants) {
            userCanWrite.put(userId, false);
        }

        //Close the lobby
        this.lobbyOpen = false;
        updateLobbyMessage();

        //Create the private channels
        for(Long userId : this.participants) {
            MessageChannel privateChannel = this.startMessage.getClient().getUserById(Snowflake.of(userId)).block().getPrivateChannel().ofType(MessageChannel.class).block();
            userChannels.put(userId, privateChannel);
            privateChannel.createMessage("Story Weaver game has started. Please reply with a title for a story. You will get updates of other stories as soon as possible").block();
            userCanWrite.put(userId, true);
        }

    }

    public void addStoryFromUser(Long userId, String story) {
        if(participants.contains(userId)) {

            //Check if the user can write
            if(userCanWrite.get(userId)) {
                stories.get(userId).add(story);
                userCanWrite.put(userId, false);

                //Check if the next person needs the story immediately
                Long nextUserId = getNextUserId(userId);
                if(stories.get(nextUserId).size() == stories.get(userId).size()) {
                    if(stories.get(userId).size() < maxRounds) {
                        userChannels.get(nextUserId).createMessage("You have a new story to continue. Please reply with the next part of the story\n\n" + stories.get(userId).get(stories.get(userId).size() - 1)).block();
                        userCanWrite.put(nextUserId, true);
                    }
                }

                //Check if the previous person has the story already meaning i could already send it to the current person
                Long previousUserId = getPreviousUserId(userId);
                if(stories.get(previousUserId).size() == stories.get(userId).size()) {
                    if(stories.get(previousUserId).size() < maxRounds) {
                        userChannels.get(userId).createMessage("You have a new story to continue. Please reply with the next part of the story\n\n" + stories.get(previousUserId).get(stories.get(userId).size() - 1)).block();
                        userCanWrite.put(userId, true);
                    }
                }

                //Check if the game is over
                if(areStoriesComplete()) {
                    this.gameOver = true;
                    sendFinalStories();
                }
            }
        }
    }

    private Long getPreviousUserId(Long userId) {
        int index = participants.indexOf(userId);
        int newIndex = index - 1;
        if(index == 0)
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
        for(Long userId : participants) {
            Long currentUser = userId;
            List<Pair<Long, String>> currentStory = new ArrayList<>();
            for(int i = 0; i < maxRounds; i++) {
                currentStory.add(new Pair<>(currentUser, stories.get(currentUser).get(i)));
                currentUser = getNextUserId(currentUser);
            }

            StringBuilder storyBuilder = new StringBuilder();
            for(Pair<Long, String> storyPart : currentStory) {
                storyBuilder.append("<@").append(storyPart.getKey()).append("> wrote:").append("\n");
                storyBuilder.append(storyPart.getValue()).append("\n");
            }
        }

        this.updateLobbyMessage();

        this.onDelete.run();
    }
}
