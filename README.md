# Canvas

[![GitHub License](https://img.shields.io/github/license/CraftCanvasMC/Canvas)](https://github.com/CraftCanvasMC/Canvas/blob/master/LICENSE)
[![GitHub contributors](https://img.shields.io/github/contributors/CraftCanvasMC/Canvas)](https://github.com/CraftCanvasMC/Canvas/graphs/contributors)
[![Discord](https://img.shields.io/discord/1168986665038127205?color=5865F2)](https://canvasmc.io/discord)

Canvas is a Minecraft server software introducing a fully multithreaded architecture to the dedicated server through a rewritten
chunk system executor, parallel world ticking and regionization. Canvas is an attempt at a fully multithreaded Minecraft
dedicated server without breaking plugin compatibility, and creating a more scalable environment for more modern CPUs.

**Canvas is not a simple drop-in replacement for Paper or Purpur. It is a fundamentally different architecture that requires
some configuration and understanding before use.**

Canvas' config(`canvas-server.yml`), is aimed for vanilla parity and stability
rather than raw performance, so it needs to be manually configured to do anything to benefit your server.
Canvas also is very hardware dependent and is not friendly with shared hosting due to its rewritten chunk system executor being much more aggressive than other
forks(however it does provide much higher performance). There is no recommended hardware for Canvas *yet*, but a good amount of
threads is highly recommended for Canvas to be effective like at least 10 threads (5 dedicated cores = 5x2).

Please also test prior to putting on a production server to see if your plugins are compatible, as Canvas' threaded nature makes a plugin compatibility layer. If you find a plugin that is incompatible, please tell us in our [Discord server](https://canvasmc.io/discord).

Canvas contains patches from [Leaf](https://github.com/Winds-Studio/Leaf) to assist in single threaded performance. This
does ***not*** mean Canvas is "better" than Leaf or worse than it. Canvas and Leaf are very different pieces of software
tailored to very specific environments with very different architectures.

Useful links:

- [Website](https://canvasmc.io)
- [Documentation](https://docs.canvasmc.io)
- [Discord](https://canvasmc.io/discord)

## Running Canvas

### Requirements

- Java 22 or higher

### Obtaining Server Jar

You can download the server jar from the [downloads page](https://canvasmc.io/downloads).

## Building Canvas

### Requirements

- Java 22 or higher
- Git (with configured email and name)
- Gradle

### Scripts

```bash
> ./gradlew applyAllPatches              # apply all patches
> ./gradlew createMojmapPaperclipJar     # build the server jar
> ./gradlew runDevServer                 # run dev server
> ./rebuildPatches                       # custom script to generate patches for modified directories
```

## REST API

Canvas has a REST API that can be used to get builds and check for new versions.

It is temporarily documented in the [Website Repository](https://github.com/CraftCanvasMC/Website/blob/main/docs/API.md). Soon it will be moved over to the documentation website.

## Support

You can help CanvasMC grow by:

- Supporting us on [Ko-fi](https://ko-fi.com/dueris)
- Starring the project on GitHub
- Contributing code or documentation

Your support helps keep this project active and improving!

## License

Canvas is licensed under the [GNU AGPLv3](https://github.com/CraftCanvasMC/Canvas/blob/master/LICENSE). <img align="right" width="100" src="https://upload.wikimedia.org/wikipedia/commons/thumb/0/06/AGPLv3_Logo.svg/1200px-AGPLv3_Logo.svg.png" alt="AGPLv3 Logo">
