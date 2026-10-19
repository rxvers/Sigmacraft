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

## Admin-Panel

`/admin` (Berechtigung `sigmacraft.admin`) öffnet eine Spielerliste. Pro Spieler: IP (inkl. weiterer Accounts mit gleicher IP), Enderchest (nur Ansicht), Ban und Mute mit Dauer (1h, 3h, 12h, 1d, 3d, 7d, 30d, permanent oder eigene Eingabe wie `90m`, `2d`, `1w`) und Grund sowie ein Strafen-Log. Offline-Spieler: `/admin <Name>`. Alle Strafen stehen in `plugins/SigmaCraftCore/punishments.yml`.

## Creeper

Creeper-Explosionen richten keinen Schaden an (`creeper.disable-explosions`).

## Top-1-Symbole

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
