package forge.ai;

import java.util.Set;

import forge.LobbyPlayer;
import forge.ai.llm.PlayerControllerLlm;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import org.tinylog.Logger;

public class LobbyPlayerAi extends LobbyPlayer implements IGameEntitiesFactory {

    private String aiProfile = "";
    private boolean rotateProfileEachGame;
    private AIOption option;
    private final AiEngine engine;

    public LobbyPlayerAi(String name, Set<AIOption> options) {
        this(name, options, AiEngine.FORGE);
    }

    public LobbyPlayerAi(String name, Set<AIOption> options, AiEngine engine) {
        super(name);
        this.engine = engine == null ? AiEngine.FORGE : engine;
        if (options != null && !options.isEmpty()) {
            option = options.iterator().next();
        }
    }

    public void setAiProfile(String profileName) {
        Logger.debug("[AI Preferences] " + name + " using profile " + profileName);
        aiProfile = profileName;
    }
    public String getAiProfile() {
        return aiProfile;
    }

    public void setRotateProfileEachGame(boolean rotateProfileEachGame) {
        this.rotateProfileEachGame = rotateProfileEachGame;
    }

    private PlayerControllerAi createControllerFor(Player ai) {
        if (engine == AiEngine.LLM) {
            return new PlayerControllerLlm(ai.getGame(), ai, this);
        }
        PlayerControllerAi result = new PlayerControllerAi(ai.getGame(), ai, this);
        result.getAi().setUseSimulation(option);
        return result;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(createControllerFor(ai));

        if (rotateProfileEachGame) {
            setAiProfile(AiProfileUtil.getRandomProfile());
        }
        return ai;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { /* Local AI is deaf. */ }
}
