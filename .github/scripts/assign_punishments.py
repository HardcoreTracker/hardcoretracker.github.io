"""Give the current punishment to whoever just earned one.

Runs on GitHub after data.json (or test-data.json) changes. If a player has earned more
punishments (every 5 points) than the history shows, the earliest such player is logged
with the current punishment, and the current punishment is marked as used so the wheel
unlocks to pick the next one. One player per run: the next spin saves the file again,
which runs this again for anyone else who's owed.
"""
import json
import sys
from datetime import datetime, timezone

PER_PUNISH = 5
AGE_POINTS = {"wood": 1, "copper": 2, "iron": 3, "diamond": 4, "end": 5}


def death_points(d):
    if isinstance(d.get("points"), (int, float)) and not isinstance(d.get("points"), bool):
        return d["points"]
    if d.get("legacy"):
        return 0
    return AGE_POINTS.get(d.get("age"), 0) + (1 if d.get("totem") else 0)


def death_ms(d):
    for key in ("time", "date"):
        v = d.get(key)
        if v:
            try:
                if key == "date":
                    v = v + "T12:00:00+00:00"
                return datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp()
            except ValueError:
                pass
    return 0


def owed(data):
    players = [p for p in data.get("players", []) if not p.get("inactive")]
    deaths = sorted((d for d in data.get("deaths", []) if not d.get("reverted")), key=death_ms)
    given = {}
    for p in data.get("punishments", []):
        given[p.get("player")] = given.get(p.get("player"), 0) + 1
    out = []
    for p in players:
        mine = [d for d in deaths if d.get("player") == p["id"]]
        base = float(p.get("base") or 0)
        total = base + sum(death_points(d) for d in mine)
        g = given.get(p["id"], 0)
        if int(total // PER_PUNISH) <= g:
            continue
        running, when = base, None
        for d in mine:
            running += death_points(d)
            if running >= (g + 1) * PER_PUNISH:
                when = d.get("time") or None
                break
        out.append((death_ms({"time": when}) if when else float("inf"), p, when))
    out.sort(key=lambda x: x[0])
    return out


def main(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    cur = data.get("currentPunishment") or {}
    if cur.get("used") or not cur.get("text"):
        print(f"{path}: waiting for a wheel spin, nothing to give out")
        return False
    todo = owed(data)
    if not todo:
        print(f"{path}: nobody is owed a punishment")
        return False
    _, player, when = todo[0]
    punishments = data.setdefault("punishments", [])
    next_id = max([int("".join(ch for ch in str(p.get("id", "")) if ch.isdigit()) or 0) for p in punishments] + [0]) + 1
    punishments.append({
        "id": f"p{next_id}",
        "time": when or datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "player": player["id"],
        "text": cur["text"],
        "source": "auto",
    })
    data["currentPunishment"] = {**cur, "used": True, "givenTo": player["id"]}
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    name = player.get("nick") or player.get("name")
    print(f"{path}: {name} gets \"{cur['text']}\"")
    with open("commit-message.txt", "a", encoding="utf-8") as f:
        f.write(("Test lab: " if "test" in path else "") + f"Punishment: {name} gets \"{cur['text']}\"\n")
    return True


if __name__ == "__main__":
    changed = [main(p) for p in sys.argv[1:]]
    sys.exit(0)
