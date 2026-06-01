# FoliaBytecodeBridge

Experimental Bukkit/Folia plugin plus Java agent that rewrites common legacy Bukkit bytecode shapes into Folia ownership routes.

## Official Discord

https://discord.gg/aT9z7q7hX8

## Building

```bash
mvn clean install
```

## Usage

Place the built jar in `plugins/`, then start Folia with the same jar as a Java agent:

```bash
java -javaagent:plugins/FoliaBytecodeBridge.jar -jar folia.jar
```

Loading the jar only as a plugin will not rewrite other plugin bytecode.

## Disclaimer

This project is experimental. It can help test legacy plugin compatibility on Folia, but it does not prove that any plugin is region-thread safe.

AI assistance was used during development, testing, and documentation.

## Documentation

See the `docs/` folder for architecture notes, route families, diagnostics, probes, and smoke-test details.

The previous detailed README was preserved at [`docs/TECHNICAL_README.md`](docs/TECHNICAL_README.md).

## Folia Inquisitors

[<img src="https://github.com/Folia-Inquisitors.png" width="80" alt="Folia-Inquisitors">](https://github.com/orgs/Folia-Inquisitors/repositories)
[<img src="https://github.com/Yomamaeatstoes.png" width="80" alt="Yomamaeatstoes">](https://github.com/Yomamaeatstoes)
