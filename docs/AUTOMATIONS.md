# Automations

An automation is a list of steps stored **on the PC** and run there, triggered
from the phone, the watch, a widget, a shortcut or another app.

They are stored on the PC rather than the phone on purpose. If the phone held
the definition and sent it at run time, the bytes someone approved and the bytes
that executed would be two different things, and every control below would be
decoration.

---

## The security model

Read this part even if you skip the rest.

### Shell steps are off until you turn them on

Steps that run `cmd` or PowerShell do nothing until **Automations → Allow steps
that run CMD or PowerShell commands** is enabled on the PC, in front of the
warning. Nothing a phone sends can flip that switch. The check happens at *run*
time, not at save time, so turning it back off stops automations that were
already approved.

### Approval is bound to the body, not the name

When an automation is saved, the PC hashes the part of it that can execute —
the steps and the variable defaults — and stores that hash alongside the
approval.

- Rename it, recolour it, change its description: approval survives.
- Change one character of one command, reorder two steps: approval is revoked
  and you are asked again.

If the stored automation ever stops matching its own hash, it refuses to run at
all.

### The dialog shows the resolved command line

This is the control that matters most. A step might be written as:

```
del {{target}}
```

The confirmation dialog on the PC never shows that. It shows the command *after*
variable substitution — the actual line about to execute. A dialog that displays
a template teaches people that the answer is always yes, because the text never
says anything worth reading.

### Two trust modes

| Mode | Behaviour |
|---|---|
| **Strict** (default) | Anything that can execute is confirmed on the PC, every run |
| **Trusted** | A device you marked trusted skips the prompt — but **only** when every shell step matches the allowlist |

In trusted mode the allowlist is matched against the command **template, before
interpolation**. A command whose executable name comes out of a variable is
never waved through, because that is exactly the case an allowlist must not
approve.

### Everything else

- **Bounded runs.** Steps, loop iterations, captured output and wall-clock time
  all have limits. A loop with a bad condition stops instead of pinning a core.
- **Never elevated by default.** A step marked "as administrator" needs a
  separate switch, and still raises a UAC prompt on the machine.
- **Network and file-writing steps** are separately switchable, both off.
- **Audit log.** Every run appends a line to `automation-audit.jsonl` in the
  WinBridge data folder — append-only, separate from settings so nothing that
  rewrites settings can quietly edit the record. The last 25 runs are shown in
  the Automations tab.
- **Panic stop.** One switch that refuses everything, for when something is
  wrong and you do not want to work out which setting caused it.
- **Locked workstation.** Automations refuse to run while the PC is locked
  unless you turn that requirement off per automation.
- **Risk labels.** `safe`, `elevated-input`, `shell`, `dangerous`. The last one
  is applied when a command matches a pattern like `format`, `del /s`,
  `Invoke-Expression`, `bcdedit` or an elevated step. That detector is **not** a
  security boundary — anything on the list can be written another way — it
  exists so the dialog says "this deletes things" instead of showing a line the
  user skims past.

---

## Steps

| Type | Does | Main fields |
|---|---|---|
| `shell` | Runs a command | `command`, `shell` (`cmd`/`powershell`/`exec`), `args`, `cwd`, `timeoutMs`, `elevated`, `capture`, `name` (store output) |
| `open` | Opens a URL, file or folder | `target` |
| `window` | Focus, minimise, maximise, restore, close | `action`, `target` (title or process) |
| `process` | Start or kill | `action`, `target`, `args` |
| `key` | Presses a key | `key`, `mods` |
| `type` | Types text | `text` |
| `mouse` | Move, click, wheel | `action`, `number` (x), `value` (y), `key` (button) |
| `media` | play, pause, next, prev, toggle, seek | `action`, `number` |
| `volume` | set, mute, unmute, up, down | `action`, `number` |
| `power` | lock, sleep, hibernate, shutdown, restart, logoff, display_off | `action`, `number` (delay) |
| `clip.get` / `clip.set` | Reads or writes the PC clipboard | `name`, `text` |
| `notify` | Shows a tray balloon on the PC | `name` (title), `text`, `action` (level) |
| `file` | read, write, append, copy, move, delete, mkdir, exists, list | `action`, `path`, `dest`, `text`, `name` |
| `http` | Makes a request | `url`, `method`, `headers`, `body`, `name` |
| `delay` | Waits | `number` (ms) |
| `set` | Assigns a variable from an expression | `name`, `value` |
| `if` | Branches | `cond`, `then`, `else` |
| `while` | Loops while true | `cond`, `do` |
| `repeat` | Loops a fixed number of times | `count`, `do` |
| `foreach` | Loops over a list | `items`, `var`, `do` |
| `break` / `continue` / `return` | Control flow | — |
| `log` | Adds a line to the run output | `text` |
| `screenshot` | Captures the screen, sends it to the phone | `target`, `name` |
| `describe` | Reads the screen with OCR | `target`, `name` |
| `phone.notify` / `phone.ring` / `phone.clip` | Acts on the phone | `text`, `number`, `action` |

Any step can set **Keep going if it fails**, which turns an error into a logged
warning instead of ending the run.

---

## Expressions

Conditions (`cond`), assignments (`value`), list sources (`items`) and anything
inside `{{ … }}` in a text field are evaluated by a small expression language.

It is a purpose-built evaluator, not an embedded scripting engine, and that is a
security decision before it is a design one. An automation arrives from a phone;
handing that text to something that can reach the filesystem, the network or
reflection would make every control above decorative. This grammar can read
variables, compare them, and call a fixed list of functions. There is nothing
else to express, so there is nothing to sandbox.

### Operators

```
||  &&  !        or  and  not
==  !=  <  <=  >  >=      ~=  (regex match)      contains
+  -  *  /  %             ( )
```

`+` concatenates when either side is text, which is what someone building a
command line means by it. Comparisons are numeric when both sides look like
numbers and case-insensitive text otherwise — a condition written as
`battery < 20` should not fail because the value arrived as a string.

### Variables

Automation variables shadow the built-ins, so a name collision with a future
built-in cannot silently change what an existing automation does.

| | |
|---|---|
| `hostName`, `user`, `device`, `runId` | Identity |
| `cpu`, `ramUsedMb`, `ramTotalMb`, `ramPct` | Load |
| `battery`, `charging` | Power |
| `volume`, `muted` | Audio |
| `activeWindow`, `activeProcess` | Foreground |
| `time`, `date`, `hour`, `minute`, `weekday` | Clock |
| `lastExit`, `lastOut`, `lastErr` | The previous shell step |
| `index`, and your loop variable | Inside a loop |

### Functions

```
contains  startsWith  endsWith  matches  regexGroup
lower  upper  trim  len  substring  indexOf  replace
split  lines  join
num  int  str  bool
min  max  abs  round  floor  ceil  rand
now  date  env
isEmpty  ifEmpty  default
fileExists  folderExists  processRunning  windowExists
```

Regular expressions run with a 250 ms budget, so a pathological pattern cannot
hang a run.

### Examples

```
battery < 20 and not charging
processRunning('chrome') and hour >= 22
contains(lower(activeWindow), 'visual studio')
lastExit == 0 and len(lastOut) > 0
```

```
{{hostName}} has been up since {{date('HH:mm')}}
Copy-Item "{{source}}" "{{env('TEMP')}}\backup"
```

---

## Running one

| From | How |
|---|---|
| Phone | Automations tab |
| Watch | Automations screen, or the tile |
| Home screen | The automations widget |
| Launcher / assistant | The published shortcut — see [ASSISTANT.md](ASSISTANT.md) |
| Another app | The intent API — see [ASSISTANT.md](ASSISTANT.md) |
| Voice | "Tell my PC", matched by name |

A **dry run** (`dryRun` on `auto.run`) returns the fully resolved step list
without executing anything. It is the fastest way to see what an automation will
actually do once its variables are filled in.
