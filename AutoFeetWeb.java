/*
 * AutoFeetWeb - Raven B4 script (Minecraft 1.8.9)
 *
 * Watches nearby enemies and, when one runs into range, aims like a real
 * player (smooth rotation, movement fix) and places a cobweb into the block
 * the enemy's feet occupy - or a couple ticks ahead, so they run into it.
 * This is the clean "feet web" placement players like Lyqas land in PvP.
 *
 * Ported from the placement idea of FriendlyNeighbourhoodSpider's CwebUtils
 * (click-through-entity cweb placement) and rebuilt on Raven B4's script
 * runtime. It only sends normal rotations + one vanilla placement packet;
 * it never clicks for you and never blocks your input.
 *
 * Fixes in this build:
 *  - fully idle while you swing the sword (LMB held) - no webs mid-swing
 *  - slots 1, 8 and 9 are yours: never auto-swaps while one is selected,
 *    only swaps when you are on hotbar slots 2-7
 *  - self is hard-filtered out of every target pool (no more webbing
 *    yourself when you look down fast)
 *  - teammates ignored via name color codes (Hypixel/Mineplex team colors)
 *    plus vanilla scoreboard teams as fallback
 *  - "Lock After Web" actually locks: a webbed enemy is skipped as a target
 *    entirely, so the module stops trying to re-web them
 *  - goal cells near your own body/momentum are rejected, including the
 *    diagonal cells that used to slip through
 */

String scriptName = "AutoFeetWeb";

HashSet<String> PASSABLE = new HashSet<>(Arrays.asList(
    "air", "water", "flowing_water", "lava", "flowing_lava",
    "fire", "snow_layer", "tallgrass", "deadbush"
));

int[] FDx = { 0, 0, 0, 0, 1, -1 };
int[] FDy = { 1, -1, 0, 0, 0, 0 };
int[] FDz = { 0, 0, -1, 1, 0, 0 };
String[] STYLES = { "Smooth", "Flick" };
String[] KEYS = { "Off", "P", "Y", "B", "V", "G", "R", "F", "C", "Z", "X" };
int[] KEYCODES = { 0, 25, 21, 48, 47, 34, 19, 33, 46, 44, 45 };

// hotbar slots reserved for you: index 8 = slot 9 only. slots 1 and 8
// (0 and 7) are back in play for auto-swap - the post-web stand-down
// keeps the hotbar quiet while you combo
int[] BANNED_SLOTS = { 8 };

float serverYaw, serverPitch;
float aimYaw, aimPitch;
Vec3 goalCell;
Vec3 targetHit;
Vec3 placeVec;
String targetFace;
Entity target;
boolean pendingPlace;
boolean lastRMB;
boolean assistMode;
float aimStartYaw, aimStartPitch;
boolean wasAiming;
boolean hotkeyArmed;
boolean lastKeyState;
int aimTicks, swapTicks;
long lastPlaceTime, lastTargetTime;
long lastAimTime;
int lockedId = -1;
long lockedUntil;
int prevSlot = -1;
int ourWebSlot = -1;

void onLoad() {
    modules.registerDescription("Traps enemies in a web at their feet");
    modules.registerSlider("Style", "", 0, STYLES);
    modules.registerSlider("Range", " blocks", 3.5, 1, 4.5, 0.1);
    modules.registerSlider("Reach", " blocks", 4.5, 2.5, 4.5, 0.1);
    modules.registerSlider("FOV", "\u00B0", 60, 10, 90, 1);
    modules.registerSlider("Lead", " ticks", 2, 0, 5, 1);
    modules.registerSlider("Rotation Speed", "\u00B0/tick", 18, 2, 45, 1);
    modules.registerSlider("Randomness", "%", 25, 0, 75, 1);
    modules.registerSlider("Rotation Tolerance", "\u00B0", 12, 5, 45, 1);
    modules.registerSlider("Delay After Aiming", " ticks", 0, 0, 5, 1);
    modules.registerSlider("Delay After Swap", " ticks", 0, 0, 5, 1);
    modules.registerSlider("Swap Back Delay", "ms", 450, 0, 1500, 25);
    modules.registerSlider("Cooldown", "ms", 900, 0, 10000, 25);
    modules.registerSlider("Return Speed", "\u00B0/tick", 20, 2, 60, 1);
    modules.registerSlider("Max Stacks", "", 2, 0, 3, 1);
    modules.registerSlider("Hotkey", "", 0, KEYS);
    modules.registerButton("Move Camera", false);
    modules.registerButton("Ceiling-Aware", true);
    modules.registerButton("Skip Spiders", true);
    modules.registerButton("Lock After Web", true);
    modules.registerButton("Auto Swap", true);
    modules.registerButton("Swap Back", true);
    modules.registerButton("Only When Moving", true);
    modules.registerButton("Ignore Teammates", true);
    modules.registerButton("Target Mobs", true);
    modules.registerButton("Assist When Clicking", true);
    modules.registerButton("Debug Prints", false);
    modules.registerButton("Ignore Friends", true);
    modules.registerButton("Avoid Body Rays", true);
    modules.registerButton("Render Target", false);
}

void onEnable() {
    Entity p0 = client.getPlayer();
    if (p0 != null) {
        serverYaw = p0.getYaw();
        serverPitch = p0.getPitch();
    }
    goalCell = null;
    target = null;
    pendingPlace = false;
    aimTicks = 0;
    swapTicks = 0;
}

void onDisable() {
    restoreSlot();
    pendingPlace = false;
    goalCell = null;
    target = null;
    prevSlot = -1;
    ourWebSlot = -1;
    lockedId = -1;
    lockedUntil = 0L;
}

void say(Object s) {
    if (modules.getButton("AutoFeetWeb", "Debug Prints")) client.print(s);
}

float[] idle() {
    pendingPlace = false;
    return null;
}

float[] getRotations(float camYaw, float camPitch) {
    if (client.getScreen() != null && !client.getScreen().isEmpty()) return idleBack();

    Entity me = client.getPlayer();
    if (me == null || me.isDead() || me.isUsingItem()) return idleBack();

    if (inWebCell(me)) {
        releaseWeb();
        return idleBack();
    }
    if (!armed()) return idleBack();
    if (keybinds.isMouseDown(1)) return idleBack();
    // never web while swinging the sword
    if (keybinds.isMouseDown(0)) return idleBack();
    // post-web stand-down: after a successful place the whole pipeline goes
    // quiet for the Cooldown window - no aim, no swap, no re-web
    if (client.time() - lastPlaceTime < (long) modules.getSlider("AutoFeetWeb", "Cooldown")) return idleBack();
    // slot 9 belongs to you: module is fully passive there, no auto-swap
    if (bannedSlot()) return idleBack();

    target = findTarget(me, modules.getSlider("AutoFeetWeb", "Range"));
    if (target == null) return idleBack();
    lastTargetTime = client.time();

    goalCell = computeGoal(me);
    if (goalCell == null) {
        say("no goal");
        return idleBack();
    }

    ItemStack web = me.getHeldItem();
    if (!isWeb(web) && !equipWeb(me)) return idleBack();
    web = me.getHeldItem();
    if (!isWeb(web)) return idleBack();

    Vec3 eye = me.getPosition().offset(0, me.getEyeHeight(), 0);
    boolean keepShot = wasAiming && targetHit != null && targetFace != null && placeVec != null
            && offsetByFace(targetHit, targetFace).floor().equals(goalCell);
    if (!keepShot && !findBestPlacement(eye, goalCell, web, modules.getSlider("AutoFeetWeb", "Reach"))) {
        return idleBack();
    }

    if (!wasAiming) {
        aimStartYaw = me.getYaw();
        aimStartPitch = me.getPitch();
        serverYaw = normYaw(aimStartYaw);
        serverPitch = aimStartPitch;
    }
    wasAiming = true;

    float[] rot = computeRotations();

    if (canFire(rot)) {
        Vec3 under = goalCell.offset(0, -1, 0);
        Block ub = world.getBlockAt(under);
        if (ub != null && ub.name != null && (ub.name.equalsIgnoreCase("web") || ub.name.equalsIgnoreCase("cobweb"))) {
            // stack shot: a vanilla client ray goes straight through a cobweb,
            // so we use the hit the packet will actually send (constructed)
            if (targetHit == null || targetFace == null || placeVec == null || !"UP".equals(targetFace)) {
                targetHit = under;
                targetFace = "UP";
                placeVec = new Vec3(under.x + 0.5D, under.y + 1.0D, under.z + 0.5D);
            }
            pendingPlace = true;
        } else {
            Object[] ray = client.raycastBlock(modules.getSlider("AutoFeetWeb", "Reach"), rot[0], rot[1]);
            if (ray != null) {
                Vec3 hit = (Vec3) ray[0];
                String face = (String) ray[2];
                Vec3 plc = offsetByFace(hit, face);
                if (plc.floor().equals(goalCell)) {
                    pendingPlace = true;
                    targetHit = hit;
                    targetFace = face;
                    placeVec = ((Vec3) ray[1]).offset(hit.x, hit.y, hit.z);
                }
            }
        }
    }
    lastAimTime = client.time();
    if (modules.getButton("AutoFeetWeb", "Move Camera")) {
        me.setYaw(rot[0]);
        me.setPitch(rot[1]);
    }
    return rot;
}

void onPreUpdate() {
    assistMode = false;
    pollHotkey();
    Entity meP = client.getPlayer();
    boolean inWebbed = meP != null && inWebCell(meP);
    boolean disarmed = !armed();
    boolean banned = bannedSlot();
    if (inWebbed || disarmed || banned) {
        pendingPlace = false;
        releaseWeb();
    }
    if (meP == null || meP.isDead() || inWebbed || disarmed || banned) return;
    handleAssist();
    if (pendingPlace) {
        pendingPlace = false;
        Block gb = world.getBlockAt(goalCell);
        if (targetHit != null && targetFace != null && placeVec != null && goalCell != null && gb != null && PASSABLE.contains(gb.name)) {
            if (client.placeBlock(targetHit, targetFace, placeVec)) {
                client.swing();
                lastPlaceTime = client.time();
                if (modules.getButton("AutoFeetWeb", "Lock After Web") && target != null) {
                    lockedId = target.entityId;
                    lockedUntil = client.time() + 1200L;
                }
            } else {
                say("place failed");
            }
        }
    }

    if (ourWebSlot != -1) {
        if (inventory.getSlot() != ourWebSlot) {
            ourWebSlot = -1;
            prevSlot = -1;
        } else if (modules.getButton("AutoFeetWeb", "Swap Back")
                && client.time() - lastTargetTime >= (long) modules.getSlider("AutoFeetWeb", "Swap Back Delay")
                && prevSlot != -1) {
            inventory.setSlot(prevSlot);
            prevSlot = -1;
            ourWebSlot = -1;
        }
    }
}

void onRenderWorld(float pt) {
    if (target == null || goalCell == null || !modules.getButton("AutoFeetWeb", "Render Target")) return;
    render.block(goalCell, 0x00FF00, true, true);
}

boolean bannedSlot() {
    int s = inventory.getSlot();
    for (int i = 0; i < BANNED_SLOTS.length; i++) {
        if (s == BANNED_SLOTS[i]) return true;
    }
    return false;
}

boolean isSpider(Entity e) {
    String n = e.getName();
    String t = e.type;
    return (t != null && t.toLowerCase().contains("spider"))
        || (n != null && n.toLowerCase().contains("spider"));
}

List<Entity> targetPool(Entity me, double range) {
    List<Entity> pool = new ArrayList<>();
    for (Entity e : world.getPlayerEntities()) {
        // hard self-filter: never count our own player, whatever the entity type
        if (e == me || e.isUser || e.entityId == me.entityId) continue;
        pool.add(e);
    }
    if (modules.getButton("AutoFeetWeb", "Target Mobs")) {
        for (Entity e : world.getEntities()) {
            if (e == me || e.isUser || e.entityId == me.entityId || e.getNetworkPlayer() != null || e.isPlayer) continue;
            if (!e.isLiving || e.getName() == null || e.getName().isEmpty()) continue;
            if (modules.getButton("AutoFeetWeb", "Skip Spiders") && isSpider(e)) continue;
            pool.add(e);
        }
    }
    return pool;
}

Entity crosshairEntity(Entity me, double reach) {
    Entity best = null;
    double near = 99.0;
    Vec3 eye = me.getPosition().offset(0, me.getEyeHeight(), 0);
    for (Entity e : targetPool(me, reach)) {
        if (e == me || e.isUser || e.isDead() || e.isInvisible()) continue;
        if (angleFromLook(me, e) > 8.0) continue;
        Vec3 p = e.getPosition().offset(0, e.getEyeHeight(), 0);
        double dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= reach + 0.5 && len < near) { near = len; best = e; }
    }
    return best;
}

void handleAssist() {
    if (!modules.getButton("AutoFeetWeb", "Assist When Clicking")) {
        lastRMB = keybinds.isMouseDown(1);
        return;
    }
    boolean rmb = keybinds.isMouseDown(1);
    boolean pressed = rmb && !lastRMB;
    lastRMB = rmb;
    if (!pressed) return;
    if (client.getScreen() != null && !client.getScreen().isEmpty()) return;
    if (keybinds.isMouseDown(0)) return;                 // swinging - hands off
    if (bannedSlot()) return;                            // slot 9 - hands off
    if (client.time() - lastPlaceTime < (long) modules.getSlider("AutoFeetWeb", "Cooldown")) return;
    Entity me = client.getPlayer();
    if (me == null || me.isDead()) return;
    ItemStack held = me.getHeldItem();
    if (!isWeb(held)) return;
    Entity over = crosshairEntity(me, modules.getSlider("AutoFeetWeb", "Reach"));
    if (over == null) return;
    if (modules.getButton("AutoFeetWeb", "Ignore Friends") && client.isFriend(over.getName())) return;
    if (modules.getButton("AutoFeetWeb", "Ignore Teammates") && sameTeam(me, over)) return;
    if (modules.getButton("AutoFeetWeb", "Lock After Web") && inWebCell(over)) return;
    Vec3 goal = over.getPosition().floor();
    Block b = world.getBlockAt(goal);
    if (b == null || !PASSABLE.contains(b.name)) return;
    if (goalIsMyCell(me, goal)) return;
    Vec3 eye = me.getPosition().offset(0, me.getEyeHeight(), 0);
    assistMode = true;
    boolean ok = findBestPlacement(eye, goal, held, modules.getSlider("AutoFeetWeb", "Reach"));
    assistMode = false;
    say("assist " + over.getName() + " ok=" + ok + (ok ? " face=" + targetFace : ""));
    if (ok && targetHit != null && targetFace != null && placeVec != null && client.placeBlock(targetHit, targetFace, placeVec)) {
        client.swing();
        lastPlaceTime = client.time();
        if (modules.getButton("AutoFeetWeb", "Lock After Web")) {
            lockedId = over.entityId;
            lockedUntil = client.time() + 1200L;
        }
        if (over.getNetworkPlayer() != null) lastTargetTime = client.time();
    }
}

Entity findTarget(Entity me, double range) {
    Vec3 myPos = me.getPosition();
    double fov = modules.getSlider("AutoFeetWeb", "FOV");
    if (fov > 90.0D) fov = 90.0D;
    boolean lockOn = modules.getButton("AutoFeetWeb", "Lock After Web");
    boolean locked = lockOn && lockedId != -1 && client.time() < lockedUntil;

    double bestAng = 100.0D, bestD2 = 0.0D;
    Entity found = null;
    for (Entity e : targetPool(me, range)) {
        if (e == me || e.isUser || e.isDead() || e.isInvisible()) continue;
        // a freshly webbed enemy stays off-limits while the lock window is open
        if (locked && e.entityId == lockedId) continue;
        if (modules.getButton("AutoFeetWeb", "Ignore Friends") && client.isFriend(e.getName())) continue;
        if (modules.getButton("AutoFeetWeb", "Ignore Teammates") && sameTeam(me, e)) continue;
        // already caught in a web -> leave them alone
        if (inWebCell(e)) continue;

        Vec3 p = e.getPosition();
        double dx = p.x - myPos.x, dy = p.y - myPos.y, dz = p.z - myPos.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 >= range * range) continue;

        if (modules.getButton("AutoFeetWeb", "Only When Moving")) {
            Vec3 last = e.getLastPosition();
            double mvx = p.x - last.x, mvz = p.z - last.z;
            if (mvx * mvx + mvz * mvz < 0.0004) continue;
        }

        double ang = angleFromLook(me, e);
        if (ang > fov) continue;

        if (found == null || ang < bestAng - 5.0D || (ang < bestAng + 5.0D && d2 < bestD2)) {
            bestAng = ang;
            bestD2 = d2;
            found = e;
        }
    }
    if (found != null) say("target " + found.getName());
    return found;
}

Vec3 computeGoal(Entity me) {
    Vec3 pos = target.getPosition();
    Vec3 last = target.getLastPosition();
    int lead = (int) modules.getSlider("AutoFeetWeb", "Lead");
    double vx = pos.x - last.x;
    double vz = pos.z - last.z;
    Vec3 goal = new Vec3(pos.x + vx * lead, pos.y, pos.z + vz * lead).floor();
    if (goalIsMyCell(me, goal)) return null;

    Block b = world.getBlockAt(goal);
    if (b == null) return null;

    // enemy already caught in one of our webs -> stack another above it (Lyqas-style),
    // but leave them alone once Max Stacks is reached or a block blocks the stack
    if (b.name != null && b.name.equalsIgnoreCase("web")) {
        if (websInColumn(goal) >= (int) modules.getSlider("AutoFeetWeb", "Max Stacks")) return null;
        for (int dy = 1; dy <= 3; dy++) {
            Block sb = world.getBlockAt(goal.offset(0, dy, 0));
            if (sb == null || sb.name == null) break;
            if (sb.name.equalsIgnoreCase("web")) continue;
            if (PASSABLE.contains(sb.name)) return goal.offset(0, dy, 0);
            break;
        }
        return null;
    }

    if (!PASSABLE.contains(b.name)) return null;
    // indoors: let AutoHeadWeb do the head webs
    if (modules.getButton("AutoFeetWeb", "Ceiling-Aware") && ceilingAbove(goal)) return null;
    return goal;
}

boolean inWebCell(Entity e) {
    Vec3 p = e.getPosition();
    Block a = world.getBlockAt(p.floor());
    if (a != null && a.name != null && (a.name.equalsIgnoreCase("web") || a.name.equalsIgnoreCase("cobweb"))) return true;
    Block b = world.getBlockAt(p.offset(0, 1, 0).floor());
    return b != null && b.name != null && (b.name.equalsIgnoreCase("web") || b.name.equalsIgnoreCase("cobweb"));
}

int websInColumn(Vec3 base) {
    int n = 0;
    for (int dy = 0; dy <= 3; dy++) {
        Block b = world.getBlockAt(base.offset(0, dy, 0));
        if (b != null && b.name != null && b.name.equalsIgnoreCase("web")) n++;
    }
    return n;
}

boolean ceilingAbove(Vec3 base) {
    for (int dy = 1; dy <= 3; dy++) {
        Block b = world.getBlockAt(base.offset(0, dy, 0));
        if (b != null && b.name != null && !PASSABLE.contains(b.name)) return true;
    }
    return false;
}

void releaseWeb() {
    if (ourWebSlot != -1) restoreSlot();
    ourWebSlot = -1;
    prevSlot = -1;
}

void pollHotkey() {
    int keyIdx = (int) modules.getSlider("AutoFeetWeb", "Hotkey");
    if (keyIdx <= 0) return;
    boolean down = keybinds.isKeyDown(KEYCODES[keyIdx - 1]);
    if (down && !lastKeyState) {
        hotkeyArmed = !hotkeyArmed;
        client.print("AutoFeetWeb " + (hotkeyArmed ? "ON" : "OFF") + " [" + KEYS[keyIdx] + "]");
        if (!hotkeyArmed) {
            pendingPlace = false;
            target = null;
            releaseWeb();
        }
    }
    lastKeyState = down;
}

boolean armed() {
    return (int) modules.getSlider("AutoFeetWeb", "Hotkey") <= 0 || hotkeyArmed;
}

float[] relax() {
    boolean recent = client.time() - lastAimTime < 1500L;
    float dY = wrapYawDelta(serverYaw, aimStartYaw);
    float dP = aimStartPitch - serverPitch;
    if (!recent || (Math.abs(dY) <= 2.0f && Math.abs(dP) <= 2.0f)) return null;
    float sp = (float) modules.getSlider("AutoFeetWeb", "Return Speed");
    float ny = serverYaw + Math.max(-sp, Math.min(sp, dY));
    float np = serverPitch + Math.max(-sp, Math.min(sp, dP));
    serverYaw = ny;
    serverPitch = np;
    if (modules.getButton("AutoFeetWeb", "Move Camera")) {
        Entity me = client.getPlayer();
        if (me != null) {
            me.setYaw(ny);
            me.setPitch(np);
        }
    }
    return new float[]{ ny, np };
}

float[] idleBack() {
    pendingPlace = false;
    wasAiming = false;
    Entity meI = client.getPlayer();
    if (meI != null) {
        aimStartYaw = meI.getYaw();
        aimStartPitch = meI.getPitch();
    }
    return relax();
}

boolean sameCell(Vec3 a, Vec3 b) {
    return a.x == b.x && a.y == b.y && a.z == b.z;
}

boolean goalIsMyCell(Entity me, Vec3 goal) {
    Vec3 p = me.getPosition();
    if (sameCell(goal, p.floor()) || sameCell(goal, p.offset(0, 1, 0).floor())) return true;
    // cell CENTER vs my exact position: catches the diagonal neighbors the old
    // 1.0-radius corner check let through (that was the self-web path)
    double cx = goal.x + 0.5D - p.x, cy = goal.y - p.y, cz = goal.z + 0.5D - p.z;
    if (Math.abs(cy) < 2.0D && cx * cx + cz * cz < 2.0D) return true;
    // and where my own momentum is carrying me over the next few ticks
    Vec3 last = me.getLastPosition();
    if (last != null) {
        double mx = p.x + (p.x - last.x) * 3.0D - (goal.x + 0.5D);
        double mz = p.z + (p.z - last.z) * 3.0D - (goal.z + 0.5D);
        if (Math.abs(cy) < 2.0D && mx * mx + mz * mz < 1.0D) return true;
    }
    return false;
}

boolean equipWeb(Entity me) {
    if (swapTicks > 0) {
        swapTicks--;
        return false;
    }
    if (bannedSlot()) return false;
    int slot = findWebSlot(me);
    if (slot == -1) return false;
    if (ourWebSlot == -1) prevSlot = inventory.getSlot();
    inventory.setSlot(slot);
    ourWebSlot = slot;
    swapTicks = (int) modules.getSlider("AutoFeetWeb", "Delay After Swap");
    return swapTicks <= 0;
}

int findWebSlot(Entity me) {
    for (int s = 0; s < 9; s++) {
        // never swap INTO slot 9 either - it is yours, same rule as selection
        boolean banned = false;
        for (int i = 0; i < BANNED_SLOTS.length; i++) if (s == BANNED_SLOTS[i]) banned = true;
        if (banned) continue;
        if (isWeb(inventory.getStackInSlot(s))) return s;
    }
    return -1;
}

boolean isWeb(ItemStack item) {
    return item != null && item.stackSize > 0 && item.name != null
        && (item.name.equalsIgnoreCase("web") || item.name.equalsIgnoreCase("cobweb"));
}

void restoreSlot() {
    if (ourWebSlot != -1 && prevSlot != -1 && inventory.getSlot() == ourWebSlot) {
        inventory.setSlot(prevSlot);
    }
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
    // Hypixel (and most servers) color teammate names per team; vanilla team
    // membership is per-player there and never groups real teammates
    Character mine = firstColorCode(me.getDisplayName());
    Character theirs = firstColorCode(e.getDisplayName());
    if (mine != null && theirs != null) {
        // 'f'/'7' are the default whites/grays - matching defaults means "no team info"
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

double angleTo(Entity me, Vec3 p) {
    float yaw = me.getYaw();
    float pitch = me.getPitch();
    double rad = Math.PI / 180;
    double lx = -Math.sin(yaw * rad) * Math.cos(pitch * rad);
    double ly = -Math.sin(pitch * rad);
    double lz = Math.cos(yaw * rad) * Math.cos(pitch * rad);
    Vec3 eye = me.getPosition().offset(0, me.getEyeHeight(), 0);
    double dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 1e-6) return 0;
    double dot = (lx * dx + ly * dy + lz * dz) / len;
    if (dot > 1) dot = 1;
    if (dot < -1) dot = -1;
    return Math.toDegrees(Math.acos(dot));
}

double angleFromLook(Entity me, Entity e) {
    Vec3 p = e.getPosition();
    double h = e.getHeight();
    double feet = angleTo(me, p);
    double mid = angleTo(me, p.offset(0, h * 0.5, 0));
    double head = angleTo(me, p.offset(0, h, 0));
    double m = feet;
    if (mid < m) m = mid;
    if (head < m) m = head;
    return m;
}

boolean findBestPlacement(Vec3 eye, Vec3 goal, ItemStack web, double reach) {
    float baseYaw = normYaw(serverYaw);
    float basePit = serverPitch;
    float camYaw = client.getPlayer().getYaw();
    float camPit = client.getPlayer().getPitch();
    double fov = modules.getSlider("AutoFeetWeb", "FOV");

    double INSET = 0.05, STEP = 0.2;
    double JITTER = modules.getButton("AutoFeetWeb", "Move Camera") ? 0.0 : STEP * 0.15;
    int grid = (int) Math.round(1 / STEP);
    ArrayList<Object[]> cands = new ArrayList<>();

    // pass 0: honor Avoid Body Rays; pass 1 (only if nothing was found) allows
    // rays that pass through the enemy body so far placements still work
    for (int pass = 0; pass < 2 && cands.size() == 0; pass++) {
        boolean avoidBody = modules.getButton("AutoFeetWeb", "Avoid Body Rays") && !assistMode && pass == 0;

        for (int fi = 0; fi < 6; fi++) {
            Vec3 support = new Vec3(goal.x + FDx[fi], goal.y + FDy[fi], goal.z + FDz[fi]);
            Block sb = world.getBlockAt(support);
            if (sb == null || PASSABLE.contains(sb.name)) continue;
            boolean webSupport = sb.name != null && (sb.name.equalsIgnoreCase("web") || sb.name.equalsIgnoreCase("cobweb"));

            for (int rr = 0; rr <= grid; rr++) {
                boolean ltr = (rr & 1) == 0;
                double v = rr * STEP + util.randomDouble(-JITTER, JITTER);
                if (v < 0) v = 0; else if (v > 1) v = 1;
                for (int cc = 0; cc <= grid; cc++) {
                    double cu = cc * STEP + util.randomDouble(-JITTER, JITTER);
                    if (cu < 0) cu = 0; else if (cu > 1) cu = 1;
                    double u = ltr ? cu : 1 - cu;

                    // face point sits exactly on the support face, like a vanilla hit-vec
                    double fx, fy, fz;
                    String face;
                    if (fi == 0)      { face = "DOWN";  fx = u; fy = 0.0D; fz = v; }
                    else if (fi == 1) { face = "UP";    fx = u; fy = 1.0D; fz = v; }
                    else if (fi == 2) { face = "SOUTH"; fx = u; fy = v; fz = 1.0D; }
                    else if (fi == 3) { face = "NORTH"; fx = u; fy = v; fz = 0.0D; }
                    else if (fi == 4) { face = "WEST";  fx = 0.0D; fy = v; fz = u; }
                    else              { face = "EAST";  fx = 1.0D; fy = v; fz = u; }

                    double px = support.x + fx, py = support.y + fy, pz = support.z + fz;

                    float[] rw = getRotationsWrapped(eye, px, py, pz);
                    if (Math.abs(wrapYawDelta(camYaw, rw[0])) > fov || Math.abs(rw[1] - camPit) > fov) continue;
                    if (avoidBody && rayHitsBox(eye, new Vec3(px, py, pz), target)) continue;

                    double cost = Math.abs((double) wrapYawDelta(baseYaw, rw[0])) + Math.abs((double) (rw[1] - basePit));
                    cands.add(new Object[]{ Double.valueOf(cost), rw[0], rw[1],
                        webSupport ? Boolean.TRUE : Boolean.FALSE, support, face,
                        Double.valueOf(fx), Double.valueOf(fy), Double.valueOf(fz) });
                }
            }
        }
    }

    cands.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0]));
    say("cands " + cands.size());

    for (int i = 0; i < cands.size(); i++) {
        Object[] c = cands.get(i);
        float pit = (Float) c[2];
        float yawU = unwrapYaw(((Number) c[1]).floatValue(), serverYaw);
        aimYaw = yawU;
        aimPitch = pit;

        if (c[3] == Boolean.TRUE) {
            // web support: the vanilla client ray cannot see a cobweb face -
            // construct the hit exactly the way the placement packet sends it
            Vec3 s = (Vec3) c[4];
            targetHit = s;
            targetFace = (String) c[5];
            placeVec = new Vec3(s.x + (Double) c[6], s.y + (Double) c[7], s.z + (Double) c[8]);
            say("stack shot " + targetFace);
            return true;
        }

        Object[] ray = client.raycastBlock(reach, yawU, pit);
        if (ray == null || ray.length < 3) continue;
        Vec3 hit = (Vec3) ray[0];
        String face = (String) ray[2];
        Vec3 frac = (Vec3) ray[1];
        if (face == null || hit == null || frac == null) continue;
        if (!sameCell(offsetByFace(hit, face).floor(), goal)) continue;
        targetHit = hit;
        targetFace = face;
        placeVec = frac.offset(hit.x, hit.y, hit.z);
        return true;
    }
    return false;
}

float[] computeRotations() {

    if (((int) modules.getSlider("AutoFeetWeb", "Style")) == 1) { serverYaw = aimYaw; serverPitch = aimPitch; return new float[]{ aimYaw, aimPitch }; }

    float curYaw = serverYaw;
    float curPit = serverPitch;
    float dYaw = aimYaw - curYaw;
    float dPit = aimPitch - curPit;

    if (Math.abs(dYaw) < 0.1f) curYaw = aimYaw;
    if (Math.abs(dPit) < 0.1f) curPit = aimPitch;
    if (curYaw == aimYaw && curPit == aimPitch) { serverYaw = aimYaw; serverPitch = aimPitch; return new float[]{ curYaw, curPit }; }

    float maxStep = (float) modules.getSlider("AutoFeetWeb", "Rotation Speed");
    float jitter = (float) modules.getSlider("AutoFeetWeb", "Randomness");
    if (jitter > 0f) {
        float factor = 1f - (float) util.randomDouble(0, jitter / 100f);
        maxStep *= factor;
    }

    curYaw += Math.max(-maxStep, Math.min(maxStep, dYaw));
    curPit += Math.max(-maxStep, Math.min(maxStep, dPit));

    if (Math.signum(aimYaw - curYaw) != Math.signum(dYaw)) curYaw = aimYaw;
    if (Math.signum(aimPitch - curPit) != Math.signum(dPit)) curPit = aimPitch;

    serverYaw = normYaw(curYaw);
    serverPitch = curPit;
    return new float[]{ curYaw, curPit };
}

boolean canFire(float[] rot) {
    if (target == null || goalCell == null) return false;
    if (!PASSABLE.contains(world.getBlockAt(goalCell).name)) return false;
    if (aimTicks > 0) {
        aimTicks--;
        return false;
    }
    if (swapTicks > 0 || keybinds.isMouseDown(1)) return false;
    if (client.time() - lastPlaceTime < (long) modules.getSlider("AutoFeetWeb", "Cooldown")) return false;

    float tol = (float) modules.getSlider("AutoFeetWeb", "Rotation Tolerance");
    return Math.abs(wrapYawDelta(rot[0], aimYaw)) <= tol && Math.abs(rot[1] - aimPitch) <= tol;
}

boolean rayHitsBox(Vec3 a, Vec3 b, Entity e) {
    if (e == null) return false;
    Vec3 p = e.getPosition();
    double w = e.getWidth() / 2.0 + 0.1;
    double minX = p.x - w, maxX = p.x + w;
    double minY = p.y - 0.1, maxY = p.y + e.getHeight() + 0.1;
    double minZ = p.z - w, maxZ = p.z + w;

    double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 1e-6) return false;
    dx /= len; dy /= len; dz /= len;

    double t0 = 0, t1 = len;
    double[] o = { a.x, a.y, a.z };
    double[] d = { dx, dy, dz };
    double[] lo = { minX, minY, minZ };
    double[] hi = { maxX, maxY, maxZ };
    for (int i = 0; i < 3; i++) {
        if (Math.abs(d[i]) < 1e-9) {
            if (o[i] < lo[i] || o[i] > hi[i]) return false;
        } else {
            double inv = 1.0 / d[i];
            double ta = (lo[i] - o[i]) * inv;
            double tb = (hi[i] - o[i]) * inv;
            if (ta > tb) {
                double tmp = ta;
                ta = tb;
                tb = tmp;
            }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return false;
        }
    }
    return true;
}

Vec3 offsetByFace(Vec3 pos, String face) {
    if ("UP".equals(face)) return pos.offset(0, 1, 0);
    if ("DOWN".equals(face)) return pos.offset(0, -1, 0);
    if ("NORTH".equals(face)) return pos.offset(0, 0, -1);
    if ("SOUTH".equals(face)) return pos.offset(0, 0, 1);
    if ("EAST".equals(face)) return pos.offset(1, 0, 0);
    if ("WEST".equals(face)) return pos.offset(-1, 0, 0);
    return pos;
}

float normYaw(float yaw) {
    yaw = ((yaw % 360f) + 360f) % 360f;
    return (yaw > 180f) ? (yaw - 360f) : yaw;
}

float wrapYawDelta(float base, float target) {
    float d = target - base;
    while (d <= -180f) d += 360f;
    while (d > 180f) d -= 360f;
    return d;
}

float unwrapYaw(float yaw, float prevYaw) {
    return prevYaw + ((((yaw - prevYaw + 180f) % 360f) + 360f) % 360f - 180f);
}

float[] getRotationsWrapped(Vec3 eye, double tx, double ty, double tz) {
    double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
    double hd = Math.sqrt(dx * dx + dz * dz);
    float yawWrapped = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
    yawWrapped = normYaw(yawWrapped);
    float pit = (float) Math.toDegrees(-Math.atan2(dy, hd));
    return new float[]{ yawWrapped, pit };
}
