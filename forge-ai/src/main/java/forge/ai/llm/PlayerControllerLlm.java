package forge.ai.llm;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Scaffold for a harness-controlled player. Other choices use the built-in AI
 * until their harness adapters are implemented.
 */
public class PlayerControllerLlm extends PlayerControllerAi {
    public PlayerControllerLlm(Game game, Player player, LobbyPlayer lobbyPlayer) {
        super(game, player, lobbyPlayer);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // Passing keeps the game moving while no harness is connected.
        return null;
    }
}
