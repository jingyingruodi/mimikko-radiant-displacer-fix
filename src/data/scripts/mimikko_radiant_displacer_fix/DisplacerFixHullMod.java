package data.scripts.mimikko_radiant_displacer_fix;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 隐藏船插（第二层保险）：每帧把舰船交给 {@link DisplacerDriver} 统一处理。
 *
 * <p>船插的 {@code advanceInCombat} 由引擎对每艘船每帧调用，与"谁在开这条船"无关；
 * 判定/限流/激活全部在 {@link DisplacerDriver} 内，因此与战斗插件、{@code aiScript}
 * 三条机制行为完全一致（重复调用由激活间隔挡住）。
 */
public class DisplacerFixHullMod extends BaseHullMod {

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null || !ship.isAlive()) return;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) return;
        try {
            DisplacerDriver.drive(ship, engine, amount);
        } catch (Throwable t) {
            Global.getLogger(DisplacerFixHullMod.class).error("[displacerFix] hullmod drive failed", t);
        }
    }
}
