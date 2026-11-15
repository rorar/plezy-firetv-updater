# 10 — TV UI design

The plan behind the interface overhaul, and the rules the screens now follow. The build is
`app/`; the running app is described in [08](08-app-implementation.md). Everything here was
checked on the stick at **1920×1080, density 320 (2.0×) → a 960×540 dp canvas**, with a D-pad
remote as the only input.

## The problem

The first version worked but was written screen by screen, each with its own colours and paddings.
On a TV that shows as:

- **Nothing was sized for 10 feet.** 15–18 sp text is legible on a phone; across a room it is not.
- **Focus was inconsistent.** Some rows used the Fire OS default (black on blue), others nothing.
- **The main screen was a wall of similar buttons** with no answer to "what should I press?".
- **Changelogs were flattened** to plain text, so sections and bullets ran together.
- **Nothing was reusable**, so the next screen drifted again.

## The plan

Three moves, in order:

1. **One shared toolkit** (`Ui.java`) — colours, sizes, focus selectors, cards, buttons. Every
   screen is built from it, so there is exactly one place to change the look.
2. **A dashboard, not a menu.** The main screen answers three questions in order — *what is
   installed*, *what is available*, *what do I do* — and only then offers secondary actions.
3. **Render the content, don't strip it.** Release notes get real typography (headings, bullets,
   inline emphasis) through `Markdown.toStyled`.

## Layout grid

| Token | Value | Why |
|---|---|---|
| Screen padding | 32–48 dp | Keeps text off the TV's overscan edges |
| Screen background | `#101820` | Dark, low-glare |
| Surface (cards/panes) | `#18222D`, 8–10 dp radius | Separates content from the background |
| Body text | `#E2E9F0` at 17 sp | Comfortable from a couch |
| Muted text | `#96A8B6` at 15 sp | Secondary lines only |
| Section label | `#6EA8C8` at 16 sp, ALL CAPS | Groups rows without a heavy header |
| Accent / focus fill | `#2F6E8F` | Matches the Fire OS highlight |
| Primary action | `#234E66` resting → `#3F86AD` focused | The one button the screen wants |
| Row height | ≥ 50 dp (`minimumHeight`) | Big enough for a remote's focus ring |

Two rules are non-negotiable: **text is large**, and **focus is unmistakable** (white on the
accent fill, everything else light grey on the dark surface).

## Focus and the D-pad

- Every interactive row is `focusable` and reaches the focus style through a single
  `StateListDrawable`, so nothing can render the unreadable default.
- Focus **order is top-to-bottom** in the order actions matter: the primary action first, then
  secondary actions, then Settings.
- The screen **takes focus on the most useful control** when it opens (the check button on the
  main screen; the version list in the picker; the value list in the interval picker).
- Lists own the vertical axis; **left/right moves between panes** (version list ↔ notes, numbers ↔
  units). In the interval picker OK advances (value → unit → Save); in the version picker **OK
  installs the highlighted version in place**, with the progress shown on the same screen. The
  remote's **Back key always cancels** — no screen spends a button on it.
- In a two-pane picker **each pane keeps its own selection visible** while the other has focus, so
  moving across never hides what was chosen: the focused row shows the full accent fill, the
  selected-but-unfocused row a dimmer fill. (The interval picker is the only two-pane screen with
  two *chosen* values; the version picker's other pane is read-only notes.)
- A `ScrollView` wraps any screen that can exceed 540 dp, and it **scrolls to the focused row**
  automatically, so nothing is ever unreachable.

## Screens

### Main — a dashboard

```
↑ Plezy Updater
Keeps com.edde746.plezy up to date on this TV.
┌───────────────────────────────────────────────┐
│ ✓ Plezy is up to date                          │   ← glyph + colour by state
│   Installed 2.26.0   ·   latest 2.26.0         │   ← the two versions
│   (one line: what to do next)                  │   ← only when there is something to do
└───────────────────────────────────────────────┘
[ ⤓ Install 2.26.0 ]      ← primary, dim accent
[ ▤ What's new ]          ← only while a release is selected
[ ↻ Check for updates ]   ← default focus
[ ⧉ Choose version… ]     ← only when "Allow older versions" is on
[ ☰ Settings ]
Checks every 3 days while the TV is on. An update needs one confirmation…
Free space: 0,91 GB
```

The headline colour carries the state at a glance — green up-to-date, bright accent update
available, amber not-installed, red check-failed — and a glyph repeats it (see
[Icons](#icons)). A single status card replaced the old stack of loose lines, and the version
comparison is spelled out (`Installed … · latest …`) so the user never has to infer it.

### Settings — grouped rows

Sections **User settings**, **Advanced settings**, **Developer settings**, **Diagnostics**. Each
row is a `Switch` with a one-line description underneath, and dynamic rows (interval, uninstall)
update their own summary. Grouping turns a flat list into something scannable.

### Check interval — two panes

Numbers `1…30` left, units **Hours / Days / Weeks / Months** right, live "Every 3 days" preview on
top, **Save** at the bottom. The chosen number and the chosen unit each stay highlighted when the
D-pad leaves their column, so the preview always matches something visible. This is the shape that
stays usable with only a D-pad, and it needs no library (see
[08](08-app-implementation.md#check-interval) for why a prebuilt picker is not an option here).

### Choose version — two panes, install in place

Versions newest-first on the left, the highlighted release's notes on the right. Up/down moves the
version and the notes follow; right moves into the notes pane to scroll a long changelog, left comes
back. **OK installs the highlighted version without leaving the screen** — the download/verify/install
progress and the final "Installed 2.26.0." line appear at the bottom of this same view, so the user
never bounces back to the dashboard to press Install. (The dashboard still owns the first install,
when Plezy is not yet present.)

### Changelog — styled release notes

Headings are bold, `#6EA8C8` and 120 % of body size; bullets hang-indent under their text; inline
bold/code/links keep emphasis. The same renderer feeds both the two-pane picker's notes pane and
the single-release **What's new** screen.

## Icons

Every glyph is a hand-written `<vector>` drawable in `app/res/drawable/`. There is no icon pack
and no AndroidX: the build is `aapt2`/`javac`/`d8` with no resource merging, so a dependency like
Material Icons cannot be linked in — the paths are written by hand. They are drawn through `Ui`
and sized for the couch (22–28 dp), never smaller than the text beside them.

| Glyph | Drawable | Used for |
|---|---|---|
| ↑ up arrow | `ic_up` | the title; **update available** |
| ↓ down arrow | `ic_down` | an **older** release is selected |
| ⤓ down-arrow-into-tray | `ic_download` | **Install update** |
| ↻ circular arrow | `ic_refresh` | **Check for updates**; the "checking…" state |
| ▤ page | `ic_news` | **What's new** |
| ⧉ layers | `ic_versions` | **Choose version…** |
| ☰ sliders | `ic_settings` | **Settings** |
| ✓ check | `ic_check` | up to date |
| ⚠ triangle | `ic_warning` | Plezy is not installed |
| ⊗ circled cross | `ic_error` | the update check failed |
| ⓘ circled i | `ic_info` | a release is selected |

Two rules:

- **Icons carry state, not decoration.** The status glyph takes the same colour as the headline,
  so a green ✓ and a red ⊗ read before the words do.
- **Icons match their label.** A row's glyph is tinted by the row's state through a
  `StateListDrawable` (`Ui.stateIcon`) — white on the focus fill, dimmed when disabled, body
  colour otherwise — so it never contradicts the text.

The launcher icon and the TV banner are separate original artwork; the sources and the redraw
scripts live in [`design/`](../design/README.md).

## What was deliberately not done

- **No AndroidX / Leanback.** The build is hand-rolled (`aapt2`/`javac`/`d8`) with no resource
  merging, so `BrowseSupportFragment`, `VerticalGridFragment` and friends cannot be linked in. The
  D-pad behaviour above is implemented directly.
- **No animations or transitions.** On a low-memory stick, a static focus ring is cheaper and
  clearer than movement.
- **No custom focus ring beyond a fill.** A colour change plus white text is unambiguous at 10 feet
  and costs nothing.

## Verifying the look without a screen

The harness cannot see images, so the look is checked two ways that *can* be scripted:

- `uiautomator dump` for **structure**: which rows exist, their `bounds` (to catch overflow past
  1080 px) and the focused node.
- `adb exec-out screencap -p` + a **pixel histogram** for **typography**: count pixels of each
  `Ui` colour and measure text ink heights. Heading ink 30 px vs body 25 px confirmed the 1.2×
  scale; a heading-coloured band per section confirmed the styling.
- The same screenshot check confirms the **icons**: each glyph's column is sampled for its state
  colour (green ✓, accent ↑, dimmed download when Install is disabled, white ↻ on the focused
  row), and the crop is printed as ASCII so the *shape* is checked, not just that ink exists.
