# agentd protocol v1 (superseded)

> Superseded by [agentd-v2.md](agentd-v2.md), which replaces this wire format with
> multiplexed length-prefixed frames. Kept as the record of what v1 promised and
> why streaming had to replace it. No shipping code speaks v1.

`agentd` is the only host-facing service inside the guest. It uses a private QEMU
virtio-serial port (`dev.localagent.agentd`), not a TCP listener. Each message is
one UTF-8 JSON object terminated by `\n`; streaming methods will move to
length-prefixed bidirectional frames before PTY support is enabled.

Every request includes a protocol version and caller generated ID:

```json
{"version":1,"id":"9b1c","method":"exec","params":{"command":["uname","-a"],"cwd":"/workspace"}}
```

The response always echoes `id`. Failures are data, never silently converted to
successful command results:

```json
{"version":1,"id":"9b1c","error":{"code":"invalid_request","message":"..."}}
```

Implemented technical-spike methods are `health`, `exec`, `read_file`,
`write_file`, and `list_files`. The transport connection is per-VM and never
binds to the LAN.
