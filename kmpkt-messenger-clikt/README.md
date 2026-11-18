# kmpkt-messenger-clikt

Clikt CLI commands for `kmpkt-messenger`, providing `messenger:consume` with Symfony-style
prioritized transport receivers, `--all`, `--bus` override, `--limit`, `--sleep`, and optional
`--lock` leader-locking via `WorkLock`, plus `messenger:setup-transports` to provision
`SetupableTransport` infrastructure ahead of consumption.
