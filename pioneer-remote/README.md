# Pioneer VSX-S510 Remote (Android)

Aplikácia na ovládanie AV receivera Pioneer VSX-S510 (2012) cez Wi-Fi.
Protokol: legacy Pioneer IP control — TCP port 8102, textové príkazy CR/LF.

## Funkcie
- Zap/vyp (receiver musí mať „Network Standby" = ON)
- Mute / Unmute
- Hlasitosť +/− a slider (sekvencia VU/VD — VSX-S510 nepodporuje absolútny príkaz VL)
- Vstupy: BD, HDMI 1–6, DVD, TV/SAT, VIDEO 1/2, DVR/BDR, iPod/USB, NETRADIO
- Priestorové režimy: Stereo, Direct, Pure Direct, Auto Surround, PLII Movie/Music
- Živý stav receivera (hlásenia VOL, FN, PWR, MUT)

## Zostavenie APK
Záložka Actions → workflow „Build Pioneer Remote APK" → po dokončení stiahni
artifact **pioneer-remote-debug-apk** → rozbaľ ZIP → nainštaluj app-debug.apk.

## Nastavenie receivera
- Ethernet z receivera do routra; telefón na rovnakej Wi-Fi
- Home Menu → System Setup → Network Setup: Network Standby = ON, poznač IP
- V appke zadaj IP a klikni „Pripoj"

## Riešenie problémov
- Nepripojí sa → zlá IP, iná sieť, Network Standby OFF; skús aj port 23
- Ak nejaký priestorový režim nefunguje, over kód SR v oficiálnom Pioneer
  dokumente „IP & RS-232 Codes" (FY13AVR) a uprav mapu modes v MainActivity.kt.
