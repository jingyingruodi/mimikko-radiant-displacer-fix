package data.scripts.mimikko_radiant_displacer_fix;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

/**
 * 第三层（{@code aiScript}）：仅在引擎确实驱动系统 AI 时才会被调用，
 * 逻辑同样委托给 {@link DisplacerDriver}，保证与另外两层行为一致。
 */
public class MimikkoModularDisplacerAI implements ShipSystemAIScript {

    private ShipAPI ship;
    private ShipSystemAPI system;
    private CombatEngineAPI engine;
    private float aliveTimer = 1f;

    public MimikkoModularDisplacerAI() {
    }

    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.engine = engine;
    }

    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        aliveTimer -= amount;
        if (aliveTimer <= 0f) {
            aliveTimer = 5f;
            Global.getLogger(MimikkoModularDisplacerAI.class).info("[displacerAI] alive: ship="
                    + (ship == null ? "NULL" : ship.getId())
                    + " system=" + (system == null ? "NULL" : system.getId())
                    + " hasEngine=" + (engine != null));
        }
        if (ship == null || system == null || engine == null) return;
        DisplacerDriver.drive(ship, engine, amount);
    }
}
