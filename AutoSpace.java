/*
 * AutoSpace - Raven B4 script (Minecraft 1.8.9)
 *
 * Ping-adaptive spacing + sprint resets. You hold W into the fight; the
 * module brakes your approach before you walk into their hitbox, keeps
 * your true distance inside the band you set (defaults 3.4-4.0) and times
 * the sprint resets against THEIR ping, the way strong combo players do it:
 *
 *   low-ping target  (0-45ms):  w-taps + s-tap escapes, tight band
 *   mid-ping target  (45-95ms): w-taps mixed with blockhits, wider band
 *   high-ping target (95ms+):   blockhits during their delayed swings plus
 *                               longer w-tap drops, widest band
 *
 * How it works without lag or packet spam:
 *  - target ping is read from the vanilla tab-list latency field
 *    (NetworkPlayerInfo.responseTime) - the server already sends that, we
 *    send nothing to measure it
 *  - movement control rides Raven's POST_PLAYER_INPUT hook: it fires after
 *    the keyboard is read and before the movement applies, so we can
 *    throttle/zero/backpedal the W you are physically holding, per tick
 *  - sprint resets flip the real sprint flag (client.setSprinting), which
 *    Raven diffs against the server state and sends as exactly ONE C0B per
 *    state change - the same two packets a human w-tap sends (STOP + START)
 *  - blockhits hold the real use-key state: one C08 on engage, one release
 *    packet on drop - identical to a human holding right click
 *  - every reset passes one shared cooldown gate scaled by their ping, so
 *    the tap cadence stays human no matter how fast the hits land
 *  - when YOU take the hit, a KB-fight window holds your W straight into
 *    the knockback; the brake only cancels momentum you build yourself,
 *    so their charge-in meets your W instead of your freeze
 *  - no timers, no speed changes, no fake positions: the C03 stream keeps
 *    its normal shape, the speed you show the server is always real
 *
 * Anti-detection profile: every packet is a vanilla packet you would send
 * manually. The only new traffic versus playing raw is the C0B/C08 of the
 * taps themselves.
 */

String scriptName = "AutoSpace";

String[] STYLES = { "Auto", "W-Tap", "S-Tap", "Blockhit" };

// LWJGL scan codes
int KEY_W = 17;

Entity target;
long lastSeenAt;
double closeBps;
double myCloseBps, theirCloseBps;
Vec3 myPrevPos, tPrevPos;
long myPrevAt, tPrevAt;
int targetPing;
long pingReadAt;
double myPing;
long myPingReadAt;
int prevHurt;
int prevMyHurt;
boolean dropping;
long dropUntil;
boolean escaping;
long escapeUntil;
boolean blocking, weHoldBlock;
String blockKey;
boolean blockKeyResolved;
long blockHoldUntil, blockCoolUntil;
long lastResetAt, sprintResumeAt;
long lastSprintEngageAt;
long lastHit;
long myLastHurtAt, kbFightUntil;
boolean meetActive;
String stateName;
double hudDist, hudMin, hudMax;
String hudState;
long lastSayAt;

void onLoad() {
    modules.registerDescription("Ping-adaptive spacing + sprint resets while you hold W");
    modules.registerSlider("Style", "", 1, STYLES);
    modules.registerSlider("Min Spacing", " blocks", 2.75, 1.8, 6.0, 0.05);
    modules.registerSlider("Max Spacing", " blocks", 2.0, 2.0, 8.0, 0.05);
    modules.registerSlider("Brake Carry", "s", 0.22, 0.0, 0.5, 0.01);
    modules.registerSlider("FOV", "\u00B0", 90, 10, 90, 1);
    modules.registerSlider("Range", " blocks", 7.0, 2.5, 7.0, 0.1);
    modules.registerSlider("W-Tap Drop", "ms", 90, 0, 300, 10);
    modules.registerSlider("KB Fight Hold", "ms", 400, 0, 1000, 10);
    modules.registerButton("Ping Adaptive", false);
    modules.registerButton("S-Tap Escape", false);
    modules.registerButton("Blockhit", false);
    modules.registerButton("Only While Attacking", false);
    modules.registerButton("Guard Brake", false);
    modules.registerButton("Render Range", false);
    modules.registerButton("HUD", false);
    modules.registerButton("Debug Prints", false);
}

void onEnable() {
    resetState(true);
    blockKeyResolved = false;
}

void onDisable() {
    if (blocking && weHoldBlock && blockKey != null) {
        try { keybinds.setPressed(blockKey, false); } catch (Throwable t) { }
    }
    resetState(true);
}

void onWorldJoin() {
    resetState(true);
}

void say(Object s) {
    if (!modules.getButton("AutoSpace", "Debug Prints")) return;
    long now = client.time();
    if (now - lastSayAt < 250) return;   // chat spam is its own stutter
    lastSayAt = now;
    client.print(s);
}

// sprint engage throttle: re-sprinting after every vanilla sprint-stop used
// to flood C0B pairs at hit rate - cap OUR re-engages to a jittered human
// cadence. vanilla's own stops are untouched
boolean sprintEngageOk(long now) {
    long gap = 220L + (long) util.randomDouble(0.0D, 140.0D);
    return now - lastSprintEngageAt >= gap;
}

void resetState(boolean full) {
    if (full) {
        target = null;
        prevHurt = 0;
        prevMyHurt = 0;
        targetPing = 0;
        pingReadAt = 0;
    }
    if (blocking && weHoldBlock && blockKey != null) {
        try { keybinds.setPressed(blockKey, false); } catch (Throwable t) { }
    }
    blocking = false;
    weHoldBlock = false;
    dropping = false;
    escaping = false;
    closeBps = 0.0D;
    myCloseBps = 0.0D;
    theirCloseBps = 0.0D;
    myPrevPos = null;
    tPrevPos = null;
    myPrevAt = 0L;
    tPrevAt = 0L;
    kbFightUntil = 0L;
    lastResetAt = 0L;
    sprintResumeAt = 0L;
    lastSprintEngageAt = 0L;
    meetActive = false;
    blockHoldUntil = 0L;
    blockCoolUntil = 0L;
    stateName = "";
    hudState = "";
}

Character firstColorCode(String s) {
    if (s == null) return null;
    for (int i = 0; i < s.length() - 1; i++) {
        char c = s.charAt(i);
        if (c == '\u00A7' || c == '&') {
            char n = s.charAt(i + 1);
            if ("0123456789abcdefABCDEF".indexOf(n) >= 0) return Character.valueOf(Character.toLowerCase(n));
        }
    }
    return null;
}

boolean sameTeam(Entity me, Entity e) {
    // Hypixel-style servers color names per team; vanilla team membership is
    // per-player there, so color codes are the real teammate signal
    Character mine = firstColorCode(me.getDisplayName());
    Character theirs = firstColorCode(e.getDisplayName());
    if (mine != null && theirs != null) {
        boolean defaultC = (mine.charValue() == 'f' || mine.charValue() == '7')
                && (theirs.charValue() == 'f' || theirs.charValue() == '7');
        if (!defaultC) return mine.charValue() == theirs.charValue();
    }
    String myName = me.getName();
    String theirName = e.getName();
    if (myName == null || theirName == null) return false;
    Map teams = world.getTeams();
    if (teams == null) return false;
    for (Object key : teams.keySet()) {
        Object members = teams.get(key);
        if (members instanceof List && ((List) members).contains(myName) && ((List) members).contains(theirName)) return true;
    }
    return false;
}

Entity findTarget(Entity me, double range) {
    Vec3 myPos = me.getPosition();
    double fov = modules.getSlider("AutoSpace", "FOV");
    Entity best = null;
    double bestScore = 1.0e9;
    List players = world.getPlayerEntities();
    if (players == null) return null;
    for (Object o : players) {
        Entity e = (Entity) o;
        if (e == me || e.isUser || e.entityId == me.entityId) continue;
        if (!e.isLiving || e.isDead() || e.isInvisible()) continue;
        if (client.isFriend(e.getName())) continue;
        if (sameTeam(me, e)) continue;

        Vec3 p = e.getPosition();
        if (Math.abs(p.y - myPos.y) > 2.2D) continue;   // not in melee plane
        Vec3 mid = p.offset(0, e.getHeight() * 0.5D, 0);
        double dx = mid.x - myPos.x, dy = mid.y - myPos.y, dz = mid.z - myPos.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > range * range) continue;

        double rad = Math.PI / 180D;
        float lyaw = me.getYaw(), lpit = me.getPitch();
        double lx = -Math.sin(lyaw * rad) * Math.cos(lpit * rad);
        double lyy = -Math.sin(lpit * rad);
        double lz = Math.cos(lyaw * rad) * Math.cos(lpit * rad);
        double len = Math.sqrt(d2);
        if (len < 1e-6) continue;
        double dot = (lx * dx + lyy * dy + lz * dz) / len;
        if (dot > 1D) dot = 1D;
        if (dot < -1D) dot = -1D;
        double ang = Math.toDegrees(Math.acos(dot));
        if (ang > fov) continue;
        if (!client.canSeeEntity(e)) continue;

        double score = ang * 2.0D + len;   // most centered, then closest
        if (score < bestScore) {
            bestScore = score;
            best = e;
        }
    }
    return best;
}

void readPing(Entity me) {
    long now = client.time();
    if (now - pingReadAt < 400 && targetPing >= 0) return;
    pingReadAt = now;
    NetworkPlayer np = target.getNetworkPlayer();
    targetPing = (np != null) ? np.getPing() : 0;
    if (now - myPingReadAt > 1000 || myPing < 0) {
        myPingReadAt = now;
        myPing = readSelfPing(me);
    }
}

double readSelfPing(Entity me) {
    String myId = me.getUUID();
    if (myId == null) return 0;
    List nps = world.getNetworkPlayers();
    if (nps == null) return 0;
    for (Object o : nps) {
        NetworkPlayer np = (NetworkPlayer) o;
        if (np != null && myId.equals(np.getUUID())) return (double) np.getPing();
    }
    return 0;
}

void onPreUpdate() {
    stateName = "";
    Entity me = client.getPlayer();
    if (me == null || me.isDead() || (client.getScreen() != null && !client.getScreen().isEmpty())) {
        resetState(false);
        return;
    }
    Entity t = findTarget(me, modules.getSlider("AutoSpace", "Range"));
    long nowP = client.time();
    if (t != null) {
        if (t != target) {
            // new opponent: fresh bookkeeping so closing-rate math starts clean
            prevHurt = 0;
            prevMyHurt = 0;
            kbFightUntil = 0L;
            closeBps = 0.0D;
            myCloseBps = 0.0D;
            theirCloseBps = 0.0D;
            myPrevPos = null;
            tPrevPos = null;
            myPrevAt = 0L;
            tPrevAt = 0L;
            dropping = false;
            escaping = false;
            meetActive = false;
            if (blocking && weHoldBlock) releaseBlock();
        }
        target = t;
        lastSeenAt = nowP;
    } else if (target != null && !target.isDead()
            && hDist(me, target) < modules.getSlider("AutoSpace", "Range") + 2.0D
            && nowP - lastSeenAt < 500L) {
        // aim flicker / strafe look-spam: hold the last lock for a short
        // grace so spacing cannot be walked through by camera jitter
        say("target held by memory");
    } else {
        target = null;
        resetState(false);
        return;
    }
    if (target.isDead()) {
        resetState(true);
        return;
    }

    // our own hurt: a fresh damage event opens the KB fight window
    int myHurt = me.getHurtTime();
    if (myHurt > prevMyHurt && nowP - myLastHurtAt > 100) {
        myLastHurtAt = nowP;
        long kbHold = (long) modules.getSlider("AutoSpace", "KB Fight Hold");
        if (kbHold > 0 && hDist(me, target) < 4.5D) {
            kbFightUntil = nowP + kbHold;
            lastHit = nowP;   // taking hits is combat too - OWA stays live
            say("kb fight " + kbHold + "ms");
        }
    }
    prevMyHurt = myHurt;

    readPing(me);

    // our hit landed (their hurt flash started) -> schedule the sprint reset
    int hurt = target.getHurtTime();
    if (hurt > 0 && prevHurt == 0 && client.time() - lastHit > 150) {
        onOurHit();
    }
    prevHurt = hurt;

    updateBlock(me);

    if (dropping && client.time() >= dropUntil) dropping = false;
}

void onOurHit() {
    long now = client.time();
    lastHit = now;
    int dropMs = (int) modules.getSlider("AutoSpace", "W-Tap Drop");
    if (dropMs <= 0) {
        // auto cadence by their ping tier, +-10% humanized
        double jitter = 0.9D + util.randomDouble(0.0D, 0.2D);
        if (targetPing < 45) dropMs = 70;
        else if (targetPing < 95) dropMs = 110;
        else dropMs = 150;
        dropMs = (int) Math.round(dropMs * jitter);
    }
    // quiet gate: one reset per jittered gap scaled by their ping, so a fast
    // click rate cannot turn into a C0B flood
    long gap = (long) ((200.0D + targetPing * 1.5D) * (0.85D + util.randomDouble(0.0D, 0.3D)));
    if (now - lastResetAt < gap) {
        say("reset skipped, " + (gap - (now - lastResetAt)) + "ms left on gate");
        return;
    }
    lastResetAt = now;
    dropping = true;
    dropUntil = now + dropMs;
    // the reset costs exactly the drop window: re-sprint is allowed the
    // moment the drop ends, like a human re-pressing W after a tap
    sprintResumeAt = dropUntil;
    lastSprintEngageAt = now - 500L;
    say("hit -> drop " + dropMs + "ms (their ping " + targetPing + ")");
}

// ---------------------------------------------------------------- blockhit

void resolveBlockKey() {
    blockKeyResolved = true;
    blockKey = null;
    String[] cands = { "key.use", "Use Item / Place Block", "use", "item.use", "keyUse" };
    for (int i = 0; i < cands.length; i++) {
        int code;
        try {
            code = keybinds.getKeycode(cands[i]);
        } catch (Throwable t) {
            code = 0;
        }
        // vanilla reports mouse-right as -99; that is the use bind
        if (code == -99) {
            blockKey = cands[i];
            say("use bind resolved [" + cands[i] + "]");
            return;
        }
    }
    say("use bind not found - blockhit falls back to pulses");
}

long blockHold() {
    // cover their hurt-stun plus the swing that follows it
    return 450L + (long) (targetPing * 0.5D) + (long) util.randomDouble(0.0D, 80.0D);
}

long blockCool() {
    return 650L + targetPing * 2L + (long) util.randomDouble(0.0D, 120.0D);
}

void updateBlock(Entity me) {
    int style = (int) modules.getSlider("AutoSpace", "Style");
    boolean attackOnly = modules.getButton("AutoSpace", "Only While Attacking");
    boolean inCombat = !attackOnly || (client.time() - lastHit <= 700);
    boolean allowed = modules.getButton("AutoSpace", "Blockhit")
            && inCombat
            && (style == 3 || (style == 0 && targetPing >= 45));
    if (!allowed) {
        if (blocking) releaseBlock();
        return;
    }
    if (!blockKeyResolved) resolveBlockKey();

    Entity meNow = client.getPlayer();
    if (meNow == null) return;
    long now = client.time();
    double dist = hDist(meNow, target);
    double maxS = modules.getSlider("AutoSpace", "Max Spacing");

    // HOLD: keep the shield up for the whole window - exactly one C08 pair
    // per window, never a flicker. releases on window end, your manual RMB,
    // losing the sword, or the gap opening up
    if (blocking) {
        boolean drop = now >= blockHoldUntil
                || keybinds.isMouseDown(1)
                || !meNow.isHoldingSword()
                || dist > maxS + 0.6D;
        if (drop) {
            releaseBlock();
            blockCoolUntil = now + blockCool();
        }
        return;
    }

    // ENGAGE: right after one of our hits lands - they are stunned, their
    // return swing arrives at stun end, and the shield eats it. classic
    // block-after-hit rhythm
    boolean canEngage = prevHurt > 0
            && now >= blockCoolUntil
            && now - lastResetAt >= 600L + targetPing * 2L
            && meNow.isHoldingSword()
            && !meNow.isUsingItem()
            && !keybinds.isMouseDown(1)
            && dist > 1.4D && dist < maxS + 0.15D;
    if (canEngage) {
        if (blockKey != null) {
            try { keybinds.setPressed(blockKey, true); } catch (Throwable t) { }
            weHoldBlock = true;
        } else {
            // fallback: sparse vanilla right-click pulse, one C08
            keybinds.rightClick();
            weHoldBlock = false;
        }
        blocking = true;
        blockHoldUntil = now + blockHold();
        lastResetAt = now;
        say("block window " + blockHold() + "ms");
    }
}

void releaseBlock() {
    if (weHoldBlock && blockKey != null) {
        try { keybinds.setPressed(blockKey, false); } catch (Throwable t) { }
    }
    blocking = false;
    weHoldBlock = false;
}

// ------------------------------------------------------------- spacing core

double hDist(Entity a, Entity b) {
    Vec3 pa = a.getPosition();
    Vec3 pb = b.getPosition();
    double dx = pb.x - pa.x, dz = pb.z - pa.z;
    return Math.sqrt(dx * dx + dz * dz);
}

boolean inWebCell(Entity e) {
    Vec3 p = e.getPosition();
    Block a = world.getBlockAt(p.floor());
    if (a != null && a.name != null && (a.name.equalsIgnoreCase("web") || a.name.equalsIgnoreCase("cobweb"))) return true;
    Block b = world.getBlockAt(p.offset(0, 1, 0).floor());
    return b != null && b.name != null && (b.name.equalsIgnoreCase("web") || b.name.equalsIgnoreCase("cobweb"));
}

// runs after the keyboard is read, before the movement applies: we override
// the W you are physically holding, tick by tick
void onPostPlayerInput() {
    Entity me = client.getPlayer();
    if (me == null || me.isDead() || (client.getScreen() != null && !client.getScreen().isEmpty())) {
        resetState(false);
        return;
    }
    if (target == null || target.isDead()) {
        resetState(false);
        return;
    }
    if (!keybinds.isKeyDown(KEY_W)) {
        // no forward intent -> hands completely off your input
        stateName = "";
        if (blocking && weHoldBlock) releaseBlock();
        dropping = false;
        escaping = false;
        return;
    }
    // our own held block reads as "using item" - never fight our own shield
    if (me.isUsingItem() && !(blocking && weHoldBlock)) { stateName = ""; return; }
    if (inWebCell(me)) { stateName = ""; return; }

    long now = client.time();
    boolean attackOnly = modules.getButton("AutoSpace", "Only While Attacking");
    boolean inCombat = !attackOnly || (now - lastHit <= 700);
    double dist = hDist(me, target);

    // split the closing rate by WHO is closing: your W momentum vs their
    // charge-in. your input only gets braked for momentum you build
    Vec3 myPos = me.getPosition();
    Vec3 tPos = target.getPosition();
    double ddx = tPos.x - myPos.x, ddz = tPos.z - myPos.z;
    double len = Math.sqrt(ddx * ddx + ddz * ddz);
    double ux = 0.0D, uz = 0.0D;
    if (len > 1e-4) { ux = ddx / len; uz = ddz / len; }
    if (myPrevPos != null && now > myPrevAt && now - myPrevAt <= 150) {
        double inst = ((myPos.x - myPrevPos.x) * ux + (myPos.z - myPrevPos.z) * uz) * (1000.0D / (now - myPrevAt));
        myCloseBps = myCloseBps * 0.5D + inst * 0.5D;
    } else {
        myCloseBps = 0.0D;
    }
    myPrevPos = myPos;
    myPrevAt = now;
    if (tPrevPos != null && now > tPrevAt && now - tPrevAt <= 150) {
        double inst2 = ((tPos.x - tPrevPos.x) * ux + (tPos.z - tPrevPos.z) * uz) * (1000.0D / (now - tPrevAt));
        theirCloseBps = theirCloseBps * 0.5D + inst2 * 0.5D;
    } else {
        theirCloseBps = 0.0D;
    }
    tPrevPos = tPos;
    tPrevAt = now;
    if (myCloseBps > 8.0D) myCloseBps = 8.0D;
    if (myCloseBps < -8.0D) myCloseBps = -8.0D;
    if (theirCloseBps > 8.0D) theirCloseBps = 8.0D;
    if (theirCloseBps < -8.0D) theirCloseBps = -8.0D;
    closeBps = myCloseBps + theirCloseBps;
    if (closeBps > 10.0D) closeBps = 10.0D;
    if (closeBps < -10.0D) closeBps = -10.0D;
    hudDist = dist;

    // ping-lag projection: your reaction and their position both lag, so
    // brake on where the gap WILL be, not where it is
    double lagSec = (myPing + targetPing) / 2000.0D;
    if (lagSec > 0.35D) lagSec = 0.35D;
    double predDist = dist - closeBps * lagSec;

    double minS = modules.getSlider("AutoSpace", "Min Spacing");
    double maxS = modules.getSlider("AutoSpace", "Max Spacing");
    boolean guardOn = modules.getButton("AutoSpace", "Guard Brake");
    double brakeCarry = modules.getSlider("AutoSpace", "Brake Carry");
    double carry = guardOn ? Math.max(0.0D, myCloseBps) * brakeCarry : 0.0D;
    if (carry > 1.6D) carry = 1.6D;
    double brakeLine = minS + carry;
    if (modules.getButton("AutoSpace", "Ping Adaptive")) {
        // chasing a lagging target: their true position is further ahead,
        // widen the band so you do not run into their hitbox
        double lead = Math.max(0.0D, closeBps) * lagSec * 0.6D;
        if (lead > 1.2D) lead = 1.2D;
        minS += lead;
        maxS += lead * 0.6D;
        brakeLine += lead * 0.6D;
    }
    hudMin = minS;
    hudMax = maxS;
    boolean stunned = prevHurt > 0;

    // 0) KB fight: we just took (or are taking) melee hits - hold the W you
    // already have straight into the knockback. impact velocity plus the
    // continued push both cut the displacement you eat
    if (now < kbFightUntil && dist < 4.5D) {
        client.setForward(1.0F);
        if (!me.isSprinting() && sprintEngageOk(now)) {
            client.setSprinting(true);
            lastSprintEngageAt = now;
        }
        sprintResumeAt = now;
        stateName = "KB FIGHT";
        hudState = stateName + (blocking ? " + BLOCK" : "");
        return;
    }

    // 1) sprint reset in progress (w-tap)
    if (dropping) {
        if (predDist > maxS + 1.2D) {
            dropping = false;
            say("drop cut - target escaping");
        } else if (theirCloseBps > 2.0D && predDist < brakeLine) {
            // they are charging into us mid-drop - the W is worth more as
            // impact velocity than as a sprint reset
            dropping = false;
            say("drop cut - charge incoming");
        } else {
            client.setForward(0.0F);
            if (me.isSprinting()) client.setSprinting(false);
            stateName = "W-TAP";
            hudState = "W-TAP";
            return;
        }
    }

    // 2) s-tap escape when they can swing and we are inside the danger line
    int style = (int) modules.getSlider("AutoSpace", "Style");
    boolean escapeAllowed = modules.getButton("AutoSpace", "S-Tap Escape") && (style == 0 || style == 2);
    double escapeLine = (style == 2) ? minS : (minS - 0.30D);
    if (escapeAllowed && inCombat && predDist < escapeLine && !stunned
            && now - escapeUntil > 400 && now - lastResetAt >= 450L + targetPing) {
        escaping = true;
        escapeUntil = now + ((style == 2) ? 220 : 180);
        sprintResumeAt = escapeUntil + 150L;
        lastResetAt = now;
    }
    if (escaping) {
        if (now < escapeUntil) {
            client.setForward(-1.0F);
            if (me.isSprinting()) client.setSprinting(false);
            stateName = "S-TAP";
            hudState = "S-TAP";
            return;
        }
        escaping = false;
    }

    // 3) band control - the brake stop: full W until the carry-extended
    // line, then a hard stop whose glide carries you the rest of the way.
    // the glide is the momentum you swing with - no input ramp in front of it
    if (predDist < brakeLine) {
        // MEET hysteresis: enter when their charge is fast, release only
        // when they truly back off, so the boundary cannot flicker
        boolean meetNow = inCombat
                && (meetActive ? theirCloseBps > 0.8D : theirCloseBps > 1.5D)
                && predDist < brakeLine + 0.4D;
        meetActive = meetNow;
        if (meetNow) {
            // THEY are the ones closing: keep the W up so the impact lands
            // while you still have momentum - that is the knockback
            // reduction, and the KB fight window carries it through the hit
            client.setForward(1.0F);
            if (!me.isSprinting() && sprintEngageOk(now)) {
                client.setSprinting(true);
                lastSprintEngageAt = now;
            }
            sprintResumeAt = now;
            stateName = "MEET";
        } else if (predDist < minS - 0.05D && (closeBps > 0.6D || predDist < minS - 0.35D)) {
            // drifted or strafed back inside with no W of ours pushing -
            // walk it back out, scaled by how fast the gap still closes.
            // deep inside the line counts too, so an orbiting strafe or
            // aim-assist drift cannot ride through the box; your A/D is
            // never touched - the pushout rides on forward alone
            double back = 0.35D + closeBps * 0.15D;
            if (back > 1.0D) back = 1.0D;
            client.setForward((float) -back);
            if (me.isSprinting()) {
                client.setSprinting(false);
                sprintResumeAt = now + 160L + targetPing;
            }
            stateName = "PUSH";
        } else {
            // the brake stop: planted under the line, strafe stays free
            client.setForward(0.0F);
            if (me.isSprinting()) {
                client.setSprinting(false);
                sprintResumeAt = now + 160L + targetPing;
            }
            stateName = inCombat ? "HOLD" : "BRAKE";
        }
    } else if (!inCombat) {
        stateName = "";   // out of combat and outside the line: hands off
    } else if (predDist < maxS) {
        double f = (predDist - brakeLine) / (maxS - brakeLine);
        if (f < 0.0D) f = 0.0D;
        if (f > 1.0D) f = 1.0D;
        f = 0.25D + 0.75D * f;
        // sprint resume gate: hold below the 0.8 auto-sprint line so vanilla
        // cannot re-sprint mid-gate and chatter C0Bs
        if (now < sprintResumeAt && f > 0.7D) f = 0.7D;
        client.setForward((float) f);
        if (me.isSprinting() && predDist < maxS - 0.25D) {
            client.setSprinting(false);
            sprintResumeAt = now + 160L + targetPing;
        }
        stateName = "TRACK";
    } else {
        boolean locked = now < sprintResumeAt;
        client.setForward(locked ? 0.7F : 1.0F);
        if (!locked && !me.isSprinting() && !blocking && sprintEngageOk(now)) {
            client.setSprinting(true);
            lastSprintEngageAt = now;
        }
        stateName = "CHASE";
    }
    hudState = stateName + (blocking ? " + BLOCK" : "");
}

// --------------------------------------------------------------------- hud

String fmt(double d) {
    return String.format("%.2f", Double.valueOf(d));
}

int stateColor() {
    if (hudState != null) {
        if (hudState.indexOf("KB FIGHT") >= 0) return 0x55FF55;
        if (hudState.indexOf("MEET") >= 0) return 0x55FF55;
        if (hudState.indexOf("PUSH") >= 0) return 0xFFAA00;
        if (hudState.indexOf("W-TAP") >= 0) return 0xFFFF55;
        if (hudState.indexOf("S-TAP") >= 0) return 0xFFAA00;
        if (hudState.indexOf("CHASE") >= 0) return 0x55FF55;
        if (hudState.indexOf("BRAKE") >= 0) return 0xFF5555;
        if (hudState.indexOf("HOLD") >= 0) return 0xFF5555;
        if (hudState.indexOf("TRACK") >= 0) return 0x55FFFF;
    }
    return 0xFFFFFF;
}

String tierName() {
    if (targetPing < 45) return "LOW";
    if (targetPing < 95) return "MID";
    return "HIGH";
}

void onRenderWorld(float pt) {
    if (!modules.getButton("AutoSpace", "HUD")) return;

    float x = 4.0F;
    float y = 4.0F;
    float step = render.getFontHeight() + 2.0F;

    if (target == null || !modules.isEnabled("AutoSpace")) {
        render.text("AutoSpace [idle]", x, y, 1.0F, 0xAAAAAA, true);
        return;
    }

    render.text("AutoSpace", x, y, 1.0F, 0x55FFFF, true);
    y += step;
    render.text("Ping: " + targetPing + "ms (" + tierName() + ")  you: " + (int) myPing + "ms", x, y, 1.0F, 0xFFFFFF, true);
    y += step;
    render.text("Gap: " + fmt(hudDist) + "  band " + fmt(hudMin) + "-" + fmt(hudMax), x, y, 1.0F, 0xFFFFFF, true);
    y += step;
    String s = hudState == null ? "" : hudState;
    if (s.isEmpty()) s = stateName == null ? "" : stateName;
    render.text(s.isEmpty() ? "STANDBY" : s, x, y, 1.0F, stateColor(), true);

    if (modules.getButton("AutoSpace", "Render Range") && target != null) {
        Vec3 feet = target.getPosition().floor();
        int col = (hudDist > hudMin && hudDist < hudMax) ? 0x00FF00 : 0xFF0000;
        render.block(feet, col, true, true);
    }
}
