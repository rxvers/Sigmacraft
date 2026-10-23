# SigmaCraftCore 0.3.0 — Paper 26.3

Dieses Projekt enthält die Basisfunktionen für SigmaCraft: gespeicherte Statistiken, Spawn-Schutz und `/spawn`, Teams mit Tags/Farben, Top-10-Textdisplays und den temporären Elytra-Boost.

## Status

**Quellcode.** Die fertige JAR entsteht automatisch über GitHub Actions (Java 25), siehe `INSTALLATION.md`.

## Build auf einem Rechner mit Internet

Voraussetzungen: JDK 25 und Gradle 9.x. Paper dokumentiert die API-Abhängigkeit als `io.papermc.paper:paper-api:26.3.build.+` und Java-Toolchain 25.

1. Gradle installieren und sicherstellen, dass `java -version` Java 25 zeigt.
2. In diesem Ordner ein Terminal öffnen.
3. Ausführen: `gradle clean build`
4. Das Ergebnis liegt in `build/libs/SigmaCraftCore-0.3.0.jar`.
5. Erst auf einem Testserver installieren; vor Änderungen an der Live-Welt ein Backup erstellen.

## Befehle

- `/team create <Tag> <Farbe>`, `/team invite <Spieler>`, `/team accept`, `/team leave`
- `/team info` — Teamleiter sowie Mitglieder (online und offline)
- `/stat [kills|deaths|blocks|diamonds|playtime|all] [Spieler]` — für alle Spieler (Alias `/stats`)
- `/leaderboard <kills|deaths|blocks|diamonds|playtime>` — Top 10, für alle Spieler (Alias `/lb`)
- `/sigmacraft stat ...` und `/sigmacraft leaderboard ...` — weiterhin möglich, für alle Spieler; Konsole/RCON bekommt wie bisher Klartext für den Discord-Bot
- `/sctop create <kills|deaths|diamonds|blocks|playtime>` / `/sctop remove` / `/sctop clean [Radius]` — Hologramme (Admin)
- `/spawn` — Teleport nach 5 Sekunden ohne Bewegung und ohne Schaden (Admins sofort)
- `/scstats <Spielername>` — maschinenlesbare Zeile für den Discord-Bot (Berechtigung `sigmacraft.stats`)

## Neu in dieser Version

- **Spawn:** kein PvP und kein Schaden im Schutzradius (`spawn.no-pvp`, `spawn.no-damage`).
- **Team-Koordinaten:** `/team coordssave <Name>`, `/team coords list`, `/team coords delete <Name>`.
- **Nether/End:** per Config (`worlds.disable-nether`, `worlds.disable-end`) oder im Admin-Panel umschaltbar. Admins sind ausgenommen.
- **Admin-Panel:** Statistiken bearbeiten (hinzufügen, entfernen, setzen), Inventar ansehen, Daten löschen (Stats, Achievements, Inventar/Spielerdaten) für einen Spieler oder alle, mit Bestätigung; Teleport zu/holen, Einfrieren (bleibt nach Aus-/Einloggen bestehen, gespeichert in `frozen.yml`).
- **Extras:** Todeskoordinaten im Chat, schlichte Join-/Quit-Meldungen.

## PvP-Kennzeichnung (Opt-in)

Standard: **geschützt**. Mit `/pvp on` (danach `/pvp bestätigen`) stimmst du PvP, Fallen und Griefing durch andere Gekennzeichnete zu.

- `/pvp off` — Austritt, frühestens nach 60 Minuten (`pvp.optout-cooldown-minutes`)
- `/pvp status`, `/pvp list` — eigener Status bzw. wer PvP will und wer nicht
- `/regeln` — PvP- und Griefing-Regeln im Wortlaut der Discord-Regeln
- Tablist: roter Punkt = PvP, grüner Punkt = geschützt, dazu Zähler im Header. Im Chat steht nur bei Gekennzeichneten ein roter Punkt.
- Die Kennzeichnung ist eine Anzeige mit Zustimmung; die Regeln setzt das Team durch (Ban/Mute und Strafen-Log im Admin-Panel).
- Erster Join: kurzer Willkommenstext zu `/regeln` und `/pvp`.
- Admin-Panel: Kennzeichnung pro Spieler setzen oder entfernen.

## Anti-Klau: Zugriffs-Log (nur Admins)

Das Plugin protokolliert Behälter (Kisten, Fässer, Shulker, Öfen, Trichter ...): wer sie öffnet und **was er herausnimmt oder hineinlegt**, außerdem wer Türen, Falltüren und Zauntore benutzt und wer Behälter zerstört. Gespeichert in `activity.log`, Einträge werden nach `accesslog.retention-days` (14) gelöscht.

- `/zugriffe inspect` (Aliase `/zlog`, `/log`) — Inspektor: Rechtsklick auf einen Block zeigt dessen Verlauf, der Behälter öffnet sich dabei nicht
- `/zugriffe near [Radius]` — Einträge in deiner Nähe
- `/zugriffe player <Name> [Anzahl]` — Einträge eines Spielers
- Admin-Panel: Kompass-Symbol bei jedem Spieler (Ansicht mit allen Einträgen), Inspektor-Schalter in der Spielerliste

Der Chat zeigt bei jedem Spieler einen Punkt: rot = PvP, grün = geschützt.

## Vorab-Zugang (vor der Eröffnung)

Zusätzlich zur normalen Whitelist gibt es eine Zugangsliste. Ist der Vorab-Zugang aktiv, dürfen nur Spieler auf dieser Liste (und Operatoren) joinen, auch wenn andere bereits gewhitelisted sind.

- `/access on` / `/access off` — einschalten (nicht zugelassene Spieler werden gekickt) / Server freigeben
- `/access add <Name>`, `/access remove <Name>`, `/access list`, `/access status`
- Auch im Admin-Panel: Schalter in der Spielerliste und pro Spieler. Zugelassene Namen stehen in `access.yml`.
- Kick-Nachricht und Server-Beschreibung (MOTD, solange aktiv) stehen in der Config unter `access`.

## Admin-Panel

`/admin` (Berechtigung `sigmacraft.admin`) öffnet eine Spielerliste. Pro Spieler: IP (inkl. weiterer Accounts mit gleicher IP), Enderchest (nur Ansicht), Ban und Mute mit Dauer (1h, 3h, 12h, 1d, 3d, 7d, 30d, permanent oder eigene Eingabe wie `90m`, `2d`, `1w`) und Grund sowie ein Strafen-Log. Offline-Spieler: `/admin <Name>`. Alle Strafen stehen in `plugins/SigmaCraftCore/punishments.yml`.

## Creeper

Creeper explodieren, zerstören aber keine Blöcke (`creeper.block-damage: false`).

## Top-1-Symbole (einziges Chat-Zeichen)

Wer in einer Rangliste auf Platz 1 steht (Wert > 0), bekommt im Chat und in der Tablist ein Symbol: ⚔ Kills, ☠ Tode, ◆ Diamanten, ⛏ Blöcke, ◷ Spielzeit. Die Symbole werden alle 10 Sekunden aktualisiert.

## Spawn-Schutz

Im Schutzradius können Nicht-Admins weder Blöcke abbauen noch platzieren (auch keine Eimer). Türen, Falltüren, Zauntore sowie Kisten, Fässer, Öfen und andere Behälter lassen sich ebenfalls nicht benutzen. Am Spawn erscheint beim Fallen eine Boss-Bar mit dem F-Hinweis.

## Elytra-Boost

Am Spawn fallen, dann **F** (Hand tauschen) drücken. Die Elytra bleibt, bis du landest (Boden oder Wasser), dann kommt die ursprüngliche Brustplatte zurück. Eine neue Aktivierung ist nur wieder am Spawn möglich.

## Konfiguration

`src/main/resources/config.yml` wird beim ersten Start in `plugins/SigmaCraftCore/config.yml` kopiert. Dort Spawn-Welt und Koordinaten, Schutzradius, `/spawn`-Radius, Wartezeit (`spawn.teleport-delay-seconds`), Mob-Sperre (`spawn.block-mobs`) und Elytra-Einstellungen setzen.

## Noch vor Live-Einsatz testen

- Build gegen exakt die Paper-26.3-API und Start auf einem Testserver.
- Elytra-Boost in allen Fällen testen (Landen, Tod, Disconnect, volles Inventar/Brustplatten-Slot).
- TextDisplay-Ranglisten nach Neustart und bei entladenen Chunks testen.
- Discord-Bot-RCON-Ausgabe gegen die echte Bot-Implementierung verbinden.
