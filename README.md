# Qashqai OBD

Android dashboard for a Nissan Qashqai J10 (2009, 1.5 dCi / K9K) that runs on an Android head unit
and reads live data through a Bluetooth ELM327 OBD-II adapter.

## What it shows
- Instant fuel consumption (L/100km while moving, L/h when stopped), with a colour bar
- Trip: average L/100km, fuel used, distance, average speed, engine time (saved across restarts)
- Speed, RPM, coolant, turbo boost, engine load, battery voltage, intake air, rail pressure, outside temp
- Trip map: records the route by GPS and colours it by fuel consumption (green = economical, red = heavy)
- Diagnostics: read and clear trouble codes, every live value, and a connection log

## Install
1. Build the APK: `./gradlew assembleDebug` (use JDK 17), or take the existing `app/build/outputs/apk/debug/app-debug.apk`.
2. Copy the APK to a USB stick, plug it into the head unit, and open it with the file manager
   (you may have to allow "install from unknown sources"). Or use `adb install app-debug.apk`.

## Connect
Pair the Bluetooth ELM327 in Android Bluetooth settings first (PIN 1234 or 0000), then
Setup → "Choose paired adapter". The app connects on its own when it starts and reconnects if the link drops.

The ignition must be ON. The J10 uses CAN 11-bit 500k, so picking that protocol makes connecting faster.

## Trip map
- With **Record trips automatically** on (Setup), a trip starts when the car connects and is saved
  2 minutes after the engine is turned off. Short Bluetooth drop-outs don't split a trip.
  You can also start and stop by hand on the **Map** tab.
- The route is cut into ~150 m stretches. Each one is coloured by its own L/100km on a
  5-step scale from green (≤ 5) to red (≥ 10). You can change both limits in Setup. Grey means the car wasn't connected.
- Saved trips are listed next to the map. Tap one to see its route, distance, time, fuel and average.
- The map is OpenStreetMap (no Google services or API key needed). It needs internet to load map tiles.
  Areas you've already viewed are cached and still show without internet.
- Tracking keeps running when a navigation app is in front (the app shows a notification while it runs).

## Fuel consumption on this diesel
The 2009 K9K ECU most likely doesn't report fuel rate (PID 5E), and a diesel has no fixed air/fuel
ratio, so the app **estimates** fuel from engine load × RPM (~45 mg per injection at full load).
This tracks real use well (it reads 0 when coasting with fuel cut), but the absolute numbers may
be off by 10–20 % until you calibrate:

1. Fill the tank to the top → Setup → **Tank is full (start)**
2. Drive 300+ km
3. Fill to the top again → enter the litres from the pump → **Calibrate**

If the ECU does report PID 5E, the app uses it automatically.

## Finding the real fuel data (Diag tab)

The load × RPM estimate works anywhere. Reading the ECU's own injected fuel quantity would be exact. That value only comes from the Renault/Nissan manufacturer data, and its address depends on which ECU this car has. Two tools collect what's needed to find it. Park with the engine running for both:

- **Scan ECU** (about 1–2 min) asks for every standard PID and the vehicle info (VIN, ECU name, calibration). It also asks the engine ECU (7E0 → 7E8) for every Renault data block (`21 01`…`21 FE`) and for identification (`22 F180`…). It only uses read services: 01, 09, 10 C0 (diagnostic session, closed with 10 81 afterwards), 21 and 22. Nothing is written or erased.
- **Capture CAN 30 s** listens to the car's own CAN traffic (`ATMA`) without sending anything to the ECUs. The trip computer gets its fuel figure from one of these messages, so the report flags bytes that only count up. Do one capture at idle and one at about 2500 rpm.

Reports are saved as text in `Android/data/com.qashqai.obd/files/scans/`. Use **View** to read one on the screen, or **Share** to send it (or copy it over USB). The next step is to read the reports and add the injected-quantity request to the app.

## Android Auto (app on a phone)

Installed on a phone, the app also shows up in Android Auto. Android Auto only allows Google's fixed screen layouts, so the car screen is one simple page:

- **Fuel now:** L/100km (or L/h when standing), with L/h and speed.
- **Trip:** average L/100km, distance, fuel used and engine time.
- **Engine:** rpm, coolant, boost and battery.
- **Car:** connection status and whether the trip map is recording.
- Buttons: **Connect/Disconnect** and **Record trip/Stop recording**.

Opening the app in the car connects to the adapter on its own, if auto-connect is on. Pick the adapter and grant permissions once in the phone app first. The trip map, Diag tools and settings are only on the phone (or head unit) screen.

The app isn't from the Play Store, so Android Auto hides it until you allow unknown sources:

1. Open Android Auto settings on the phone.
2. Tap **Version** about 10 times to unlock developer settings.
3. In the ⋮ menu, open **Developer settings** and turn on **Unknown sources**.
4. Reconnect the phone to the car.

## Code layout

- `auto/` – Android Auto entry point and car screen (Car App Library templates)
- `scan/` – ECU scan, CAN capture and report files; `obd/ScanParser.kt` – ISO-TP reassembly and bus statistics (unit-tested against a scripted fake ELM327)
- `obd/` – Bluetooth transport, ELM327 protocol, reply parser, PIDs, fuel model
- `data/ObdManager.kt` – connection, auto-reconnect, polling loop, trip integration, settings
- `trip/` – GPS recorder, trip files (CSV in app storage), route colouring
- `TripService.kt` – foreground service so GPS and OBD keep working in the background
- `ui/` – Jetpack Compose screens (dashboard, map, diagnostics, setup)
- Unit tests: `./gradlew testDebugUnitTest`
