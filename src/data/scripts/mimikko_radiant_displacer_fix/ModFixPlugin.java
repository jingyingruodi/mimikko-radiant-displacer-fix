package data.scripts.mimikko_radiant_displacer_fix;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

/**
 * 补丁入口。
 *
 * <ol>
 *   <li>{@link #onApplicationLoad()}：把星之迹的战术系统指向多模块兼容版闪现
 *       （公开 API {@code setShipSystemId}，与 mod 加载顺序无关；隐藏船插保留但未挂载）。</li>
 *   <li>{@link #pickShipAI}：引擎给每艘船创建 AI 时都会问一次 mod 插件——
 *       借这个必然发生的时机注册"每帧战斗插件"（{@link DisplacerFixCombatPlugin}），
 *       然后返回 {@code null}，**不接管舰船 AI**，保持原版行为。</li>
 * </ol>
 */
public class ModFixPlugin extends BaseModPlugin {

    public static final String HULL_ID = "mimikko_kasutariri_radiant_1";
    /**
     * 路线 B（当前方案）：**原版闪现本体**（`type: DISPLACER`，range 300 / 3 充能……与 vanilla 一致），
     * 只是把 `aiType` 换成 `CUSTOM` 并挂上我们的 `aiScript`——该 AI 按 `voidsuper` 逐条复刻，
     * **唯一差异是落点净空忽略友军（含本舰自身模块）**。
     */
    public static final String SYSTEM_ID = "mimikko_kasutariri_radiant_displacer";
    /** 隐藏船插（已不挂载，保留以备回退）。 */
    public static final String HULLMOD_ID = "mimikko_radiant_displacer_fix_hullmod";

    private static CombatEngineAPI registeredEngine = null;

    @Override
    public void onApplicationLoad() throws Exception {
        applyFix("onApplicationLoad");
    }

    @Override
    public void onDevModeF8Reload() {
        applyFix("onDevModeF8Reload");
    }

    /**
     * 不接管任何舰船 AI（返回 null），只借这个必被调用的钩子把每帧战斗插件装进当前战斗。
     */
    @Override
    public PluginPick<ShipAIPlugin> pickShipAI(FleetMemberAPI member, ShipAPI ship) {
        try {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine != null && engine != registeredEngine) {
                registeredEngine = engine;
                engine.addPlugin(new DisplacerFixCombatPlugin());
                logger().info("[pickShipAI] displacer combat plugin registered");
            }
        } catch (Throwable t) {
            logger().error("[pickShipAI] failed to register combat plugin", t);
        }
        return null;
    }

    private void applyFix(String where) {
        try {
            ShipHullSpecAPI spec = Global.getSettings().getHullSpec(HULL_ID);
            if (spec == null) {
                logger().warn("[" + where + "] hull not found: " + HULL_ID);
                return;
            }
            String before = spec.getShipSystemId();
            spec.setShipSystemId(SYSTEM_ID);
            logger().info("[" + where + "] " + HULL_ID
                    + " ship system: " + before + " -> " + spec.getShipSystemId()
                    + " (原版闪现本体 + 兼容多模块的 AI)");
        } catch (Throwable t) {
            logger().error("[" + where + "] failed to apply fix", t);
        }
    }

    private org.apache.log4j.Logger logger() {
        return Global.getLogger(ModFixPlugin.class);
    }
}
