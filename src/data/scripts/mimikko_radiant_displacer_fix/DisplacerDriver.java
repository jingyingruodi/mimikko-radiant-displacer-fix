package data.scripts.mimikko_radiant_displacer_fix;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;

/**
 * 闪现驱动：**按原版 {@code voidsuper} 的判定结构复刻**，唯一的改动是
 * "落点净空判定跳过本舰自身模块"（原版未跳过，导致模块化舰船的 AI 永远用不出闪现）。
 *
 * <h3>原版结构（由字节码还原）</h3>
 * <pre>
 * 每 0.25~0.5s：
 *   系统激活中 / 冷却中 / 无弹药            → 返回
 *   落点 = 本体位置 + range × 行驶方向
 *   if (!Object(落点, 目标, null)) 返回      // 净空判定，所有路径共用这道闸门
 *
 *   // ① 非交战 + 距"命令目的地"很远（>range+500）+ 威胁小
 *   if (loc16 && (无目的地 || 距目的地 > range+500) && loc14 && loc11) → 用
 *   // ② 正在撤退（期望航向有效 + loc14 + loc11）
 *   if (ship.isRetreating() && 期望航向有效 && loc14 && loc11)         → 用
 *   // ③ 危险规避（导弹/碰撞/护盾覆盖不到）与 ④ 目标过载/排热时突进 等后续路径
 * </pre>
 * 其中：<br>
 * {@code loc14} = 系统 {@code range > randomRange + 200}（displacer 恒为真）；<br>
 * {@code loc11} = |移动AI期望航向 − 当前运动方向| &lt; 20°（"已经朝那个方向在走"）。
 *
 * <h3>无法复刻的部分（公开 API 拿不到的内部信号）</h3>
 * <ul>
 *   <li>移动 AI 的"期望航向"：用 {@code MOVEMENT_DEST} 的目的地方向代替（撤退时用当前行驶方向）。</li>
 *   <li>威胁评估 {@code D}、碰撞追踪 {@code ooOO}、{@code BasicShipAI.isEngaged()}：
 *       分别用"是否有活目标"、"落点净空"、"来袭导弹是否存在"作近似。</li>
 *   <li>护盾覆盖判定（{@code Ship.getShield()} 非公开）：③只按"有来袭导弹"触发。</li>
 * </ul>
 */
public final class DisplacerDriver {

    public static final String OUR_SYSTEM = "mimikko_kasutariri_radiant_displacer";
    public static final String VANILLA_SYSTEM = "displacer";
    /** 路线 A 的系统：闪现数值 + 原版 PHASE_TELEPORTER_2 AI。这里只观察记录，不介入激活。 */
    public static final String BLINK_SYSTEM = "mimikko_kasutariri_radiant_blink";

    /** 原版 loc11 的朝向一致性阈值（度）。 */
    private static final float HEADING_ARC = 20f;
    /** 原版 loc16/loc15：距目的地需超过 系统射程 + 该值。 */
    private static final float DEST_MARGIN = 500f;
    /** 原版落点净空余量。 */
    private static final float CLEARANCE = 50f;
    /** 原版友军落点前方锥角/额外余量。 */
    private static final float FRIENDLY_ARC = 30f;
    private static final float FRIENDLY_CLEARANCE = 500f;
    /** 评估间隔，对齐原版 IntervalTracker(0.25f, 0.5f)。 */
    private static final float INTERVAL_MIN = 0.25f;
    private static final float INTERVAL_MAX = 0.5f;
    /** 危险规避：来袭导弹进入该距离视为需要位移。 */
    private static final float MISSILE_DANGER_RANGE = 700f;
    /** 状态日志限流（秒）。 */
    private static final float STATUS_LOG_INTERVAL = 6f;
    private static final float FALLBACK_RANGE = 300f;

    private static final Map<String, Float> INTERVAL = new HashMap<String, Float>();
    private static final Map<String, Float> LOG_TIMER = new HashMap<String, Float>();
    private static final java.util.Random RAND = new java.util.Random();

    private DisplacerDriver() {
    }

    public static void drive(ShipAPI ship, CombatEngineAPI engine, float amount) {
        if (ship == null || engine == null || !ship.isAlive()) return;

        ShipSystemAPI sys = ship.getSystem();
        if (sys == null) return;

        // 路线 A 的系统由引擎自带的 PHASE_TELEPORTER_2 AI 驱动；此处仅记录状态，绝不激活。
        if (BLINK_SYSTEM.equals(sys.getId())) {
            Float lt0 = LOG_TIMER.get(ship.getId());
            float left0 = (lt0 == null ? 0f : lt0) - amount;
            boolean doLog0 = left0 <= 0f;
            LOG_TIMER.put(ship.getId(), doLog0 ? STATUS_LOG_INTERVAL : left0);
            if (doLog0) log().info("[blink] 原版 AI 驱动中: " + status(ship, sys));
            return;
        }

        if (!OUR_SYSTEM.equals(sys.getId()) && !VANILLA_SYSTEM.equals(sys.getId())) return;

        // 玩家手操（未开自动驾驶）不干预；自动驾驶 = AI 驾驶，与原版语义一致
        if (ship == engine.getPlayerShip() && !isAutopilotOn(engine)) return;

        String id = ship.getId();

        // ---- 状态日志（限流；阈值用原版的评估间隔）----
        float lt = dec(LOG_TIMER, id, amount);
        boolean doLog = lt <= 0f;
        if (doLog) LOG_TIMER.put(id, STATUS_LOG_INTERVAL);

        // ---- 原版：每 0.25~0.5 秒评估一次 ----
        float iv = dec(INTERVAL, id, amount);
        if (iv > 0f) {
            if (doLog) log().info("[displacerFix] (interval " + f(iv) + ") " + status(ship, sys));
            return;
        }
        INTERVAL.put(id, INTERVAL_MIN + RAND.nextFloat() * (INTERVAL_MAX - INTERVAL_MIN));

        // ---- 原版早期门控 ----
        String veto = null;
        if (sys.isActive()) veto = "system active";
        else if (sys.isCoolingDown()) veto = "cooling down";
        else if (sys.getCooldownRemaining() > 0f) veto = "cooldown=" + f(sys.getCooldownRemaining());
        else if (sys.isOutOfAmmo()) veto = "out of ammo";
        else if (!sys.canBeActivated()) veto = "cannot activate";
        else if (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting()) {
            veto = "overloaded/venting";
        }

        float range = getRange(sys, ship);
        Vector2f dest = getDisplacerDestination(ship, range);

        // ---- 原版闸门：落点不可行 → 直接返回（唯一修改：跳过自身模块）----
        if (veto == null) {
            String blocker = findBlocker(ship, engine, dest);
            if (blocker != null) veto = "destination blocked by " + blocker;
        }

        // ---- loc14：系统 range > randomRange + 200（displacer 恒真）----
        boolean loc14 = true;
        ShipSystemSpecAPI spec = sys.getSpecAPI();
        if (spec != null) {
            loc14 = spec.getRange(ship.getMutableStats()) > spec.getRandomRange() + 200f;
        }

        String why = null;
        if (veto == null) {
            ShipwideAIFlags flags = ship.getAIFlags();
            ShipAPI target = ship.getShipTarget();
            Vector2f moveDest = customVec(flags, AIFlags.MOVEMENT_DEST);
            float shipToDest = (moveDest == null) ? -1f : distance(ship.getLocation(), moveDest);
            boolean engaged = target != null && target.isAlive() && !target.isHulk();
            boolean loc11 = headingAligned(ship, moveDest);

            // ---- ① 非交战 + 距命令目的地 > range+500（原版 loc16 的旅行/赶路路径）----
            if (!engaged && moveDest != null && shipToDest > range + DEST_MARGIN && loc14 && loc11) {
                why = "非交战赶路";
            }
            // ---- ② 撤退（原版保留路径）----
            else if (ship.isRetreating() && loc14 && headingAligned2(ship)) {
                why = "撤退";
            }
            // ---- ③ 危险规避（原版导弹/碰撞路径的近似：有来袭导弹靠近）----
            else if (loc14 && incomingMissileNear(ship, engine)) {
                why = "规避导弹";
            }
            // ---- ④ 目标过载/排热较久时突进（原版后续路径之一）----
            else if (engaged && loc14 && target.getFluxTracker() != null
                    && target.getFluxTracker().isOverloadedOrVenting()
                    && (target.getFluxTracker().getOverloadTimeRemaining() > 5f
                            || target.getFluxTracker().getTimeToVent() > 5f)) {
                why = "目标失能突进";
            }
            if (why == null) veto = "no vanilla condition met";
        }

        if (why == null) {
            if (doLog) log().info("[displacerFix] skip: " + veto + " " + status(ship, sys));
            return;
        }

        ship.useSystem();
        log().info("[displacerFix] USE SYSTEM (" + why + ") hull=" + hullId(ship)
                + " ammo=" + sys.getAmmo() + " state=" + sys.getState());
    }

    // ------------------------------------------------------------------

    /** 原版 loc11：移动 AI 期望航向 与 当前运动方向 夹角 < 20°（用目的地方向近似期望航向）。 */
    private static boolean headingAligned(ShipAPI ship, Vector2f moveDest) {
        if (moveDest == null) return false;
        float desired = angleTo(ship.getLocation(), moveDest);
        return angleDiff(desired, travelDirection(ship)) < HEADING_ARC;
    }

    /** 撤退时用"当前运动方向"作为期望航向（原版此处用的是移动 AI 的期望航向）。 */
    private static boolean headingAligned2(ShipAPI ship) {
        Vector2f v = ship.getVelocity();
        if (v == null || v.length() <= 5f) return true;   // 原地/低速时不做朝向约束
        float velDir = (float) Math.toDegrees(Math.atan2(v.y, v.x));
        return angleDiff(velDir, ship.getFacing()) < 60f;
    }

    private static boolean incomingMissileNear(ShipAPI ship, CombatEngineAPI engine) {
        List<MissileAPI> missiles = engine.getMissiles();
        if (missiles == null) return false;
        Vector2f loc = ship.getLocation();
        if (loc == null) return false;
        for (MissileAPI m : missiles) {
            if (m == null || m.isFizzling() || m.getLocation() == null) continue;
            if (m.getOwner() == ship.getOwner()) continue;
            float d = distance(loc, m.getLocation());
            if (d < MISSILE_DANGER_RANGE + m.getCollisionRadius()) return true;
        }
        return false;
    }

    private static float dec(Map<String, Float> map, String id, float amount) {
        Float v = map.get(id);
        float nv = (v == null ? 0f : v) - amount;
        map.put(id, nv);
        return nv;
    }

    /**
     * 玩家舰是否处于"自动驾驶"（= 由 AI 驾驶）。
     *
     * <p>注意：{@code CombatEngineAPI.isUIAutopilotOn()} **返回的是反面**
     * （其实现为 {@code return !ui.isAutopilotOn();}，手动飞行时为 true），
     * 早期版本误用它导致"手动飞行时反而自动放闪现"。这里只用
     * {@link CombatUIAPI#isAutopilotOn()}（原版 {@code NeuralLinkScript} 也是读它）。
     */
    private static boolean isAutopilotOn(CombatEngineAPI engine) {
        try {
            CombatUIAPI ui = engine.getCombatUI();
            return ui != null && ui.isAutopilotOn();
        } catch (Throwable t) {
            return false;
        }
    }

    private static float getRange(ShipSystemAPI sys, ShipAPI ship) {
        ShipSystemSpecAPI spec = sys.getSpecAPI();
        if (spec != null) {
            float r = spec.getRange(ship.getMutableStats());
            if (r > 0f) return r;
        }
        return FALLBACK_RANGE;
    }

    private static Vector2f getDisplacerDestination(ShipAPI ship, float range) {
        Vector2f loc = ship.getLocation();
        if (loc == null) return null;
        double rad = Math.toRadians(travelDirection(ship));
        return new Vector2f(loc.x + range * (float) Math.cos(rad), loc.y + range * (float) Math.sin(rad));
    }

    private static float travelDirection(ShipAPI ship) {
        Vector2f v = ship.getVelocity();
        if (v != null && v.length() > 5f) return (float) Math.toDegrees(Math.atan2(v.y, v.x));
        return ship.getFacing();
    }

    /**
     * 落点净空判定：与 `voidsuper.Object(...)` 的舰船部分逐条一致
     * （跳过战机/穿梭舱；落点距任一舰船 &lt; 该船半径 + 本船半径 + 50 → 否决），
     * **唯一差异**：**同属主（友军，含本舰自身模块）的舰船不再阻塞落点**。
     * 原版对友军额外有一条"友军挡在落点前方 30°、距离 &lt; +500 也否决"的规则，
     * 该规则随"忽略友军"一并取消。
     *
     * <p>另外补上原版 `Object()` 的导弹危害判定：落点附近有**敌方**导弹也否决
     * （原版还比较护盾覆盖与碰撞体，公开 API 拿不到，此处按"距离"近似）。
     */
    private static String findBlocker(ShipAPI ship, CombatEngineAPI engine, Vector2f dest) {
        if (dest == null) return "null destination";
        List<ShipAPI> ships = engine.getShips();
        if (ships == null) return null;

        float myRadius = ship.getCollisionRadius();
        float missileClearance = myRadius + 300f;   // 原版：shipRadius + max(300, randomRange+100)

        for (ShipAPI s : ships) {
            if (s == null || s == ship) continue;
            if (s.isFighter()) continue;
            if (s.isShuttlePod()) continue;
            // ★★★ 与 voidsuper 的唯一差异：同属主舰船（含本舰模块）一律不阻塞落点 ★★★
            if (ship.getOwner() == s.getOwner()) continue;

            Vector2f loc = s.getLocation();
            if (loc == null) continue;
            float d = distance(dest, loc);
            if (d < s.getCollisionRadius() + myRadius + CLEARANCE) {
                return s.getId() + "(d=" + (int) d + ")";
            }
        }

        List<MissileAPI> missiles = engine.getMissiles();
        if (missiles != null) {
            for (MissileAPI m : missiles) {
                if (m == null || m.isFizzling() || m.getLocation() == null) continue;
                if (m.getOwner() == ship.getOriginalOwner()) continue;
                float d = distance(dest, m.getLocation());
                if (d < m.getCollisionRadius() + missileClearance) return "missile(d=" + (int) d + ")";
            }
        }
        return null;
    }

    private static String status(ShipAPI ship, ShipSystemAPI sys) {
        Vector2f vel = ship.getVelocity();
        ShipAPI t = ship.getShipTarget();
        Vector2f md = customVec(ship.getAIFlags(), AIFlags.MOVEMENT_DEST);
        return "hull=" + hullId(ship)
                + " ammo=" + sys.getAmmo() + "/" + sys.getMaxAmmo()
                + " state=" + sys.getState()
                + " speed=" + (vel == null ? 0 : (int) vel.length())
                + " retreating=" + ship.isRetreating()
                + " target=" + (t == null ? "null"
                        : t.getId() + "(d=" + (int) distance(ship.getLocation(), t.getLocation()) + ")")
                + " moveDest=" + (md == null ? "null" : String.valueOf((int) distance(ship.getLocation(), md)));
    }

    private static Vector2f customVec(ShipwideAIFlags flags, AIFlags f) {
        try {
            Object o = flags == null ? null : flags.getCustom(f);
            return (o instanceof Vector2f) ? (Vector2f) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hullId(ShipAPI ship) {
        try {
            return ship.getHullSpec() == null ? "?" : ship.getHullSpec().getHullId();
        } catch (Throwable t) {
            return "err";
        }
    }

    private static Logger log() {
        return Global.getLogger(DisplacerDriver.class);
    }

    private static String f(float v) {
        return String.valueOf(Math.round(v * 100f) / 100f);
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float angleTo(Vector2f from, Vector2f to) {
        return (float) Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
    }

    private static float angleDiff(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    private static boolean isInFront(float facing, float arc, Vector2f from, Vector2f to) {
        return angleDiff(facing, angleTo(from, to)) <= arc * 0.5f;
    }
}
