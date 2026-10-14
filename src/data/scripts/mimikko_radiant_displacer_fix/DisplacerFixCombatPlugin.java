package data.scripts.mimikko_radiant_displacer_fix;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/**
 * 每帧战斗插件：只负责"找到该管的船"，判定与激活统一交给 {@link DisplacerDriver}。
 *
 * <p>关键点：模块化舰船的**核心舰不在 {@link CombatEngineAPI#getShips()} 里**，
 * 必须通过模块的 {@link ShipAPI#getParentStation()} 反查；且战斗实体上的系统
 * 实测仍是原版 {@code displacer}，所以驱动层同时接受原版与自定义闪现系统。
 */
public class DisplacerFixCombatPlugin extends BaseEveryFrameCombatPlugin {

    public static final String CORE_HULL = "mimikko_kasutariri_radiant_1";

    private CombatEngineAPI engine;
    private int rosterLogs = 0;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null) return;
        if (engine.isPaused()) return;

        try {
            Set<ShipAPI> candidates = collect();
            if (candidates.isEmpty()) return;

            if (rosterLogs < 2) {
                rosterLogs++;
                StringBuilder sb = new StringBuilder();
                for (ShipAPI s : candidates) {
                    if (s == null) continue;
                    sb.append(hullOf(s)).append('|').append(sysOf(s)).append('|')
                      .append(s.isAlive() ? "A" : "d").append("  ");
                }
                log().info("[displacerFix] roster(" + candidates.size() + "): " + sb);
            }

            for (ShipAPI s : candidates) {
                if (s == null || !s.isAlive()) continue;
                if (!isCore(s)) continue;              // 模块自身无系统，不处理
                DisplacerDriver.drive(s, engine, amount);
            }
        } catch (Throwable t) {
            log().error("[displacerFix] advance failed", t);
        }
    }

    // ------------------------------------------------------------------

    /** 候选集 = getShips() 里的船 + 这些船的 parentStation（模块化舰的核心舰）。 */
    private Set<ShipAPI> collect() {
        Set<ShipAPI> out = new LinkedHashSet<ShipAPI>();
        List<ShipAPI> ships = engine.getShips();
        if (ships == null) return out;
        for (ShipAPI s : ships) {
            if (s == null) continue;
            out.add(s);
            ShipAPI parent = s.getParentStation();
            if (parent != null) out.add(parent);
        }
        return out;
    }

    private boolean isCore(ShipAPI ship) {
        if (CORE_HULL.equals(hullOf(ship))) return true;
        ShipSystemAPI sys = ship.getSystem();
        return sys != null && DisplacerDriver.OUR_SYSTEM.equals(sys.getId());
    }

    private String hullOf(ShipAPI s) {
        try {
            return s.getHullSpec() == null ? "?" : s.getHullSpec().getHullId();
        } catch (Throwable t) {
            return "err";
        }
    }

    private String sysOf(ShipAPI s) {
        try {
            return s.getSystem() == null ? "NULL" : s.getSystem().getId();
        } catch (Throwable t) {
            return "err";
        }
    }

    private Logger log() {
        return Global.getLogger(DisplacerFixCombatPlugin.class);
    }
}
