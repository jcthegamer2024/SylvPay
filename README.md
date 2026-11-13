# SylvPay

**Tap2Go transport cards, ticket machines, fare gates, and validators for Sylvarion City.**

SylvPay is a Paper Minecraft plugin that connects a persistent transport-card balance to a Vault economy. Players buy and recharge cards at ticket machines, tap into stations, and pay a journey fare when tapping out.

Current version: **0.4.0-beta**.

## Features

- Physical Tap2Go cards with unique IDs, persistent item metadata, and configurable resource-pack models.
- Card purchases and top-ups using a decimal number keypad, with up to two decimal places.
- Balance checks, remaining-balance refunds, and paginated transaction history for the inserted card.
- Nexo furniture ticket machines, directional entrance/exit gates, and stationary validators.
- Persistent journeys shared between gates and validators: enter through either system and exit through the other.
- Station-to-station fares, reverse-route fallback, numbered-zone fares, and same-station pricing.
- Configurable GUI slots, Nexo glyph titles, item models, sounds, currency formatting, and texture-only buttons.
- MiniMessage messages and display branding in `lang.yml`.
- SQLite storage for cards, balances, journeys, and transaction history.

## Requirements

| Component | Project configuration |
| --- | --- |
| Server | Paper for Minecraft 26.3 |
| Java | Java 25 toolchain |
| Nexo | Required; compiled against Nexo 1.30.2 |
| Vault | Required; compiled against VaultAPI 1.7 |
| Economy provider | A registered Vault economy provider, such as CMI's economy |
| CMI | Optional; another Vault-compatible economy provider can be used |

The build uses Paper API `26.3.build.+`; `paper-plugin.yml` declares API version `26.1`. Adventure/MiniMessage is supplied through Paper. SQLite JDBC is loaded at runtime; the build does not bundle a separate JDBC driver. SylvPay disables itself if it cannot initialise SQLite or find a Vault economy provider.

## Installation and development server

1. Build using an installed Gradle distribution or the IDE's Gradle integration:

   ```text
   gradle build
   ```

   This checkout does not include Gradle wrapper launch scripts.

2. For a normal server, copy `build/libs/SylvPay-0.4.0-beta.jar` into `plugins/`, alongside Nexo, Vault, and an economy provider. Remove older SylvPay JARs before starting.
3. Start the server to generate `plugins/SylvPay/` and its configuration files.
4. Configure your furniture IDs, resource pack, station definitions, fares, and GUI.
5. Run `/sylvpay reload` after configuration edits. Restart after installing changed plugin code.

For local development, use the IDE's Gradle `runServer` task or:

```text
gradle runServer
```

The task uses the `run/` directory and supplies the built plugin directly from `build/libs/`. **Do not also put a SylvPay JAR in `run/plugins/`**, as this creates duplicate plugin entries. Keep the `run/plugins/SylvPay/` data folder.

Stop the test server before rebuilding if Windows locks the plugin JAR. The `run/` and `build/` directories are excluded from Git.

## Using a ticket machine

Players right-click furniture matching `ticket-machine.furniture-id` to open the machine. Machines need no individual registration. Administrators can also open it with `/sylvpay machine`.

- **Buy Card:** enter a starting balance and confirm. Vault charges the starting balance plus `card.price`.
- **Balance:** insert a card; its balance is displayed in chat.
- **Top Up:** insert a card, enter an amount, and confirm payment from your Vault account.
- **Refund:** insert a card to refund its remaining balance immediately. There is no separate confirmation screen. The purchase price is not refunded, and the card is permanently deactivated.
- **Transaction History:** insert a card to view issuance, top-ups, refunds, and completed journeys, with 21 entries per page.

To insert a card, pick it up from your inventory and place or drag it into the reader slot. Shift-click and hotbar transfers are blocked. The machine uses that specific card and returns it when the interface closes; inventory overflow is dropped at the player's location.

Cards with active journeys cannot be refunded. The original buyer UUID is recorded, but use is based on possession of a valid card, rather than an enforced ownership restriction.

## Commands and permissions

| Command | Purpose |
| --- | --- |
| `/sylvpay` | Show command help. |
| `/sylvpay machine` | Open the ticket machine as a player. |
| `/sylvpay reload` | Reload config, language, stations, fares, and saved gate/validator assignments. |
| `/sylvpay stations` | List stations with registered gates. |
| `/sylvpay gates <station>` | List gate numbers, directions, and locations at a station. |
| `/sylvpay gate set <station> entrance` | Assign the targeted closed gate as an entrance. |
| `/sylvpay gate set <station> exit` | Assign the targeted closed gate as an exit. |
| `/sylvpay gate remove` | Unregister the targeted closed gate. |
| `/sylvpay gate delete <id>` | Unregister a numbered gate, leaving its furniture in place. |
| `/sylvpay validator set <station>` | Assign the targeted validator to a station. |
| `/sylvpay validator remove` | Unregister the targeted validator. |
| `/sylvpay validator list` | List numbered validators and their locations. |
| `/sylvpay validator delete <id>` | Unregister a numbered validator, leaving furniture in place. |
| `/sylvpay debug` | Toggle diagnostic logging. |
| `/sylvpay debug on` / `off` / `status` | Control or inspect diagnostic logging. |

All commands above except base help require `sylvpay.admin`, which defaults to operators. The entire `gate` command branch is player-only. Targeting commands require looking at the configured Nexo furniture within six blocks.

`sylvpay.use` defaults to everyone and controls opening machines, including furniture interaction. The machine command additionally requires `sylvpay.admin`. Gate and validator tapping do not check these permission nodes.

## Configuration files

| File in `plugins/SylvPay/` | Purpose |
| --- | --- |
| `config.yml` | Card settings, currency, GUI layout/models/titles, furniture IDs, sounds, cooldowns, and gate opening behaviour. |
| `lang.yml` | Display branding and general, gate, and validator messages. |
| `stations.yml` | Station IDs, display names, and optional positive integer zones. |
| `fares.yml` | Fare rules, minimum entry balance, and maximum journey duration. |
| `gates.yml` | Generated when assignments are saved; gate IDs, entity UUIDs, locations, stations, and directions. |
| `validators.yml` | Generated when assignments are saved; validator IDs, entity UUIDs, locations, and stations. |
| `cards.db` | SQLite database containing card and journey state. |

The bundled [config.yml](src/main/resources/config.yml), [lang.yml](src/main/resources/lang.yml), [stations.yml](src/main/resources/stations.yml), and [fares.yml](src/main/resources/fares.yml) provide the complete default configuration. The bundled `paper-plugin.yml` is plugin metadata, not an administrator configuration file.

Examples below are excerpts. Merge them into the existing sections rather than creating duplicate YAML keys.

### Card pricing, appearance, and currency

```yaml
currency:
  symbol: '£'
  position: before
  decimals: 2
  thousands-separator: true
  space: false
card:
  material: PAPER
  item-model: sylvpay:tap2go_card
  name: '<gold><bold>SylvPay Tap2Go'
  lore:
    - '<gray>Card ID: <white>{id}'
    - '<dark_gray>Top up at any ticket machine.'
  price: 2.5
  max-creation-balance: 500.0
max-balance: 1000
```

`card.price` is the non-refundable purchase charge. `card.max-creation-balance` sets the maximum preload, with a code ceiling of 500. `max-balance` limits top-ups; a non-positive value disables that balance cap. Amount entry and stored balance updates use two decimal places. Currency formatting changes display, not the economy's currency or exchange rate.

Item models use namespaced resource-pack keys. They must exist in the resource pack sent to players.

### GUI layout and texture-only buttons

Every menu uses 54 slots, numbered **0–53**, left to right and top to bottom.

```yaml
gui:
  create:
    show-confirm-item: false
    show-back-item: false
    slots:
      info: 13
      confirm: 33
      back: 53
  topup:
    show-confirm-item: false
    show-back-item: false
    slots:
      info: 13
      confirm: 33
      back: 53
  history:
    show-back-item: false
    slots:
      back: 49
```

Setting a visibility option to `false` leaves its configured slot clickable without an inventory item, allowing the GUI texture to draw the button. Set it to `true` to show the item again.

Other layout controls:

- `gui.main.slots.<action>` accepts lists of clickable slots for `create-card`, `balance`, `topup`, `refund`, `history`, and `card-reader`. One action can cover multiple slots. History takes precedence if main-menu actions overlap.
- `gui.main.show-items` controls main-menu button items independently of clickable areas.
- `gui.insert.card-slot`, `back-slot`, `info-slot`, and `continue-slot` position the reader controls. `gui.insert.show-items` hides its decorative controls; Back remains clickable, but Continue is only active when items are shown.
- `gui.numpad.slots` positions digits `0`–`9`, `decimal`, `clear`, `confirm`, and `back`. Entries under `gui.create.slots` or `gui.topup.slots` override the shared slots for that screen. The default screen-specific Confirm/Back settings therefore take priority over shared keypad settings.
- `gui.numpad.show-items` controls all keypad items, including information items. Leave it `true` when hiding only Confirm and Back.
- `gui.numpad.items.<key>.item-model` and optional `.material` customise digits, decimal, and Clear. Custom keypad buttons hide tooltips.
- `gui.numpad.info.item-model` can provide a transparent information item that retains its live tooltip.
- `gui.history.slots` positions the reader, pagination, Back, empty-history indicator, and entries. `entries` must contain exactly 21 unique valid slots. Keep controls outside entry slots and avoid overlaps.
- `buttons.balance`, `buttons.topup`, `buttons.refund`, and `buttons.back` define visible button material, model, name, and lore. An optional `buttons.create-card` section uses the same format for the main-menu purchase button. Keypad Confirm labels are generated in code.
- `gui.sounds` controls local button/keypad click feedback.

Default main-menu and Confirm/Back items are hidden, while custom glyph titles are disabled. Configure your textures, or enable visible items to make controls apparent without a custom GUI pack.

### Nexo GUI backgrounds

```yaml
gui:
  custom:
    enabled: true
    glyph: tap2gomainscreen
    title-shift: -9
    prefix: '<!italic><!bold><white>'
    suffix: ''
    insert:
      enabled: true
      glyph: tap2goinsertcard
      title-shift: -9
    create:
      enabled: true
      glyph: tap2gokeypad
      title-shift: -9
    numpad:
      enabled: true
      glyph: tap2gokeypad
      title-shift: -9
    history:
      enabled: true
      glyph: tap2gohistory
      title-shift: -9
```

`gui.custom` controls the main screen and globally enables custom titles. `create` controls Buy Card; `numpad` controls Top Up. Other screens also require their own `enabled: true`.

Glyph IDs must exist in Nexo. Plain IDs and `<glyph:id>` tags are accepted. Inline `<shift:...>` takes priority over `title-shift`; legacy `shift` and `topup-glyph` fallbacks remain supported. Normal titles use `gui.title`, `create-title`, `topup-title`, `insert-title`, and `history-title` when custom titles are disabled or rendering fails.

### Language and branding

```yaml
branding:
  plugin: SylvPay
  card: Tap2Go
messages:
  prefix: '<gold>[SylvPay] </gold>'
  balance: '<gray>Card <white>{id}</white> balance: <green>{balance}'
faregates:
  messages:
    tap-in: '<green>Tapped into <gold>{station}</gold>'
validators:
  messages:
    tap-out: '<green>Exited {station}. Fare: {fare}. Balance: {balance}'
```

These sections belong in **`lang.yml`**. Preserve the other message keys from the defaults when editing. MiniMessage formatting and message-specific placeholders are supported:

- General messages use `{id}`, `{amount}`, `{balance}`, or `{max}` as appropriate.
- Gate messages support `{station}`, `{origin}`, `{zone}`, `{origin_zone}`, `{fare}`, and `{balance}`.
- Validator messages in the active implementation support `{station}`, `{fare}`, and `{balance}`.

Branding replaces display occurrences of SylvPay and Tap2Go; it does not rename commands, internal IDs, furniture IDs, or glyph keys. GUI titles, item names, and lore remain in `config.yml`. Some GUI labels and administrative messages are still defined in Java.

On upgrade, legacy message values from `config.yml` are copied into missing `lang.yml` entries. Explicit language-file values take priority, and bundled language defaults supply missing messages. `/sylvpay reload` reloads language changes; reopen an existing GUI to see updated text.

### Stations and fares

`stations.yml`:

```yaml
stations:
  sylvcentral:
    name: 'Sylvarion Central'
    zone: 1
  sylvairport:
    name: 'Sylvarion Airport'
    zone: 3
```

`fares.yml`:

```yaml
fares:
  default-fare: 3.00
  same-station-fare: 0.00
  reverse-routes: true
  maximum-journey-minutes: 180
  minimum-entry-balance: 0.00
  routes:
    sylvcentral:
      sylvairport: 5.50
  zones:
    1: 2.00
    2: 3.00
    3: 4.00
```

Fare selection order is:

1. Same-station fare when origin and destination match.
2. Explicit origin-to-destination route.
3. Reverse route, if enabled and available.
4. Zone price for `abs(origin zone - destination zone) + 1`.
5. Default fare if zone information or the corresponding zone price is missing.

The example Central-to-Airport journey costs 5.50 because the explicit route overrides its three-zone price. There is no separate zone-pricing enable switch; configure `fares.zones` to use it. Fares must be finite, non-negative values and are rounded to two decimal places.

Tap-in checks minimum entry balance without deducting a fare. Tap-out deducts the calculated fare and records the completed journey. Insufficient funds or an expired journey leave the journey active. There is currently no journey-reset command; expired journeys require staff intervention.

### Fare gates and validators

```yaml
ticket-machine:
  furniture-id: sylv_ticket_machine
faregates:
  enabled: true
  models:
    closed: sylv_faregate_closed
  states:
    closed: ''
    open: open
  tap-cooldown-ms: 1500
  opening:
    timeout-ticks: 100
    clearance-radius: 0.85
    lane-half-width: 1.0
validators:
  enabled: true
  furniture-id: sylv_validator
  tap-cooldown-ms: 1500
```

Fare gates are disabled in the default config; validators are enabled. Furniture IDs refer to Nexo furniture definitions, rather than item-model resource keys. The base furniture must define Nexo states that change both appearance and collision. SylvPay changes states on the same entity; it no longer removes and replaces furniture to open or close a gate.

`faregates.models.closed` is the base furniture ID, retained for existing configurations. `faregates.models.open` is no longer used. `faregates.states.closed: ''` selects Nexo's implicit base state; `open: open` selects the named `open` state. Install Nexo 1.30.2 or a compatible fixed build before testing.

For the Nexo furniture definition, merge an open state into your existing closed gate. For example (replace the model key and use your existing closed hitbox):

```yaml
# Inside your existing Nexo item's Mechanics.furniture section:
hitbox:
  barriers:
    - 0,0,0
states:
  condition: "false"
  open:
    type: FURNITURE
    item_model: sylvpay:sylv_faregate_open
    hitbox:
      barriers: []
```

Use `FURNITURE`, rather than a visual-only `ITEM_MODEL` state, so the open state removes the blocking barriers. Preserve any interaction hitbox needed for tapping. `condition: "false"` prevents Nexo's normal player-click state cycling; SylvPay explicitly changes states only after card validation. Do not configure Nexo `next_after` or `reset_after` timers for these gates: SylvPay controls closing and passenger clearance. See [Nexo furniture states](https://docs.nexomc.com/mechanics/furniture-mechanic/furniture-states) for the state configuration format.

Existing placed furniture may need refreshing or replacing after updating its Nexo definition; do not delete `gates.yml`. Test the closed collision, authorised opening, crossing, timeout, and closing before using the gates with passengers.

After defining stations and reloading, look at each furniture item and assign it:

```text
/sylvpay gate set sylvcentral entrance
/sylvpay gate set sylvairport exit
/sylvpay validator set sylvcentral
```

Hold a valid card in the **main hand** and right-click to tap. Entrance gates start journeys; exit gates complete them. Validators toggle between entry and exit according to whether the card already has an active journey.

Gates close after the tapping player passes through, or after the configured timeout. Closing is delayed while a player occupies the barrier footprint. The default timeout is 100 ticks, approximately five seconds at 20 TPS. Tap-in and tap-out sounds are configured separately under each system's `sounds` section.

Gate state changes preserve the same entity UUID, assignment, and numerical ID. If furniture is replaced at the same saved position, interaction can recover its registration when the old entity is absent and there is one unambiguous match within 0.1 block. Moved furniture should be reassigned manually. Gate/validator deletion commands remove registrations without removing furniture.

## Troubleshooting and operational notes

- **Two SylvPay entries in `/plugins`:** remove extra SylvPay JARs. Gradle `runServer` already loads the build output directly.
- **GUI unchanged after reload:** reopen it. Changed Java code requires a new build and server restart; `/sylvpay reload` only reloads configuration.
- **Invisible controls:** check `show-items`, per-button visibility, enabled glyphs, and the resource pack received by the player.
- **Slots do not move:** check screen-specific keypad overrides and use slot numbers 0–53. Avoid overlapping controls and history entries.
- **No gate response:** enable fare gates, verify the furniture IDs and assignment, and hold the card in your main hand.
- **Gate cannot open:** verify the base Nexo furniture and both configured state IDs exist. If a state change fails after a card transaction commits, the server logs that staff must review the journey; it is not automatically reversed.
- **Economy failure:** purchase/top-up database failures attempt a Vault reversal. Failed reversals or refund payouts are logged for manual repayment.
- **More diagnostics:** use `/sylvpay debug on`, reproduce the issue, then `/sylvpay debug off`.

Back up `cards.db`, `gates.yml`, and `validators.yml` with the server stopped before changing persistent data or upgrading. SQLite operations currently run synchronously on the server thread.

## Source layout

All Java classes are under `org.sylvarion.sylvpay`:

| Class | Responsibility |
| --- | --- |
| `SylvPayMain` | Lifecycle, Vault setup, permissions, and commands. |
| `MachineGui` | Inventory menus, card handling, purchases, top-ups, refunds, and history. |
| `CardDatabase` | SQLite schema, balances, journey transactions, and history queries. |
| `StationManager` | Stations and persistent numbered gate assignments. |
| `FareManager` | Route, zone, and default fare selection. |
| `FareGateManager` | Gate taps, opening, crossing detection, and closing. |
| `NexoGateBridge` | Nexo interaction events, furniture targeting, and explicit state changes. |
| `ValidatorManager` | Validator assignments and the active validator tap flow. |
| `ValidatorTapManager` | Separate validator tap implementation, currently not wired into startup. |
| `LangManager` | Language loading, legacy message migration, and branding. |
| `DebugManager` | Configurable diagnostic logging. |
