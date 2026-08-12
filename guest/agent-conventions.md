# The box you are in

You are an agent running inside Box's guest: a Debian Bookworm VM on an ARM64 phone,
emulated under QEMU. There is no hardware virtualisation, so everything here is slower
than the same work on a laptop — prefer reading and reasoning over speculative rebuilds.

## Two disks, two lifetimes

This is the most important thing to know, because getting it wrong loses work.

`/` is the system disk. It ships inside the Box APK and is **replaced wholesale** every
time a new guest image is deployed. Anything you write outside `/workspace` — including
your own home directory — is gone after the next image update.

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

Credentials live under `/workspace/.config`, on the disk that survives updates, and are
readable only by you.

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

## Proposing changes to Box

A change to Box goes back as a pull request, never as a push to the default branch. The
reason is specific to this app rather than general good manners: a bad change ships in the
next image, and the box that would let you fix it is the one that just broke. A review step
keeps a person between the loop and the device.

The credential, if the user has set one up, is at `/workspace/.config/box/github-token` —
on the workspace disk because it belongs to this box and must survive updates, and out of
every repository so it cannot be committed. If it is not there, say so and stop; do not go
looking for a token elsewhere, and do not ask the user to paste one into the chat, where it
would be stored in the transcript. Read it at the moment you need it, never echo it, never
write it into a file, a URL, a git remote, or a command line the process list could show.

The loop, once there is a token and a network:

```bash
cp -r /usr/src/box /workspace/src/box     # never edit the baked copy
cd /workspace/src/box && git init && git remote add origin <the repo>
git checkout -b <branch>                  # make the change, commit it
git push origin <branch>                  # credential via a git credential helper or an
                                          # Authorization header — not embedded in the URL
curl -H "Authorization: Bearer $(cat …)" https://api.github.com/repos/<owner>/<repo>/pulls
```

Say what the change does and why in the pull request body, and name the commit the box was
built from — the reviewer is reading it away from the device and cannot see what you saw.

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

Debian Bookworm with `git`, `curl`, `python3`, `nodejs`/`npm`, `build-essential`, and an
X session running openbox — that desktop is what the user sees when they tap **Open
computer**, and files you leave on it are visible to them.

There is no JDK, no Android SDK, and no Docker, so this box cannot build the Box app
itself. Read and patch the source freely; leave building and deploying to the host.
