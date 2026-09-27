# NOVA AntiCheat

Paper 1.21.11 / Java 21.

## Build
Run `gradlew build` (Windows: `gradlew.bat build`).

The re-obfuscated plugin JAR is produced in `build/libs/`.

## Install
Copy the JAR from `build/libs/` into the server `plugins/` folder.

## Commands
- `/novac alerts`
- `/novac info <player>`
- `/novac reload`

Permission: `novac.admin`

This is a server-side anti-cheat starter. It cannot guarantee detection of every cheat; test and tune it before enabling aggressive punishments.
