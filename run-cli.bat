@echo off
rem SQXDL CLI launcher - run in a real terminal (PowerShell / CMD / IDEA Terminal)
rem to get full JLine experience: sqxdl> prompt, Up/Down history, Ctrl+R search
java --enable-native-access=ALL-UNNAMED -cp "executor\target\classes;parser\target\classes;semantic\target\classes;out\libs\jline-3.27.1.jar;out\libs\jline-terminal-jna-3.27.1.jar;out\libs\jna-5.14.0.jar" com.sqxdl.executor.Main
