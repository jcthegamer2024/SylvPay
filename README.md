# SylvPay 🚇

**Tap2Go transport payment and fare-gate system for Sylvarion City**, built as a Java plugin for a **Paper Minecraft 26.3** server.

SylvPay combines reloadable station and fare configuration, persistent Tap2Go smart cards, an inventory-based ticket machine, and Nexo furniture fare gates. It is designed for a city roleplay server rather than a real payment network.

> **Development status:** Active development / beta. The features below describe the current source files and updates; test thoroughly before using on a production server.

## Features

### Tap2Go smart cards

- Buy a uniquely identified Tap2Go card at a ticket machine, with a configurable card purchase price and starting balance.
- Card identity is stored in the item's persistent data; balances and card status are kept in SQLite, not in the item's lore.
- Top up using configurable preset amounts, subject to a maximum card balance.
- Check the balance of the **specific card inserted into the machine**.
- Refund the remaining balance through a confirmation screen; the card is deactivated after refund.
- Cards with an active journey cannot be refunded.
- Card appearance, item model, name, lore, currency display, and machine buttons are configurable.

### Ticket machine GUI

Open a ticket machine using `/sylvpay machine`.

- Buy-card buttons offer configurable preload options.
- Insert a Tap2Go card by clicking it in your player inventory while the machine is open, or by placing it into the machine's card slot.
- The selected card is used for balance checks, top-ups, refunds, and transaction history; the plugin does not silently pick another card from your inventory.
- The card is returned when you remove it or close the interface; if inventory space is unavailable, the code attempts to drop it back to the player.
- Transaction history displays completed journeys and card transactions, with pagination.
- Refunds require confirmation.

**Current GUI implementation note:** The history title, refund title, history button, card-slot placeholder, and confirmation buttons are currently constructed in Java. Some similarly named settings in `config.yml` are placeholders for future customisation, not active overrides.

### Nexo fare gates

- Register a closed Nexo furniture model as an **entrance** or **exit** gate for a station.
- Right-click a registered closed gate while holding a valid Tap2Go card in your **main hand**.
- Entrance gates start a journey; exit gates calculate and charge its fare.
- Gates temporarily swap from the configured closed Nexo furniture model to the open model and back.
- The gate closing logic takes passenger clearance into account and has a configurable timeout.
- Configurable tap cooldowns, sound effects, and MiniMessage messages.
- Tap messages can include station names and numbered fare zones.
- Gate registrations are persisted in `gates.yml` with **stable numerical IDs**.
- Normal open/closed furniture swaps preserve the registration and numerical ID.
- If registered furniture is broken and replaced **at the same saved location**, the recovery logic can reconnect the new furniture UUID to the existing registration when it is clicked, preserving its gate ID, station and direction. Recovery requires a stale previous entity and an unambiguous same-world location match (within approximately 0.1 block); moved gates should be manually reassigned.

### Journeys and fares

- Each card can have one active journey at a time.
- Tap-in checks the configured minimum entry balance.
- Tap-out charges the applicable fare and records the completed journey.
- Journey duration is limited by the configured maximum journey time; expired journeys require staff intervention.
- Fare selection supports:
  1. A configured same-station fare.
  2. A station-to-station route override.
  3. An optional reverse-route lookup.
  4. Optional numbered-zone fares, based on the number of zones covered.
  5. A default fallback fare.
- Missing station zone information falls back to the configured default fare when zone pricing is enabled.

### Persistent data

SylvPay uses SQLite for card balances, active journeys, completed journey history, and card transactions. Station definitions, fare rules, and gate registrations are stored in YAML files.

## Requirements

- A compatible **Paper** server (developed for Minecraft **26.3**).
- **Java** version required by your Paper version and Gradle project.
- **Nexo** for the furniture-based fare gates.
- **Vault** and a Vault-compatible economy provider for purchases, top-ups, and refunds.
- The dependencies declared in your Gradle build, including Paper API and Nexo API, must resolve correctly.

> The main plugin class disables SylvPay if no Vault economy provider is registered. If Nexo is unavailable, the plugin logs a warning and its fare-gate integration is unavailable.

## Installation

1. Build the plugin with your project's Gradle wrapper:

   ```powershell
   .\gradlew.bat clean build
   ```

2. Copy the resulting plugin JAR from `build/libs/` into the Paper server's `plugins/` directory.
3. Install and configure Nexo, Vault, and a compatible economy provider.
4. Start the server to create the SylvPay data directory and default configuration files.
5. Configure `config.yml`, `stations.yml`, and `fares.yml` for your network.
6. Make sure the closed and open Nexo furniture item IDs match those configured in `config.yml`.
7. Restart the server, then register gates using the commands below.

**Back up** `gates.yml` and the SQLite database before upgrading or replacing plugin builds.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/sylvpay` | — | Displays the command summary. |
| `/sylvpay machine` | `sylvpay.use` | Opens the Tap2Go ticket machine GUI. |
| `/sylvpay reload` | `sylvpay.admin` | Reloads plugin configuration, stations and fares. |
| `/sylvpay stations` | `sylvpay.admin` | Lists stations that currently have registered gates. |
| `/sylvpay gates <station>` | `sylvpay.admin` | Lists numbered gates at a particular station. |
| `/sylvpay gate set <station> entrance` | `sylvpay.admin` | Registers the targeted closed Nexo furniture as an entrance. |
| `/sylvpay gate set <station> exit` | `sylvpay.admin` | Registers the targeted closed Nexo furniture as an exit. |
| `/sylvpay gate remove` | `sylvpay.admin` | Removes the assignment of the targeted closed gate. |
| `/sylvpay gate delete <id>` | `sylvpay.admin` | Removes a gate registration by its numerical ID, leaving the furniture in place. |

Gate targeting commands require a player to look at a Nexo furniture item within approximately **6 blocks**. Registration and targeted removal require the configured **closed** furniture model.

### Permissions

| Node | Default | Purpose |
| --- | --- | --- |
| `sylvpay.use` | Everyone | Access the ticket machine command. |
| `sylvpay.admin` | Operators | Manage and inspect stations/gates and reload configuration. |

## Quick-start example

1. Define a station in `stations.yml`:

   ```yaml
   stations:
     sylvcentral:
       name: "Sylvarion Central"
       zone: 1
     airport:
       name: "Sylvarion Airport"
       zone: 3
   ```

2. Set up `fares.yml` with your preferred defaults, route overrides and/or zone fares. For example:

   ```yaml
   fares:
     default-fare: 3.0
     same-station-fare: 0.0
     reverse-routes: true
     zone-based: true
     minimum-entry-balance: 1.0
     maximum-journey-minutes: 180
     zone-fares:
       1: 1.50
       2: 2.50
       3: 3.50
     routes:
       sylvcentral:
         airport: 4.00
   ```

   In this example, the explicit Central-to-Airport fare of **£4.00** takes priority over the zone-based calculation. Currency symbol and formatting are controlled separately in `config.yml`.

3. Set the Nexo furniture IDs in `config.yml`:

   ```yaml
   faregates:
     enabled: true
     models:
       closed: "sylv_faregate_closed"
       open: "sylv_faregate_open"
     tap-cooldown-ms: 1500
     opening:
       timeout-ticks: 400
       clearance-radius: 0.85
       lane-half-width: 1.0
   ```

   This is an **excerpt**, not a replacement for your full `faregates` section; keep your sounds and message configuration too.

4. Look at the closed furniture at the entrance and run:

   ```text
   /sylvpay gate set sylvcentral entrance
   ```

5. Look at the closed furniture at the destination exit and run:

   ```text
   /sylvpay gate set airport exit
   ```

6. Use `/sylvpay machine` to purchase a card, then hold it in your main hand and right-click the entrance gate. Travel to the destination and tap the exit gate to complete and pay for the journey.

## Configuration files

| File | Purpose |
| --- | --- |
| `config.yml` | Currency, card appearance and pricing, ticket-machine GUI/buttons, messages, Nexo models, sounds, gate timing and clearance. |
| `stations.yml` | Station IDs, display names and optional numbered zones. |
| `fares.yml` | Default fares, route overrides, reverse-route behaviour, zone fares, minimum entry balance and journey duration. |
| `gates.yml` | Persisted gate UUIDs, stable numerical IDs, station/direction assignments and physical locations. |
| SQLite database (plugin data folder) | Card records, balances, active journeys and transaction history. |

`gates.yml` and the SQLite database contain operational state. Do not commit live copies to a public GitHub repository.

## Gate replacement and recovery

If you break a registered Nexo gate and place an identical closed model back in the **exact same location**, the new furniture will have a different entity UUID. SylvPay's recovery logic checks the saved gate position on interaction and attempts to transfer the original registration to the new entity.

- The **gate number** should stay the same.
- The **station** and **entrance/exit direction** should stay the same.
- A gate moved to a different location is **not** automatically reassigned.
- If recovery cannot safely identify the original registration, remove the stale gate by number with `/sylvpay gate delete <id>` and register the replacement normally.

To check registrations:

```text
/sylvpay stations
/sylvpay gates sylvcentral
```

## Troubleshooting

**A gate does nothing when tapped**

- Confirm Nexo is enabled and the furniture is the configured **closed** model.
- Hold a valid, active Tap2Go card in your main hand.
- Confirm the gate is registered with `/sylvpay gates <station>`.
- If the furniture was replaced, check that it is at the exact original position and that the server is running the build containing gate recovery.
- Check the server console for Nexo or SylvPay warnings.

**The gate registers but does not open**

- Check that both configured Nexo furniture IDs exist.
- Check that the card has sufficient balance and that the requested tap matches the gate direction.
- Verify the plugin has access to the SQLite database.

**A card cannot be refunded**

- Finish any active journey first; refunding a card with an active journey is intentionally blocked.

**A journey is expired**

- The plugin blocks tap-out when the configured maximum journey duration has elapsed. Administrative handling is currently required; no player-facing journey reset command is defined in the inspected command implementation.

**Changes to stations or fares are not showing**

- Run `/sylvpay reload`, or fully restart after plugin/code changes. Reloading configuration does **not** replace the running plugin JAR.

## Development notes

- Java package: `org.sylvarion.sylvpay`
- Main class: `SylvPayMain`
- Relevant components: `MachineGui`, `CardDatabase`, `StationManager`, `FareManager`, `FareGateManager`, `NexoGateBridge`.
- Nexo furniture is managed through its Java API; the bridge defers gate tapping to the next server tick after a furniture interaction.
- Currency display is configurable; stored amounts and fare calculations use numeric values.
- Source-controlled configuration examples should not contain private data or real player transactions.

## Licence

No licence has been specified in the inspected project files. Add a `LICENSE` file if you intend to grant others permission to use, modify or redistribute the source code.
