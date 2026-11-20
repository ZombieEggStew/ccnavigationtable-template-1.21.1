
<h1 align="center">CCPE — Aircraft Systems  <br>
	<a href="https://www.curseforge.com/minecraft/mc-mods/cc-my-peripheral-extender">
    <img src="https://img.shields.io/curseforge/dt/1635726?logo=curseforge&label=&suffix=%20&style=flat&color=242629&labelColor=F16436&logoColor=1C1C1C" alt="CurseForge"></a>
    <a href="https://modrinth.com/mod/cc-my-peripheral-extender">
    <img src="https://img.shields.io/modrinth/dt/cc-my-peripheral-extender?logo=modrinth&label=&suffix=%20&style=flat&color=242629&labelColor=5CA424&logoColor=1C1C1C" alt="Modrinth"></a>
</h1>

## → [wiki](https://zombieeggstew.github.io/ccnavigationtable-template-1.21.1/) ←

## → [code-map](https://github.com/zombieeggstew/ccnavigationtable-template-1.21.1/blob/main/memo/project/code-map.md) ←

dsh :
- AGENTS.md
- .agents\skills\

github copilot :
- .github\

## Dependencies

| Mod | Version |
|---|---|
| NeoForge | 1.21.1 |
| CC:Tweaked | 1.118.0+ |
| Create | 6.0.10+ |
| Simulated (Aeronautics) | 1.3.0+ |
| Sable | 2.0.3+ |

## Where this came from

The mod started as **CC Navigation Table** — a small addon that read the coordinates out of the navigation table.

The next step came from **Microcontrollers**, a computer mod distinct from CC:Tweaked: its Sensor connected wirelessly via channels and read target block NBT directly, without per-mod peripheral adapters. That "universal sensor" idea became the Peripheral Extender. Microcontrollers later disappeared, so I wrote my own Sensor.

After that, the mods I depended on could not do what I wanted, and waiting for someone else's update felt slower than writing it myself. One block at a time the mod grew the whole chain — sensors → flight computers → actuators → engines — until "peripheral extender" no longer described it. That is why it is now **CCPE — Aircraft Systems** (formerly CC Peripheral Extender).

The mod is still in development — feature requests are welcome.

## License

MIT
