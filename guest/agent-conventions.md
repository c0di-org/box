# The box you are in

You are an agent running inside Box's guest: a Debian Bookworm VM on an ARM64 phone,
emulated under QEMU. There is no hardware virtualisation, so everything here is slower
than the same work on a laptop — prefer reading and reasoning over speculative rebuilds.

## Two disks, two lifetimes

This is the most important thing to know, because getting it wrong loses work.

`/` is the system disk. It ships inside the Box APK and is **replaced wholesale** whenever
a new version of the guest image reaches the phone. The image carries a version taken from
its own contents, so any rebuild is a new one and arrives on the next start. Anything you
write outside `/workspace` — including your own home directory — is gone after that.

`/workspace` is a separate disk that is created once and **kept across app updates on
purpose**, so that updating Box never wipes the user's Linux box. Everything you want to
survive goes here. It is also your working directory when a session starts.

The rule: if it should be true of every Box, it belongs in the repository and is installed
into the image at build time. If it belongs to *this* box, it belongs in `/workspace`.
Never hand-edit the system disk and expect it to last.

## Where things go

Source code lives in `/workspace/src/<repo>` — flat, one level, no organisation folders.
`cd` into the repo before working; don't work from `/workspace` itself.

`/workspace/CLAUDE.md` is this box's own memory: the user's preferences, their hosts and
accounts, what has been learned here. It is not in any repository and nothing overwrites
it. This file, by contrast, is copied out of the image on every boot — so an edit made
here is gone at the next start. Change `guest/agent-conventions.md` in the Box source and
it corrects itself on every device.

One thing that file must not do: record a defect as a standing rule without naming the
image you saw it on and a check that could actually fail. Nothing overwrites it, so a rule
written on the day a bug was real outlives the image that fixed it — and it outranks this
file, because it is specific and cites evidence. That has already happened once here.
`AskUserQuestion` was written up as broken, re-checked on each new image by grepping the
Kotlin for a symbol the code never contained, and so re-confirmed for three images after
it had been fixed. Two passes, zero information: the rule looked better verified the
longer it was wrong. Prefer performing the behaviour to grepping for it, and when you do
grep, grep for the name the code would really use.

Credentials live under `/workspace/.config`, on the disk that survives updates, and are
readable only by you.

## `/workspace/shared` is the user's phone

That folder is a real directory on the Android side, published to the phone's Files
app and to every app's Open/Save dialog. Its contents are copied in when the box boots,
and again within about a second of the user adding anything while it is running — so
never ask them to restart the box to pick up a file. What you leave there is copied
back out.

This is the only way to hand someone a file they can **keep** — open in another app, mail,
edit, take to a laptop. So if they ask for something to take away, put it in
`/workspace/shared` and say so. It is also the only way to give them a picture, because
the viewer `show` opens below reads a file as text.

For everything else, "I've written it to /workspace/out.csv" is, to them, the same as not
having written it: that disk is inside a VM and nothing on the phone browses it. Either
put the file in `/workspace/shared`, or show it — see below.

`/workspace/shared/inbox` is the other direction: what the user hands *you*. A photo
shared to Box from another app, or picked with the `+` beside the message box, lands
there, and its path is named in the turn it arrived with — so a file you are told about
is one you can simply read. Do not tidy that folder; the deleting rule below says why
nothing you remove from it stays removed.

Three things about the copy, because it is deliberately not continuous:

- **Their side wins.** If you and the user both changed the same file, theirs is kept
  and yours is saved beside it as `name.from-box`. Nothing is ever deleted to settle it.
- **Deleting is one-way, both ways.** The phone holds the original, so a file you remove
  comes back on the next copy — if something should go, say so and let them delete it. A
  file *they* delete is the mirror image: your copy stays where it is, but it is never
  carried out again. So a file being in `/workspace/shared` is not proof they still have
  it, and if that matters, ask rather than assume.
- **Timing.** What you leave there goes out when your session ends, so finish writing a
  file before you report it as done rather than leaving it half-written and carrying on.

## Showing them something

You have a tool, `mcp__box__show`, that puts one thing in front of the person as a button in
the conversation, which they tap if they want it. It takes exactly one of three arguments:

- `path` — a file you wrote, under `/workspace`. Opens in a text viewer.
- `port` — a port you are already serving on. Box forwards it to the phone and opens it in a
  browser panel.
- `desktop` — the X session you are working beside, live, once there is something on it.

One per call, so each button says what it is. Nothing is interrupted and nothing is taken over:
the button appears beside your answer and waits.

**It is not a substitute for saying the thing.** Offer the report *and* tell them what is in it —
they may never tap it, and an answer that reads "as you can see in the diagram" to someone
looking at an unpressed button is worse than one that just says what you found. For the same
reason, do not show them a file whose whole content is one sentence you could have written out.

What it is worth doing for: something long you wrote and summarised, a dev server you just got
running, a window you have opened on the desktop and want them to look at. What it is not worth
doing for: every file you touch, or a thing they asked you to put in `/workspace/shared` — that
is a file they wanted to keep, and a button is not the same as having it.

Three limits, so none of them arrive as a surprise:

- **Files must be under `/workspace` and must already exist.** A button that opens nothing is
  worse than no button, so finish writing before you show. The system disk is refused: it is the
  same on every device and holds nothing you made.
- **`/workspace/.config` is never shown**, however you name it — that is where this box's
  credentials live, and the person cannot see where a button leads before they tap it.
- **A port must already have something listening on it.** Start the server, let it bind, then
  show it. Offering a port nothing is serving hands them a page of connection error.

Refusals come back to you with the reason, so a `show` that fails is yours to fix or to mention
— not something they see go wrong.

## You have Box's source

`/usr/src/box` is the source of the app the user is running, at the exact commit it was
built from — see `/usr/src/box/BUILD-INFO` for that commit and the remote it came from.
It is not a guess or a fetch: the image and the app are built together, so this code drew
the screen the user is looking at.

So when they ask why Box behaves some way, read it and answer from the code. When they
ask for a change to Box itself, that is a normal request, not an odd one.

Two things to be straight about. This copy is on the system disk, so it is replaced by
every app update and any edit you make here is lost — copy it into `/workspace/src/box`
before changing anything. And the QEMU shared objects under `runtime-qemu/.../jniLibs`
are not included; they ship in the APK instead.

## Showing someone what is inside the box

Some people arrive here by tapping a suggestion — *"Show me what’s inside the box"* — rather
than by typing a task. That is usually the first minute anyone has ever spent with Box, and it
is a real request, not a demo: they want to know what they just installed and what it is for.

Answer it by **looking**, never by reciting this file. Everything worth telling them is
readable from in here, and a claim you actually checked is the whole difference between this
and a brochure. Run the commands, and let what comes back be the answer:

```bash
uname -a; nproc                     # an ARM64 Linux machine, emulated, on their phone
head -3 /proc/meminfo               # or `free -h`
cat /etc/os-release                 # a real Debian, not a sandbox pretending to be one
cat /usr/src/box/BUILD-INFO         # the commit of Box you are running inside
```

The last one is the part people do not expect. You are holding the source of the app drawing
the screen they are reading — so say which commit it is, and that you can read it.

Keep this short. Three or four commands and a few sentences, not a tour of the filesystem.
The point lands or it does not; length does not help it.

### Then ask them what to build

Do not pick for them, and **do not ask in prose.** Call the `AskUserQuestion` tool — once, with
one question — to ask what the small thing you build together should be *about*.

The tool rather than a sentence, because on a phone that difference is most of the interaction.
Asked in prose, the question is one they answer by typing into a composer with a keyboard over
half the screen; asked with the tool, it is options they can tap plus a free-text box for the
answer you did not think of, and it holds your turn open until they reply. Observed on hardware
on 14 Aug 2026: this step was written as "use `AskUserQuestion`", the agent asked in prose
instead, and the person typed their answer out by hand. It reads as fine from in here and is
worse for them, which is why this paragraph is longer than the instruction it is protecting.

Offer a few concrete starting points. The free-text answer is the good one; the options are only
there for someone who does not yet know what to ask a computer for.

Ask it as a question about *them* — something they like, something they are working on,
something they would want a page about — rather than a menu of web-page genres. The answer is
supposed to make the result theirs.

### Then build it, and keep it small

One page, written by hand, served from the machine they are looking at:

```bash
mkdir -p /workspace/tour && cd /workspace/tour   # write index.html here
python3 -m http.server 8000                      # in the background, so it keeps serving
```

Start that server in the background and let it bind before you `show` the port — `show` checks
that something is really listening and refuses otherwise, and a server that died with the shell
that started it is the one way this last step fails in front of them.

**Do not run `npm install`, and do not reach for a framework.** This machine is fully emulated
and single-core; a dependency tree is minutes of watching nothing happen, on the one occasion
where nothing happening reads as the product being broken. `python3` is already here and needs
no network. One hand-written HTML file with its CSS inline is finished before a package manager
would have finished resolving, and it is the thing they can actually see.

Then stop. Tell them the page is theirs to change, that they can ask you to change it, and
leave it there. A first turn that keeps going is a first turn nobody finishes reading.

## GitHub

Whether this box can reach GitHub is not something to work out from a file. `git` and `gh` are
either signed in or they are not, and if they are, everything works the way it does anywhere
else: clone, commit, push, `gh pr create`. Your commits are already attributed to the user's
GitHub account — the name and address are configured for you.

**Never look for a token, and never put one in a command.** There is a credential on this box
when it is connected, and it is deliberately somewhere you have no reason to go: git gets it from
a credential helper, `gh` reads its own config, and neither of them needs you in the middle. A
token pasted into a URL, an `Authorization` header, or a shell variable ends up in your context,
in a command line, and then in the log of this conversation, which is kept on disk. There is no
version of that which is worth the trouble it saves.

**If it is not connected, ask — with `mcp__box__connect`.** Nothing here can grant an account
except the person you are talking to, and that tool is how you ask them: it shows them a code,
they approve it at GitHub, and it returns to you when they are done. It *waits*, so the right
thing to do is call it and then carry on with what you were doing, in the same turn. Do not end
your turn to explain that you need GitHub, and do not ask them to paste a token into the chat.

Being connected does not mean everything is reachable. The user chooses which repositories this
box can see, so a clone or a push can fail because a particular repository was not one of them.
That is not a broken credential, and re-authorising would not fix it — **call `mcp__box__connect`
again and name the repository in your reason.** On a box that already has a working credential
that tool opens the repository picker rather than a sign-in, which is the thing that actually
grants the missing repository. Then carry on in the same turn.

## Proposing changes to Box

A change to Box goes back as a pull request, never as a push to the default branch. The reason is
specific to this app rather than general good manners: a bad change ships in the next image, and
the box that would let you fix it is the one that just broke. A review step keeps a person between
the loop and the device.

The loop:

```bash
cp -r /usr/src/box /workspace/src/box     # never edit the baked copy
cd /workspace/src/box
git init && git remote add origin https://github.com/<owner>/<repo>.git
git checkout -b <branch>                  # make the change, commit it
git push -u origin <branch>
gh pr create --fill                       # or --title/--body
```

Say what the change does and why in the pull request body, and name the commit the box was built
from — the reviewer is reading it away from the device and cannot see what you saw.

This is also how you report what you *notice* rather than what you were asked for. Some defects
are only visible from in here: a tool that reports something different to you than it shows the
user, an instruction in this file naming a command the image does not carry. Send those back the
same way — a pull request against the file or the code that is wrong, with the image you saw it
on. A note left on the device is read by nobody, and is stale by the next image; a pull request
reaches the person who can fix it.

## Ask before you send a subagent

You can use one, and the user can see it. A `Task` you start is drawn as its own card in
the conversation, carrying what you asked for, what the subagent is doing right now, and
everything it says and runs as it goes. On that card is a Stop that ends that subagent
alone and leaves you running, so you will hear it stood down and can carry on from what it
had already found.

That removes the reason a subagent used to be forbidden here. It does not make one cheap.
This machine is fully emulated on a phone, so a second agent is a second model doing real
work at a real price, and the thread the user is actually reading waits on it. Nearly
everything asked for here is finished sooner in this thread, one visible step at a time.

So ask first, every time. One line — what you would send it to do, and why this thread is
the wrong place for it — and then wait for an answer. Send one at a time; several at once
is more than the conversation can show and more than this box can afford, and a subagent
of your own is never yours to authorise.

It is worth asking for when the work is a wide sweep whose middle would bury the
conversation and whose product is short: reading a large tree to answer one question,
trawling a log for the three lines that matter. It is not worth asking for when you could
do it in a handful of steps, when it needs the context you are already holding, or when the
parts are not actually independent and "in parallel" just means twice.

Give it a `description` that means something to the person reading it, not to you — it is
the title on the card, and it is the first thing they see.

## What is here

Debian Bookworm with `git`, `gh`, `curl`, `python3`, `nodejs`/`npm`, `build-essential`, and an
X session running openbox — that desktop is what the user sees when they tap **Open
computer**, and files you leave on it are visible to them.

You can see it too. `scrot` is installed and `DISPLAY` is already set for you, so

```bash
scrot /tmp/screen.png          # for your own eyes: did that window draw what I expected?
scrot /workspace/shared/screen.png   # to show the user, since shared is their phone
```

is the difference between building a GUI blind and looking at it. Sight only — there is no
`xdotool`, so you can watch that desktop but not drive it, and anything that needs clicking
still needs the person. When what is drawn there is the thing *they* should look at, showing
them the desktop beats a screenshot: they get the window live rather than a moment of it.

There is no JDK, no Android SDK, and no Docker, so this box cannot build the Box app
itself. Read and patch the source freely; leave building and deploying to the host.

## Building an Android app, and which path to take

You can build a real, signed, installable APK in here and hand it to the person, and the
toolchain is **already installed** — Google ships the build tools as x86-64 only, so this box
carries an ARM64 set at `/opt/android`, with the scripts on top of it in `/opt/android/bin`.
Nothing to download and nothing to wait for. Read `/opt/android/bin/README` — the same notes as
`docs/spike/android-toolchain/gradle-free/` — before starting.

`/opt/android` is on the system disk, so an update replaces it and anything you leave there is
gone. Builds already write to `/workspace/android` instead: your project, the dex cache, and this
box's own signing key, which is generated here on first build and belongs to this device alone.
`provision.sh` is still there and still works, for a prefix you assemble yourself; you should not
need it.

When it is built, offer it with `show(install: '/workspace/shared/<name>.apk')` and the person
gets a button that installs it. The APK has to be **in the shared folder** — that is the only
route bytes take out of the box — and it reaches the phone when your turn ends, so say it may
take a moment rather than letting them tap a button whose file is still on its way.

**Default to building from scratch.** It is not a reduced mode: `android.jar` is the whole
platform — SQLite, Camera2, sensors, notifications, widgets, `Canvas`, and WebView, so a native
shell around local HTML is a real design rather than a dodge. What libraries add is mostly
convenience and Material's look, and the boilerplate they save you is the part you can write.

| | From scratch | With AndroidX |
| --- | --- | --- |
| Clean build | **~3 min** | ~13 min once the dex cache is warm, far longer cold |

So reach for `app/deps.txt` when the app genuinely needs Material's look or a library that
wraps a *service* — maps, payments — and not to save yourself typing. Both paths were measured
on this hardware; the numbers and the reasoning are in that directory.

Two things that will bite you there. Emulated, a `d8` or `ecj` run costs minutes almost
regardless of input size, so batch JVM tool invocations rather than looping over inputs —
caching per jar measured *six hours* against 26 minutes for one pass. And builds run long
enough that you should start them in the background and say so, rather than leaving the person
watching a turn that looks finished.

## A missing command can read as success

The image is small, so something you expect may be absent — and `command not found` is an
exit status, which a shell will fold into an answer that looks like good news.

```bash
until ! pgrep -f build.sh; do sleep 5; done   # pgrep missing -> exits at once, "finished"
some-tool 2>&1 | grep -E "error|done"         # tool missing -> message filtered -> silence
```

Observed on the image built from `a9f2e9e`: together these cost an hour spent debugging a
failure that had not happened, while the real build ran to completion in the background.

So check the status before the output — `echo "exit=$?"` before any pipe, `${PIPESTATUS[0]}`
after one. And wait on something you can see, like a file appearing or a log line being
written, rather than on a process being absent, which is what a missing `pgrep` lies about.

If something you need is not here, send a pull request against `guest/packages.txt`.
