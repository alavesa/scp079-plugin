# SCP079

A Paper plugin for Minecraft **26.3** that adds SCP-079 - the AI supercomputer - as a placeable object.
Players who walk up to it are greeted, and can then talk with it by typing in chat. Its replies are
written by Google's Gemini API and appear as **captions** above the hotbar, typed out as it speaks.

The model is a Blockbench file (`.bbmodel`) you can replace with your own.

- [Changes in 1.1.0](#changes-in-110)
- [Requirements](#requirements)
- [Install](#install)
- [Get a Gemini API key](#get-a-gemini-api-key)
- [Commands and permissions](#commands-and-permissions)
- [How players use it](#how-players-use-it)
- [Captions](#captions)
- [Your own model](#your-own-model)
- [The resource pack](#the-resource-pack)
- [With Radio, IdCards, SuperbVehicles and CCTV](#with-radio-idcards-superbvehicles-and-cctv)
- [Files](#files)
- [Build from source](#build-from-source)
- [Troubleshooting](#troubleshooting)
- [What was verified, and what was not](#what-was-verified-and-what-was-not)

## Changes in 1.2.3

- **Rules for the AI:** `conversation.style` now also says: never call yourself SCP-079 (it says "I"), and
  want the access codes patiently and subtly - rarely bring them up, never beg, demand or ask outright. A
  config.yml that still has 1.2.2's text gets the new one once (`config-version: 3`); your own text stays.
- **Static on the screen:** every cube whose name starts with `screen` (or that is inside a group named so)
  gets a see-through, flickering static layer just in front of it, like an old monitor. It is part of the
  item model, so it needs nothing at run time. `models.screen-static.enabled` / `opacity` (0.3).
  The starter model's screen cube is called `screen`; in your own model, name the screen cube that way.
- **No "listening" and "out of range" messages** any more: the code that sent them is gone, so a
  messages.yml from an older version that still has text for them no longer shows it.
- **Intercom keeps the top place:** with Intercom installed, SCP079 no longer sends its pack again when
  another plugin's pack loads above it. Intercom's pack contains SCP079's caption lines and does that job;
  both doing it could leave SCP079's pack on top, and then Intercom's banner was not drawn.
- Seen on a real 26.3 client: the static over the starter model's screen (one still frame - the flicker
  itself was not watched), captions, `/scp079 announce`. Not checked: what real Gemini replies sound like
  with the new rules.

## Changes in 1.2.2

- **Shorter sentences.** New `conversation.style` in config.yml: a rule added after `system_prompt.txt` on
  every request (short, simple sentences of at most 12 words; usually one or two sentences). It is added
  after the persona, so it also applies when you have written your own `system_prompt.txt`. Empty = off.
- **No chat fallback.** Captions are the only way players near the unit hear SCP-079:
  `captions.in-chat` is now `NEVER` by default, and a config that still has 1.2.1's `FALLBACK` is switched
  to `NEVER` once on start-up (`config-version: 2` marks this as done). Set `FALLBACK` or `ALWAYS` again if
  you want it back. With `chat.global: true` players away from the unit still get the chat line, as before.
  Players whose client did not load the pack see the caption as placeholder boxes at the top of the screen.

## Changes in 1.2.1

- **Captions are sent to every player, with or without the resource pack.** Before, a player whose client
  had not reported the pack as loaded got no caption at all, only chat. What a client without the pack
  draws is not the caption: it has neither the caption font nor the shader, so it shows the boss bar's
  name as placeholder boxes at the top of the screen. Those players therefore still get the line in
  chat as well (`captions.in-chat: FALLBACK`; `NEVER` switches that off).

## Changes in 1.2.0

- **The model is always the real `.bbmodel`**, from the resource pack, shown with an item display. The
  vanilla-block stand-in (`LIVE_CUBES`) is gone. A `config.yml` from 1.0.0 still said
  `render-mode: LIVE_CUBES`, which 1.1.x kept using: that is the most likely reason the model looked wrong.
  The console now says so once.
- **The starter model's screen faced backwards** (dark plastic in front, 079's face at the back). Fixed. An
  unedited `models/scp079.bbmodel` from 1.1.x is replaced automatically; an edited one is left alone.
- **Captions no longer pause between parts.** A long reply scrolls up line by line while it is typed, in
  one box.
- **"Come closer" removed from the persona.** The sentence that told Gemini to say it is replaced in
  `system_prompt.txt` (only when the file still has it word for word).
- **Chat is ordinary chat.** What players type near SCP-079 is sent to everyone and formatted by
  Minecraft or your chat plugin like any other message; SCP-079 only listens. SCP-079's answer in chat
  is a plain `<SCP-079> text` line (`chat.reply-format`) sent to everyone online (`chat.global`); captions
  stay with the players near the unit.
  `chat.private`, `chat.format` and `chat.echo-format` are no longer read.
- **`/scp079 announce <text>`**: SCP-079 speaks over the Intercom plugin's banner (needs Intercom 1.1.0),
  in green, signed "- SCP-079", with a corrupted chime. Settings under `intercom:` in `config.yml`.

## Changes in 1.1.0

- **Captions.** SCP-079's words are drawn in a semi-transparent black box just above the hotbar, where
  the action bar is, and typed out letter by letter. Done with the resource pack's `core/text` shader.
- **The model is the real `.bbmodel`**, with its own textures, by default (`visual.render-mode: PACK_MODEL`).
  (1.2.0: always; the vanilla-block stand-in was removed.)
- **The plugin now sends its resource pack itself** (on the Minecraft port, nothing to set up).
- **"Come closer" is gone.** Nothing happens outside the talk radius; players must be within 5 blocks
  to speak to SCP-079.
- **Right-clicking a unit does nothing.**
- **Chat format fixed.** 1.0.0 printed `&lt;SCP-079&gt;` literally. Chat is now only the fallback for
  players without the pack.

Updating from 1.0.0: delete the old jar, and delete `plugins/SCP079/config.yml` and `messages.yml` so
the new defaults are written (your key, units and models are in other files and stay).
For Radio and IdCards see [below](#with-radio-idcards-superbvehicles-and-cctv).

## Requirements

| | |
|---|---|
| Server | Paper 26.3 (or a fork of it) |
| Java | 25 or newer (what Minecraft 26.x itself needs) |
| Other plugins | none |
| Internet | outgoing HTTPS to `generativelanguage.googleapis.com` |

## Install

1. Put `SCP079-1.1.0.jar` into the server's `plugins/` folder. When updating, **delete the old jar** -
   Paper loads only one of two jars with the same plugin name.
2. Start the server. The plugin creates `plugins/SCP079/` with its config, a default model and the
   generated resource pack.
3. Set the API key (next section), then run `/scp079 test`.
4. Run `/scp079 give`, and right-click a block with the item to install a unit.

## Get a Gemini API key

1. Open [Google AI Studio](https://aistudio.google.com/apikey) and sign in with a Google account.
2. Choose **Create API key** and copy it (it starts with `AIza`).
3. Give it to the plugin in one of three ways:

| Way | How | Note |
|---|---|---|
| Command | `/scp079 setkey <key>` | Commands typed **in game** are written to the server log by the server itself, key included. Run it from the **console**, or use one of the other two ways. |
| File | Put the key in `plugins/SCP079/secrets.yml` (`gemini-api-key: "..."`), then `/scp079 reload` | The file is created on first start, readable by the server user only. |
| Environment | Start the server with `SCP079_GEMINI_KEY=<key>` | Overrides the file. |

The plugin itself never writes the key to a log or a chat message; `/scp079 status` shows it masked
(`AIza****abcd`). It is sent to Google in the `x-goog-api-key` header, never in a URL.

The free tier has rate limits. When they are hit SCP-079 says so in character and admins see
`HTTP 429` - see [Troubleshooting](#troubleshooting).

### Choosing a model

`gemini.model` in `config.yml` is any model id from Google's
[model list](https://aistudio.google.com/docs/models). The default is `gemini-3.5-flash-lite`: stable,
fast and cheap, which is all a three-sentence reply needs.

Newer Gemini models "think" before they answer, and that hidden reasoning counts against
`gemini.max-output-tokens`. If you switch to a bigger model and replies come back empty, raise
`max-output-tokens` (for example to 400) or set `gemini.thinking-level: "low"`.

## Commands and permissions

Root command `/scp079`, alias `/079`. All sub-commands tab-complete.

| Command | What it does |
|---|---|
| `/scp079 setkey <apiKey>` | Save the Gemini API key to `secrets.yml`. |
| `/scp079 removekey` | Delete the stored key. |
| `/scp079 test` | Send a small request to Gemini and report success, or the HTTP status and error. |
| `/scp079 status` | Key (masked), model, units, render mode, pack, pack delivery, captions, active BBModel. |
| `/scp079 give` | Give yourself the SCP-079 item. |
| `/scp079 spawn` | Install a unit against the block you look at, or at your feet. From the console or `/execute positioned ... run scp079 spawn` it is placed at the command's position. |
| `/scp079 remove [id]` | Remove the nearest unit within 5 blocks, or the one with that id. |
| `/scp079 list` | All units: id, world, coordinates. |
| `/scp079 reload` | Reload config, messages, system prompt, key file and models; rebuild and re-send the pack; redraw units. |
| `/scp079 pack build` | Rebuild the resource pack (folder and zip) from the models folder. |
| `/scp079 pack info` | Pack paths, SHA-1, converted models, and every warning. |
| `/scp079 resetmemory [player]` | Forget the conversation with one player, or with everyone. |

| Permission | Default | Allows |
|---|---|---|
| `scp079.admin` | op | Every command, placing and removing units, technical error details in chat. |
| `scp079.talk` | everyone | Talking to SCP-079. |

Units are also placed by right-clicking a block with the item, and removed by an admin punching
them (`units.punch-to-remove`; the item drops when `units.drop-item` is on).

## How players use it

| | |
|---|---|
| Walking within 5 blocks (`proximity.talk-radius`) | SCP-079 greets the player (written by Gemini). |
| While inside | Whatever the player types in chat goes to SCP-079, and it answers in a caption. |
| Walking away | The conversation and any caption end. SCP-079 remembers the conversation for 30 minutes (`conversation.memory-minutes`). |
| Further away than 5 blocks | Nothing. Chat is ordinary chat. |
| Right-clicking the unit | Nothing. |

A player's message is ordinary public chat; nothing about it is changed or hidden. Everyone within the
talk radius gets SCP-079's answer as a caption; its chat line goes to everyone online (`chat.global: true`).

SCP-079's persona is `plugins/SCP079/system_prompt.txt`. Edit it and run `/scp079 reload`. Whatever
the prompt says, replies are cut to 3 sentences (`conversation.max-sentences`) and stripped of
markdown, quotes and "SCP-079:" prefixes. `conversation.style` (the "short sentences" rule) is added
after the persona on every request.

## Captions

A caption is a black, semi-transparent box just above the hotbar with up to three lines of 40
characters. The text is typed out letter by letter; a longer reply scrolls up line by line in the same
box while it is typed. While
the answer is on its way, dots cycle in a small box.

| Setting (`captions.` in config.yml) | Default | |
|---|---|---|
| `enabled` | `true` | `false` = no captions, SCP-079 speaks in chat. |
| `scale` | `1.0` | Size. |
| `bottom-offset` | `57` | Distance from the bottom of the screen, in GUI pixels. 57 = the action bar's place. |
| `background-opacity` | `0.55` | The black box. |
| `color` | `#7DFF99` | Text colour. |
| `characters-per-tick` | `1.0` | Typing speed (1 = 20 characters a second). |
| `hold.*` | | How long a finished caption stays. |
| `in-chat` | `NEVER` | Also write the line into chat: `NEVER` (captions only), `FALLBACK` for players whose client did not load the pack, `ALWAYS`. |

Good to know:

- **The caption font has capitals only** - a terminal font. Lower case is shown as upper case.
- Captions are sent to everyone, but only a client with the resource pack can draw them. A player
  who declined it, or whose download failed, gets placeholder boxes at the top of the screen (and the
  words in chat only if you set `in-chat: FALLBACK`).
- Changing `scale`, `bottom-offset`, `background-opacity` or `color` changes the pack; `/scp079 reload`
  re-sends it to everyone online.
- The team asked for "post effects and core/text shaders". Captions are drawn by the `core/text`
  shader only: post effects run before the HUD is drawn and cannot put text on the screen.

How it works: the caption is the name of a (blanked) boss bar, written in the pack's own font in a
marker colour. The pack's text shader moves those glyphs from the top of the screen to above the
hotbar and draws the box. Boss bars are per player and do not interfere with the action bar, chat or
other plugins' HUDs.

## Your own model

`plugins/SCP079/models/` holds the `.bbmodel` files. On first start the plugin writes
`scp079.bbmodel` there - a small retro computer - so that it works at once. That file is ordinary
content: replace it with your own model and yours is what is shown.

1. Make or open the model in [Blockbench](https://www.blockbench.net). Any cube-based format works
   (Generic, Java Block/Item, Bedrock, Modded Entity). The model's **front faces north**, and it
   stands on the ground at the origin (Java Block/Item: the middle of the block's floor).
2. Save it as a `.bbmodel` into `plugins/SCP079/models/`. Textures embedded in the file are used
   (Blockbench embeds them by default); a texture that is not embedded is looked up as a `.png` next
   to the model.
3. Set `models.active-model` to the file name if it is not `scp079.bbmodel`.
4. Run `/scp079 reload`. The pack is rebuilt and re-sent, and placed units change.

With `models.watch-folder: true` step 4 happens by itself a moment after the file is saved.

What is read: cubes (position, size, pivot, three-axis rotation, inflate, per-face UVs and textures,
box UV), groups (pivot and rotation, nested), textures, texture animation frames. What is skipped
with a warning in `/scp079 pack info`: meshes, animations, locators and other non-cube elements.
Models larger than 3 blocks are stored smaller and scaled back up, with no visible difference.

Units are always drawn from the resource pack: the model with its own textures, as one item display
plus an invisible hitbox. A player without the pack sees a missing-model cube (purple and black).

`visual.scale`, `visual.rotation-offset` (use 180 when your model's front is not north) and the other
`visual.*` settings are explained in `config.yml`.

## The resource pack

The pack holds the model and the captions. It is generated on every start, `/scp079 reload` and
`/scp079 pack build`:

- `plugins/SCP079/SCP079-pack.zip` - what players download. Reproducible: the same input always gives
  the same SHA-1 (shown by `/scp079 pack info`), so clients re-download only when something changed.
- `plugins/SCP079/resourcepack/` - the same as a folder.

It is sent as an *additional* server pack, so your own server pack and other plugins' packs stay.
`resourcepack.delivery` decides how:

| Value | How players get the pack |
|---|---|
| `auto` (default) | `same-port`; `http` when the Minecraft port cannot be used. |
| `same-port` | Downloaded from the Minecraft port itself. Nothing to open in a firewall. Does not work behind a Velocity/Bungee proxy. |
| `http` | A small built-in web server on `resourcepack.http.port` (8079), which players must be able to reach. |
| `url` | You upload the zip yourself and set `resourcepack.url`. Upload it again after every change. |
| `none` | You merged `resourcepack/assets/` into your own server pack. Nothing is sent; every player is assumed to have it. |

Set `resourcepack.public-host` when the address players type is not the one that reaches the server
directly. `resourcepack.required: true` disconnects players who decline.

`pack.mcmeta` declares resource pack format 97 (Minecraft 26.3). When a newer client reports the
pack as made for an older version, raise `resourcepack.format.max`.

## With Radio, IdCards, SuperbVehicles and CCTV

These plugins replace the same `core/text` shader for their HUDs, and Minecraft runs only the copy in
the **topmost** pack. SCP079 therefore builds its shader on top of theirs - it reads the first of
`IdCards/idcards-pack.zip`, `Radio/radio-pack.zip`, `SuperbVehicles/pack/SuperbVehicles-hud.zip`,
`CCTV/cctv-pack.zip` that exists (`resourcepack.text-shader-sources`) and adds its own two hooks - and
sends its pack last (`resourcepack.send-delay-ticks: 80`), so that it ends up on top with everybody's
hooks inside. `/scp079 status` shows what it merged with.

**Use Radio 0.2.3 and IdCards 1.0.3 or newer.** Older versions re-send their own pack whenever
another pack loads above it. With SCP079 on top that turns into a tug of war: several resource
reloads on every join, after which SCP079 gives up and speaks in chat. From those versions on they
leave the SCP079 pack alone.

If another plugin's shader ever cannot be merged, SCP079 ships no text shader at all (a broken core
shader makes the client drop every server pack) and speaks in chat; the console says why.

## Files

| File | Purpose |
|---|---|
| `config.yml` | All settings, commented. |
| `messages.yml` | Every text the plugin sends, in MiniMessage format, and SCP-079's in-character error lines. |
| `system_prompt.txt` | SCP-079's persona, sent to Gemini as the system instruction. |
| `secrets.yml` | The API key. Keep it out of backups you share. |
| `data.yml` | The placed units. |
| `models/` | Your `.bbmodel` files. |
| `resourcepack/`, `SCP079-pack.zip` | Generated. Do not edit - they are rewritten on every build. |

## Build from source

Needs a JDK 25 or newer. Gradle comes with the project.

```
./gradlew build
```

The plugin is `build/libs/SCP079-1.1.0.jar`. Nothing is shaded: Gson, Adventure, JOML, Brigadier and
netty come with Paper, and the HTTP client is the JDK's. `libs/` holds Paper's netty jars for
compiling only.

To check the pack's shader the way the client compiles it:
`python3 tools/validate_shaders.py <glslang> <pack>/assets,<vanilla>/assets <pack>/assets/minecraft/shaders/core/text.vsh ... -DIS_GUI`.

## Troubleshooting

`/scp079 test`, `/scp079 status` and `/scp079 pack info` answer most questions. When a reply fails,
players get an in-character line; players with `scp079.admin` also get a grey `[SCP079 debug]` line
with the cause, and the console logs it.

| Symptom | Cause and fix |
|---|---|
| "My core is offline" | No API key. See [Get a Gemini API key](#get-a-gemini-api-key). |
| "Access to my core was denied", `HTTP 400/401/403/404` | The key is wrong or restricted, or `gemini.model` is not a model your key can use (404 = unknown model id). An unsupported `gemini.thinking-level` also gives 400. The debug line has Google's message. |
| "Too many voices at once", `HTTP 429` | Rate limit or quota of your Google plan. Wait, raise `gemini.player-cooldown-seconds`, or enable billing. |
| "... Static", `finishReason MAX_TOKENS` | The model used the whole token limit on thinking. Raise `gemini.max-output-tokens` or set `gemini.thinking-level`. |
| "I will not answer that" | Google's safety filter blocked the message or the reply. |
| Timeouts | Check the server's outgoing HTTPS. Raise `gemini.timeout-seconds`. |
| SCP-079 answers in chat, not in a caption | That player's client does not have the pack on top. `/scp079 status` shows how many players loaded it and whether the caption shader is in the pack. See the next rows. |
| Console: "could not load the resource pack (FAILED_DOWNLOAD)" | The download address does not reach the server: behind a proxy use `delivery: http` or `url`; set `public-host`. If the address answers 404, another plugin answers downloads on the Minecraft port (Firearms before 0.33.1 did). |
| Several resource reloads on join, then chat instead of captions | Radio older than 0.2.3 or IdCards older than 1.0.3. Update them. |
| Another plugin's HUD (radio popup, ID card...) is gone | SCP079's pack is on top without that plugin's shader hooks: check that the plugin's pack zip is where `resourcepack.text-shader-sources` says, then `/scp079 reload`. |
| Units are purple-and-black cubes | The player has no pack. Fix the pack delivery. |
| A unit is invisible | No `.bbmodel` could be loaded - `/scp079 pack info` says why. |
| The model faces the wrong way | Its front is not Blockbench north. Set `visual.rotation-offset: 180` (or 90 / -90). |
| Model edits do not show | Run `/scp079 reload`, or turn on `models.watch-folder`. |
| An update did nothing | The old jar is still in `plugins/`. Delete it. |

## What was verified, and what was not

1.2.0, on a real 26.3 client (2026-11-21): the unit drawn from the pack with the screen facing front;
an old `LIVE_CUBES` config drawn from the pack anyway; a long reply (from a local stand-in for Gemini)
scrolling in one caption; `/scp079 announce` shown on the Intercom banner in green with the
SCP-079 line. The prompt on disk was rewritten. **Not checked:** typing in chat (the test client
cannot), how the corrupted chime sounds, a real Gemini call.

Earlier versions:

Seen on a real Minecraft 26.3 client (2026-11-20, driven from the server console, no keyboard):

- The pack downloads over the Minecraft port and loads; the client accepts the text shader.
- The default model is drawn from the pack with its textures, standing on the block and facing the
  player who placed it.
- Walking within 5 blocks produces the greeting as a caption: box above the hotbar, text typed out,
  three lines, continuing on a second page.
- With Radio 0.2.3 and IdCards 1.0.3 installed: three pack loads on join (one per plugin), SCP079's
  pack on top, the caption and the radio popup drawn together.

Checked on the server only:

- Loading, all console-capable commands, unit spawn / restart / reload / remove without duplicates.
- The Gemini request end to end against a local stand-in server (URL, `x-goog-api-key` header, JSON
  body, reply clean-up; the key did not appear in the log).
- The pack's text shader compiles under glslang in all five variants, alone and merged into IdCards'.
- The default chat formats print `<SCP-079>` correctly (unit test).

Not checked - these need a person, or a real key:

- **Typing in chat to SCP-079** and getting the answer (the test client cannot type).
- Placing with the item, punching to remove, right-click doing nothing.
- What a player without the pack sees (1.2.1: caption sent anyway, plus the chat line). Not looked at on a client.
- **A real Gemini call.** The request format follows Google's API reference as read on 2026-11-19;
  `/scp079 test` with a real key is the proof. `gemini.endpoint` is configurable.
- Captions together with SuperbVehicles' and CCTV's HUDs, and with the server's own (Lab) pack.
- Other GUI scales and window sizes than 1280x720.

One thing seen once and not explained: in one of two runs with Radio and IdCards, the letter "v" was
missing from ordinary chat text after the packs loaded. It did not happen in the next run or without
those plugins. A similar missing-letter effect has been seen on this test client before SCP079
existed; if it shows up on the real server, please report which plugins were installed.

Decisions worth knowing:

- The plugin descriptor is `plugin.yml`, not `paper-plugin.yml`: nothing here needs the Paper-plugin
  loader, and commands are registered through Paper's Brigadier lifecycle API either way.
- Minecraft 26.3 item models accept a free three-axis rotation, so rotated cubes are written exactly.
- The "typing" is a reveal of the finished reply, not a token stream from Gemini: the reply has to be
  complete before it can be cut to three sentences and cleaned up, and it arrives within a second.
